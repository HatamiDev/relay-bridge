package com.relay.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.data.CallLogStore
import com.relay.client.data.MessageCache
import com.relay.client.data.RelayRepository
import com.relay.client.ui.theme.Glass
import com.relay.core.crypto.CryptoBox
import com.relay.core.net.ConnectionState
import com.relay.core.net.PairingApi
import com.relay.core.util.SamsungBatterySettings
import com.relay.core.util.SystemHealth
import kotlinx.coroutines.launch

/**
 * Agent 4 + 5 + 6 — diagnostics, security verification and survival settings.
 *
 * The verification code block is the important one. A user who never compares
 * the two SAS codes has end-to-end encryption with an unverified key exchange,
 * which is meaningfully weaker than one who spent three seconds looking at both
 * screens. So the code is shown prominently rather than buried behind "Advanced".
 */
@Composable
fun SettingsScreen(
    repository: RelayRepository,
    onUnpaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connection by repository.connection.collectAsState()
    val presence by repository.gatewayPresence.collectAsState()

    val sas = remember {
        repository.secureStore.gatewayPeer()?.sas.orEmpty()
    }
    val fingerprint = remember {
        repository.secureStore.gatewayPeer()?.deviceId
            ?.let { repository.secureStore.loadRootKey(it) }
            ?.let { key -> CryptoBox.keyFingerprint(key).also { key.fill(0) } }
            .orEmpty()
    }

    var dozeExempt by remember {
        mutableStateOf(SystemHealth.isIgnoringBatteryOptimizations(context))
    }

    Column(modifier.fillMaxSize().background(colors.canvas)) {

        // The kit has no settings frame — it is a chat SDK — so this screen is
        // designed in its language rather than copied: same 64dp bar, same
        // hairline, same Bold 24 title as Chats and Calls, so the four tabs
        // read as one app instead of three plus an outlier.
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.canvasRaised)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = dimens.dockHeight + 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Connection ───────────────────────────────────────────────────────
            SettingsCard("Connection") {
                KeyValue("Relay server", repository.secureStore.serverUrl.substringAfter("//"))
                KeyValue(
                    "Socket",
                    when (connection) {
                        ConnectionState.CONNECTED -> "Connected"
                        ConnectionState.CONNECTING -> "Connecting"
                        ConnectionState.RECONNECTING -> "Reconnecting"
                        ConnectionState.UNAUTHORIZED -> "Rejected — re-pair required"
                        ConnectionState.DISCONNECTED -> "Offline"
                    },
                    valueColor = if (connection == ConnectionState.CONNECTED) {
                        colors.success
                    } else {
                        colors.warning
                    },
                )
                KeyValue("Gateway", if (presence?.online == true) "Online" else "Offline")
                presence?.let {
                    if (it.batteryPct >= 0) {
                        KeyValue(
                            "Gateway battery",
                            "${it.batteryPct}%" + if (it.charging) " (charging)" else "",
                        )
                    }
                    if (it.simState.isNotEmpty()) KeyValue("SIM", it.simState)
                    if (it.appVersion.isNotEmpty()) KeyValue("Gateway build", it.appVersion)
                }

                Spacer(Modifier.height(10.dp))
                ActionRow(Icons.Rounded.Refresh, "Reconnect now", colors.accent) {
                    repository.connect()
                    repository.requestSync()
                    repository.requestContacts()
                }
            }

            // ── Security ─────────────────────────────────────────────────────────
            SettingsCard("End-to-end encryption") {
                Text(
                    "AES-256-GCM with HKDF-derived directional keys. The relay server " +
                        "sees ciphertext only and never held the root key.",
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                if (sas.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Verification code", color = colors.textSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sas.chunked(3).joinToString(" "),
                        color = colors.accent,
                        fontSize = 32.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open Settings on the gateway. If these six digits differ, the " +
                            "pairing QR was intercepted — unpair immediately.",
                        color = colors.warning,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }

                if (fingerprint.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    KeyValue("Key fingerprint", fingerprint, mono = true)
                }
                KeyValue(
                    "Rejected envelopes",
                    repository.rejectedEnvelopes.toString(),
                    valueColor = if (repository.rejectedEnvelopes > 0) colors.danger else colors.textSecondary,
                )
            }

            // ── Battery survival ─────────────────────────────────────────────────
            SettingsCard("Background reliability") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (dozeExempt) colors.success else colors.danger),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (dozeExempt) "Doze exemption granted" else "Doze exemption missing",
                        color = if (dozeExempt) colors.success else colors.danger,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Without this, Android can hold the socket closed for up to 15 " +
                        "minutes at a time and a call will ring long after it stopped.",
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                ActionRow(Icons.Rounded.BatteryAlert, "Request exemption", colors.warning) {
                    runCatching {
                        context.startActivity(
                            SystemHealth.requestIgnoreBatteryOptimizationsIntent(context),
                        )
                    }.onFailure {
                        context.startActivity(SystemHealth.batteryOptimizationSettingsIntent())
                    }
                    dozeExempt = SystemHealth.isIgnoringBatteryOptimizations(context)
                }
                if (SamsungBatterySettings.isSamsung()) {
                    Spacer(Modifier.height(8.dp))
                    ActionRow(Icons.Rounded.Lock, "Samsung power settings", colors.textSecondary) {
                        context.startActivity(SamsungBatterySettings.openPowerSettingsIntent(context))
                    }
                    Spacer(Modifier.height(10.dp))
                    SamsungBatterySettings.manualSteps.forEach {
                        Text("• $it", color = colors.textTertiary, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }

            // ── Danger zone ──────────────────────────────────────────────────────
            SettingsCard("Pairing") {
                Text(
                    "Unpairing destroys the room on the server and wipes the root key, " +
                        "the auth token and the encrypted message cache from this device. " +
                        "It cannot be undone.",
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                ActionRow(Icons.Rounded.LinkOff, "Unpair this device", colors.danger) {
                    scope.launch {
                        val store = repository.secureStore
                        PairingApi(store.serverUrl).unpair(store.authToken)
                        repository.disconnect()
                        MessageCache(context).clear()
                        CallLogStore.clear()
                        // Keep the role so the user is not sent back to the role picker.
                        store.unpair()
                        onUnpaired()
                    }
                }
            }
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = Glass.colors
    val shape = RoundedCornerShape(Glass.dimens.cardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.canvasRaised)
            .border(1.dp, colors.glassBorder, shape)
            .padding(16.dp),
    ) {
        Text(
            title,
            color = colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun KeyValue(
    key: String,
    value: String,
    valueColor: Color = Glass.colors.textSecondary,
    mono: Boolean = false,
) {
    val colors = Glass.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, color = colors.textSecondary, fontSize = 14.sp)
        Text(
            value,
            color = valueColor,
            fontSize = 14.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
