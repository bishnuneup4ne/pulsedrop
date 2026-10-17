package com.pulsedrop.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Motion scale, driven by the platform's "remove animations" switch (animation duration scale 0).
 * Every transition in the app asks for its duration through [pulseTween] so one flip here is
 * enough to honour it app-wide.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * The duration every animation in PulseDrop goes through. Under a reduced-motion preference these
 * collapse to zero rather than being shortened: the point of the setting is to stop things moving,
 * not to slow them down.
 */
@Composable
fun pulseTween(
    durationMs: Int,
    easing: Easing = LinearEasing
): FiniteAnimationSpec<Float> = pulseSpec(durationMs, easing)

/** The generic form, for colour, size or anything else that animates. */
@Composable
fun <T> pulseSpec(
    durationMs: Int,
    easing: Easing = LinearEasing
): FiniteAnimationSpec<T> = tween(
    durationMillis = if (LocalReducedMotion.current) 0 else durationMs,
    easing = easing
)

/**
 * A Material 3 scheme derived from one [PulseColors] table.
 *
 * Material's own roles are generated rather than hand-set, so anything using a stock M3 component
 * (dialogs, menus, text fields) inherits the same palette as the custom ones.
 */
internal fun schemeOf(c: PulseColors): ColorScheme = if (c.isDark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentTint,
        onPrimaryContainer = c.onAccentTint,
        inversePrimary = LightColors.accent,

        secondary = c.success,
        onSecondary = c.onSuccess,
        secondaryContainer = c.successTint,
        onSecondaryContainer = c.textPrimary,

        tertiary = c.warning,
        onTertiary = c.onWarning,
        tertiaryContainer = c.warningTint,
        onTertiaryContainer = c.textPrimary,

        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceElevated,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.accent,
        inverseSurface = c.surfaceElevated,
        inverseOnSurface = c.textPrimary,

        outline = c.borderStrong,
        outlineVariant = c.borderSubtle,

        error = c.danger,
        onError = c.onDanger,
        errorContainer = c.dangerTint,
        onErrorContainer = c.onDangerTint,

        surfaceContainerLowest = c.surfaceSunken,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surfaceElevated,
        surfaceContainerHigh = c.surfaceOverlay,
        surfaceContainerHighest = c.surfaceOverlay,

        scrim = Color(0xB3000000),
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentTint,
        onPrimaryContainer = c.onAccentTint,
        inversePrimary = DarkColors.accent,

        secondary = c.success,
        onSecondary = c.onSuccess,
        secondaryContainer = c.successTint,
        onSecondaryContainer = c.textInverse,

        tertiary = c.warning,
        onTertiary = c.onWarning,
        tertiaryContainer = c.warningTint,
        onTertiaryContainer = c.textInverse,

        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceSunken,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.accent,
        inverseSurface = c.surfaceElevated,
        inverseOnSurface = c.textPrimary,

        outline = c.borderStrong,
        outlineVariant = c.borderSubtle,

        error = c.danger,
        onError = c.onDanger,
        errorContainer = c.dangerTint,
        onErrorContainer = c.onDangerTint,

        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.surfaceSunken,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceElevated,
        surfaceContainerHighest = c.surfaceOverlay,

        scrim = Color(0xB3000000),
    )
}

/**
 * The single palette the app composes against.
 *
 * Kept a function so screens ask for the theme rather than naming a table: the day a second theme
 * comes back, it comes back here and in [schemeOf], and no screen has to be hunted down.
 */
@Composable
fun rememberPulsePalette(): PulseColors = LightColors

/**
 * The single theme entry point. Screens get colour, typography, the semantic palette and the
 * motion preference from here, and from nowhere else.
 */
@Composable
fun PulseDropTheme(content: @Composable () -> Unit) {
    val colors = rememberPulsePalette()
    val context = LocalContext.current
    // Read live rather than at start-up: the developer option flips without a restart, and motion
    // that only stops after a reboot is motion people think is broken.
    val reduced = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) <= 0.0001f

    CompositionLocalProvider(
        LocalPulseColors provides colors,
        LocalReducedMotion provides reduced
    ) {
        MaterialTheme(
            colorScheme = schemeOf(colors),
            typography = PulseTypography,
            shapes = PulseShapes,
            content = {
                // A bare Text() with no style reads LocalTextStyle, which defaults to the
                // platform font; swapping only the family puts Google Sans on every last label.
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = PulseFontFamily),
                    content = content
                )
            }
        )
    }
}

/**
 * The palette media surfaces read: the call stage and the full-screen attachment viewer.
 *
 * One table on purpose, so the stage composes against named roles like everything else and the
 * browser client can be held to the same numbers (WebThemeParityTest). The video itself is still
 * black inside it — [com.pulsedrop.app.ui.theme.PulseColors.stage] is a deliberate black in both
 * tables, because a frame must not pick up the theme's grey; what went light around it is the
 * chrome, the chips and the controls.
 */
val PulseStageColors: PulseColors = LightColors

/** The point where text stops being legible on a tinted background in either theme. */
internal val disabledContentAlpha = 0.42f
