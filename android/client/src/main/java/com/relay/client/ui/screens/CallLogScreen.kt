package com.relay.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.data.CallLog
import com.relay.client.data.CallLogStore
import com.relay.client.data.RelayRepository
import com.relay.client.ui.call.CallActivity
import com.relay.client.ui.components.Dialpad
import com.relay.client.ui.theme.Glass
import com.relay.client.util.decodeBase64Image
import com.relay.client.util.initials
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Relayed call history, laid out to the kit's `Calls > Call Logs` frame.
 *
 * Only calls that passed through this bridge appear here. The gateway's own
 * native call log is deliberately not mirrored: it would include calls taken
 * on the gateway handset directly, and presenting those as "your calls" on a
 * device that never rang would be a lie.
 *
 * Numbers from Figma node 13057:134782:
 *   row 72dp · padding 16dp · avatar 48dp circle · gap 12dp
 *   name Medium 16 (error red when missed) · direction icon 16dp + timestamp
 *   Regular 14 on line two · trailing 24dp call action · no dividers
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLogScreen(repository: RelayRepository, modifier: Modifier = Modifier) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val context = LocalContext.current

    val entries by CallLogStore.entries.collectAsState()

    // The keypad is a sheet over the history rather than a fifth tab. Dialling
    // an unsaved number is a burst activity — open, type, call, done — and it
    // belongs on top of the list you just failed to find the number in, not in
    // a destination you have to navigate away from.
    var dialpadOpen by rememberSaveable { mutableStateOf(false) }
    var dialled by rememberSaveable { mutableStateOf("") }

    // skipPartiallyExpanded, or the sheet opens at half height with the keypad
    // cut off below the fold — a keypad you have to drag before you can dial is
    // worse than no keypad. There is nothing above the fold worth previewing
    // here, so the half-expanded state has no purpose.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun place(number: String, displayName: String = number) {
        val target = number.trim()
        if (target.isEmpty()) return
        val callId = repository.placeCall(target)
        context.startActivity(
            CallActivity.outgoingIntent(context, callId, target, displayName),
        )
    }

    Box(modifier.fillMaxSize().background(colors.canvasRaised)) {

        Column(Modifier.fillMaxSize()) {

            // ── App bar ──────────────────────────────────────────────────────
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
                        "Calls",
                        color = colors.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.glassBorder),
                )
            }

            // ── List ─────────────────────────────────────────────────────────
            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No calls yet",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Calls relayed through the sender appear here.",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = dimens.dockHeight + 88.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        val contact = repository.contactFor(entry.number)
                        val name = contact?.name?.ifEmpty { null } ?: entry.number
                        CallLogRow(
                            entry = entry,
                            displayName = name,
                            photoB64 = contact?.photoB64.orEmpty(),
                            onCallBack = { place(entry.number, name) },
                        )
                    }
                }
            }
        }

        // ── Keypad ───────────────────────────────────────────────────────────
        FloatingActionButton(
            onClick = { dialpadOpen = true },
            containerColor = colors.accent,
            contentColor = colors.textOnLight,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = dimens.dockHeight + 24.dp),
        ) {
            Icon(Icons.Rounded.Dialpad, contentDescription = "Open the keypad")
        }

        if (dialpadOpen) {
            ModalBottomSheet(
                onDismissRequest = { dialpadOpen = false },
                sheetState = sheetState,
                containerColor = colors.canvasRaised,
                contentColor = colors.textPrimary,
            ) {
                Dialpad(
                    number = dialled,
                    onDigit = { dialled += it },
                    onBackspace = { dialled = dialled.dropLast(1) },
                    onClear = { dialled = "" },
                    onCall = {
                        place(dialled)
                        dialpadOpen = false
                        // Cleared on success so the next open starts fresh
                        // rather than re-offering a number already dialled.
                        dialled = ""
                    },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CallLogRow(
    entry: CallLog,
    displayName: String,
    photoB64: String,
    onCallBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors

    // The kit draws in and out with the same green and reserves red for missed,
    // so colour answers "did I lose this call?" and the arrow answers "which
    // way did it go?" — two questions, two channels, neither overloaded.
    val (directionIcon, directionTint, directionLabel) = when {
        entry.missed -> Triple(
            Icons.AutoMirrored.Rounded.CallMissed, MISSED_ARROW, "Missed call",
        )
        entry.inbound -> Triple(
            Icons.AutoMirrored.Rounded.CallReceived, CONNECTED_ARROW, "Incoming call",
        )
        else -> Triple(
            Icons.AutoMirrored.Rounded.CallMade, CONNECTED_ARROW, "Outgoing call",
        )
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(colors.canvasRaised)
            .clickable(onClick = onCallBack)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CallAvatar(displayName, photoB64)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                displayName,
                // Red only on the name. The kit leaves the timestamp grey, so a
                // missed call reads as one red mark in the row rather than a
                // red row, and a screen of missed calls stays scannable.
                color = if (entry.missed) colors.danger else colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    directionIcon,
                    contentDescription = directionLabel,
                    tint = directionTint,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stampFormat.format(Date(entry.startedAt)) +
                        if (entry.durationMs > 0) " · ${formatDuration(entry.durationMs)}" else "",
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            Icons.Rounded.Call,
            contentDescription = "Call $displayName back",
            tint = colors.textPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun CallAvatar(name: String, photoB64: String) {
    val colors = Glass.colors
    val letters = name.initials()
    val photo = remember(photoB64) { decodeBase64Image(photoB64) }

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
        } else if (letters.isNotEmpty()) {
            Text(
                letters,
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = colors.textPrimary.copy(alpha = 0.72f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

// ── Direction arrow colours ──────────────────────────────────────────────────
//
// Taken from the exported icon assets rather than from the colour variables:
// the kit ships the arrows as flat SVGs with these fills baked in, and they are
// deliberately a shade off the semantic success/error tokens.
private val CONNECTED_ARROW = Color(0xFF09C26F)
private val MISSED_ARROW = Color(0xFFF44649)

private val stampFormat = SimpleDateFormat("d MMMM, h:mm a", Locale.getDefault())

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
