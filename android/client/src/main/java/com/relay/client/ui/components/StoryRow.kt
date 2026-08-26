package com.relay.client.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.theme.Glass
import com.relay.client.util.decodeBase64Image

/**
 * The pinned-contacts story row.
 *
 * Squircle cards with the contact's own photo behind them, pushed heavily out
 * of focus, a legibility scrim, a crisp avatar disc on top, and an online dot.
 *
 * The blurred-photo treatment is the whole idea: the card becomes a piece of
 * frosted glass tinted by the person behind it, which is the same metaphor the
 * rest of the surface stack runs on. A flat colour chip would break it.
 *
 * The first card is always "Your story" — a plus over your own blurred photo,
 * so the row starts with an action rather than with someone else's face.
 */

data class StoryItem(
    val id: String,
    val name: String,
    val number: String,
    val photoB64: String,
    val online: Boolean,
    val unread: Int = 0,
    val seen: Boolean = false,
)

@Composable
fun StoryRow(
    stories: List<StoryItem>,
    onStoryClick: (StoryItem) -> Unit,
    onAddStory: () -> Unit,
    modifier: Modifier = Modifier,
    myPhotoB64: String = "",
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Glass.dimens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(key = "__yours__") { YourStoryCard(myPhotoB64, onAddStory) }
        items(stories, key = { it.id }) { story ->
            StoryCard(story) { onStoryClick(story) }
        }
    }
}

@Composable
private fun StoryCard(story: StoryItem, onClick: () -> Unit) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val shape = remember { SquircleShape(dimens.squircleRadius) }

    // Online contacts breathe rather than wearing a static ring. Motion is what
    // separates "present right now" from "decorative border".
    val pulse by rememberInfiniteTransition(label = "storyPulse").animateFloat(
        initialValue = 0.32f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "pulseAlpha",
    )

    val photo: ImageBitmap? = remember(story.photoB64) { decodeBase64Image(story.photoB64) }

    Box(
        Modifier
            .size(dimens.storyWidth, dimens.storyHeight)
            .then(
                if (story.online) {
                    Modifier.glow(colors.auroraCyan.copy(alpha = pulse), shape, spread = 13.dp)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(colors.canvasRaised)
            .clickable(onClick = onClick),
    ) {
        StoryBackdrop(photo, story.name, shape)

        // Crisp avatar disc.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(colors.canvasRaised)
                .then(
                    // Unseen stories get the full sweep ring; seen ones a plain
                    // hairline. Same convention every story product uses, and
                    // users already know how to read it.
                    if (!story.seen) {
                        Modifier.gradientRing(colors.auroraSweep, CircleShape, 1.8.dp)
                    } else {
                        Modifier.border(1.dp, colors.glassBorderSoft, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = story.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidthCircle(),
                )
            } else {
                Text(
                    text = story.name.initialsOf(),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (story.online) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(colors.online)
                    .border(2.dp, Color.Black.copy(alpha = 0.45f), CircleShape),
            )
        }

        if (story.unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp)
                    .clip(CircleShape)
                    .background(colors.heart)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    if (story.unread > 9) "9+" else story.unread.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Text(
            text = story.name.firstWordOf(),
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 6.dp, vertical = 9.dp)
                .fillMaxWidth(),
        )

        Box(Modifier.matchParentSizeBorder(shape))
    }
}

@Composable
private fun YourStoryCard(myPhotoB64: String, onClick: () -> Unit) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val shape = remember { SquircleShape(dimens.squircleRadius) }
    val photo = remember(myPhotoB64) { decodeBase64Image(myPhotoB64) }

    Box(
        Modifier
            .size(dimens.storyWidth, dimens.storyHeight)
            .clip(shape)
            .background(colors.canvasRaised)
            .clickable(onClick = onClick),
    ) {
        StoryBackdrop(photo, "You", shape)

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Add to your story",
                tint = colors.textOnLight,
                modifier = Modifier.size(19.dp),
            )
        }

        Text(
            "Your story",
            color = colors.textPrimary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 4.dp, vertical = 9.dp)
                .fillMaxWidth(),
        )

        Box(Modifier.matchParentSizeBorder(shape))
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

/** Blurred photo (or a deterministic gradient) plus the legibility scrim. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.StoryBackdrop(
    photo: ImageBitmap?,
    name: String,
    shape: androidx.compose.ui.graphics.Shape,
) {
    val colors = Glass.colors

    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .blur(11.dp)
                .clip(shape),
        )
    } else {
        Box(
            Modifier
                .matchParentSize()
                .background(nameGradient(name, colors.auroraCyan, colors.auroraViolet)),
        )
    }

    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.74f)),
                ),
            ),
    )
}

/** Hairline over the whole card, drawn last so nothing paints over it. */
@Composable
private fun Modifier.matchParentSizeBorder(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this
        .then(Modifier)
        .border(1.dp, Glass.colors.glassBorder, shape)

/** Circle-cropped fill for the avatar disc. */
@Composable
private fun Modifier.fillMaxWidthCircle(): Modifier = this.size(38.dp).clip(CircleShape)

private fun String.initialsOf(): String = trim()
    .split(' ')
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { "?" }

private fun String.firstWordOf(): String = trim().substringBefore(' ').ifEmpty { this }

/** Deterministic per-contact gradient, so an avatar-less contact stays stable. */
private fun nameGradient(name: String, a: Color, b: Color): Brush {
    val t = ((name.hashCode() ushr 8) and 0xFF) / 255f
    return Brush.linearGradient(
        listOf(
            lerpColor(a, b, t).copy(alpha = 0.62f),
            lerpColor(b, a, t).copy(alpha = 0.28f),
        ),
    )
}

private fun lerpColor(from: Color, to: Color, t: Float) = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)
