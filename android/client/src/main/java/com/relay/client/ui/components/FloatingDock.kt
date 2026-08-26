package com.relay.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass

/**
 * The bottom tab bar, rebuilt to the kit's `Primary Tab` component.
 *
 * ## What the previous version did wrong
 *
 * It was a floating capsule with a pale pill that slid to the active tab and
 * *expanded* to reveal that tab's label, while inactive tabs stayed icon-only.
 * The idea was that showing one label teaches the mapping without a wall of
 * text. In practice, on a 360dp screen with four tabs:
 *
 *  * The pill was sized as a multiple of one slot, so a label longer than a few
 *    characters wrapped — "Messages" rendered as "Messag / es" across two lines.
 *  * The unread badge was positioned against the icon inside that expanding
 *    row, so once the label appeared the badge sat on top of the text.
 *  * The capsule floated over content with horizontal insets, so the pill for
 *    an edge tab was clipped by the capsule's own rounded end.
 *
 * Three separate symptoms, one cause: a layout whose width depended on the
 * animated state of one of its children.
 *
 * ## What it does now
 *
 * The kit's version, which is fixed-width and static: a full-bleed bar pinned to
 * the bottom, four equal columns, icon over label, both always visible. The
 * active tab is indicated by **colour alone** — primary for the active label and
 * icon, secondary grey for the rest. No pill, no slide, no expansion, so no
 * child can resize its neighbours.
 *
 * Labels are single-line with ellipsis as a backstop: at 12sp in a 90dp column
 * they fit, but a user with a large font scale should get "Message…" rather than
 * a broken two-line row.
 */
enum class DockTab(val label: String, val icon: ImageVector) {
    Home("Chats", Icons.Rounded.ChatBubbleOutline),
    Search("Calls", Icons.Rounded.Call),
    Activity("Contacts", Icons.Rounded.Person),
    Profile("Settings", Icons.Rounded.Settings),
}

@Composable
fun FloatingDock(
    selected: DockTab,
    onSelect: (DockTab) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<DockTab, Int> = emptyMap(),
) {
    val colors = Glass.colors
    val tabs = remember { DockTab.entries.toList() }

    Column(modifier.fillMaxWidth().background(colors.canvasRaised)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.glassBorder),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            tabs.forEach { tab ->
                DockItem(
                    tab = tab,
                    selected = tab == selected,
                    badge = badges[tab] ?: 0,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
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
    val tint = if (selected) colors.accent else colors.textSecondary

    Column(
        modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(top = 12.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,   // the label below is the label
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
            if (badge > 0) {
                // Anchored to the icon, which is a fixed-size sibling of the
                // label rather than sharing a row with it — so a badge can no
                // longer land on top of text.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .clip(CircleShape)
                        .background(colors.danger)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = tab.label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
