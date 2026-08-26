package com.relay.client.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.GlassTone

/**
 * The feed's vertical action rail.
 *
 * A single light-frost capsule pinned to the right edge of the media card,
 * holding like / save / comment / share with their counts underneath.
 *
 * Two decisions worth naming:
 *
 * * **It is one capsule, not four buttons.** A column of separate circles
 *   fragments the right edge and competes with the media; a single pill reads
 *   as one piece of chrome laid over the photo and gets out of the way.
 * * **Light tone, always.** The rail sits over photographic content that could
 *   be any brightness, so a dark-smoke fill disappears against a dark photo.
 *   White frost at low alpha survives both extremes.
 */

data class RailAction(
    val icon: ImageVector,
    val count: String? = null,
    val contentDescription: String,
    val active: Boolean = false,
    val activeColor: Color? = null,
    val onClick: () -> Unit,
)

@Composable
fun ActionRail(
    actions: List<RailAction>,
    modifier: Modifier = Modifier,
) {
    val dimens = Glass.dimens

    GlassSurface(
        modifier = modifier.width(dimens.railWidth),
        shape = RoundedCornerShape(dimens.pillRadius),
        tone = GlassTone.Light,
        strong = false,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.forEach { RailButton(it) }
        }
    }
}

/**
 * Convenience builder for the standard four. Pass `null` for any count you do
 * not have yet — the label collapses rather than showing a placeholder zero,
 * because "0" and "not loaded" mean very different things to a reader.
 */
@Composable
fun FeedActionRail(
    liked: Boolean,
    likeCount: String?,
    saved: Boolean,
    saveCount: String?,
    commentCount: String?,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors

    ActionRail(
        modifier = modifier,
        actions = listOf(
            RailAction(
                icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                count = likeCount,
                contentDescription = if (liked) "Unlike" else "Like",
                active = liked,
                activeColor = colors.heart,
                onClick = onLike,
            ),
            RailAction(
                icon = Icons.Rounded.BookmarkBorder,
                count = saveCount,
                contentDescription = if (saved) "Remove bookmark" else "Bookmark",
                active = saved,
                activeColor = colors.accent,
                onClick = onSave,
            ),
            RailAction(
                icon = Icons.Rounded.ChatBubbleOutline,
                count = commentCount,
                contentDescription = "Comments",
                onClick = onComment,
            ),
            RailAction(
                icon = Icons.AutoMirrored.Rounded.Reply,
                contentDescription = "Share",
                onClick = onShare,
            ),
        ),
    )
}

@Composable
private fun RailButton(action: RailAction) {
    val colors = Glass.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // A press dips the glyph rather than tinting the background: on translucent
    // chrome a background flash reads as a rendering glitch, a scale does not.
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.86f
            action.active -> 1.08f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "railScale",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .clickableNoRipple(interaction, action.onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.contentDescription,
                tint = if (action.active) {
                    action.activeColor ?: colors.accent
                } else {
                    colors.textPrimary
                },
                modifier = Modifier
                    .size(24.dp)
                    .scale(scale),
            )
        }
        action.count?.let {
            Text(
                text = it,
                color = colors.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

// ── Small helpers ────────────────────────────────────────────────────────────

/**
 * Ripple-free click.
 *
 * Material's ripple is a rectangle of colour spreading under the finger, which
 * is exactly wrong on a translucent surface — it lights up the frost instead of
 * the control. The scale animation above is the feedback.
 */
@Composable
private fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interactionSource,
    indication = null,
    onClick = onClick,
)
