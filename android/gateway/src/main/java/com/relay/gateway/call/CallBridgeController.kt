package com.relay.gateway.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.getSystemService
import com.relay.core.model.CallIncoming
import com.relay.core.model.CallState
import com.relay.core.model.CallStateUpdate
import com.relay.core.model.Ev
import com.relay.core.model.IceServerDto
import com.relay.core.model.RtcIce
import com.relay.core.model.RtcSdp
import com.relay.core.net.SignalingClient
import com.relay.core.webrtc.WebRtcEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Agent 3 — the call state machine that glues Telecom to WebRTC.
 *
 * Lives inside the foreground service so a PeerConnection outlives Telecom
 * binding and unbinding [RelayInCallService]. One instance, one active bridged
 * call — relaying two simultaneous calls would require two capture paths, which
 * no Android device provides.
 *
 * The gateway is always the WebRTC offerer, so there is no glare to resolve.
 *
 * With several receivers able to ring for the same cellular call, exactly one
 * of them wins the bridge: whichever `call:answer` arrives first. [answeringDeviceId]
 * records the winner; every other receiver is told the call ended
 * (`cause = "answered_elsewhere"`) and any later control message from a
 * non-winning device is silently dropped.
 */
class CallBridgeController(
    private val context: Context,
    /** [targetDeviceId] null means broadcast to every paired receiver. */
    private val emit: (event: String, plaintextJson: String, targetDeviceId: String?) -> Unit,
    private val onStateChanged: () -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val audioBridge = CallAudioBridge(context)
    private val telecom = context.getSystemService<TelecomManager>()

    private var signaling: SignalingClient? = null
    private var iceServers: List<IceServerDto> = emptyList()

    private var engine: WebRtcEngine? = null
    private var telecomCall: Call? = null
    private var callId: String = ""
    private var state: CallState = CallState.IDLE
    private var activeStrategy: CallAudioBridge.Strategy? = null

    /** The receiver that won the answer race for [callId]. Empty until answered. */
    private var answeringDeviceId: String = ""

    /** ICE candidates that arrive before the remote description is applied. */
    private val pendingRemoteIce = ConcurrentLinkedQueue<RtcIce>()
    private var remoteDescriptionSet = false

    init { current = this }

    fun attachSignaling(client: SignalingClient) { signaling = client }
    fun onIceServers(servers: List<IceServerDto>) {
        if (servers.isNotEmpty()) iceServers = servers
    }

    fun activeCallSummary(): String? = when (state) {
        CallState.IDLE, CallState.ENDED -> null
        CallState.RINGING -> "Incoming call — ringing on client"
        CallState.DIALING, CallState.CONNECTING -> "Connecting call…"
        CallState.ACTIVE -> "Call bridged · ${activeStrategy?.label ?: "audio"}"
        CallState.HELD -> "Call on hold"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Telecom → relay
    // ─────────────────────────────────────────────────────────────────────────

    fun onTelecomCallAdded(call: Call) {
        // Never relay an emergency call — the user must speak on the handset
        // that is actually placing it, and interfering is dangerous.
        if (isEmergency(call)) {
            Log.w(TAG, "emergency call detected — bridge stands down")
            return
        }
        if (telecomCall != null && telecomCall !== call) {
            Log.w(TAG, "second concurrent call ignored; one bridge at a time")
            return
        }

        telecomCall = call
        callId = UUID.randomUUID().toString()
        answeringDeviceId = ""
        remoteDescriptionSet = false
        pendingRemoteIce.clear()

        when (call.state) {
            Call.STATE_RINGING -> {
                state = CallState.RINGING
                val number = handleOf(call)
                // Broadcast: nobody has answered yet, so every receiver rings.
                emit(
                    Ev.CALL_INCOMING,
                    json.encodeToString(
                        CallIncoming(
                            callId = callId,
                            from = number,
                            displayName = call.details?.callerDisplayName.orEmpty(),
                        ),
                    ),
                    null,
                )
                pushState(CallState.RINGING)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                state = CallState.DIALING
                pushState(CallState.DIALING)
            }
            Call.STATE_ACTIVE -> {
                // Call was already up (e.g. we bound mid-call after a restart).
                startBridge()
            }
        }
        onStateChanged()
    }

    fun onTelecomStateChanged(call: Call, newState: Int) {
        if (call !== telecomCall) return
        when (newState) {
            Call.STATE_ACTIVE -> startBridge()
            Call.STATE_HOLDING -> { state = CallState.HELD; pushState(CallState.HELD) }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                val cause = call.details?.disconnectCause?.reason.orEmpty()
                teardown(cause.ifEmpty { "remote_hangup" })
            }
            Call.STATE_DIALING -> { state = CallState.DIALING; pushState(CallState.DIALING) }
        }
        onStateChanged()
    }

    fun onTelecomCallRemoved(call: Call) {
        if (call === telecomCall) teardown("call_removed")
    }

    fun onTelecomDetailsChanged(call: Call, details: Call.Details) {
        // Nothing to relay today; hook exists for CDMA/VoLTE detail changes.
        Log.d(TAG, "details changed: caps=${details.callCapabilities}")
    }

    fun onCallAudioRouteChanged(route: Int) {
        // If Telecom yanks us off speakerphone while a loopback strategy is
        // active the bridge goes silent, so re-assert it.
        val strategy = activeStrategy ?: return
        if (!strategy.privileged) audioBridge.prepareRouting(strategy)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Relay → Telecom
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * First `call:answer` for [requestedCallId] wins the bridge. A later answer
     * from a different device is ignored; every device that lost the race is
     * told the call ended so its ringing UI clears.
     */
    fun answer(requestedCallId: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (answeringDeviceId.isNotEmpty()) {
            if (answeringDeviceId != fromDeviceId) {
                Log.i(TAG, "ignoring answer from $fromDeviceId — $answeringDeviceId already won")
            }
            return
        }
        answeringDeviceId = fromDeviceId
        notifyLosers()

        val call = telecomCall ?: return
        Log.i(TAG, "answering on behalf of $fromDeviceId")
        pushState(CallState.CONNECTING)
        runCatching {
            @Suppress("DEPRECATION")
            call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
        }.onFailure {
            Log.e(TAG, "answer failed", it)
            pushState(CallState.ENDED, cause = "answer_failed")
        }
    }

    /** Tell every receiver other than the winner that the call is no longer theirs. */
    private fun notifyLosers() {
        val losers = signaling?.knownPeerIds().orEmpty() - answeringDeviceId
        for (peerId in losers) {
            emit(
                Ev.CALL_STATE,
                json.encodeToString(
                    CallStateUpdate(callId = callId, state = CallState.ENDED, cause = "answered_elsewhere"),
                ),
                peerId,
            )
        }
    }

    fun reject(requestedCallId: String, reason: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        runCatching { telecomCall?.reject(false, null) }
        teardown(reason.ifEmpty { "rejected_by_client" })
    }

    fun hangup(requestedCallId: String, reason: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        runCatching { telecomCall?.disconnect() }
        teardown(reason.ifEmpty { "client_hangup" })
    }

    fun sendDtmf(requestedCallId: String, tone: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        val call = telecomCall ?: return
        for (char in tone) {
            runCatching {
                call.playDtmfTone(char)
                call.stopDtmfTone()
            }
        }
    }

    /** Mute what the far end hears (i.e. disable our outgoing WebRTC track). */
    fun setRemoteMuted(requestedCallId: String, muted: Boolean, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        engine?.setMicrophoneMuted(muted)
    }

    /** True once someone has answered and [fromDeviceId] is not that device. */
    private fun isFromLoser(fromDeviceId: String): Boolean =
        answeringDeviceId.isNotEmpty() && fromDeviceId != answeringDeviceId

    /**
     * Place an outbound cellular call on behalf of [fromDeviceId].
     * Requires `CALL_PHONE`; Telecom will surface the dialing call back through
     * [onTelecomCallAdded], which starts the bridge. There is no race to
     * arbitrate here — the requester owns the call from the start.
     */
    fun placeCall(requestedCallId: String, destination: String, fromDeviceId: String) {
        callId = requestedCallId
        answeringDeviceId = fromDeviceId
        state = CallState.DIALING
        pushState(CallState.DIALING)

        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pushState(CallState.ENDED, cause = "call_phone_permission_missing")
            return
        }

        val uri = Uri.fromParts("tel", destination, null)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && telecom != null) {
                val extras = android.os.Bundle().apply {
                    putInt(
                        TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                        android.telecom.VideoProfile.STATE_AUDIO_ONLY,
                    )
                }
                telecom.placeCall(uri, extras)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }.onFailure {
            Log.e(TAG, "placeCall failed", it)
            pushState(CallState.ENDED, cause = "place_failed")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebRTC
    // ─────────────────────────────────────────────────────────────────────────

    private fun startBridge() {
        if (engine != null) {
            state = CallState.ACTIVE
            pushState(CallState.ACTIVE)
            return
        }
        Log.i(TAG, "starting audio bridge for $callId")
        state = CallState.CONNECTING
        pushState(CallState.CONNECTING)

        val sources = audioBridge.preferredSources()

        val webRtc = WebRtcEngine(
            context = context,
            profile = WebRtcEngine.AudioProfile.TELEPHONY_BRIDGE,
            callbacks = object : WebRtcEngine.Callbacks {

                override fun onLocalDescription(type: String, sdp: String) {
                    if (type == "offer") {
                        emitToActive(Ev.RTC_OFFER, json.encodeToString(RtcSdp(callId, type, sdp)))
                    }
                }

                override fun onIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    emitToActive(
                        Ev.RTC_ICE,
                        json.encodeToString(RtcIce(callId, sdpMid, sdpMLineIndex, candidate)),
                    )
                }

                override fun onConnectionStateChanged(pcState: PeerConnection.PeerConnectionState) {
                    when (pcState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            state = CallState.ACTIVE
                            pushState(CallState.ACTIVE)
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.w(TAG, "peer connection failed — attempting ICE restart")
                            engine?.createOffer(iceRestart = true)
                        }
                        PeerConnection.PeerConnectionState.CLOSED ->
                            teardown("peer_connection_closed")
                        else -> Unit
                    }
                    onStateChanged()
                }

                override fun onRemoteAudioTrack(track: AudioTrack) {
                    // The client's voice. Playing it out of the gateway's chosen
                    // output is what feeds the modem uplink.
                    track.setEnabled(true)
                }

                override fun onStats(stats: WebRtcEngine.CallQuality) = Unit

                override fun onError(message: String, cause: Throwable?) {
                    Log.e(TAG, "webrtc: $message", cause)
                }
            },
        )

        engine = webRtc
        webRtc.initialize(preferredSources = sources)

        // Discover which strategy actually opened and configure routing for it.
        activeStrategy = CallAudioBridge.Strategy.entries
            .firstOrNull { WebRtcEngine.sourceName(it.source) == webRtc.activeCaptureSource }
        activeStrategy?.let(audioBridge::prepareRouting)

        // Same guard as the client: an empty ICE list gathers host candidates
        // only and cannot traverse NAT. Fall back to public STUN and ask the
        // server for fresh TURN credentials in the background.
        val servers = iceServers.ifEmpty {
            Log.w(TAG, "no ICE servers cached — using STUN fallback")
            signaling?.refreshIceServers()
            listOf(
                IceServerDto(
                    urls = listOf(
                        "stun:stun.l.google.com:19302",
                        "stun:stun1.l.google.com:19302",
                    ),
                ),
            )
        }

        webRtc.createPeerConnection(servers)
        webRtc.createOffer()

        Log.i(TAG, "bridge up via ${activeStrategy?.label ?: webRtc.activeCaptureSource}")
    }

    fun onRemoteAnswer(requestedCallId: String, sdp: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        engine?.setRemoteDescription("answer", sdp)
        remoteDescriptionSet = true
        // Drain any candidates that raced ahead of the answer.
        while (true) {
            val ice = pendingRemoteIce.poll() ?: break
            engine?.addIceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        }
    }

    fun onRemoteIce(ice: RtcIce, fromDeviceId: String) {
        if (ice.callId != callId) return
        if (isFromLoser(fromDeviceId)) return
        if (!remoteDescriptionSet) {
            pendingRemoteIce.add(ice)
            return
        }
        engine?.addIceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
    }

    fun renegotiate(requestedCallId: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        remoteDescriptionSet = false
        engine?.createOffer(iceRestart = true)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun teardown(cause: String) {
        if (state == CallState.IDLE) return
        Log.i(TAG, "tearing down bridge: $cause")
        pushState(CallState.ENDED, cause)

        engine?.close()
        engine = null
        telecomCall = null
        activeStrategy = null
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        state = CallState.IDLE
        callId = ""
        answeringDeviceId = ""
        onStateChanged()
    }

    fun shutdown() {
        teardown("service_shutdown")
        if (current === this) current = null
    }

    private fun pushState(newState: CallState, cause: String = "") {
        state = newState
        emitToActive(
            Ev.CALL_STATE,
            json.encodeToString(
                CallStateUpdate(
                    callId = callId,
                    state = newState,
                    cause = cause,
                    audioMode = activeStrategy?.label
                        ?: engine?.activeCaptureSource.orEmpty(),
                ),
            ),
        )
    }

    /**
     * Route per-call traffic: broadcast while nobody has answered yet (so every
     * ringing receiver stays in sync), then only to whichever device won once
     * [answeringDeviceId] is set.
     */
    private fun emitToActive(event: String, plaintextJson: String) {
        emit(event, plaintextJson, answeringDeviceId.ifEmpty { null })
    }

    private fun handleOf(call: Call): String =
        call.details?.handle?.schemeSpecificPart ?: "unknown"

    /**
     * Is this an emergency call?
     *
     * The modern check hangs off `TelephonyManager`, not `TelecomManager` —
     * an easy thing to get wrong, because `TelecomManager` is what owns the
     * call object itself. It also needs READ_PHONE_STATE, which the user can
     * revoke, so the whole thing is wrapped: any failure answers "not an
     * emergency", which keeps the relay running rather than silently blocking
     * ordinary calls on a permission hiccup.
     */
    private fun isEmergency(call: Call): Boolean = runCatching {
        val number = handleOf(call)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService<android.telephony.TelephonyManager>()
                ?.isEmergencyNumber(number) == true
        } else {
            @Suppress("DEPRECATION")
            android.telephony.PhoneNumberUtils.isEmergencyNumber(number)
        }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "CallBridgeController"

        /** Reachable from [RelayInCallService], which Telecom owns. */
        @Volatile
        var current: CallBridgeController? = null
            private set
    }
}
