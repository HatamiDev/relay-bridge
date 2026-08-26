package com.relay.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.data.Conversation
import com.relay.client.data.RelayRepository
import com.relay.client.ui.call.CallActivity
import com.relay.client.ui.components.*
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.GlassTone
import com.relay.client.util.decodeBase64Image
import com.relay.client.util.initials
import com.relay.core.model.SmsState
import com.relay.core.net.ConnectionState

/**
 * The conversation list, laid out like the reference's feed.
 *
 * The reference is a social app, so a literal copy would give an SMS relay a
 * scrolling wall of media it has no content for. What transfers is the
 * *structure*, and it transfers well:
 *
 *  * circular glass header buttons instead of a title bar
 *  * text-only segmented tabs
 *  * a squircle story row of pinned contacts
 *  * one **hero card** — the newest conversation, rendered full-bleed with the
 *    contact's blurred photo behind it and an action rail down the right edge
 *  * ordinary rows underneath
 *
 * The hero card earns its size: on a relay the thing you almost always want is
 * the message that just arrived, and giving it a full card means replying or
 * calling back is one tap from launch instead of a scan down a list.
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
    val context = LocalContext.current

    val threads by repository.threads.collectAsState()
    val contacts by repository.contacts.collectAsState()
    val connection by repository.connection.collectAsState()
    val presence by repository.gatewayPresence.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val visible = remember(threads, tab) {
        if (tab == 1) threads.filter { it.unread > 0 } else threads
    }
    val unreadTotal = remember(threads) { threads.sumOf { it.unread } }

    val photoOf = remember(contacts) {
        contacts.associate { it.number.digitsOnly() to it.photoB64 }
    }

    val stories = remember(contacts, threads, presence) {
        val byThread = threads.associateBy { it.threadId }
        contacts
            .filter { it.pinned || byThread.containsKey(it.number.digitsOnly()) }
            .sortedByDescending { byThread[it.number.digitsOnly()]?.lastTimestamp ?: it.lastSeenTs }
            .take(14)
            .map { contact ->
                val thread = byThread[contact.number.digitsOnly()]
                StoryItem(
                    id = contact.id.ifEmpty { contact.number },
                    name = contact.name,
                    number = contact.number,
                    photoB64 = contact.photoB64,
                    online = presence?.online == true,
                    unread = thread?.unread ?: 0,
                    seen = (thread?.unread ?: 0) == 0,
                )
            }
    }

    val hero = visible.firstOrNull()
    val rest = if (hero != null) visible.drop(1) else emptyList()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dimens.dockHeight + 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Header ───────────────────────────────────────────────────────────
        item(key = "header") {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = dimens.screenPadding),
            ) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(Icons.Rounded.Menu, "Menu", onOpenMenu)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircleIconButton(
                            Icons.Rounded.NotificationsNone,
                            "Notifications",
                            onClick = {},
                            badge = unreadTotal,
                        )
                        CircleIconButton(
                            Icons.AutoMirrored.Rounded.Send,
                            "New message",
                            onClick = { },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                SegmentedTabs(
                    tabs = listOf("All", "Unread"),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                )
                Spacer(Modifier.height(12.dp))
                GatewayHealthStrip(connection, presence)
                Spacer(Modifier.height(2.dp))
            }
        }

        // ── Stories ──────────────────────────────────────────────────────────
        if (stories.isNotEmpty()) {
            item(key = "stories") {
                StoryRow(
                    stories = stories,
                    onStoryClick = { onOpenThread(it.number.digitsOnly()) },
                    onAddStory = { repository.requestContacts() },
                )
            }
        }

        // ── Hero ─────────────────────────────────────────────────────────────
        if (hero != null) {
            item(key = "hero-${hero.threadId}") {
                HeroConversationCard(
                    conversation = hero,
                    photoB64 = photoOf[hero.address.digitsOnly()].orEmpty()
                        .ifEmpty { hero.photoB64 },
                    modifier = Modifier.padding(horizontal = dimens.screenPadding),
                    onOpen = { onOpenThread(hero.threadId) },
                    onCall = {
                        val callId = repository.placeCall(hero.address)
                        context.startActivity(
                            CallActivity.outgoingIntent(
                                context, callId, hero.address, hero.displayName,
                            ),
                        )
                    },
                )
            }
        }

        // ── The rest ─────────────────────────────────────────────────────────
        if (visible.isEmpty()) {
            item(key = "empty") { EmptyState(connection, tab == 1) }
        } else {
            items(rest, key = { it.threadId }) { thread ->
                ConversationRow(
                    conversation = thread,
                    modifier = Modifier.padding(horizontal = dimens.screenPadding),
                    onClick = { onOpenThread(thread.threadId) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-bleed conversation card.
 *
 * The contact's photo fills it, blurred hard and darkened — the same "frosted
 * glass tinted by the person behind it" idea as the story squircles, at poster
 * size. Where there is no photo, a deterministic gradient derived from the name
 * takes its place so the card is never an empty grey rectangle.
 */
