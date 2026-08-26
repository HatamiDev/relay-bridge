package com.relay.client.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass
import com.relay.core.model.SmsState
import com.relay.client.util.decodeBase64Image
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A message bubble with an optional avatar attached at its tail.
 *
 * ## Why both directions are dark
 *
 * The obvious move is a saturated colour for outgoing and grey for incoming.
 * Over a long thread that produces a striped wall which is tiring to read and
 * makes the *colour* the loudest thing on screen rather than the words.
 *
 * The reference keeps both sides dark and separates them three quieter ways:
 * alignment, a small avatar on the sender's side, and a slight tint shift. That
 * leaves the accent colour free to mean something — a delivery tick, an active
 * control — instead of meaning "you typed this".
 *
 * [accentOutgoing] restores the gradient treatment for anyone who wants it.
 *
 * ## The tail
 *
 * Expressed by collapsing one corner to 6dp rather than drawing a triangle. At
 * a 22dp radius it reads identically and costs nothing extra to draw.
 */
@Composable
fun MessageBubble(
    text: String,
    timestamp: Long,
    inbound: Boolean,
    state: SmsState,
    modifier: Modifier = Modifier,
    showTail: Boolean = true,
    /** Base64 JPEG; when present it is pinned to the bubble's tail corner. */
    avatarB64: String = "",
    accentOutgoing: Boolean = false,
) {
    val colors = Glass.colors
    val radius = Glass.dimens.bubbleRadius
    val avatar = remember(avatarB64) { decodeBase64Image(avatarB64) }

    val shape = RoundedCornerShape(
        topStart = radius,
        topEnd = radius,
        bottomStart = if (!inbound || !showTail) radius else 7.dp,
        bottomEnd = if (inbound || !showTail) radius else 7.dp,
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (inbound) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (inbound && showTail) {
            TailAvatar(avatar, text)
            Spacer(Modifier.width(7.dp))
        } else if (inbound) {
            // Keep the run visually aligned even where no avatar is drawn.
            Spacer(Modifier.width(TAIL_AVATAR + 7.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(max = 272.dp)
                .clip(shape)
                .then(
                    when {
                        !inbound && accentOutgoing ->
                            Modifier.background(colors.outgoingAccentBrush)
                        inbound ->
                            Modifier
                                .background(colors.bubbleIncoming)
                                .background(colors.sheenBrush)
                                .border(1.dp, colors.glassBorderSoft, shape)
                        else ->
                            Modifier
                                .background(colors.bubbleOutgoing)
                                .background(colors.sheenBrush)
                                .border(1.dp, colors.glassBorder, shape)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                color = if (!inbound && accentOutgoing) Color.White else colors.textPrimary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    text = formatClock(timestamp),
                    color = colors.textTertiary,
                    fontSize = 10.5.sp,
                )
                if (!inbound) DeliveryTick(state)
            }
        }

        if (!inbound && showTail) {
            Spacer(Modifier.width(7.dp))
            TailAvatar(avatar, "You")
        } else if (!inbound) {
            Spacer(Modifier.width(TAIL_AVATAR + 7.dp))
        }
    }
}

private val TAIL_AVATAR = 26.dp

@Composable
private fun TailAvatar(avatar: androidx.compose.ui.graphics.ImageBitmap?, fallback: String) {
    val colors = Glass.colors
    Box(
        Modifier
            .size(TAIL_AVATAR)
            .clip(CircleShape)
            .background(colors.canvasRaised)
            .border(1.dp, colors.glassBorderSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(TAIL_AVATAR).clip(CircleShape),
            )
        } else {
            Text(
                fallback.trim().take(1).uppercase(),
                color = colors.textTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Delivery receipts, mapped onto what SMS actually reports.
 *
 * There is no "read" state in SMS, so there is no blue double-tick meaning
 * "seen" — the cyan double-tick here means the carrier confirmed delivery,
 * which is the strongest signal the protocol offers.
 */
@Composable
private fun DeliveryTick(state: SmsState) {
    val colors = Glass.colors
    val (icon, tint, description) = when (state) {
        SmsState.QUEUED, SmsState.SENDING ->
            Triple(Icons.Rounded.Schedule, colors.textTertiary, "Queued")
        SmsState.SENT ->
            Triple(Icons.Rounded.Check, colors.textSecondary, "Sent to the network")
        SmsState.DELIVERED ->
            Triple(Icons.Rounded.DoneAll, colors.accent, "Delivered")
        SmsState.FAILED ->
            Triple(Icons.Rounded.ErrorOutline, colors.danger, "Failed")
    }
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(13.dp))
}

/** Sticky day separator between message groups. */
@Composable
fun DayDivider(timestamp: Long, modifier: Modifier = Modifier) {
    val colors = Glass.colors
    Box(modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        GlassSurface(shape = RoundedCornerShape(50)) {
            Text(
                text = formatDay(timestamp),
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

// ── Formatting ───────────────────────────────────────────────────────────────

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())

fun formatClock(ts: Long): String = clockFormat.format(Date(ts))

fun formatDay(ts: Long): String {
    val now = System.currentTimeMillis()
    val dayMs = 24 * 60 * 60 * 1000L
    val startOfToday = now - (now % dayMs)
    return when {
        ts >= startOfToday -> "Today"
        ts >= startOfToday - dayMs -> "Yesterday"
        else -> dayFormat.format(Date(ts))
    }
}

/** "now", "4m", "3h", "Mon", "12 Mar" — used in the conversation list. */
fun formatRelative(ts: Long): String {
    val delta = System.currentTimeMillis() - ts
    return when {
        delta < 60_000 -> "now"
        delta < 3_600_000 -> "${delta / 60_000}m"
        delta < 86_400_000 -> "${delta / 3_600_000}h"
        delta < 7 * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }
}
