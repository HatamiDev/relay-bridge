package com.relay.client.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.relay.client.ui.theme.AuroraVariant
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.GlassTone
import com.relay.client.ui.theme.LocalGlassTone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The primitives every screen is built from.
 *
 * Four things carry the aesthetic, and none of them is optional:
 *
 *  1. [AuroraBackground] — a full-screen vertical gradient with slow-drifting
 *     colour blooms on top. This is the light source; glass is only convincing
 *     when there is something behind it worth refracting.
 *  2. [GlassSurface] — translucent fill + hairline + top specular sheen, in one
 *     of two tones depending on how bright the region behind it is.
 *  3. [SquircleShape] — a true superellipse, not a rounded rectangle.
 *  4. [glow] — coloured outer bloom, which is how an active state announces
 *     itself here instead of by changing fill colour.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Squircle
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Continuous-curvature superellipse: |x/a|^n + |y/b|^n = 1.
 *
 * `RoundedCornerShape` uses circular arcs, which produce a visible curvature
 * discontinuity where the arc meets the straight edge — the faintly pinched
 * corner that makes a card look like a rectangle wearing a costume. n ≈ 4
 * matches the iOS and One UI icon curve.
 */
class SquircleShape(private val cornerRadius: Dp = 24.dp) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = with(density) { cornerRadius.toPx() }
            .coerceAtMost(minOf(size.width, size.height) / 2f)
        return Outline.Generic(superellipsePath(size, radiusPx))
    }

    companion object {
        private const val EXPONENT = 4.0
        private const val SEGMENTS = 96

        fun superellipsePath(size: Size, cornerPx: Float): Path {
            val path = Path()
            val halfW = size.width / 2f
            val halfH = size.height / 2f

            // Corner size drives how square the curve is: a large radius
            // approaches an ellipse, a small one approaches a rectangle.
            val tension = (cornerPx / minOf(halfW, halfH)).coerceIn(0.05f, 1f)
            val n = EXPONENT / tension.pow(0.35f).toDouble()

            for (i in 0..SEGMENTS) {
                val theta = 2.0 * PI * i / SEGMENTS
                val cosT = cos(theta)
                val sinT = sin(theta)

                val x = halfW * signOf(cosT) * abs(cosT).pow(2.0 / n)
                val y = halfH * signOf(sinT) * abs(sinT).pow(2.0 / n)

                val px = (halfW + x).toFloat()
                val py = (halfH + y).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            return path
        }

        private fun signOf(v: Double): Double = if (v < 0) -1.0 else 1.0
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Aurora background
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The light source.
 *
 * A vertical gradient across the whole screen, plus three slow blooms drifting
 * on mutually prime periods so the composition never visibly repeats. All three
 * blooms are drawn into one layer and blurred once — three separate blur passes
 * would cost three times as much for an identical result.
 *
 * Provides [LocalGlassTone] to descendants: children in the bright lower band
 * default to light frost, everything else to dark. A screen can still override
 * per-surface, but the default is usually right.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    variant: AuroraVariant = AuroraVariant.Feed,
    /** 0 = flat gradient, 1 = shipped intensity, >1 for hero screens. */
    intensity: Float = 1f,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens
    val stops = colors.auroraBrushStops(variant)

    val transition = rememberInfiniteTransition(label = "aurora")

    @Composable
    fun drift(periodMs: Int, label: String): Float =
        if (!animated) 0f
        else transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = label,
        ).value

    val p1 = drift(41_000, "bloom1")
    val p2 = drift(53_000, "bloom2")
    val p3 = drift(67_000, "bloom3")

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = stops)),
    ) {

        Box(
            Modifier
                .fillMaxSize()
                .blur(dimens.auroraBlur)
                .drawBehind {
                    val w = size.width
                    val h = size.height

                    bloom(
                        colors.auroraCyan, 0.20f * intensity,
                        Offset(w * (0.16f + 0.10f * cos(p1)), h * (0.16f + 0.05f * sin(p1))),
                        w * 0.70f,
                    )
                    bloom(
                        colors.auroraIndigo, 0.17f * intensity,
                        Offset(w * (0.88f + 0.08f * sin(p2)), h * (0.34f + 0.06f * cos(p2))),
                        w * 0.75f,
                    )
                    bloom(
                        colors.auroraViolet, 0.13f * intensity,
                        Offset(w * (0.72f + 0.09f * cos(p3)), h * (0.72f + 0.05f * sin(p3))),
                        w * 0.80f,
                    )
                },
        )

        // A faint vignette pushes the blooms behind the content so bright edges
        // do not fight the dock for attention.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color(0x4D000000)),
                            center = Offset(size.width / 2f, size.height * 0.42f),
                            radius = size.height * 0.9f,
                        ),
                    )
                },
        )

        CompositionLocalProvider(
            LocalGlassTone provides
                if (variant == AuroraVariant.Feed) GlassTone.Dark else GlassTone.Dark,
            content = { content() },
        )
    }
}

