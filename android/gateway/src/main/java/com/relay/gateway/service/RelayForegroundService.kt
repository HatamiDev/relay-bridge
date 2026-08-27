package com.relay.gateway.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.relay.core.model.CallDtmf
import com.relay.core.model.CallMute
import com.relay.core.model.CallPlace
import com.relay.core.model.CallRef
import com.relay.core.model.CallStateUpdate
import com.relay.core.model.Contact
import com.relay.core.model.ContactsResult
import com.relay.core.model.DeviceRole
import com.relay.core.model.Ev
import com.relay.core.model.RtcIce
import com.relay.core.model.RtcSdp
import com.relay.core.model.SmsMessage
import com.relay.core.model.SmsOutboundRequest
import com.relay.core.model.SmsStatusUpdate
import com.relay.core.model.SmsSyncRequest
import com.relay.core.model.SmsSyncResult
import com.relay.core.net.ConnectionState
import com.relay.core.net.RelayEvent
import com.relay.core.net.SignalingClient
import com.relay.core.util.SystemHealth
import com.relay.gateway.GatewayRuntime
import com.relay.gateway.R
import com.relay.gateway.call.CallBridgeController
import com.relay.gateway.call.TelephonyCallWatcher
import com.relay.gateway.contacts.ContactsMirror
import com.relay.gateway.sms.SmsInbox
import com.relay.gateway.sms.SmsSender
import com.relay.gateway.ui.GatewayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Agent 3 — the always-on gateway brain.
 *
 * Owns the single [SignalingClient] for this device — which itself may hold a
 * [com.relay.core.crypto.CryptoBox] per paired receiver — and fans events out
 * to the SMS, contacts and call subsystems. Runs as a foreground service so
 * the OS cannot reclaim it, holds a partial wake lock while a call is
 * bridged, and restarts itself with `START_STICKY` if it is ever killed
 * anyway.
 *
 * Outbound traffic that concerns every receiver (inbound SMS, status updates,
 * ringing) goes through [emit], which broadcasts one sealed envelope per
 * peer. Replies scoped to a single requester (`sms:sync`, `contacts:sync`)
 * go through [emitTo] instead.
 */
class RelayForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var signaling: SignalingClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null

    private lateinit var smsSender: SmsSender
    private lateinit var smsInbox: SmsInbox
    private lateinit var contactsMirror: ContactsMirror
    private lateinit var callBridge: CallBridgeController
    private lateinit var callWatcher: TelephonyCallWatcher

    /** Outbound envelopes that could not be sent while the socket was down. */
    private val pendingOutbound = ArrayDeque<Pair<String, String>>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service creating")

        startForeground(
            SystemHealth.NOTIFICATION_SERVICE_ID,
            buildNotification("Starting…", connected = false),
            foregroundTypes(),
        )

        smsSender = SmsSender(this)
        smsInbox = SmsInbox(this)
        contactsMirror = ContactsMirror(this)
        callBridge = CallBridgeController(
            context = this,
            emit = { event, plaintextJson, targetDeviceId ->
                if (targetDeviceId != null) emitTo(targetDeviceId, event, plaintextJson) else emit(event, plaintextJson)
            },
            onStateChanged = { updateNotification() },
        )

        // The path that works without the dialer role. RelayInCallService is
        // only bound when this app is the phone's in-call UI, which it
        // deliberately is not, so without this watcher no call event ever
        // reaches the bridge at all.
        callWatcher = TelephonyCallWatcher(this) { CallBridgeController.current }
        callWatcher.start()

        instance = this
        connect()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECONNECT -> connect()
            ACTION_SMS_RECEIVED -> {
                val message = intent.getStringExtra(EXTRA_SMS_JSON)
                if (message != null) relayInboundSms(message)
            }
            ACTION_SMS_STATUS -> {
                val update = intent.getStringExtra(EXTRA_STATUS_JSON)
                if (update != null) emit(Ev.SMS_STATUS, update)
            }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        // START_STICKY: if the OS kills us under memory pressure it will restart
        // the service (with a null intent) as soon as resources allow.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(TAG, "service destroyed")
        heartbeatJob?.cancel()
        if (::callWatcher.isInitialized) callWatcher.stop()
        callBridge.shutdown()
        signaling?.disconnect()
        releaseWakeLock()
        scope.cancel()
        instance = null
        // Best-effort self-resurrection — some OEM task killers stop the service
        // without stopping the process.
        if (GatewayRuntime.secureStore.isPaired) {
            sendBroadcast(Intent(this, BootReceiver::class.java).setAction(ACTION_RESURRECT))
        }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection
    // ─────────────────────────────────────────────────────────────────────────

    private fun connect() {
        val store = GatewayRuntime.secureStore
        if (!store.isPaired) {
            Log.w(TAG, "not paired; idling")
            updateNotification()
            return
        }

        signaling?.disconnect()

        val boxes = store.allCryptoBoxes()
        if (boxes.isEmpty()) {
            Log.w(TAG, "no receiver keys yet — connecting anyway so pairing can proceed")
        }

        val client = SignalingClient(
            serverUrl = store.serverUrl,
            authToken = store.authToken,
            role = DeviceRole.GATEWAY,
            ownDeviceId = store.deviceId,
        )
        client.setBoxes(boxes)
        signaling = client
        callBridge.attachSignaling(client)

        scope.launch { client.events.collect(::onRelayEvent) }
        scope.launch {
            client.connectionState.collectLatest { state ->
                updateNotification()
                if (state == ConnectionState.CONNECTED) flushPendingOutbound()
                if (state == ConnectionState.UNAUTHORIZED) {
                    Log.e(TAG, "server rejected our token — re-pairing required")
                }
            }
        }

        client.connect()
        store.fcmToken.takeIf { it.isNotEmpty() }?.let(client::registerFcmToken)
    }

    /** Presence heartbeat + periodic TURN credential refresh. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var ticks = 0
            while (isActive) {
                val client = signaling
                if (client?.isConnected == true) {
                    client.sendPresence(
                        batteryPct = SystemHealth.batteryPercent(this@RelayForegroundService),
                        charging = SystemHealth.isCharging(this@RelayForegroundService),
                        signalDbm = 0,
                        simState = SystemHealth.simState(this@RelayForegroundService),
                        appVersion = installedVersionName(),
                        model = android.os.Build.MODEL,
                    )
                    // TURN credentials live one hour; refresh well inside that.
                    if (ticks % 60 == 0) client.refreshIceServers()
                } else {
                    // The service-level connect(), not `client?.connect()`.
                    //
                    // When the service starts before the first receiver has
                    // joined, connect() bails out at the `isPaired` check and
                    // leaves `signaling` null. `client?.connect()` is then a
                    // no-op on null and this loop spins every 30s doing nothing
                    // for the life of the process — the gateway sits on the
                    // pairing screen forever even after a receiver is
                    // confirmed. Calling connect() re-reads `isPaired` and
                    // builds the client once pairing has actually completed,
                    // so the gateway self-heals within one heartbeat from any
                    // path that left it idle.
                    connect()
                }
                ticks++
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inbound events from the client
    // ─────────────────────────────────────────────────────────────────────────

    private fun onRelayEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.Encrypted -> handlePayload(event.event, event.json, event.fromDeviceId)
            is RelayEvent.SessionReady -> {
                callBridge.onIceServers(event.session.iceServers)
                updateNotification()
            }
            is RelayEvent.Presence -> updateNotification()
            is RelayEvent.PeerJoined -> {
                // A new receiver paired while we were running — pick up its key
                // without waiting for the next full reconnect.
                GatewayRuntime.secureStore.cryptoBox(event.peer.deviceId)?.let {
                    signaling?.addBox(event.peer.deviceId, it)
                }
                updateNotification()
            }
            is RelayEvent.PeerLeft -> {
                signaling?.removeBox(event.deviceId)
                updateNotification()
            }
            is RelayEvent.QueueFlushed -> Log.i(TAG, "flushed ${event.count} queued items")
        }
    }

    /** [fromDeviceId] is the receiver that sent this payload — used to target replies. */
    private fun handlePayload(event: String, payloadJson: String, fromDeviceId: String) {
        runCatching {
            when (event) {
                Ev.SMS_OUTBOUND -> {
                    val request = json.decodeFromString<SmsOutboundRequest>(payloadJson)
                    Log.i(TAG, "outbound SMS request ${request.id}")
                    smsSender.send(request)
                    emit(Ev.SMS_STATUS, SmsStatusUpdate(request.id, com.relay.core.model.SmsState.SENDING))
                }

                // Only the receiver that asked gets the sync page — the others
                // already have this history or will ask when they need it.
                Ev.SMS_SYNC -> {
                    val request = json.decodeFromString<SmsSyncRequest>(payloadJson)
                    scope.launch {
                        val page = smsInbox.query(request.sinceTs, request.limit)
                        emitTo(fromDeviceId, Ev.SMS_SYNC_RESULT, SmsSyncResult(page.messages, page.hasMore))
                    }
                }

                Ev.CONTACTS_SYNC -> scope.launch { syncContacts(fromDeviceId) }

                Ev.CALL_PLACE -> {
                    val place = json.decodeFromString<CallPlace>(payloadJson)
                    callBridge.placeCall(place.callId, place.to, fromDeviceId)
                }
                Ev.CALL_ANSWER -> {
                    val ref = json.decodeFromString<CallRef>(payloadJson)
                    acquireWakeLock()
                    callBridge.answer(ref.callId, fromDeviceId)
                }
                Ev.CALL_REJECT -> {
                    val ref = json.decodeFromString<CallRef>(payloadJson)
                    callBridge.reject(ref.callId, ref.reason, fromDeviceId)
                }
                Ev.CALL_HANGUP -> {
                    val ref = json.decodeFromString<CallRef>(payloadJson)
                    callBridge.hangup(ref.callId, ref.reason, fromDeviceId)
                    releaseWakeLock()
                }
                Ev.CALL_DTMF -> {
                    val dtmf = json.decodeFromString<CallDtmf>(payloadJson)
                    callBridge.sendDtmf(dtmf.callId, dtmf.tone, fromDeviceId)
                }
                Ev.CALL_MUTE -> {
                    val mute = json.decodeFromString<CallMute>(payloadJson)
                    callBridge.setRemoteMuted(mute.callId, mute.muted, fromDeviceId)
                }

                Ev.RTC_ANSWER -> {
                    val sdp = json.decodeFromString<RtcSdp>(payloadJson)
                    callBridge.onRemoteAnswer(sdp.callId, sdp.sdp, fromDeviceId)
                }
                Ev.RTC_ICE -> {
                    val ice = json.decodeFromString<RtcIce>(payloadJson)
                    callBridge.onRemoteIce(ice, fromDeviceId)
                }
                Ev.RTC_RENEGOTIATE -> {
                    val ref = json.decodeFromString<CallRef>(payloadJson)
                    callBridge.renegotiate(ref.callId, fromDeviceId)
                }

                else -> Log.w(TAG, "unhandled event $event")
            }
        }.onFailure { Log.e(TAG, "handling $event failed", it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Outbound
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Encrypt-and-send to **every** paired receiver, queuing locally if none
     * could be reached while the socket was momentarily down.
     */
    private fun emit(event: String, plaintextJson: String) {
        val client = signaling
        val delivered = client?.broadcast(event, plaintextJson) ?: 0
        if (delivered == 0) {
            synchronized(pendingOutbound) {
                pendingOutbound.addLast(event to plaintextJson)
                while (pendingOutbound.size > MAX_PENDING) pendingOutbound.removeFirst()
            }
            Log.w(TAG, "queued $event locally (no receivers reached)")
            client?.connect()
        }
    }

    private inline fun <reified T> emit(event: String, payload: T) =
        emit(event, json.encodeToString(payload))

    /** Encrypt-and-send to exactly one receiver — used for targeted replies. */
    /**
     * Send the address book to one receiver, in pages small enough to survive
     * the relay's envelope cap.
     *
     * The previous version sent the whole book as a single [ContactsResult].
     * With avatars attached that is megabytes; the relay rejects any envelope
     * over `MAX_ENVELOPE_BYTES` (128 KB) and drops it without an error, so the
     * receiver's contact list simply stayed empty forever with nothing in the
     * log to say why.
     *
     * Names go first with the photo field stripped — that is a couple of pages
     * for a 500-entry book, so the list fills almost at once — then the photos
     * follow and the client merges each into the row already showing.
     */
    private suspend fun syncContacts(toDeviceId: String) {
        val book = contactsMirror.load()
        if (book.isEmpty()) {
            // Still answer, so the receiver can tell "none" from "never arrived"
            // and stop showing a spinner.
            emitTo(toDeviceId, Ev.CONTACTS_RESULT, ContactsResult(emptyList(), 0, 1, false))
            return
        }

        val lean = paginate(book.map { it.copy(photoB64 = "") })
        lean.forEachIndexed { index, page ->
            emitTo(toDeviceId, Ev.CONTACTS_RESULT, ContactsResult(page, index, lean.size, false))
            delay(PAGE_GAP_MS)
        }

        val withPhotos = book.filter { it.photoB64.isNotEmpty() }
        if (withPhotos.isEmpty()) return

        val photos = paginate(withPhotos)
        photos.forEachIndexed { index, page ->
            emitTo(toDeviceId, Ev.CONTACTS_RESULT, ContactsResult(page, index, photos.size, true))
            delay(PAGE_GAP_MS)
        }
        Log.i(TAG, "contacts: ${book.size} entries in ${lean.size}+${photos.size} pages")
    }

    /**
     * Cut a contact list into pages by measured serialized size rather than by
     * a fixed count, because entries differ by three orders of magnitude — a
     * name and number is ~60 bytes, the same contact with an avatar is ~6 KB.
     * A fixed page size would be either wasteful or occasionally over the cap.
     */
    private fun paginate(contacts: List<Contact>): List<List<Contact>> {
        val pages = mutableListOf<List<Contact>>()
        var current = mutableListOf<Contact>()
        var size = 0

        for (contact in contacts) {
            val cost = json.encodeToString(contact).length + 1
            if (current.isNotEmpty() && size + cost > PAGE_BUDGET_CHARS) {
                pages += current
                current = mutableListOf()
                size = 0
            }
            current += contact
            size += cost
        }
        if (current.isNotEmpty()) pages += current
        return pages
    }

    private fun emitTo(peerDeviceId: String, event: String, plaintextJson: String) {
        if (signaling?.sendTo(peerDeviceId, event, plaintextJson) != true) {
            Log.w(TAG, "failed to deliver $event to $peerDeviceId")
        }
    }

    private inline fun <reified T> emitTo(peerDeviceId: String, event: String, payload: T) =
        emitTo(peerDeviceId, event, json.encodeToString(payload))

    private fun flushPendingOutbound() {
        val client = signaling ?: return
        val drained = synchronized(pendingOutbound) {
            val copy = pendingOutbound.toList()
            pendingOutbound.clear()
            copy
        }
        if (drained.isEmpty()) return
        Log.i(TAG, "flushing ${drained.size} locally queued events")
        for ((event, payload) in drained) {
            if (client.broadcast(event, payload) == 0) {
                synchronized(pendingOutbound) { pendingOutbound.addLast(event to payload) }
            }
        }
    }

    /** Called by [com.relay.gateway.sms.SmsBroadcastReceiver] via startService. */
    private fun relayInboundSms(messageJson: String) {
        emit(Ev.SMS_INBOUND, messageJson)
        runCatching {
            val message = json.decodeFromString<SmsMessage>(messageJson)
            Log.i(TAG, "relayed inbound SMS from ${message.address.takeLast(4).padStart(4, '*')}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification + wake lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun foregroundTypes(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun buildNotification(status: String, connected: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GatewayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, SystemHealth.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_relay_status)
            .setContentTitle(if (connected) "Relay active" else "Relay reconnecting")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification() {
        val client = signaling
        val connected = client?.isConnected == true
        val onlineCount = client?.peers?.value?.count { it.online } ?: 0
        val anyOnline = client?.anyPeerOnline == true
        val call = callBridge.activeCallSummary()

        val status = when {
            !GatewayRuntime.secureStore.isPaired -> "Not paired — open the app to pair"
            call != null -> call
            connected && anyOnline -> "Connected · $onlineCount receiver${if (onlineCount == 1) "" else "s"} online"
            connected -> "Connected · waiting for receivers"
            else -> "Reconnecting…"
        }

        getSystemService<android.app.NotificationManager>()
            ?.notify(SystemHealth.NOTIFICATION_SERVICE_ID, buildNotification(status, connected))
    }

    /**
     * Held only while a call is bridged. A permanent wake lock would cost several
     * percent of battery per hour; the foreground service alone is enough to keep
     * the socket alive between calls.
     */
    /**
     * The installed app's version name, read from the package manager.
     *
     * Deliberately *not* `BuildConfig.VERSION_NAME`: this is a library module,
     * and the Android Gradle plugin only emits VERSION_NAME into an
     * *application* module's BuildConfig. Asking the package manager also has
     * the nicer property of reporting what is actually installed rather than
     * what this module happened to be compiled against.
     */
    private fun installedVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:call")
            ?.apply { setReferenceCounted(false); acquire(MAX_CALL_WAKE_MS) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "RelayFgService"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_PENDING = 300

        // 40 KB of JSON per page against a 128 KB envelope cap. The gap leaves
        // room for the base64 expansion of the ciphertext and the envelope
        // header, with margin — going over means the page is dropped silently.
        private const val PAGE_BUDGET_CHARS = 40 * 1024

        // Paced so a large book does not monopolise the socket while a call or
        // an inbound SMS is trying to get through.
        private const val PAGE_GAP_MS = 40L
        private const val MAX_CALL_WAKE_MS = 4 * 60 * 60 * 1000L

        const val ACTION_RECONNECT = "com.relay.gateway.RECONNECT"
        const val ACTION_STOP = "com.relay.gateway.STOP"
        const val ACTION_SMS_RECEIVED = "com.relay.gateway.SMS_RECEIVED"
        const val ACTION_SMS_STATUS = "com.relay.gateway.SMS_STATUS_UPDATE"
        const val ACTION_RESURRECT = "com.relay.gateway.RESURRECT"
        const val EXTRA_SMS_JSON = "sms_json"
        const val EXTRA_STATUS_JSON = "status_json"

        /** Live instance, used by the InCallService to reach the bridge. */
        @Volatile
        var instance: RelayForegroundService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Ask a running service to re-evaluate pairing and open its socket.
         *
         * [start] alone is not enough once the service exists: a plain start
         * re-enters `onStartCommand` with no action, which does not call
         * `connect()`. Only `onCreate` connects unconditionally, and that runs
         * once. This carries the action that the `ACTION_RECONNECT` branch
         * handles, so it works whether the service is already up or is being
         * created by this very call.
         */
        fun reconnect(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
                .setAction(ACTION_RECONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun deliverInboundSms(context: Context, messageJson: String) {
            val intent = Intent(context, RelayForegroundService::class.java)
                .setAction(ACTION_SMS_RECEIVED)
                .putExtra(EXTRA_SMS_JSON, messageJson)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun deliverSmsStatus(context: Context, statusJson: String) {
            val intent = Intent(context, RelayForegroundService::class.java)
                .setAction(ACTION_SMS_STATUS)
                .putExtra(EXTRA_STATUS_JSON, statusJson)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
