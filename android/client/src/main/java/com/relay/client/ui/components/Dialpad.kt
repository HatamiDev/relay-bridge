package com.relay.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass

/**
 * A telephone keypad.
 *
 * The CometChat kit has no dialer — it is a chat product — so this is designed
 * from the kit's own vocabulary rather than copied: the same greys, the same
 * primary, Roboto at the kit's sizes, circular keys on `Radius/radius_Max`.
 *
 * Two decisions that are not arbitrary:
 *
 * **Letters under the digits.** They cost a line of 10sp text and they are what
 * makes a number dictated as "555-CALL" dialable. Every native dialer keeps
 * them for this reason.
 *
 * **Backspace only appears once there is something to delete.** An always-on
 * backspace beside an empty field is a control that does nothing, and it
 * invites a tap that teaches the user the app ignores them. The call button is
 * likewise disabled — visibly, at 38% — until the number is long enough to be
 * real, rather than accepting a tap and failing later.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dialpad(
    number: String,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val canCall = number.length >= 3

    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Entry ────────────────────────────────────────────────────────────
        Box(
            Modifier.fillMaxWidth().height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.ifEmpty { "Enter a number" },
                color = if (number.isEmpty()) colors.textSecondary else colors.textPrimary,
                // Shrinks past 12 digits so a long international number stays
                // on one line instead of wrapping mid-number, which is very
                // hard to read back.
                fontSize = when {
                    number.length > 15 -> 24.sp
                    number.length > 11 -> 30.sp
                    else -> 34.sp
                },
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Keys ─────────────────────────────────────────────────────────────
        KEYS.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (digit, letters) ->
                    DialKey(
                        digit = digit,
                        letters = letters,
                        onClick = { onDigit(digit) },
                        // Long-press 0 for "+", the international prefix. This
                        // is the universal convention and the only way to type
                        // a "+" without a separate key.
                        onLongClick = if (digit == '0') ({ onDigit('+') }) else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(8.dp))

        // ── Call row ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left spacer keeps the call button optically centred while the
            // backspace occupies the right slot.
            Spacer(Modifier.width(64.dp))

            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (canCall) colors.success
                            else colors.success.copy(alpha = 0.38f),
                        )
                        .combinedClickable(enabled = canCall, onClick = onCall),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Call,
                        contentDescription = "Call",
                        tint = colors.textOnLight,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Box(
                Modifier.width(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (number.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .combinedClickable(onClick = onBackspace, onLongClick = onClear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Backspace,
                            contentDescription = "Delete last digit",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    digit: Char,
    letters: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors

    Box(
        modifier.aspectRatio(1.35f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                digit.toString(),
                color = colors.textPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
            )
            if (letters.isNotEmpty()) {
                Text(
                    letters,
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Digit and its letters, in ITU E.161 order. */
private val KEYS: List<List<Pair<Char, String>>> = listOf(
    listOf('1' to "", '2' to "ABC", '3' to "DEF"),
    listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
    listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ"),
    listOf('*' to "", '0' to "+", '#' to ""),
)