@Composable
private fun HeroConversationCard(
    conversation: Conversation,
    photoB64: String,
    onOpen: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val shape = RoundedCornerShape(dimens.cardRadius)
    val photo = remember(photoB64) { decodeBase64Image(photoB64) }

    Box(
        modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(shape)
            .background(colors.canvasRaised)
            .clickable(onClick = onOpen),
    ) {
        // ── Backdrop ─────────────────────────────────────────────────────────
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(26.dp).clip(shape),
            )
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.auroraTeal.copy(alpha = 0.75f),
                                colors.auroraIndigo.copy(alpha = 0.45f),
                            ),
                        ),
                    ),
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.42f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )

        // ── Avatar, top-left ─────────────────────────────────────────────────
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .size(46.dp)
                .clip(CircleShape)
                .background(colors.canvasRaised)
                .gradientRing(colors.auroraSweep, CircleShape, 1.8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp).clip(CircleShape),
                )
            } else {
                Text(
                    conversation.displayName.initials(),
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ── Action rail, right edge ──────────────────────────────────────────
        OverBrightRegion {
            ActionRail(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                actions = listOf(
                    RailAction(
                        icon = Icons.Rounded.Call,
                        contentDescription = "Call ${conversation.displayName}",
                        activeColor = colors.success,
                        onClick = onCall,
                    ),
                    RailAction(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        count = conversation.unread.takeIf { it > 0 }?.toString(),
                        contentDescription = "Reply",
                        onClick = onOpen,
                    ),
                    RailAction(
                        icon = Icons.Rounded.DoneAll,
                        contentDescription = "Mark read",
                        onClick = onOpen,
                    ),
                ),
            )
        }

        // ── Identity + preview, bottom-left ──────────────────────────────────
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .padding(end = dimens.railWidth + 18.dp),
        ) {
            Text(
                conversation.displayName,
                color = colors.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                formatRelative(conversation.lastTimestamp),
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                conversation.lastMessage,
                color = colors.textPrimary.copy(alpha = 0.92f),
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            OverBrightRegion {
                Pill(
                    label = "Open chat",
                    icon = Icons.AutoMirrored.Rounded.Send,
                    tone = GlassTone.Light,
                    onClick = onOpen,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// List row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val photo = remember(conversation.photoB64) { decodeBase64Image(conversation.photoB64) }
    val avatarShape = remember { SquircleShape(18.dp) }

    GlassSurface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(dimens.cardRadius),
        glowColor = if (conversation.unread > 0) colors.accent.copy(alpha = 0.35f) else null,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(dimens.avatarSize)
                    .clip(avatarShape)
                    .background(colors.canvasRaised)
                    .then(
                        if (conversation.unread > 0) {
                            Modifier.gradientRing(colors.auroraSweep, avatarShape, 1.5.dp)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) {
                    Image(
                        bitmap = photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().clip(avatarShape),
                    )
                } else {
                    Text(
                        conversation.displayName.initials(),
                        color = colors.textSecondary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.width(13.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.displayName,
                        color = colors.textPrimary,
                        fontSize = 15.5.sp,
                        fontWeight = if (conversation.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatRelative(conversation.lastTimestamp),
                        color = if (conversation.unread > 0) colors.accent else colors.textTertiary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!conversation.lastInbound) {
                        Icon(
                            Icons.Rounded.DoneAll,
                            contentDescription = null,
                            tint = when (conversation.lastState) {
                                SmsState.DELIVERED -> colors.accent
                                SmsState.FAILED -> colors.danger
                                else -> colors.textTertiary
                            },
                            modifier = Modifier.size(13.dp).padding(end = 3.dp),
                        )
                    }
                    Text(
                        conversation.lastMessage,
                        color = if (conversation.unread > 0) colors.textSecondary else colors.textTertiary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            if (conversation.unread > 0) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(colors.accent)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (conversation.unread > 99) "99+" else conversation.unread.toString(),
                        color = colors.textOnLight,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Health strip
// ─────────────────────────────────────────────────────────────────────────────

/**
 * On a relayed system the single most valuable fact is whether the other phone
 * is alive — if it is not, nothing below this strip matters. It sits directly
 * under the tabs for that reason, not buried in Settings.
 */
@Composable
private fun GatewayHealthStrip(
    connection: ConnectionState,
    presence: com.relay.core.model.PeerPresence?,
) {
    val colors = Glass.colors
    val online = connection == ConnectionState.CONNECTED && presence?.online == true

    val (dotColor, label) = when {
        online -> colors.online to "Sender online"
        connection == ConnectionState.CONNECTED -> colors.warning to "Sender offline"
        connection == ConnectionState.UNAUTHORIZED -> colors.danger to "Pairing invalid"
        else -> colors.textTertiary to "Connecting…"
    }

    GlassSurface(shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Text(label, color = colors.textSecondary, fontSize = 12.sp)

            presence?.takeIf { it.online }?.let { info ->
                if (info.batteryPct >= 0) {
                    Icon(
                        if (info.charging) Icons.Rounded.Bolt else Icons.Rounded.BatteryStd,
                        contentDescription = "Sender battery",
                        tint = when {
                            info.charging -> colors.success
                            info.batteryPct < 15 -> colors.danger
                            info.batteryPct < 30 -> colors.warning
                            else -> colors.textTertiary
                        },
                        modifier = Modifier.size(13.dp),
                    )
                    Text("${info.batteryPct}%", color = colors.textTertiary, fontSize = 11.sp)
                }
                if (info.simState.isNotEmpty() && info.simState != "READY") {
                    Icon(
                        Icons.Rounded.SignalCellularAlt,
                        contentDescription = "SIM state",
                        tint = colors.danger,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(info.simState, color = colors.danger, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(connection: ConnectionState, unreadFilter: Boolean) {
    val colors = Glass.colors
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Glass.dimens.screenPadding, vertical = 40.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (unreadFilter) "Nothing unread" else "No conversations yet",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    unreadFilter -> "You are caught up."
                    connection == ConnectionState.CONNECTED ->
                        "Messages appear here as soon as the sender relays them."
                    connection == ConnectionState.UNAUTHORIZED ->
                        "This pairing is no longer valid. Re-pair from Settings."
                    else -> "Waiting for a connection to the relay server…"
                },
                color = colors.textTertiary,
                fontSize = 13.sp,
            )
        }
    }
}

private fun String.digitsOnly() = filter { it.isDigit() || it == '+' }.ifEmpty { this }
