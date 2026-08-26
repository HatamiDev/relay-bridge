package com.relay.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import com.relay.client.ReceiverActivity
import com.relay.client.ui.components.AuroraBackground
import com.relay.client.ui.components.GlassSurface
import com.relay.client.ui.components.SquircleShape
import com.relay.client.ui.components.glow
import com.relay.client.ui.components.gradientRing
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.RelayGlassTheme
import com.relay.core.model.DeviceRole
import com.relay.gateway.ui.GatewayActivity

/**
 * First-launch role picker.
 *
 * One APK installs on every phone; this screen decides which half of the bridge
 * each install becomes. The choice is written to encrypted preferences and is
 * effectively permanent — changing it later means unpairing, because the role
 * determines the direction of every derived key.
 *
 * The screen reads the SIM state and *recommends* accordingly rather than
 * deciding for the user: a phone can have a SIM and still be the receiver, and
 * an eSIM-only device can report ABSENT while working fine.
 */
class RoleSelectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RelayGlassTheme {
                RoleSelectScreen(
                    onChosen = { role ->
                        RelayApp.instance.secureStore.role = role
                        val next = when (role) {
                            DeviceRole.GATEWAY -> Intent(this, GatewayActivity::class.java)
                            else -> Intent(this, ReceiverActivity::class.java)
                        }
                        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun RoleSelectScreen(onChosen: (DeviceRole) -> Unit) {
    val colors = Glass.colors
    val context = LocalContext.current

    val hasSim = remember {
        runCatching {
            context.getSystemService<TelephonyManager>()?.simState == TelephonyManager.SIM_STATE_READY
        }.getOrDefault(false)
    }

    var selected by remember {
        mutableStateOf(if (hasSim) DeviceRole.GATEWAY else DeviceRole.RECEIVER)
    }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            Text(
                "Set up this device",
                color = colors.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Install the same app on every phone. One holds the SIM and does " +
                    "the sending; the others receive.",
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            RoleCard(
                icon = Icons.Rounded.SimCard,
                title = "Sender",
                subtitle = "This phone has the SIM",
                bullets = listOf(
                    "Relays its SMS and cellular calls out",
                    "Generates the pairing code",
                    "Must stay powered and online",
                ),
                recommended = hasSim,
                selected = selected == DeviceRole.GATEWAY,
                accent = colors.auroraCyan,
                onClick = { selected = DeviceRole.GATEWAY },
            )

            Spacer(Modifier.height(14.dp))

            RoleCard(
                icon = Icons.Rounded.PhoneAndroid,
                title = "Receiver",
                subtitle = "This phone has no SIM (or you just want the mirror)",
                bullets = listOf(
                    "Reads and replies to the sender's messages",
                    "Rings for the sender's calls",
                    "Enter the code from the sender",
                ),
                recommended = !hasSim,
                selected = selected == DeviceRole.RECEIVER,
                accent = colors.auroraViolet,
                onClick = { selected = DeviceRole.RECEIVER },
            )

            Spacer(Modifier.height(18.dp))

            // The choice is load-bearing for the key schedule, so say so once,
            // here, rather than surprising the user at unpair time.
            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = colors.warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Changing this later means unpairing and starting over — the " +
                            "role decides the direction of the encryption keys.",
                        color = colors.textTertiary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .fillMaxWidth()
                    .glow(colors.accent, RoundedCornerShape(50), 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .clickable { onChosen(selected) }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Continue as ${if (selected == DeviceRole.GATEWAY) "Sender" else "Receiver"}",
                    color = Color(0xFF04121C),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "SIM detected: ${if (hasSim) "yes" else "no"} · ${Build.MODEL}",
                color = colors.textTertiary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    bullets: List<String>,
    recommended: Boolean,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val colors = Glass.colors
    val shape = remember { SquircleShape(26.dp) }

    // A small scale lift rather than a border colour change: at a glance the
    // selected card should read as *nearer*, not merely differently outlined.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.975f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "roleCardScale",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .then(if (selected) Modifier.glow(accent, shape, 16.dp) else Modifier)
            .clip(shape)
            .background(if (selected) colors.glassDarkStrong else colors.glassDark)
            .background(colors.sheenBrush)
            .then(
                if (selected) {
                    Modifier.gradientRing(colors.auroraSweep, shape, 1.5.dp)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = if (selected) 0.24f else 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(21.dp),
                    )
                }

                Spacer(Modifier.width(13.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (recommended) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(colors.success.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "Suggested",
                                    color = colors.success,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Text(subtitle, color = colors.textSecondary, fontSize = 12.5.sp)
                }

                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            bullets.forEach { line ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(colors.textTertiary),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(line, color = colors.textTertiary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}
