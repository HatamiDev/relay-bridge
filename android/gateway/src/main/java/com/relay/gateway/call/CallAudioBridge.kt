package com.relay.gateway.call

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService

/**
 * Agent 3 + Agent 6 — the honest audio-capture strategy resolver.
 *
 * ## Why this class exists
 *
 * Android does **not** give ordinary apps the cellular voice-call audio stream.
 * `MediaRecorder.AudioSource.VOICE_CALL`, `VOICE_DOWNLINK` and `VOICE_UPLINK`
 * are gated behind `android.permission.CAPTURE_AUDIO_OUTPUT`, which carries
 * `protectionLevel="signature|privileged"`. A normally-installed APK cannot hold
 * it. Samsung additionally blocks these sources at the HAL on most retail
 * firmware even for privileged callers.
 *
 * Code that simply calls `setAudioSource(VOICE_CALL)` and hopes therefore ships
 * silence on the majority of devices. This resolver instead probes what the
 * device will actually grant and reports the answer, so the client UI can show
 * the user the truth ("Loopback mode — keep the gateway handset face-down").
 *
 * ## Strategies, best to worst
 *
 * | Strategy | Requires | Quality | Both legs? |
 * |---|---|---|---|
 * | `VOICE_CALL` | privileged/system install, or Knox-enabled MDM build | excellent | yes |
 * | `VOICE_DOWNLINK` | same | excellent | far end only |
 * | `VOICE_COMMUNICATION` + speakerphone | ordinary install | fair | yes, acoustically |
 * | `MIC` + speakerphone | ordinary install | poor | yes, acoustically |
 *
 * The loopback strategies work because the gateway puts the cellular call on
 * speakerphone: the far end's voice comes out of the loudspeaker, our AudioRecord
 * picks it up, and our WebRTC playback goes back out of the same speaker where
 * the modem's uplink mic captures it. Crude, but it is the only thing that
 * functions on a stock retail device — and hardware AEC keeps it from howling.
 */
class CallAudioBridge(private val context: Context) {

    enum class Strategy(val source: Int, val label: String, val privileged: Boolean) {
        VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL, "Telephony (both legs)", true),
        VOICE_DOWNLINK(MediaRecorder.AudioSource.VOICE_DOWNLINK, "Telephony (far end)", true),
        LOOPBACK_COMM(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "Speakerphone loopback",
            false,
        ),
        LOOPBACK_MIC(MediaRecorder.AudioSource.MIC, "Microphone loopback", false),
    }

    private val audioManager = context.getSystemService<AudioManager>()

    /**
     * Ordered list of sources to hand to `WebRtcEngine.initialize`.
     *
     * Privileged strategies are only offered when the app actually holds
     * `CAPTURE_AUDIO_OUTPUT`; otherwise probing them wastes ~200 ms per call
     * setup on an AudioRecord that is guaranteed to fail.
     */
    fun preferredSources(): List<Int> = availableStrategies().map { it.source }

    fun availableStrategies(): List<Strategy> {
        val privileged = hasCaptureAudioOutput()
        return Strategy.entries
            .filter { !it.privileged || privileged }
            .also {
                Log.i(
                    TAG,
                    "capture strategies: ${it.joinToString { s -> s.name }} " +
                        "(privileged=$privileged)",
                )
            }
    }

    /**
     * True when this build was installed with system/privileged privileges and
     * therefore may open the telephony audio sources.
     */
    fun hasCaptureAudioOutput(): Boolean =
        context.checkSelfPermission(CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED

    /** True when the OS reports another app already owns the capture path. */
    fun isCaptureContested(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        audioManager?.activeRecordingConfigurations?.any {
            it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                it.clientAudioSource == MediaRecorder.AudioSource.VOICE_CALL
        } ?: false
    }.getOrDefault(false)

    /**
     * Prepare the device's audio policy for a bridged call.
     *
     * @param strategy the source that [com.relay.core.webrtc.WebRtcEngine]
     *        actually managed to open
     */
    fun prepareRouting(strategy: Strategy) {
        val am = audioManager ?: return
        runCatching {
            am.mode = AudioManager.MODE_IN_CALL
            when (strategy) {
                Strategy.VOICE_CALL, Strategy.VOICE_DOWNLINK -> {
                    // Privileged tap: no acoustic path needed, keep the earpiece
                    // so a person standing next to the gateway hears nothing.
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                Strategy.LOOPBACK_COMM, Strategy.LOOPBACK_MIC -> {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    // Push the loudspeaker up so the far end is clearly captured,
                    // but not to max — clipping destroys the AEC reference.
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    am.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        (max * 0.8f).toInt().coerceAtLeast(1),
                        0,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "routing setup failed", it) }
    }

    /** Advice text surfaced on the client so the user knows what to expect. */
    fun advisoryFor(strategy: Strategy?): String = when (strategy) {
        Strategy.VOICE_CALL -> "Direct telephony tap — full duplex, HD."
        Strategy.VOICE_DOWNLINK ->
            "Telephony tap (far end only). Your voice reaches them through the " +
                "gateway's microphone, so keep it in a quiet place."
        Strategy.LOOPBACK_COMM, Strategy.LOOPBACK_MIC ->
            "Speakerphone loopback — this device's firmware does not expose the " +
                "call stream. Keep the gateway phone in a quiet room; ambient " +
                "noise will be audible to both parties."
        null -> "No audio capture path available on this device."
    }

    private companion object {
        const val TAG = "CallAudioBridge"
        const val CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"
    }
}
