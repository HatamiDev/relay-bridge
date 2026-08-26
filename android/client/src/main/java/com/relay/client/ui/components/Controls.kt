package com.relay.client.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.GlassTone

/**
 * The small controls that appear on nearly every screen.
 *
 * Kept together because they share one rule: **44dp minimum touch target, no
 * matter how small the glyph looks.** The reference's header buttons read as
 * about 26dp of icon inside a 44dp circle, and the temptation is to shrink the
 * circle to match the drawing. Doing that puts the control under the platform
 * minimum and makes it miss on a moving train.
 */

/** Circular glass icon button — header actions, call controls, back buttons. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Glass.dimens.iconButton,
    tone: GlassTone = Glass.tone,
    tint: Color? = null,
    background: Color? = null,
    glowColor: Color? = null,
    badge: Int = 0,
) {
    val colors = Glass.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "circleButtonScale",
    )

    Box(modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .scale(scale)
                .then(if (glowColor != null) Modifier.glow(glowColor, CircleShape, 12.dp) else Modifier)
                .clip(CircleShape)
                .background(background ?: colors.fill(tone))
                .background(colors.sheenBrush)
                .then(
                    if (background == null) {
                        Modifier.gradientRing(
                            Brush.linearGradient(listOf(colors.glassBorder, Color.Transparent)),
                            CircleShape,
                            1.dp,
                        )
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint ?: colors.textPrimary,
                modifier = Modifier.size(size * 0.45f),
            )
        }

        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(colors.heart)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Text-only segmented tabs, as in the reference's "Discover / Following".
 *
 * No pill, no underline, no divider: the active tab is simply larger and white,
 * the inactive one smaller and muted. On a gradient canvas any container around
 * the tabs competes with the cards below — weight and colour carry the state on
 * their own, and the result is quieter and easier to scan.
 *
 * A 2dp dot under the active label gives the state a non-colour cue, so it
 * survives for a user who cannot distinguish the two greys.
 */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }

            Box(
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                ) { onSelect(index) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        color = if (active) colors.textPrimary else colors.textTertiary,
                        fontSize = if (active) 19.sp else 18.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = (-0.3).sp,
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
    }
}

/** Small labelled pill — "+ Follow", "Reply", "Live", capability chips. */
@Composable
fun Pill(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: GlassTone = Glass.tone,
    solidColor: Color? = null,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = Glass.colors
    val fg = contentColor
        ?: if (solidColor != null) colors.textOnLight else colors.textPrimary

    Row(
        modifier
            .clip(CircleShape)
            .background(solidColor ?: colors.fill(tone, strong = true))
            .background(colors.sheenBrush)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The pulsing "Live" badge from the reference's camera screen.
 *
 * The dot pulses; the word does not. Animating the text as well would make the
 * badge flicker in peripheral vision, which is distracting rather than urgent.
 */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val colors = Glass.colors
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "liveDot",
    )

    Row(
        modifier
            .clip(CircleShape)
            .background(colors.live)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(6.dp))
        Text("Live", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Thin spacer used to keep content clear of the floating dock. */
@Composable
fun DockSpacer(extra: Dp = 28.dp) {
    Spacer(Modifier.height(Glass.dimens.dockHeight + extra))
}
