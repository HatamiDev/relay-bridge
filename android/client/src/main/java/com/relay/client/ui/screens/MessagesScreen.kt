package com.relay.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.data.Conversation
import com.relay.client.data.RelayRepository
import com.relay.client.ui.components.formatRelative
import com.relay.client.ui.theme.Glass
import com.relay.client.util.decodeBase64Image
import com.relay.client.util.initials
import com.relay.core.model.SmsState
import com.relay.core.net.ConnectionState

/**
 * The conversation list, rebuilt to the CometChat kit's "Chats" screen.
 *
 * ## What this replaced, and why
 *
 * The previous version was a social-media feed: a segmented All/Unread tab row,
 * a horizontal "stories" strip of pinned contacts, and — taking a third of the
 * screen — a **hero card** for the newest conversation, with the contact's photo
 * blurred behind it and a floating vertical action rail over the top.
 *
 * On a real device with real data that fell apart. The hero card showed a
 * blurred purple rectangle because SMS contacts rarely have photos, the action
 * rail floated over the text it was meant to accompany, and one conversation
 * consumed the space that should have shown six. A list whose first item is
 * eight times the height of the second is not a list.
 *
 * The kit's answer is the boring, correct one: every conversation is the same
 * 72dp row, and the list is scannable from the first frame. Numbers below come
 * from the Figma frame (node 13057:134200) rather than from taste:
 *
 *   row 72dp · padding 16dp · avatar 48dp circle · gap 12dp
 *   name Medium 16 · preview Regular 14 · timestamp Regular 12
 *   unread pill h20 min-w20 on Primary · no dividers · no FAB
 */
@Composable
fun MessagesScreen(
    repository: RelayRepository,
    onOpenThread: (String) -> Unit,
    onOpenMenu: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens

    val threads by repository.threads.collectAsState()
    val connection by repository.connection.collectAsState()
    val presence by repository.gatewayPresence.collectAsState()

    Column(modifier.fillMaxSize().background(colors.canvasRaised)) {

        // ── App bar ──────────────────────────────────────────────────────────
        // Title only. The kit reserves a trailing slot for action icons but
        // ships it empty, and this screen has no action that belongs there —
        // the bell and the compose arrow that used to sit here did nothing.
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.canvasRaised)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Chats",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            HairlineDivider()
        }

        // ── Gateway health ───────────────────────────────────────────────────
        // Not in the kit, and kept anyway: on a relayed system, whether the
        // other handset is alive decides whether anything below is real. It is
        // one 36dp strip rather than a card, so it costs almost no list space.
        GatewayHealthStrip(connection, presence)

        // ── List ─────────────────────────────────────────────────────────────
        if (threads.isEmpty()) {
            EmptyState(connection)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = dimens.dockHeight + 24.dp),
            ) {
                items(threads, key = { it.threadId }) { thread ->
                    ConversationRow(
                        conversation = thread,
                        onClick = { onOpenThread(thread.threadId) },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Row
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One conversation. Fixed 72dp, exactly as the kit specifies.
 *
 * Fixed rather than wrapped: a list of identical-height rows can be scanned by
 * position alone, and the eye stops having to re-measure every item. It also
 * means a two-line preview can never push the next row off a predictable grid.
 */
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val photo = remember(conversation.photoB64) { decodeBase64Image(conversation.photoB64) }
    val unread = conversation.unread

    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(colors.canvasRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            photo = photo,
            initials = conversation.displayName.initials(),
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            // Line 1 — name and timestamp.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.displayName,
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatRelative(conversation.lastTimestamp),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(4.dp))

            // Line 2 — delivery tick, preview, unread count.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!conversation.lastInbound) {
                    Icon(
                        Icons.Rounded.DoneAll,
                        contentDescription = null,
                        tint = when (conversation.lastState) {
                            // The kit gives "seen" its own mint green rather
                            // than reusing the primary, so a read receipt is
                            // never confused with an unread badge.
                            SmsState.DELIVERED -> colors.messageSeen
                            SmsState.FAILED -> colors.danger
                            else -> colors.textSecondary
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    conversation.lastMessage,
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(unread)
                }
            }
        }
    }
}

/**
 * 48dp circle. Photo when the contact has one, initials on the kit's deep
 * indigo otherwise.
 *
 * The old version fell back to a grey tile showing "+", which read as an
 * "add contact" affordance rather than as a person.
 */
@Composable
private fun Avatar(
    photo: androidx.compose.ui.graphics.ImageBitmap?,
    initials: String,
) {
    val colors = Glass.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.auroraTeal),   // Extended Primary 500 · #3E3180
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
        } else {
            Text(
                initials,
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Primary-filled pill, 20dp tall, widening past 20dp only for a second digit. */
@Composable
private fun UnreadBadge(count: Int) {
    val colors = Glass.colors
    Box(
        Modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.accent)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = colors.textOnLight,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun HairlineDivider() {
    val colors = Glass.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.glassBorder),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Health strip
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Whether the SIM handset is reachable, in one line.
 *
 * Hidden entirely when everything is healthy — a permanent green "all fine"
 * banner is pure noise, and its absence is a stronger signal than its presence.
 */
@Composable
private fun GatewayHealthStrip(
    connection: ConnectionState,
    presence: com.relay.core.model.PeerPresence?,
) {
    val colors = Glass.colors
    val online = connection == ConnectionState.CONNECTED && presence?.online == true

    val batteryLow = presence?.let { it.online && it.batteryPct in 0..29 } == true
    val simBad = presence?.let { it.online && it.simState.isNotEmpty() && it.simState != "READY" } == true
    if (online && !batteryLow && !simBad) return

    val (dotColor, label) = when {
        online -> colors.warning to "Sender needs attention"
        connection == ConnectionState.CONNECTED -> colors.warning to "Sender offline"
        connection == ConnectionState.UNAUTHORIZED -> colors.danger to "Pairing invalid"
        else -> colors.textSecondary to "Connecting…"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.glassDarkStrong)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(label, color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))

        presence?.takeIf { it.online }?.let { info ->
            if (info.batteryPct in 0..29) {
                Icon(
                    if (info.charging) Icons.Rounded.Bolt else Icons.Rounded.BatteryStd,
                    contentDescription = "Sender battery",
                    tint = if (info.batteryPct < 15) colors.danger else colors.warning,
                    modifier = Modifier.size(14.dp),
                )
                Text("${info.batteryPct}%", color = colors.textSecondary, fontSize = 12.sp)
            }
            if (info.simState.isNotEmpty() && info.simState != "READY") {
                Icon(
                    Icons.Rounded.SignalCellularAlt,
                    contentDescription = "SIM state",
                    tint = colors.danger,
                    modifier = Modifier.size(14.dp),
                )
                Text(info.simState, color = colors.danger, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyState(connection: ConnectionState) {
    val colors = Glass.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvasRaised)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No conversations yet",
            color = colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when (connection) {
                ConnectionState.CONNECTED ->
                    "Messages appear here as soon as the sender relays them."
                ConnectionState.UNAUTHORIZED ->
                    "This pairing is no longer valid. Re-pair from Settings."
                else -> "Waiting for a connection to the relay server…"
            },
            color = colors.textSecondary,
            fontSize = 14.sp,
        )
    }
}
