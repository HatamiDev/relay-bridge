package com.relay.gateway.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.relay.gateway.service.RelayForegroundService
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 3 — cellular call interception.
 *
 * Binding this service requires **two** things, and permissions alone are not
 * enough:
 *
 *  1. `BIND_INCALL_SERVICE` in the manifest (declared), and
 *  2. the app holding `RoleManager.ROLE_DIALER` at runtime (requested from
 *     [com.relay.gateway.ui.GatewayActivity]).
 *
 * We deliberately declare `IN_CALL_SERVICE_UI = false`. That keeps the stock
 * Samsung in-call screen as the visible UI on the gateway handset while still
 * giving us the `Call` objects — so the Note 10+ remains a usable phone and the
 * relay is purely additive. If we claimed the UI we would have to reimplement
 * the entire dialer, emergency-call handling included, which is the wrong
 * trade for a bridge.
 *
 * All state mutation is funnelled to [CallBridgeController] through the running
 * foreground service, because Telecom binds this service on its own schedule and
 * it must not own long-lived resources like PeerConnections.
 */
class RelayInCallService : InCallService() {

    private val callbacks = ConcurrentHashMap<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(TAG, "call added, state=${stateName(call.state)}")

        val callback = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                Log.i(TAG, "call state → ${stateName(state)}")
                bridge()?.onTelecomStateChanged(c, state)
            }

            override fun onDetailsChanged(c: Call, details: Call.Details) {
                bridge()?.onTelecomDetailsChanged(c, details)
            }

            override fun onPostDialWait(c: Call, remaining: String) {
                // Continue through any post-dial pause so relayed DTMF works on IVRs.
                c.postDialContinue(true)
            }
        }

        callbacks[call] = callback
        call.registerCallback(callback)

        activeCalls.add(call)
        bridge()?.onTelecomCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "call removed")
        callbacks.remove(call)?.let(call::unregisterCallback)
        activeCalls.remove(call)
        bridge()?.onTelecomCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        Log.d(TAG, "audio route=${CallAudioState.audioRouteToString(audioState.route)}")
        bridge()?.onCallAudioRouteChanged(audioState.route)
    }

    override fun onSilenceRinger() {
        super.onSilenceRinger()
        Log.d(TAG, "ringer silenced")
    }

    /**
     * The bridge lives in the foreground service so it survives Telecom
     * unbinding us between calls. Null means the service is not running, which
     * only happens when the device is unpaired.
     */
    private fun bridge(): CallBridgeController? =
        RelayForegroundService.instance?.let { CallBridgeController.current }

    companion object {
        private const val TAG = "RelayInCallService"

        /**
         * Telecom hands out `Call` objects only through this service, so the
         * controller needs a way to reach them. A plain synchronized set is
         * enough — there are at most two or three calls at any moment.
         */
        val activeCalls: MutableSet<Call> =
            java.util.Collections.synchronizedSet(mutableSetOf())

        fun stateName(state: Int): String = when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            Call.STATE_PULLING_CALL -> "PULLING"
            Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"
            Call.STATE_SIMULATED_RINGING -> "SIMULATED_RINGING"
            else -> "UNKNOWN($state)"
        }
    }
}
