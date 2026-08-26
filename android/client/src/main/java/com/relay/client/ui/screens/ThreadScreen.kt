package com.relay.client.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.data.RelayRepository
import com.relay.client.ui.call.CallActivity
import com.relay.client.ui.components.AuroraBackground
import com.relay.client.ui.components.CircleIconButton
import com.relay.client.ui.components.DayDivider
import com.relay.client.ui.components.GlassSurface
import com.relay.client.ui.components.MessageBubble
import com.relay.client.ui.components.VoiceCapsule
import com.relay.client.ui.theme.AuroraVariant
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.layout.ContentScale
import com.relay.client.util.decodeBase64Image
import com.relay.client.util.initials
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.relay.client.ui.theme.Glass
import com.relay.core.model.SmsMessage
import kotlinx.coroutines.delay

/**
 * Agent 4 — a single conversation.
 *
 * The composer is a glass capsule pinned above the IME. Messages group by day
 * and collapse consecutive bubbles from the same sender (only the last one in a
 * run gets a tail), which is what stops a long exchange reading as a wall.
 *
 * The screen owns its own canvas: [AuroraVariant.Chat] puts the teal behind the
 * header and a quiet near-black under the message list, which is the inverse of
 * the feed. A bright gradient beneath a wall of text is exhausting to read.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    repository: RelayRepository,
    threadId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val context = LocalContext.current

    val threads by repository.threads.collectAsState()
    val thread = remember(threads, threadId) { threads.firstOrNull { it.threadId == threadId } }

    // Re-read the bucket whenever the thread list changes; the repository is the
    // owner and rebuilds threads on every mutation.
    var messages by remember { mutableStateOf(repository.messagesFor(threadId)) }
    LaunchedEffect(threads) { messages = repository.messagesFor(threadId) }

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Long-press selection, the standard messaging idiom. Kept as a plain id
    // set rather than a "delete mode" flag: emptying the set *is* leaving the
    // mode, so the two can never disagree about whether selection is active.
    // An immutable Set in plain `remember`, not rememberSaveable.
    //
    // Saveable needs a custom Saver for a Set and buys nothing here: a
    // selection is a transient gesture, and restoring one after process death
    // would put the user back in delete-mode with no memory of choosing it.
    // Immutable rather than MutableSet so every change is a new instance and
    // Compose actually sees it — mutating a set in place leaves the reference
    // equal and the recomposition never happens.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    // Back leaves selection before it leaves the thread — otherwise a user
    // trying to cancel a selection is thrown out of the conversation.
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    // Voice playback state for capsules in this thread.
    var playingId by remember { mutableStateOf<String?>(null) }
    var playbackMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(threadId) { repository.markRead(threadId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(playingId) {
        // Drives the capsule progress bar. A real audio pipeline would emit
        // positions; this keeps the UI honest when no player is attached.
        while (playingId != null) {
            delay(50)
            playbackMs += 50
        }
        playbackMs = 0L
    }

    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.glassDarkStrong,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text(if (n == 1) "Delete message?" else "Delete $n messages?") },
            // Says plainly what the scope is. "Delete" with no qualifier would
            // imply it is gone from the SIM handset too, which it is not.
            text = {
                Text(
                    "Removed from this device only. The sender's phone keeps its " +
                        "own copy, and a later sync can bring these back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteMessages(threadId, selected)
                    messages = repository.messagesFor(threadId)
                    selected = emptySet()
                    confirmDelete = false
                }) {
                    Text("Delete", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }

    AuroraBackground(modifier.fillMaxSize(), variant = AuroraVariant.Chat) {
        Column(Modifier.fillMaxSize()) {

            // ── Selection bar ─────────────────────────────────────────────────
            // Replaces the header outright rather than stacking above it: two
            // bars would push the conversation down and leave two competing
            // back affordances on screen at once.
            if (selected.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.glassDarkStrong)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Cancel selection",
                        onClick = { selected = emptySet() },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${selected.size} selected",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    CircleIconButton(
                        icon = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete selected",
                        onClick = { confirmDelete = true },
                    )
                }
            }

            // ── Header ────────────────────────────────────────────────────────
            // Flat 64dp bar on Background1 with a hairline underneath — the
            // kit's Base_Header. The old version was a rounded glass card with
            // a 26dp bottom sweep, which on a flat palette just looked like the
            // top of the screen had been cut off.
            if (selected.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(colors.canvasRaised),
                ) {
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable(onClick = onBack),
                        )

                        ThreadAvatar(
                            photoB64 = thread?.photoB64.orEmpty(),
                            name = thread?.displayName ?: threadId,
                        )

                        Column(Modifier.weight(1f)) {
                            Text(
                                thread?.displayName ?: threadId,
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // The kit shows presence here. On a relay the
                                // useful fact is the number itself — the
                                // conversation is often with an unsaved sender,
                                // and presence belongs to the gateway, not to
                                // the person on the other end of the SMS.
                                thread?.address ?: threadId,
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Icon(
                            Icons.Rounded.Call,
                            contentDescription = "Call",
                            tint = colors.textPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    val number = thread?.address ?: threadId
                                    val callId = repository.placeCall(number)
                                    context.startActivity(
                                        CallActivity.outgoingIntent(
                                            context, callId, number,
                                            thread?.displayName ?: number,
                                        ),
                                    )
                                },
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.glassBorder),
                    )
                }
            }

            // ── Messages ──────────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = dimens.screenPadding,
                    vertical = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(messages, key = { _, item -> item.id }) { index, message ->
                    val previous = messages.getOrNull(index - 1)
                    val next = messages.getOrNull(index + 1)

                    if (previous == null || !sameDay(previous.ts, message.ts)) {
                        DayDivider(message.ts)
                    }

                    // Only the final bubble in a run carries the tail.
                    val showTail = next == null || next.inbound != message.inbound ||
                        next.ts - message.ts > GROUPING_WINDOW_MS

                    if (message.isVoiceNote()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                if (message.inbound) Arrangement.Start else Arrangement.End,
                        ) {
                            VoiceCapsule(
                                waveform = message.decodeWaveform(),
                                durationMs = message.voiceDurationMs(),
                                positionMs = if (playingId == message.id) playbackMs else 0L,
                                isPlaying = playingId == message.id,
                                outgoing = !message.inbound,
                                onPlayPause = {
                                    playingId = if (playingId == message.id) null else message.id
                                    playbackMs = 0L
                                },
                                onSeek = { fraction ->
                                    playbackMs = (message.voiceDurationMs() * fraction).toLong()
                                },
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (message.id in selected) {
                                        colors.accent.copy(alpha = 0.22f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .combinedClickable(
                                    onClick = {
                                        // Once a selection exists, a plain tap
                                        // extends it. Anything else means the
                                        // first tap after a long-press would
                                        // silently do nothing.
                                        if (selected.isNotEmpty()) toggle(message.id)
                                    },
                                    onLongClick = { toggle(message.id) },
                                ),
                        ) {
                        MessageBubble(
                            text = message.body,
                            timestamp = message.ts,
                            inbound = message.inbound,
                            state = message.state,
                            showTail = showTail,
                            // Only inbound bubbles carry a face: on your own side
                            // the sender is never in doubt, and a repeated self
                            // portrait down the right edge is pure noise.
                            avatarB64 = if (message.inbound) {
                                thread?.photoB64.orEmpty()
                            } else {
                                ""
                            },
                        )
                        }
                    }
                }
            }

            // ── Composer ──────────────────────────────────────────────────────
            // A bordered 8dp card on Background1, inset 8dp from the edges —
            // the kit's Base_Message Composer. The old one was a 28dp glass
            // pill; against flat surfaces that reads as a floating search box
            // rather than a text field you can type a paragraph into.
            Box(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.canvasRaised)
                        .border(1.dp, colors.glassBorder, RoundedCornerShape(8.dp)),
                ) {
                    // Row 1 — the field, on its own line so a long draft grows
                    // downward instead of squeezing the actions.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 41.dp, max = 132.dp)
                            .padding(12.dp),
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                "Type your message...",
                                color = colors.textTertiary,
                                fontSize = 14.sp,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.glassBorder),
                    )

                    // Row 2 — actions.
                    //
                    // The kit shows five leading icons (attach, mic, emoji,
                    // sticker, AI). Four of those have nothing behind them
                    // here: this is an SMS relay, not a chat product, and a
                    // control that does nothing is worse than no control. Only
                    // the mic is real, and only because a voice note already
                    // rides the relay.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val canSend = draft.isNotBlank()

                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = "Record voice note",
                            tint = colors.textTertiary,
                            modifier = Modifier.size(24.dp),
                        )

                        Spacer(Modifier.weight(1f))

                        // 32dp circle. Neutral grey until there is something to
                        // send, primary once there is — the kit ships only the
                        // idle fill, but a send button that looks identical
                        // empty and full gives the user no signal at all.
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) colors.accent else colors.glassLightStrong,
                                )
                                .clickable(enabled = canSend) {
                                    repository.sendSms(
                                        thread?.address ?: threadId,
                                        draft.trim(),
                                    )
                                    draft = ""
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Small pieces ─────────────────────────────────────────────────────────────

private const val GROUPING_WINDOW_MS = 3 * 60 * 1000L

private fun sameDay(a: Long, b: Long): Boolean {
    val day = 24 * 60 * 60 * 1000L
    return (a / day) == (b / day)
}

// ── Voice-note encoding ──────────────────────────────────────────────────────
//
// Voice notes ride the same SMS-shaped channel using a compact marker so the
// protocol needs no second message type:
//
//     [voice:<durationMs>:<b64 waveform bytes>] <opaque media reference>
//
// A production build would carry the Opus payload out of band and put only the
// reference here; the parser is deliberately tolerant so an unrecognised body
// simply renders as ordinary text rather than breaking the thread.

private val VOICE_PREFIX = Regex("""^\[voice:(\d+):([A-Za-z0-9+/=_-]*)]""")

fun SmsMessage.isVoiceNote(): Boolean = VOICE_PREFIX.containsMatchIn(body)

fun SmsMessage.voiceDurationMs(): Long =
    VOICE_PREFIX.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

fun SmsMessage.decodeWaveform(): List<Float> {
    val encoded = VOICE_PREFIX.find(body)?.groupValues?.getOrNull(2).orEmpty()
    if (encoded.isEmpty()) return emptyList()
    return runCatching {
        android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            .map { (it.toInt() and 0xFF) / 255f }
    }.getOrDefault(emptyList())
}

/** 40dp circular avatar for the thread header. */
@Composable
private fun ThreadAvatar(photoB64: String, name: String) {
    val colors = Glass.colors
    val photo = remember(photoB64) { decodeBase64Image(photoB64) }
    val letters = name.initials()

    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.auroraTeal),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )

            letters.isNotEmpty() -> Text(
                letters,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )

            else -> Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = colors.textPrimary.copy(alpha = 0.72f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
