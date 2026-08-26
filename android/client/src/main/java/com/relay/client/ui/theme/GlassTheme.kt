package com.relay.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Aurora Glass design system.
 *
 * ## What changed from the first pass, and why
 *
 * The original take used a flat near-black canvas with a few coloured orbs. The
 * reference is doing something different and better: the *whole screen* is a
 * vertical gradient from near-black navy at the status bar down to a luminous
 * teal at the home indicator. Content floats on that gradient, so a card near
 * the top reads as dark glass and the same card near the bottom reads as light
 * frost — the material is constant, the light behind it is not.
 *
 * That single decision is what makes the reference look expensive, and it is
 * why this file exposes **two** glass tones rather than one:
 *
 *  * [GlassColors.glassDark] for surfaces sitting over the dark upper region
 *  * [GlassColors.glassLight] for surfaces sitting over the bright lower region
 *    — the dock, the feed action rail, the Follow pill
 *
 * Using the dark tone at the bottom of the screen produces a muddy grey smear;
 * using the light tone at the top produces a washed-out ghost. [GlassTone]
 * makes the choice explicit at every call site instead of leaving it to luck.
 *
 * ## Two backgrounds, not one
 *
 * [AuroraVariant.Feed] is dark-to-bright: it puts the light where the thumb is
 * and lets photo content glow into the dock.
 *
 * [AuroraVariant.Chat] is the inverse — teal behind the header, near-black
 * behind the message list. Long-form reading needs a quiet field; a bright
 * gradient under a wall of text is exhausting.
 */

@Immutable
data class GlassColors(
    // ── Aurora canvas ────────────────────────────────────────────────────────
    /** Feed / stories / call: dark at the top, luminous teal at the bottom. */
    val auroraFeed: List<Pair<Float, Color>> = listOf(
        0.00f to Color(0xFF050A12),
        0.26f to Color(0xFF0A1C29),
        0.55f to Color(0xFF114054),
        0.80f to Color(0xFF1F7799),
        1.00f to Color(0xFF32A6CB),
    ),
    /** Chat / settings: teal behind the header, quiet near-black under text. */
    val auroraChat: List<Pair<Float, Color>> = listOf(
        0.00f to Color(0xFF0C3040),
        0.22f to Color(0xFF0B1F2B),
        0.55f to Color(0xFF080D14),
        1.00f to Color(0xFF04060A),
    ),

    val canvas: Color = Color(0xFF050A12),
    val canvasRaised: Color = Color(0xFF0B141F),

    // ── Glass, two tones ─────────────────────────────────────────────────────
    /** Over the dark upper region. */
    val glassDark: Color = Color(0x9E0A1220),
    val glassDarkStrong: Color = Color(0xD10A1220),
    /** Over the bright lower region — white frost, not dark smoke. */
    val glassLight: Color = Color(0x24FFFFFF),
    val glassLightStrong: Color = Color(0x3DFFFFFF),

    val glassBorder: Color = Color(0x24FFFFFF),
    val glassBorderSoft: Color = Color(0x14FFFFFF),
    val sheenTop: Color = Color(0x1FFFFFFF),
    val sheenBottom: Color = Color(0x00FFFFFF),

    // ── Ambient light ────────────────────────────────────────────────────────
    val auroraCyan: Color = Color(0xFF35C8EC),
    val auroraTeal: Color = Color(0xFF2AA5C9),
    val auroraIndigo: Color = Color(0xFF5B6BE8),
    val auroraViolet: Color = Color(0xFF9B6BF0),

    // ── Content ──────────────────────────────────────────────────────────────
    val textPrimary: Color = Color(0xFFFFFFFF),
    val textSecondary: Color = Color(0xFFB7CBD6),
    val textTertiary: Color = Color(0xFF7E96A3),
    /** For text sitting on a light-frost surface over the bright region. */
    val textOnLight: Color = Color(0xFF06202B),

    // ── Semantic ─────────────────────────────────────────────────────────────
    val accent: Color = Color(0xFF4CC7EE),
    val accentSoft: Color = Color(0x334CC7EE),
    val heart: Color = Color(0xFFFF3B47),
    val live: Color = Color(0xFFF4353F),
    val success: Color = Color(0xFF27D07E),
    val warning: Color = Color(0xFFFFC24B),
    val danger: Color = Color(0xFFFF4D5E),
    val online: Color = Color(0xFF27D07E),

    // ── Message bubbles ──────────────────────────────────────────────────────
    /**
     * The reference keeps both directions dark and separates them by alignment
     * plus an attached avatar, rather than by a saturated colour. It reads
     * calmer over a long thread; the outgoing tint is kept just far enough from
     * the incoming one to survive a glance.
     */
    val bubbleIncoming: Color = Color(0xE616202B),
    val bubbleOutgoing: Color = Color(0xE61B2A3A),
    val bubbleOutgoingAccentA: Color = Color(0xFF2563EB),
    val bubbleOutgoingAccentB: Color = Color(0xFF4F46E5),

    // ── Waveform ─────────────────────────────────────────────────────────────
    val waveActiveStart: Color = Color(0xFF6FD8F5),
    val waveActiveEnd: Color = Color(0xFF5B6BE8),
    val waveInactive: Color = Color(0x3D9FC7DA),
) {
    /** Signature cyan → indigo → violet sweep, used on rings and CTAs. */
    val auroraSweep: Brush
        get() = Brush.linearGradient(listOf(auroraCyan, auroraIndigo, auroraViolet))

    val waveformBrush: Brush
        get() = Brush.verticalGradient(listOf(waveActiveStart, waveActiveEnd))

    /** Applied over every glass fill to fake a backdrop specular edge. */
    val sheenBrush: Brush
        get() = Brush.verticalGradient(listOf(sheenTop, sheenBottom))

    val outgoingAccentBrush: Brush
        get() = Brush.linearGradient(listOf(bubbleOutgoingAccentA, bubbleOutgoingAccentB))

    fun auroraBrushStops(variant: AuroraVariant): Array<Pair<Float, Color>> =
        when (variant) {
            AuroraVariant.Feed -> auroraFeed.toTypedArray()
            AuroraVariant.Chat -> auroraChat.toTypedArray()
        }

    /** Fill for a given tone. */
    fun fill(tone: GlassTone, strong: Boolean = false): Color = when (tone) {
        GlassTone.Dark -> if (strong) glassDarkStrong else glassDark
        GlassTone.Light -> if (strong) glassLightStrong else glassLight
    }

    /** Foreground that stays legible on a given tone. */
    fun onFill(tone: GlassTone): Color = when (tone) {
        GlassTone.Dark -> textPrimary
        GlassTone.Light -> textPrimary
    }
}

