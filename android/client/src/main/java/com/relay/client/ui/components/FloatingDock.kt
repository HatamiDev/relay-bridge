package com.relay.client.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.GlassTone

/**
 * The floating navigation dock.
 *
 * A light-frost capsule hovering over the bright lower band of the aurora, with
 * one moving part: a pale pill that slides to the active tab and expands to
 * reveal that tab's label. Inactive tabs are icon-only.
 *
 * Why label-on-active-only, rather than labels everywhere or nowhere:
 *
 * * Labels on every tab turn the dock into a wall of small text and force the
 *   capsule taller, which pushes it into the content.
 * * No labels at all makes the first-run user guess what a glyph means.
 *
 * Showing the label for exactly the destination you are on teaches the mapping
 * over the first few taps and then gets out of the way — and the expanding pill
 * gives the slide somewhere to go, so the motion is doing work rather than
 * decorating.
 */

enum class DockTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Search("Search", Icons.Rounded.Search),
    Activity("Activity", Icons.Rounded.FavoriteBorder),
    Profile("Profile", Icons.Rounded.Person),
}

@Composable
fun FloatingDock(
    selected: DockTab,
    onSelect: (DockTab) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<DockTab, Int> = emptyMap(),
) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val tabs = remember { DockTab.entries.toList() }
    val density = LocalDensity.current

    var dockWidthPx by remember { mutableIntStateOf(0) }
    val dockWidth = with(density) { dockWidthPx.toDp() }

    val edge = 6.dp
    val slot: Dp = if (dockWidth > 0.dp) (dockWidth - edge * 2) / tabs.size else 0.dp

    // The active pill is wider than one slot so the label has room; it is
    // clamped so it never overhangs the capsule at either end.
    val pillWidth = (slot * 1.62f).coerceAtMost(dockWidth - edge * 2)
    val rawOffset = slot * tabs.indexOf(selected) - (pillWidth - slot) / 2f
    val clamped = rawOffset.coerceIn(0.dp, (dockWidth - edge * 2 - pillWidth).coerceAtLeast(0.dp))

    val pillOffset by animateDpAsState(
        targetValue = clamped,
        animationSpec = spring(
            dampingRatio = 0.76f,                // a hint of overshoot, no wobble
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "dockPill",
    )

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.dockHeight)
            .onSizeChanged { dockWidthPx = it.width },
        shape = CircleShape,
        tone = GlassTone.Light,
        strong = true,
        glowColor = Color.White.copy(alpha = 0.18f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = edge)) {

            // ── Sliding pill ──────────────────────────────────────────────────
            if (slot > 0.dp) {
                Box(
                    Modifier
                        .offset(x = pillOffset)
                        .width(pillWidth)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.22f))
                        .gradientRing(colors.auroraSweep, RoundedCornerShape(50), 1.dp),
                )
            }

            // ── Tabs ─────────────────────────────────────────────────────────
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    DockItem(
                        tab = tab,
                        selected = tab == selected,
                        badge = badges[tab] ?: 0,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DockItem(
    tab: DockTab,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val interaction = remember { MutableInteractionSource() }

    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "dockIconScale",
    )

    Box(
        modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,          // the sliding pill IS the indication
            role = Role.Tab,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box {
                // Active tab gets its glyph on a small opaque chip — the detail
                // that makes the pill read as holding something, not as an
                // empty highlight sliding under a flat icon.
                Box(
                    Modifier
                        .size(if (selected) 30.dp else 26.dp)
                        .clip(RoundedCornerShape(if (selected) 10.dp else 13.dp))
                        .background(
                            if (selected) Color.White.copy(alpha = 0.92f) else Color.Transparent,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) colors.textOnLight else colors.textSecondary,
                        modifier = Modifier.size(17.dp).scale(iconScale),
                    )
                }

                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-3).dp)
                            .clip(CircleShape)
                            .background(colors.heart)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            if (badge > 99) "99+" else badge.toString(),
                            color = Color.White,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(spring()) + expandHorizontally(spring()),
                exit = fadeOut(spring()) + shrinkHorizontally(spring()),
            ) {
                Row {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = tab.label,
                        color = colors.textPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