private fun DrawScope.bloom(color: Color, alpha: Float, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * Marks a subtree as sitting over the bright lower band, so [GlassSurface]
 * inside it defaults to light frost. Wrap the dock and the feed action rail.
 */
@Composable
fun OverBrightRegion(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassTone provides GlassTone.Light, content = content)
}

// ─────────────────────────────────────────────────────────────────────────────
// Glass surface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The canonical frosted panel.
 *
 * @param tone   defaults to whatever region the surface is in; override when a
 *               card deliberately spans the gradient
 * @param glow   paints an outer bloom — how an active state announces itself
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Glass.dimens.cardRadius),
    tone: GlassTone = Glass.tone,
    strong: Boolean = false,
    glowColor: Color? = null,
    borderColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Glass.colors
    val dimens = Glass.dimens

    Box(
        modifier
            .then(if (glowColor != null) Modifier.glow(glowColor, shape) else Modifier)
            .clip(shape)
            .background(colors.fill(tone, strong))
            .background(colors.sheenBrush)
            .border(
                width = dimens.hairline,
                color = borderColor ?: colors.glassBorder,
                shape = shape,
            ),
        content = content,
    )
}

/**
 * Outer bloom.
 *
 * Compose has no coloured box-shadow, so this strokes the outline three times
 * at increasing width and decreasing alpha. Cheap, and against a dark canvas it
 * reads exactly like a soft glow.
 */
fun Modifier.glow(color: Color, shape: Shape, spread: Dp = 14.dp): Modifier =
    this.drawWithCache {
        val path = shape.toPath(size, layoutDirection, this)
        val spreadPx = spread.toPx()
        onDrawBehind {
            for (i in 3 downTo 1) {
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.11f / i),
                    style = Stroke(width = spreadPx * i / 1.5f),
                )
            }
        }
    }

/** Hairline gradient ring — pinned stories, call avatar, active dock pill. */
fun Modifier.gradientRing(
    brush: Brush,
    shape: Shape,
    width: Dp = 1.5.dp,
): Modifier = this.drawWithCache {
    val path = shape.toPath(size, layoutDirection, this)
    val strokeWidth = width.toPx()
    onDrawWithContent {
        drawContent()
        drawPath(path = path, brush = brush, style = Stroke(width = strokeWidth))
    }
}

private fun Shape.toPath(size: Size, layoutDirection: LayoutDirection, density: Density): Path =
    when (val outline = createOutline(size, layoutDirection, density)) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }

/**
 * Full-bleed blurred wallpaper, used behind the call screen avatar.
 *
 * On API < 31 [blur] is a no-op, so the scrim gets heavier instead — the text
 * over it stays legible either way rather than silently losing contrast on
 * older devices.
 */
@Composable
fun BlurredWallpaper(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 80.dp,
    scrimAlpha: Float = 0.5f,
    content: @Composable BoxScope.() -> Unit,
) {
    val supportsBlur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val scrim = if (supportsBlur) scrimAlpha else (scrimAlpha + 0.22f).coerceAtMost(0.9f)

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (supportsBlur) Modifier.blur(blurRadius) else Modifier),
            content = content,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = scrim * 0.95f),
                            Color.Black.copy(alpha = scrim * 0.55f),
                            Color.Black.copy(alpha = scrim),
                        ),
                    ),
                ),
        )
    }
}

/** Consistent screen gutters. */
@Composable
fun Modifier.screenGutter(): Modifier =
    this.padding(horizontal = Glass.dimens.screenPadding)
