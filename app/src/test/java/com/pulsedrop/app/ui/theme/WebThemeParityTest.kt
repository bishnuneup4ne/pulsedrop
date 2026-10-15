package com.pulsedrop.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.pulsedrop.app.server.WebClientHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The browser client is a second, independent implementation of the same design system, and a
 * second implementation drifts the moment nobody checks it. These tests are that check: they read
 * the HTML the phone actually serves and hold its numbers against the table the components paint
 * with, so `--stage-bg` on the page and `PulseStageColors.background` on the phone cannot quietly
 * become two different colours.
 */
class WebThemeParityTest {

    private val html = WebClientHtml.getHtml("Parity Device", false)

    private fun Color.toHex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt()
    )

    /** Every declaration of `--name`, in source order: the light table comes first, the dark one second. */
    private fun declarations(name: String): List<String> =
        Regex("--" + Regex.escape(name) + ":\\s*([^;]+);")
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .toList()

    /** The stage vocabulary is declared once, in the light/`:root` block, and is inherited by dark. */
    private fun stage(name: String): String {
        val all = declarations(name)
        assertEquals("--$name must be declared exactly once, got ${all.size}", 1, all.size)
        return all.single()
    }

    private val HEX = Regex("#[0-9A-Fa-f]{3,8}")
    private val RGBA = Regex("rgba\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")

    @Test
    fun `the page stage palette is the phone stage palette`() {
        val stage = PulseStageColors
        val pairs = listOf(
            "stage-bg" to stage.background,
            "stage-surface" to stage.surface,
            "stage-text" to stage.textPrimary,
            "stage-muted" to stage.textSecondary,
            "stage-accent" to stage.onAccentTint,
            "stage-good" to stage.success,
            "stage-on-good" to stage.onSuccess,
            "stage-good-tint" to stage.successTint,
            "stage-on-good-tint" to stage.onSuccessTint,
            "stage-bad" to stage.danger,
            "stage-on-bad" to stage.onDanger,
            "stage-black" to stage.stage
        )
        pairs.forEach { (name, expected) ->
            assertEquals("--$name drifts from the phone table", expected.toHex(), stage(name).uppercase())
        }
        assertEquals("--avatar-ink drifts", PulseAvatarInk.toHex(), stage("avatar-ink").uppercase())
    }

    @Test
    fun `the alpha variants of the stage dim a token rather than inventing a colour`() {
        val stage = PulseStageColors
        fun channels(value: String): Triple<Int, Int, Int> {
            val m = RGBA.find(value) ?: throw AssertionError("not an rgba() value: $value")
            return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        fun of(c: Color) = Triple(
            (c.red * 255).roundToInt(),
            (c.green * 255).roundToInt(),
            (c.blue * 255).roundToInt()
        )
        // Scrims are the stage background with alpha; chips and lines are its secondary text. If
        // either ever uses a third colour, the video feed and its overlays stop being one surface.
        listOf("stage-scrim", "stage-scrim-soft", "stage-scrim-strong", "stage-scrim-mid", "stage-clear")
            .forEach { assertEquals("$it is not painted from the stage background", of(stage.background), channels(stage(it))) }
        listOf("stage-line", "stage-line-soft", "stage-line-faint", "stage-wash", "stage-wash-hover", "stage-chip")
            .forEach { assertEquals("$it is not painted from the stage's secondary text", of(stage.textSecondary), channels(stage(it))) }
        assertEquals("a panel is the elevated surface, dimmed", of(stage.surfaceElevated), channels(stage("stage-panel")))
    }

    @Test
    fun `avatar colours come from the same identity palette on both clients`() {
        val declared = Regex("var AV_COLORS = \\[([^\\]]*)\\];").find(html)
            ?: throw AssertionError("the page has no AV_COLORS table")
        val got = declared.groupValues[1].split(",").map { it.trim().trim('\'', '"').uppercase() }
        assertEquals(
            "the browser must paint the same six identities the phone does",
            PulseAvatarPalette.map { it.toHex() },
            got
        )
        assertTrue("--avatar-ink is what initials use", PulseAvatarInk.toHex() == stage("avatar-ink").uppercase())
    }

    @Test
    fun `every custom property the page reads is declared`() {
        val used = Regex("var\\(--([a-z0-9-]+)").findAll(html).map { it.groupValues[1] }.toSet()
        val declared = Regex("--([a-z0-9-]+)\\s*:").findAll(html).map { it.groupValues[1] }.toSet()
        val missing = used - declared
        assertTrue("the page reads undeclared tokens, so they paint nothing: $missing", missing.isEmpty())
        // The same check the other way, limited to the stage family: a token nobody uses is a claim
        // about the design system that nothing in the design upholds.
        val dead = declared.filter { it.startsWith("stage-") || it == "avatar-ink" }
            .filter { "var(--$it)" !in html }
        assertTrue("unused stage tokens: $dead", dead.isEmpty())
    }

    @Test
    fun `no rule paints with a colour that is not a token`() {
        // A declaration in one of the two tables is where a colour is allowed to be written down.
        // Everything else has to read a token. Four things are exempt, and each is the colour of
        // something that is not the interface, or a place CSS cannot reach: the browser-chrome hint
        // (an attribute the browser reads before it reads a stylesheet, so var() is meaningless
        // there, and the test below pins it to --bg), the paper of a PDF, and the per-contact
        // identity palette, which is a token list in JavaScript rather than a rule.
        val offenders = html.lines().filter { HEX.containsMatchIn(it) }.filterNot { line ->
            val t = line.trim()
            t.startsWith("--") ||
                t.startsWith("/*") ||
                "theme-color" in t ||
                "tc.setAttribute" in t ||
                "AV_COLORS" in t ||
                ".lb-pdf" in t
        }
        assertTrue(
            "hard-coded colours outside the token tables:\n" + offenders.joinToString("\n") { it.trim() },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the browser chrome and the page background are the same colour`() {
        val lightBg = declarations("bg")[0].uppercase()
        val darkBg = declarations("bg")[1].uppercase()
        assertEquals("light --bg", LightColors.background.toHex(), lightBg)
        assertEquals("dark --bg", DarkColors.background.toHex(), darkBg)
        // The meta tag is what paints the URL bar, so it has to carry those two values and no others.
        val meta = Regex("<meta name=\"theme-color\"[^>]*content=\"(#[0-9A-Fa-f]+)\"").find(html)
            ?: throw AssertionError("the page never declares a theme-color")
        assertEquals(darkBg, meta.groupValues[1].uppercase())
        val js = Regex("tc\\.setAttribute\\('content', dark \\? '(#[0-9A-Fa-f]+)' : '(#[0-9A-Fa-f]+)'\\)")
            .find(html) ?: throw AssertionError("the theme switch does not move the browser chrome")
        assertEquals(darkBg, js.groupValues[1].uppercase())
        assertEquals(lightBg, js.groupValues[2].uppercase())
    }
}
