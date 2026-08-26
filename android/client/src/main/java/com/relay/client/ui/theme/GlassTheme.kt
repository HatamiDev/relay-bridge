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
 * The design system, retargeted to the CometChat UI Kit (dark theme).
 *
 * Values here are lifted from the Figma file's published variables rather than
 * eyeballed from a screenshot — colours, the type ramp, the radius scale and
 * the 4-point spacing scale all carry their Figma names in the comments so a
 * future change in the design file can be traced to a line in this one.
 *
 * ## Why the type and class names still say "Glass"
 *
 * The previous system was a glassmorphic aurora: a full-screen teal gradient
 * with translucent surfaces floating on it. Every screen and component in this
 * module reads its colours through `Glass.colors.*`. Renaming the tokens would
 * have meant touching every call site in the same change that alters how the
 * app looks, which makes a visual regression impossible to tell apart from a
 * refactoring mistake. So the *names* are held constant and the *values* are
 * repointed. The vocabulary is now inaccurate in places — `glassDark` is an
 * opaque surface, `auroraSweep` is a flat purple — and renaming is worth doing
 * once the visual result has been signed off.
 *
 * ## What changed materially
 *
 * * **No gradients.** The canvas is a flat `#141414`; surfaces are flat greys.
 *   The gradient stop lists survive as two-stop flats so existing callers keep
 *   compiling and simply paint a solid colour.
 * * **No translucency and no blur.** Surfaces are opaque, and `backdropBlur` is
 *   0.dp. A blur radius over an opaque fill costs a full offscreen pass and
 *   changes nothing on screen.
 * * **Tighter radii.** 28dp cards and 22dp bubbles become 12dp — the kit's
 *   `Radius/radius_3`.
 * * **Roboto, 1.2 line height.** The kit sets every style at 1.2× with zero
 *   tracking. Body copy at 1.2 is tight for long paragraphs, but matching the
 *   design matters more than my preference, and chat lines are short.
 */

@Immutable
data class GlassColors(
    // ── Canvas ───────────────────────────────────────────────────────────────
    // Kept as gradient stop lists so AuroraBackground and friends still compile,
    // but both ends are the same colour: the design has no gradient.
    /** Background2 — the page behind everything. */
    val auroraFeed: List<Pair<Float, Color>> = listOf(
        0.00f to Color(0xFF141414),
        1.00f to Color(0xFF141414),
    ),
    val auroraChat: List<Pair<Float, Color>> = listOf(
        0.00f to Color(0xFF141414),
        1.00f to Color(0xFF141414),
    ),

    /** Color/Background Color/Background2 */
    val canvas: Color = Color(0xFF141414),
    /** Color/Background Color/Background1 — cards, bars, list rows. */
    val canvasRaised: Color = Color(0xFF1A1A1A),

    // ── Surfaces ─────────────────────────────────────────────────────────────
    // Opaque now. `strong` steps one level up the grey ramp instead of raising
    // an alpha, which is what gives depth without translucency.
    /** Background1 */
    val glassDark: Color = Color(0xFF1A1A1A),
    /** Background3 */
    val glassDarkStrong: Color = Color(0xFF272727),
    /** Background3 — the "light" tone is no longer white frost. */
    val glassLight: Color = Color(0xFF272727),
    /** Background4 */
    val glassLightStrong: Color = Color(0xFF383838),

    /** Color/Border Color/Border Light */
    val glassBorder: Color = Color(0xFF272727),
    /** Color/Border Color/Border Dark, softened for hairlines. */
    val glassBorderSoft: Color = Color(0xFF272727),
    // No specular edge on a flat surface.
    val sheenTop: Color = Color(0x00FFFFFF),
    val sheenBottom: Color = Color(0x00FFFFFF),

    // ── Accent ───────────────────────────────────────────────────────────────
    /** Color/Primary Color/Primary */
    val auroraCyan: Color = Color(0xFF6852D6),
    /** Color/Extended Primary Color/Extended Primary 500 */
    val auroraTeal: Color = Color(0xFF3E3180),
    val auroraIndigo: Color = Color(0xFF6852D6),
    val auroraViolet: Color = Color(0xFF6852D6),

    // ── Content ──────────────────────────────────────────────────────────────
    /** Color/Text Color/Text Primary */
    val textPrimary: Color = Color(0xFFFFFFFF),
    /** Color/Text Color/Text Secondary */
    val textSecondary: Color = Color(0xFF989898),
    /** Color/Text Color/Text Tertiary */
    val textTertiary: Color = Color(0xFF858585),
    /**
     * Foreground for a filled primary surface.
     *
     * White, not a dark ink: the primary is #6852D6, and white on it clears
     * 4.5:1 while near-black does not.
     */
    val textOnLight: Color = Color(0xFFFFFFFF),

    // ── Semantic ─────────────────────────────────────────────────────────────
    val accent: Color = Color(0xFF6852D6),
    val accentSoft: Color = Color(0x336852D6),
    val heart: Color = Color(0xFFE04562),
    val live: Color = Color(0xFFE04562),
    /** Color/Alert Color/Success */
    val success: Color = Color(0xFF0B9F5D),
    /** Color/Alert Color/Warning */
    val warning: Color = Color(0xFFD08D04),
    val danger: Color = Color(0xFFE04562),
    val online: Color = Color(0xFF0B9F5D),

    // ── Message bubbles ──────────────────────────────────────────────────────
    /** Received Bubble BG - N300 */
    val bubbleIncoming: Color = Color(0xFF383838),
    /** Send Bubble BG - SP */
    val bubbleOutgoing: Color = Color(0xFF6852D6),
    val bubbleOutgoingAccentA: Color = Color(0xFF6852D6),
    val bubbleOutgoingAccentB: Color = Color(0xFF6852D6),
    /** Color/Alert Color/Message Seen — the double-tick. */
    val messageSeen: Color = Color(0xFF56E8A7),

    // ── Waveform ─────────────────────────────────────────────────────────────
    val waveActiveStart: Color = Color(0xFF6852D6),
    val waveActiveEnd: Color = Color(0xFF6852D6),
    val waveInactive: Color = Color(0xFF4C4C4C),
) {
    /**
     * Formerly a cyan→indigo→violet sweep on rings and CTAs.
     *
     * Now a flat primary. It stays a Brush so ring and button call sites are
     * untouched; a two-stop gradient between identical colours is a solid fill.
     */
    val auroraSweep: Brush
        get() = Brush.linearGradient(listOf(accent, accent))

    val waveformBrush: Brush
        get() = Brush.verticalGradient(listOf(waveActiveStart, waveActiveEnd))

    /** Fully transparent — flat surfaces have no specular edge to fake. */
    val sheenBrush: Brush
        get() = Brush.verticalGradient(listOf(sheenTop, sheenBottom))

    val outgoingAccentBrush: Brush
        get() = Brush.linearGradient(listOf(bubbleOutgoing, bubbleOutgoing))

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
    fun onFill(tone: GlassTone): Color = textPrimary
}

