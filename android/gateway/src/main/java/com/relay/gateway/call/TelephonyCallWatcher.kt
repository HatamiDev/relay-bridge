package com.relay.gateway.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * The call path that does not need the dialer role.
 *
 * ## Why this exists
 *
 * [RelayInCallService] is the precise way to observe cellular calls — real
 * `Call` objects, exact states, DTMF, per-call disconnect causes. It is also,
 * for this app, dead code, and the reason is a single line of manifest metadata:
 *
 * ```xml
 * <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="false" />
 * ```
 *
 * That flag was set to keep the stock Samsung in-call screen — a deliberate
 * choice, and the wrong one. Telecom's `InCallController` classifies a service
 * with `IN_CALL_SERVICE_UI = false` as a *non-UI* InCallService, and non-UI
 * services are only bound if the app holds `CONTROL_INCALL_EXPERIENCE`, which is
 * `signature|privileged` and unobtainable by an ordinary install. So the service
 * was never bound, `onCallAdded` never fired, and every symptom followed from
 * that one fact: the receiver never rang, no audio bridge ever started in either
 * direction, and hanging up from the receiver did nothing because there was no
 * `Call` object to disconnect.
 *
 * The alternative — setting the flag to `true` — makes this app the phone's
 * in-call UI, which means reimplementing the entire in-call screen, emergency
 * calling included, on a handset that is supposed to keep working as a normal
 * phone. That is the wrong trade for a bridge.
 *
 * ## What this uses instead
 *
 * Three ordinary APIs, none of which need the dialer role:
 *
 *  * **Detect** — `TelephonyCallback.CallStateListener` (API 31+) or
 *    `PhoneStateListener` below it, backed by the `PHONE_STATE` broadcast, which
 *    is the only source of the *caller's number*.
 *  * **Answer** — `TelecomManager.acceptRingingCall()`, gated on
 *    `ANSWER_PHONE_CALLS`.
 *  * **Hang up** — `TelecomManager.endCall()`, same permission.
 *
 * What is lost: DTMF during a call, hold, and precise disconnect causes. What is
 * kept: ringing, answering, hanging up, audio in both directions, and a phone
 * that still behaves like a phone.
 *
 * If the dialer role ever *is* held, [RelayInCallService] binds and its events
 * arrive first; the controller ignores this watcher for the duration of that
 * call, so the two paths never both drive one call.
 */
class TelephonyCallWatcher(
    private val context: Context,
    private val controller: () -> CallBridgeController?,
) {

    private val telephony = context.getSystemService<TelephonyManager>()

    private var callback: Any? = null
    private var legacyListener: PhoneStateListener? = null
    private var receiver: BroadcastReceiver? = null

    /**
     * The last number the PHONE_STATE broadcast carried.
     *
     * The state callback and the broadcast are two separate deliveries of the
     * same event and arrive in no guaranteed order, so the number is cached here
     * and read when the ringing state is handled. Without READ_CALL_LOG the
     * extra is absent and this stays empty — the call still rings on the
     * receiver, just as "Unknown".
     */
    @Volatile private var lastIncomingNumber: String = ""

    @Volatile private var lastState: Int = TelephonyManager.CALL_STATE_IDLE

    fun start() {
        registerBroadcast()
        registerStateListener()
        Log.i(TAG, "telephony watcher started")
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (callback as? TelephonyCallback)?.let {
                runCatching { telephony?.unregisterTelephonyCallback(it) }
            }
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let {
                runCatching { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        }
        callback = null
        legacyListener = null
        Log.i(TAG, "telephony watcher stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun registerBroadcast() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

                @Suppress("DEPRECATION")
                intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { lastIncomingNumber = it }

                val name = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val state = when (name) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                    else -> return
                }
                dispatch(state)
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            context,
            r,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun registerStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = dispatch(state)
            }
            callback = cb
            runCatching {
                telephony?.registerTelephonyCallback(context.mainExecutor, cb)
            }.onFailure { Log.e(TAG, "registerTelephonyCallback failed", it) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (!phoneNumber.isNullOrBlank()) lastIncomingNumber = phoneNumber
                    dispatch(state)
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            runCatching {
                telephony?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }.onFailure { Log.e(TAG, "listen failed", it) }
        }
    }

    /**
     * Both sources report the same transitions, so identical consecutive states
     * are dropped — otherwise a single incoming call would announce itself twice
     * and the receiver would ring, stop, and ring again.
     */
    @Synchronized
    private fun dispatch(state: Int) {
        if (state == lastState) return
        val previous = lastState
        lastState = state

        val bridge = controller()
        if (bridge == null) {
            Log.w(TAG, "state ${nameOf(state)} with no controller — dropped")
            return
        }

        Log.i(TAG, "telephony ${nameOf(previous)} → ${nameOf(state)}")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING ->
                bridge.onTelephonyRinging(lastIncomingNumber)

            // OFFHOOK after RINGING is "the call was answered". OFFHOOK from
            // IDLE is an outgoing call connecting — which is equally a cue to
            // bring the audio bridge up, since placeCall() already told the
            // receiver a call is dialling.
            TelephonyManager.CALL_STATE_OFFHOOK ->
                bridge.onTelephonyOffhook()

            TelephonyManager.CALL_STATE_IDLE -> {
                lastIncomingNumber = ""
                bridge.onTelephonyIdle()
            }
        }
    }

    private fun nameOf(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
        else -> "UNKNOWN($state)"
    }

    private companion object {
        const val TAG = "TelephonyCallWatcher"
    }
}
