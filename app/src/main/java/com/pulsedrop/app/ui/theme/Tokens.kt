package com.pulsedrop.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Semantic colour tokens.
 *
 * Nothing outside this package should name a colour again: every screen, card and control reads a
 * role (`surface`, `textSecondary`, `danger`) so that switching theme is switching this table, not
 * hunting hundreds of literals. The two tables are the design spec's tokens for the dark-neutral
 * default and its complete light counterpart.
 */
@Immutable
class PulseColors(
    // Surfaces
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceSunken: Color,
    val surfaceOverlay: Color,
    val onOverlay: Color,
    /** Wash behind a selected or hovered row — a tint, never a border of colour. */
    val highlight: Color,

    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textInverse: Color,

    // Lines
    val borderSubtle: Color,
    val borderStrong: Color,

    // Accent
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    val accentTint: Color,
    val onAccentTint: Color,

    // Status. Each has an indicator colour (a dot, an icon, a border) and a separate text colour
    // for sitting on its own tint: the vivid one is too light against a pale background to be
    // readable text, and the theme test in ThemeTokensTest fails the pair if they are merged.
    val success: Color,
    val onSuccess: Color,
    val successTint: Color,
    val onSuccessTint: Color,
    val warning: Color,
    val onWarning: Color,
    val warningTint: Color,
    val onWarningTint: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerTint: Color,
    val onDangerTint: Color,

    // Media surfaces are a deliberate black: a video frame must not pick up the theme's grey.
    val stage: Color,

    /** 0xF5F5F5 / 0x171717 — the scrim that makes controls readable over video. */
    val scrim: Color,
    val disabled: Color,
    val focusRing: Color,
    val isDark: Boolean
)

internal val DarkColors = PulseColors(
    background = Color(0xFF0B0B0C),
    surface = Color(0xFF121214),
    surfaceElevated = Color(0xFF1A1A1D),
    surfaceSunken = Color(0xFF08080A),
    surfaceOverlay = Color(0xFF1E1E22),
    onOverlay = Color(0xFFF5F5F5),
    highlight = Color(0xFF232329),

    textPrimary = Color(0xFFF5F5F5),
    textSecondary = Color(0xFFA1A1AA),
    textTertiary = Color(0xFF71717A),
    textInverse = Color(0xFF0B0B0C),

    borderSubtle = Color(0xFF29292D),
    borderStrong = Color(0xFF3A3A40),

    accent = Color(0xFF5E63F0),
    accentHover = Color(0xFF5257E0),
    onAccent = Color(0xFFFFFFFF),
    accentTint = Color(0xFF24244A),
    onAccentTint = Color(0xFFC4C6FA),

    success = Color(0xFF22C55E),
    onSuccess = Color(0xFF052010),
    successTint = Color(0xFF12291B),
    onSuccessTint = Color(0xFF22C55E),
    warning = Color(0xFFF59E0B),
    onWarning = Color(0xFF241701),
    warningTint = Color(0xFF2A2011),
    onWarningTint = Color(0xFFF59E0B),
    danger = Color(0xFFF87171),
    onDanger = Color(0xFF2A0B0B),
    dangerTint = Color(0xFF2D1617),
    onDangerTint = Color(0xFFF87171),

    stage = Color(0xFF000000),
    scrim = Color(0x99000000),
    disabled = Color(0xFF4B4B52),
    focusRing = Color(0xFF5E63F0),
    isDark = true
)

internal val LightColors = PulseColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF8F8F8),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFF2F2F4),
    surfaceOverlay = Color(0xFFFFFFFF),
    onOverlay = Color(0xFF171717),
    highlight = Color(0xFFEEEEF1),

    textPrimary = Color(0xFF171717),
    textSecondary = Color(0xFF71717A),
    textTertiary = Color(0xFF9A9AA3),
    textInverse = Color(0xFFFFFFFF),

    borderSubtle = Color(0xFFE7E7EA),
    borderStrong = Color(0xFFD3D3D8),

    accent = Color(0xFF4F46E5),
    accentHover = Color(0xFF4338CA),
    onAccent = Color(0xFFFFFFFF),
    accentTint = Color(0xFFE5E4FC),
    onAccentTint = Color(0xFF312C8C),

    success = Color(0xFF16A34A),
    onSuccess = Color(0xFFFFFFFF),
    successTint = Color(0xFFDCFCE8),
    onSuccessTint = Color(0xFF166534),
    warning = Color(0xFFB45309),
    onWarning = Color(0xFFFFFFFF),
    warningTint = Color(0xFFFDEBD3),
    onWarningTint = Color(0xFF854D0E),
    danger = Color(0xFFDC2626),
    onDanger = Color(0xFFFFFFFF),
    dangerTint = Color(0xFFFEE2E2),
    onDangerTint = Color(0xFF991B1B),

    stage = Color(0xFF000000),
    scrim = Color(0x99000000),
    disabled = Color(0xFFB6B6BD),
    focusRing = Color(0xFF4F46E5),
    isDark = false
)

internal val LocalPulseColors = staticCompositionLocalOf { DarkColors }

/**
 * Identity colours for avatars — the one place a contact may be a colour rather than a token.
 * Each entry is dark enough that [PulseAvatarInk] initials clear AA, which is why none of them are
 * the vivid accent hues: a sky-blue circle looks friendly and fails contrast.
 */
val PulseAvatarPalette: List<Color> = listOf(
    Color(0xFF2563EB), // blue
    Color(0xFF7C3AED), // violet
    Color(0xFF0F766E), // teal
    Color(0xFF15803D), // green
    Color(0xFFB45309), // amber
    Color(0xFFBE123C)  // rose
)

val PulseAvatarInk = Color(0xFFFFFFFF)

/**
 * Non-colour tokens. Fixed across themes: a card is the same radius and a tap target is the same
 * size whether the room is bright or dark, so these hang off the object rather than the palette.
 */
object PulseSpacing {
    const val xxs = 2
    const val xs = 4
    const val sm = 8
    const val md = 12
    const val lg = 16
    const val xl = 20
    const val xxl = 24
    const val xxxl = 32
    const val gutter = 16
}

object PulseRadius {
    const val sm = 8
    const val md = 12
    const val lg = 16
    const val xl = 20
    const val pill = 999
}

object PulseSizes {
    /** The accessibility floor for anything a thumb can hit. */
    const val touchTarget = 44
    const val iconSm = 16
    const val icon = 20
    const val iconLg = 24
    const val topBar = 60
    const val bottomNav = 68
    const val sidebar = 240
    const val sidebarCollapsed = 72
    const val avatar = 40
    const val focusRing = 2
    const val hairline = 1
}

/** Durations in ms. Short enough to feel instant, long enough to be followed with the eye. */
object PulseMotion {
    const val instant = 80
    const val fast = 120
    const val base = 180
    const val slow = 260
    const val sheet = 320
    /** Scales a page in on navigation; there is no bounce anywhere in this design. */
    const val enterScale = 0.985f
}

/** One radius scale, so a chip and a dialog corner are never two different numbers by accident. */
val PulseShapes = Shapes(
    extraSmall = RoundedCornerShape(PulseRadius.sm.dp),
    small = RoundedCornerShape(PulseRadius.sm.dp),
    medium = RoundedCornerShape(PulseRadius.md.dp),
    large = RoundedCornerShape(PulseRadius.lg.dp),
    extraLarge = RoundedCornerShape(PulseRadius.xl.dp)
)
