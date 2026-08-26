package com.relay.client.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Agent 4 — the voice-message capsule.
 *
 * Spec: a pill with a Play/Pause button, blue audio waveform bars, and a
 * duration timestamp.
 *
 * Three details that make it feel real rather than decorative:
 *
 *  1. **The waveform is the scrubber.** Tapping a bar seeks there. A separate
 *     slider under a waveform is redundant chrome.
 *  2. **Played bars are gradient, unplayed are flat and faint.** Progress is
 *     read from colour, so no progress bar is needed at all.
 *  3. **Bars have a minimum height.** A true amplitude of zero renders as an
 *     invisible gap, which looks like a rendering bug rather than silence.
 */
@Composable
fun VoiceCapsule(
    /** Normalised 0..1 amplitudes, typically 32–48 samples. */
    waveform: List<Float>,
    durationMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
    outgoing: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val shape = RoundedCornerShape(50)

    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(progress, tween(120), label = "voiceProgress")

    Row(
        modifier = modifier
            .widthIn(max = 288.dp)
            .clip(shape)
            .then(
                if (outgoing) {
                    Modifier.background(colors.outgoingAccentBrush)
                } else {
                    Modifier
                        .background(colors.bubbleIncoming)
                        .background(colors.sheenBrush)
                        .border(1.dp, colors.glassBorder, shape)
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Play / pause ─────────────────────────────────────────────────────
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (outgoing) Color.White.copy(alpha = 0.18f) else colors.accentSoft,
                )
                .then(
                    if (isPlaying) Modifier.glow(colors.accent, CircleShape, 10.dp) else Modifier,
                )
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = if (outgoing) Color.White else colors.accent,
                modifier = Modifier.size(21.dp),
            )
        }

        Spacer(Modifier.width(10.dp))

        // ── Waveform (doubles as the scrubber) ───────────────────────────────
        Waveform(
            amplitudes = waveform,
            progress = animatedProgress,
            outgoing = outgoing,
            onSeek = onSeek,
            modifier = Modifier
                .weight(1f)
                .height(30.dp),
        )

        Spacer(Modifier.width(10.dp))

        // ── Duration ─────────────────────────────────────────────────────────
        Text(
            text = formatDuration(if (isPlaying || positionMs > 0) positionMs else durationMs),
            color = if (outgoing) Color.White.copy(alpha = 0.85f) else colors.textSecondary,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

@Composable
private fun Waveform(
    amplitudes: List<Float>,
    progress: Float,
    outgoing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val bars = remember(amplitudes) {
        if (amplitudes.isEmpty()) defaultWaveform() else amplitudes
    }

    val activeBrush = if (outgoing) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color.White, Color.White.copy(alpha = 0.85f)),
        )
    } else {
        colors.waveformBrush
    }
    val inactiveColor =
        if (outgoing) Color.White.copy(alpha = 0.32f) else colors.waveInactive

    Canvas(
        modifier = modifier.pointerInput(bars.size) {
            detectTapGestures { offset ->
                onSeek((offset.x / size.width).coerceIn(0f, 1f))
            }
        },
    ) {
        val count = bars.size
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)
        val minHeight = 3.dp.toPx()
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val playedBars = (progress * count).roundToInt()

        bars.forEachIndexed { index, amplitude ->
            val height = (amplitude.coerceIn(0f, 1f) * size.height).coerceAtLeast(minHeight)
            val x = index * (barWidth + gap)
            val y = (size.height - height) / 2f

            if (index < playedBars) {
                drawRoundRect(
                    brush = activeBrush,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, height),
                    cornerRadius = radius,
                )
            } else {
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * Live recording capsule — shown while the user holds the mic button.
 * The waveform grows in real time from the mic amplitude stream.
 */
@Composable
fun RecordingCapsule(
    liveAmplitudes: List<Float>,
    elapsedMs: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        glowColor = colors.danger,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(formatDuration(elapsedMs), color = colors.textPrimary, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            Waveform(
                amplitudes = liveAmplitudes,
                progress = 1f,
                outgoing = false,
                onSeek = {},
                modifier = Modifier.weight(1f).height(26.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Cancel",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onCancel),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Send",
                color = colors.accent,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onSend),
            )
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Placeholder envelope for clips whose amplitudes were never computed.
 * Deterministic so the same clip always renders identically, and shaped like
 * speech (loud middle, quiet edges) rather than random noise.
 */
private fun defaultWaveform(count: Int = 36): List<Float> = List(count) { index ->
    val position = index.toFloat() / count
    val envelope = 1f - abs(position - 0.5f) * 1.4f
    val texture = ((index * 37) % 11) / 11f
    (envelope * (0.45f + texture * 0.55f)).coerceIn(0.12f, 1f)
}
