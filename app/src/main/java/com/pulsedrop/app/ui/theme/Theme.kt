package com.pulsedrop.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** What the user picked in Appearance. SYSTEM follows the platform and still offers both tables. */
enum class PulseTheme(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * The theme preference is process-wide state, not ViewModel state.
 *
 * A ViewModel is created and destroyed with the host; a theme outlives that and has to be readable
 * before the first frame or the app paints light for a beat and then flips. Keeping a second copy
 * in a UI model would be a synchronisation problem, and a theme is the one setting where being a
 * frame late is visible.
 */
object ThemePreference {
    private const val PREFS = "localdrop_settings"
    private const val KEY = "themeMode"

    private val _mode = mutableStateOf(PulseTheme.LIGHT)
    val mode: State<PulseTheme> = _mode

    /** Called once from Application creation, before any Activity composes. */
    fun load(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        _mode.value = stored?.let { name -> PulseTheme.entries.firstOrNull { it.name == name } }
            ?: PulseTheme.LIGHT
    }

    fun save(context: Context, theme: PulseTheme) {
        _mode.value = theme
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, theme.name).apply()
    }
}

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

        scrim = androidx.compose.ui.graphics.Color(0xB3000000),
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

        scrim = androidx.compose.ui.graphics.Color(0xB3000000),
    )
}

/**
 * The palette the app actually composes against.
 *
 * [isDark] is the resolved answer, so the status-bar styling in the Activity can be driven from
 * the same decision the colours came from — two places reading the theme differently is how an app
 * ends up with dark text on a dark bar.
 */
@Composable
fun rememberPulsePalette(): PulseColors =
    resolvePulsePalette(ThemePreference.mode.value, isSystemInDarkTheme())

/**
 * Which table to paint, given what the user picked and what the platform is doing.
 *
 * This is the theme's only real decision, so the pre-Compose window background, the Compose
 * palette and the tests all read it. Two places resolving darkness their own way is how an app
 * ends up with dark icons on a dark bar.
 */
fun resolvePulsePalette(mode: PulseTheme, systemIsDark: Boolean): PulseColors = when (mode) {
    PulseTheme.SYSTEM -> if (systemIsDark) DarkColors else LightColors
    PulseTheme.LIGHT -> LightColors
    PulseTheme.DARK -> DarkColors
}

/** The platform answer without a composable in sight — read from resources, as the Activity must. */
fun systemUiIsDark(context: Context): Boolean =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

/**
 * The single theme entry point. Screens get colour, typography, the semantic palette and the
 * motion preference from here, and from nowhere else.
 */
@Composable
fun PulseDropTheme(content: @Composable () -> Unit) {
    val colors = rememberPulsePalette()
    val context = LocalContext.current
    // Read live: the developer option and the system dark-mode toggle both change without a
    // restart, and a theme that only applies after a reboot is a theme people think is broken.
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

/** The palette outside the composable call stack — status-bar styling reads the same answer. */
@Composable
fun isPulseDark(): Boolean = LocalPulseColors.current.isDark

/**
 * The palette media surfaces read: the call stage and the full-screen attachment viewer.
 *
 * Those two stay on the dark table in both themes. A video feed is dark content by nature, and a
 * light theme would otherwise put a bright page beside it — every white label in the call sheet
 * would need inverting by hand. Keeping one table means the stage reads real tokens instead of
 * hex literals, and a contact's initials are legible in the stage's colours whatever the app theme
 * is set to.
 */
val PulseStageColors: PulseColors get() = DarkColors

/** The point where text stops being legible on a tinted background in either theme. */
internal val disabledContentAlpha = 0.42f
