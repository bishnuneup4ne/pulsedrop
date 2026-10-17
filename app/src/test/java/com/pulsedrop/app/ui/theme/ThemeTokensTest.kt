package com.pulsedrop.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The parts of a theme that can be proven without looking at a screen.
 *
 * Contrast is not a taste question: text below the WCAG ratio is unreadable to a real share of
 * users, and a palette built from a table can silently contain a pair nobody checked. These tests
 * are that check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeTokensTest {

    /** WCAG relative luminance, on the un-premultiplied sRGB channels Compose stores. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double =
            if (v <= 0.03928f) (v / 12.92).toDouble()
            else ((v + 0.055) / 1.055).pow(2.4).toDouble()
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertPair(label: String, fg: Color, bg: Color, atLeast: Double = 4.5) {
        val got = ratio(fg, bg)
        assertTrue(
            "$label needs $atLeast:1 but measured ${got.roundToInt() * 10.0 / 10}:1 " +
                "(fg=${fg.toHex()} on bg=${bg.toHex()})",
            got >= atLeast
        )
    }

    private fun Color.toHex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt()
    )

    private fun tables() = listOf("dark" to DarkColors, "light" to LightColors)

    @Test
    fun `body text clears AA on every surface it is placed on`() {
        tables().forEach { (name, c) ->
            assertPair("$name primary text on app background", c.textPrimary, c.background)
            assertPair("$name primary text on surface", c.textPrimary, c.surface)
            assertPair("$name primary text on elevated surface", c.textPrimary, c.surfaceElevated)
            assertPair("$name secondary text on app background", c.textSecondary, c.background)
            assertPair("$name secondary text on surface", c.textSecondary, c.surface)
            assertPair("$name secondary text on elevated surface", c.textSecondary, c.surfaceElevated)
        }
    }

    @Test
    fun `accent carries readable text in both themes`() {
        tables().forEach { (name, c) ->
            assertPair("$name text on accent", c.onAccent, c.accent)
            assertPair("$name tinted text on tint", c.onAccentTint, c.accentTint)
            // A link or a selected nav item is not text-sized everywhere; 3:1 is the floor for
            // large text and for non-text UI, and the accent has to clear it against the page.
            assertPair("$name accent as UI against background", c.accent, c.background, atLeast = 3.0)
        }
    }

    @Test
    fun `status colours stay legible on the surfaces they badge`() {
        tables().forEach { (name, c) ->
            assertPair("$name danger text on its tint", c.onDangerTint, c.dangerTint)
            assertPair("$name warning text on its tint", c.onWarningTint, c.warningTint)
            assertPair("$name success text on its tint", c.onSuccessTint, c.successTint)
            // An indicator dot or a bar is non-text UI: 3:1 against the surface it sits on.
            assertPair("$name danger as an indicator", c.danger, c.surface, atLeast = 3.0)
            assertPair("$name warning as an indicator", c.warning, c.surface, atLeast = 3.0)
            assertPair("$name success as an indicator", c.success, c.surface, atLeast = 3.0)
        }
    }

    @Test
    fun `the two tables are genuinely different themes`() {
        // Guards against a copy-paste that leaves the "dark" palette identical to the light one:
        // the background must invert, and a divider must be visible in each direction.
        assertTrue(ratio(DarkColors.textPrimary, DarkColors.background) > 10)
        assertTrue(ratio(LightColors.textPrimary, LightColors.background) > 10)
        assertFalse(DarkColors.isDark == LightColors.isDark)
        assertTrue(luminance(DarkColors.background) < 0.05)
        assertTrue(luminance(LightColors.background) > 0.9)
    }

    @Test
    fun `the window behind the first frame is the palette the app paints`() {
        // One theme means the pre-Compose window background can no longer disagree with Compose,
        // but it can still be stale: this resource is what a user sees for the moment before the
        // first frame, so it has to be the same white the palette calls `background`.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val window = context.getColor(R.color.pulse_window_bg_light)
        assertEquals("window background vs LightColors.background", LightColors.background, Color(window))
    }

    @Test
    fun `the media stage composes against the app's own palette`() {
        // Call sheets and the attachment viewer read the stage table rather than hardcoding black
        // panels and white labels. It is the light table now, and the video keeps its own black
        // letterbox inside it via `stage`, so the two clients (phone and browser) stay one design.
        val stage = PulseStageColors
        assertEquals("the stage is the app table", LightColors.background, stage.background)
        assertEquals("the feed stays letterbox black", Color(0xFF000000), stage.stage)
        assertPair("stage title", stage.textPrimary, stage.background)
        assertPair("stage detail text", stage.textSecondary, stage.background)
        assertPair("stage initials", stage.onAccentTint, stage.surfaceElevated)
        assertPair("stage icon on a filled control", stage.onAccent, stage.accent)
        assertPair("stage icon on the end-call control", stage.onDanger, stage.danger)
        assertPair("stage ringing label", stage.onWarningTint, stage.warningTint)
    }

    @Test
    fun `avatar identity colours carry readable initials`() {
        // The one place a colour is chosen per contact rather than per role. Each entry has to
        // hold the ink it is paired with, or an initial becomes a smudge on a small circle.
        assertEquals(6, PulseAvatarPalette.size)
        PulseAvatarPalette.forEachIndexed { i, container ->
            assertPair("avatar $i initials", PulseAvatarInk, container)
        }
    }

    @Test
    fun `every material role is derived from the same table the components read`() {
        tables().forEach { (name, c) ->
            val scheme = schemeOf(c)
            assertEquals("$name primary comes from the accent token", c.accent, scheme.primary)
            assertEquals("$name background comes from the app background token", c.background, scheme.background)
            assertEquals("$name surface comes from the surface token", c.surface, scheme.surface)
            assertEquals("$name error comes from the danger token", c.danger, scheme.error)
            assertEquals("$name outline comes from the divider token", c.borderSubtle, scheme.outlineVariant)
        }
    }
}