/**
 * Which surface level something sits on.
 *
 * Once this meant "which half of the gradient". Now it selects a step on the
 * grey ramp: [Dark] is Background1, [Light] is Background3 — one step raised,
 * for chrome that must separate from a card behind it.
 */
enum class GlassTone { Dark, Light }

/** Retained so existing call sites compile; both variants now paint the same flat canvas. */
enum class AuroraVariant { Feed, Chat }

@Immutable
data class GlassDimens(
    /** Radius/radius_3 */
    val cardRadius: Dp = 12.dp,
    val sheetRadius: Dp = 16.dp,
    /** Radius/radius_2 — avatars in the kit are circles, tiles are 8dp. */
    val squircleRadius: Dp = 8.dp,
    /** Radius/radius_3 */
    val bubbleRadius: Dp = 12.dp,
    /** Radius/radius_Max */
    val pillRadius: Dp = 100.dp,
    val hairline: Dp = 1.dp,

    /**
     * Zero. Every surface is opaque, so a backdrop blur would cost an offscreen
     * render pass per surface and produce an identical frame.
     */
    val backdropBlur: Dp = 0.dp,
    val auroraBlur: Dp = 0.dp,

    /** Padding/padding_4 */
    val screenPadding: Dp = 16.dp,
    val dockHeight: Dp = 64.dp,
    val railWidth: Dp = 56.dp,
    val iconButton: Dp = 40.dp,
    val avatarSize: Dp = 48.dp,
    val storyWidth: Dp = 64.dp,
    val storyHeight: Dp = 84.dp,
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
 * The kit's type ramp: Roboto, line height 1.2×, letter spacing 0 throughout.
 *
 * Figma names map to Material slots as:
 *   H1 Bold 24    → displaySmall / headlineMedium
 *   H2 Bold 20    → titleLarge
 *   H3 Bold 18    → titleLarge (dense contexts)
 *   H4 Medium 16  → titleMedium
 *   Body 14       → bodyLarge / bodyMedium
 *   Caption1 12   → labelMedium
 *   Caption2 10   → labelSmall
 */
private val KitTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,   // Roboto is the platform sans on Android
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.8.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.8.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 19.2.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.6.sp,   // 1.4× — a wall of chat text at 1.2 is punishing
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 16.8.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 14.4.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.sp,
    ),
)

/**
 * Dark-only, for now.
 *
 * The kit publishes a full light theme beside the dark one, so a light mode is
 * a straight swap of this colour set rather than a redesign — worth doing, but
 * it is a separate change from adopting the kit at all.
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
                secondary = colors.auroraTeal,
                background = colors.canvas,
                onBackground = colors.textPrimary,
                surface = colors.canvasRaised,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.glassDarkStrong,
                onSurfaceVariant = colors.textSecondary,
                error = colors.danger,
                outline = colors.glassBorder,
            ),
            typography = KitTypography,
            content = content,
        )
    }
}
