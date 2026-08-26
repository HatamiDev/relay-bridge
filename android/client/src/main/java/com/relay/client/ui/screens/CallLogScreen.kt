package com.relay.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.data.CallLog
import com.relay.client.data.CallLogStore
import com.relay.client.data.RelayRepository
import com.relay.client.ui.call.CallActivity
import com.relay.client.ui.components.CircleIconButton
import com.relay.client.ui.components.DockSpacer
import com.relay.client.ui.components.GlassSurface
import com.relay.client.ui.components.formatRelative
import com.relay.client.ui.theme.Glass

/**
 * Agent 4 — relayed call history.
 *
 * Only calls that passed through this bridge appear here. The gateway's own
 * native call log is intentionally not mirrored: it would include calls the user
 * took on the gateway handset directly, and presenting those as "your calls" on
 * a device that never rang would be misleading.
 */
@Composable
fun CallLogScreen(repository: RelayRepository, modifier: Modifier = Modifier) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val context = LocalContext.current

    val entries by CallLogStore.entries.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Text(
                "Calls",
                color = colors.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
            )
        }

        if (entries.isEmpty()) {
            item(key = "empty") {
                GlassSurface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimens.screenPadding, vertical = 30.dp),
                ) {
                    Text(
                        "No relayed calls yet. Incoming cellular calls on the gateway " +
                            "will ring here.",
                        color = colors.textTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                CallLogRow(
                    entry = entry,
                    displayName = repository.displayNameFor(entry.number).ifEmpty { entry.number },
                    modifier = Modifier.padding(horizontal = dimens.screenPadding),
                    onCallBack = {
                        val callId = repository.placeCall(entry.number)
                        context.startActivity(
                            CallActivity.outgoingIntent(
                                context, callId, entry.number,
                                repository.displayNameFor(entry.number),
                            ),
                        )
                    },
                )
            }
        }

        // The dock floats over the list, so the last row needs somewhere to go.
        item(key = "dock-gap") { DockSpacer() }
    }
}

@Composable
private fun CallLogRow(
    entry: CallLog,
    displayName: String,
    onCallBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors

    val (icon, tint) = when {
        entry.missed -> Icons.AutoMirrored.Rounded.CallMissed to colors.danger
        entry.inbound -> Icons.AutoMirrored.Rounded.CallReceived to colors.success
        else -> Icons.AutoMirrored.Rounded.CallMade to colors.accent
    }

    GlassSurface(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    color = if (entry.missed) colors.danger else colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatRelative(entry.startedAt),
                        color = colors.textTertiary,
                        fontSize = 11.5.sp,
                    )
                    if (entry.durationMs > 0) {
                        Text(" · ", color = colors.textTertiary, fontSize = 11.5.sp)
                        Text(
                            formatCallDuration(entry.durationMs),
                            color = colors.textTertiary,
                            fontSize = 11.5.sp,
                        )
                    }
                    if (entry.audioMode.isNotEmpty()) {
                        Text(" · ", color = colors.textTertiary, fontSize = 11.5.sp)
                        Text(entry.audioMode, color = colors.textTertiary, fontSize = 11.5.sp)
                    }
                }
            }

            CircleIconButton(
                icon = Icons.Rounded.Call,
                contentDescription = "Call $displayName back",
                tint = colors.success,
                background = colors.success.copy(alpha = 0.15f),
                onClick = onCallBack,
            )
        }
    }
}

private fun formatCallDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