/** Which half of the aurora a surface is sitting on. */
enum class GlassTone { Dark, Light }

/** Which direction the canvas gradient runs. */
enum class AuroraVariant { Feed, Chat }

@Immutable
data class GlassDimens(
    /** Reference cards are noticeably rounder than Material's default. */
    val cardRadius: Dp = 28.dp,
    val sheetRadius: Dp = 32.dp,
    /** Story squircle: rounded-[22px] in the spec, 24 reads better at 72dp. */
    val squircleRadius: Dp = 24.dp,
    val bubbleRadius: Dp = 22.dp,
    val pillRadius: Dp = 50.dp,
    val hairline: Dp = 1.dp,

    /** backdrop-blur-3xl ≈ 64px; the orb layer goes further. */
    val backdropBlur: Dp = 40.dp,
    val auroraBlur: Dp = 110.dp,

    val screenPadding: Dp = 18.dp,
    val dockHeight: Dp = 68.dp,
    val railWidth: Dp = 56.dp,
    val iconButton: Dp = 44.dp,
    val avatarSize: Dp = 52.dp,
    val storyWidth: Dp = 74.dp,
    val storyHeight: Dp = 96.dp,
)

val LocalGlassColors = staticCompositionLocalOf { GlassColors() }
val LocalGlassDimens = staticCompositionLocalOf { GlassDimens() }
/** The tone surfaces should default to, set by whichever region they are in. */
val LocalGlassTone = staticCompositionLocalOf { GlassTone.Dark }

/** Shorthand: `Glass.colors.accent`, `Glass.dimens.cardRadius`, `Glass.tone`. */
object Glass {
    val colors: GlassColors
        @Composable get() = LocalGlassColors.current
    val dimens: GlassDimens
        @Composable get() = LocalGlassDimens.current
    val tone: GlassTone
        @Composable get() = LocalGlassTone.current
}

/**
 * Type scale.
 *
 * Tight negative tracking on the large sizes and a generous 1.4 line height on
 * body copy — the reference's headings are set close, its paragraphs are not.
 */
private val AuroraTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        letterSpacing = (-0.6).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * Dark-only by design.
 *
 * A glassmorphic stack has no honest light-mode translation — the whole system
 * depends on light coming from *behind* the material, and a white background
 * has nowhere for that light to come from. Inventing one would dilute the
 * identity rather than serve anyone.
 */
@Composable
fun RelayGlassTheme(content: @Composable () -> Unit) {
    val colors = GlassColors()
    val dimens = GlassDimens()

    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalGlassDimens provides dimens,
        LocalGlassTone provides GlassTone.Dark,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.textOnLight,
                secondary = colors.auroraIndigo,
                background = colors.canvas,
                onBackground = colors.textPrimary,
                surface = colors.canvasRaised,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.glassDark,
                onSurfaceVariant = colors.textSecondary,
                error = colors.danger,
                outline = colors.glassBorder,
            ),
            typography = AuroraTypography,
            content = content,
        )
    }
}
