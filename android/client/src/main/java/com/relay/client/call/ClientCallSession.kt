package com.relay.client.call

import android.content.Context
import android.util.Log
import com.relay.client.data.RelayRepository
import com.relay.client.data.Signal
import com.relay.core.model.RtcIce
import com.relay.core.webrtc.WebRtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Agent 4 — the client end of the audio bridge.
 *
 * The gateway always offers, so this side's job is narrow and therefore robust:
 * apply the remote offer, answer it, trickle ICE, and surface live quality
 * metrics for the call screen's HD readout.
 *
 * One session per call. Reusing a PeerConnection across calls leaks DTLS state
 * and produces one-way audio on the second call.
 */
class ClientCallSession(
    private val context: Context,
    private val repository: RelayRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { encodeDefaults = true }

    private var engine: WebRtcEngine? = null
    private var callId: String = ""

    /** ICE that outran the offer. Applying it early throws in libwebrtc. */
    private val pendingIce = ConcurrentLinkedQueue<RtcIce>()
    private var remoteDescriptionApplied = false

    private val _quality = MutableStateFlow(WebRtcEngine.CallQuality())
    val quality: StateFlow<WebRtcEngine.CallQuality> = _quality.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    init {
        // Consume the repository's one-shot signals for the media plane.
        scope.launch {
            repository.signals.collect { signal ->
                when (signal) {
                    is Signal.RemoteOffer -> onRemoteOffer(signal.callId, signal.sdp)
                    is Signal.RemoteIce -> onRemoteIce(signal.ice)
                    is Signal.CallEnded -> close()
                    else -> Unit
                }
            }
        }
    }

    /**
     * Prepare the engine before the offer lands so answering is instant.
     * Safe to call more than once for the same call.
     */
    fun prepare(newCallId: String) {
        if (engine != null && callId == newCallId) return
        close()

        callId = newCallId
        remoteDescriptionApplied = false
        pendingIce.clear()

        val webRtc = WebRtcEngine(
            context = context,
            profile = WebRtcEngine.AudioProfile.HANDSET,
            callbacks = object : WebRtcEngine.Callbacks {

                override fun onLocalDescription(type: String, sdp: String) {
                    if (type == "answer") repository.sendAnswerSdp(callId, sdp)
                }

                override fun onIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    repository.sendIce(RtcIce(callId, sdpMid, sdpMLineIndex, candidate))
                }

                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    _connected.value = state == PeerConnection.PeerConnectionState.CONNECTED
                    if (state == PeerConnection.PeerConnectionState.FAILED) {
                        // The gateway owns the offer, so recovery is its job.
                        // Ask it for an ICE restart rather than racing it.
                        Log.w(TAG, "connection failed — requesting ICE restart")
                        remoteDescriptionApplied = false
                        pendingIce.clear()
                        repository.requestRenegotiate(callId)
                    }
                }

                override fun onRemoteAudioTrack(track: AudioTrack) {
                    // Playback is handled by the ADM; enabling the track is all
                    // that is required to route it to the earpiece/speaker.
                    track.setEnabled(true)
                }

                override fun onStats(stats: WebRtcEngine.CallQuality) {
                    _quality.value = stats
                    // Feed the server's quality log, and with it the UI readout.
                    repository.reportStats(
                        json.encodeToString(
                            com.relay.core.model.RtcStats(
                                callId = callId,
                                rttMs = stats.rttMs,
                                jitterMs = stats.jitterMs,
                                lossPct = stats.lossPct,
                                bitrateKbps = stats.bitrateKbps,
                                codec = stats.codec,
                                audioLevel = stats.audioLevel,
                            ),
                        ),
                    )
                }

                override fun onError(message: String, cause: Throwable?) {
                    Log.e(TAG, "webrtc: $message", cause)
                }
            },
        )

        engine = webRtc
        webRtc.initialize()

        // A PeerConnection built with an empty ICE list can only ever gather host
        // candidates, so it fails behind any NAT. This happens on the very first
        // call after pairing, before `session:ready` has landed — so ask for
        // servers and fall back to public STUN rather than building a doomed
        // connection.
        val servers = repository.iceServers.value.ifEmpty {
            Log.w(TAG, "no ICE servers yet — requesting refresh, using STUN fallback")
            repository.refreshIceServers()
            listOf(
                com.relay.core.model.IceServerDto(
                    urls = listOf(
                        "stun:stun.l.google.com:19302",
                        "stun:stun1.l.google.com:19302",
                    ),
                ),
            )
        }

        webRtc.createPeerConnection(servers)
        Log.i(TAG, "session prepared for $newCallId (${servers.size} ICE server entries)")
    }

    private fun onRemoteOffer(offerCallId: String, sdp: String) {
        if (offerCallId != callId) prepare(offerCallId)
        val webRtc = engine ?: return

        webRtc.setRemoteDescription("offer", sdp)
        remoteDescriptionApplied = true

        // Drain candidates that arrived before the offer.
        while (true) {
            val ice = pendingIce.poll() ?: break
            webRtc.addIceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        }

        webRtc.createAnswer()
        Log.i(TAG, "answered offer for $callId")
    }

    private fun onRemoteIce(ice: RtcIce) {
        if (ice.callId != callId) return
        if (ice.candidate.isEmpty()) return          // end-of-candidates marker
        if (!remoteDescriptionApplied) {
            pendingIce.add(ice)
            return
        }
        engine?.addIceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    /** Mute our microphone so the far end stops hearing us. */
    fun setMuted(muted: Boolean) = engine?.setMicrophoneMuted(muted)

    fun setSpeakerphone(on: Boolean) = engine?.setSpeakerphone(on)

    /** Mute the incoming audio locally without telling the far end. */
    fun setIncomingMuted(muted: Boolean) = engine?.setRemoteAudioMuted(muted)

    fun close() {
        engine?.close()
        engine = null
        _connected.value = false
        _quality.value = WebRtcEngine.CallQuality()
        remoteDescriptionApplied = false
        pendingIce.clear()
        callId = ""
    }

    private companion object { const val TAG = "ClientCallSession" }
}
