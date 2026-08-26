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

    AuroraBackground(modifier.fillMaxSize(), variant = AuroraVariant.Chat) {
        Column(Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────────
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
                strong = true,
            ) {
                Row(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            thread?.displayName ?: threadId,
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            thread?.address ?: threadId,
                            color = colors.textTertiary,
                            fontSize = 11.5.sp,
                        )
                    }
                    CircleIconButton(
                        icon = Icons.Rounded.Call,
                        contentDescription = "Call",
                        tint = colors.success,
                        background = colors.success.copy(alpha = 0.16f),
                        onClick = {
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

            // ── Composer ──────────────────────────────────────────────────────
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
                    .imePadding()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(28.dp),
                strong = true,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 132.dp)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) {
                        if (draft.isEmpty()) {
                            Text("Message", color = colors.textTertiary, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Send and record share one button because they are the same
                    // intent — "commit this" — and the draft decides which.
                    val canSend = draft.isNotBlank()
                    CircleIconButton(
                        icon = if (canSend) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic,
                        contentDescription = if (canSend) "Send" else "Record voice note",
                        tint = if (canSend) colors.textOnLight else colors.accent,
                        background = if (canSend) colors.accent else colors.accentSoft,
                        onClick = {
                            if (canSend) {
                                repository.sendSms(thread?.address ?: threadId, draft.trim())
                                draft = ""
                            }
                        },
                    )
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
