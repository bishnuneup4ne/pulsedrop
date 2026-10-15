package com.pulsedrop.app.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public stats page is a third implementation of the same design system, and it is the one
 * nobody runs beside the app. These tests read the stylesheet that ships in `site/public/` and hold
 * it to the two rules the app already lives by: every colour arrives through a named role, and the
 * role carries the same value on the page that it carries on the phone.
 */
class SiteThemeParityTest {

    private val css: String = run {
        val candidates = listOf("site/public/styles.css", "../site/public/styles.css")
        val file = candidates.map(::File).firstOrNull { it.isFile }
            ?: error("site/public/styles.css is not reachable from " + File(".").absolutePath)
        file.readText()
    }

    /** Split the stylesheet into its two tables, light first (it is also `:root`), then dark. */
    private fun table(selector: String): Map<String, String> {
        val start = css.indexOf(selector)
        assertTrue("`$selector` block missing", start >= 0)
        val open = css.indexOf('{', start)
        val body = css.substring(open + 1, css.indexOf('}', open))
        return Regex("--([a-z0-9-]+):\\s*([^;]+);").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private val light = table("[data-theme=\"light\"]")
    private val dark = table("[data-theme=\"dark\"]")

    private fun Color.toHex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt()
    )

    /** The page's role names, mapped onto the fields they stand for in [PulseColors]. */
    private fun pairs(colors: PulseColors): List<Pair<String, Color>> = listOf(
        "bg" to colors.background,
        "surface" to colors.surface,
        "surface-elevated" to colors.surfaceElevated,
        "highlight" to colors.highlight,
        "text" to colors.textPrimary,
        "text-secondary" to colors.textSecondary,
        "text-tertiary" to colors.textTertiary,
        "border" to colors.borderSubtle,
        "border-strong" to colors.borderStrong,
        "accent" to colors.accent,
        "accent-hover" to colors.accentHover,
        "on-accent" to colors.onAccent,
        "accent-tint" to colors.accentTint,
        "on-accent-tint" to colors.onAccentTint,
        "danger-tint" to colors.dangerTint,
        "on-danger-tint" to colors.onDangerTint,
        "focus-ring" to colors.focusRing
    )

    @Test
    fun `the page's dark table is the app's dark table`() {
        pairs(DarkColors).forEach { (name, color) ->
            assertEquals("--$name", color.toHex(), dark[name])
        }
    }

    @Test
    fun `the page's light table is the app's light table`() {
        pairs(LightColors).forEach { (name, color) ->
            assertEquals("--$name", color.toHex(), light[name])
        }
    }

    /** Shape is not a colour: one radius serves both themes, so it is declared once, in the root table. */
    private val shapeRoles = setOf("radius")

    @Test
    fun `both tables paint the same roles`() {
        assertEquals(
            "a role in one table and not the other is a theme that paints it by accident",
            light.keys - shapeRoles, dark.keys
        )
        assertTrue("every role the app has a colour for must be readable on the page too",
            pairs(DarkColors).all { (name, _) -> name in dark && name in light })
    }

    @Test
    fun `the page paints with roles, not with colours`() {
        val declared = light.keys.toSet()
        val used = Regex("var\\(--([a-z0-9-]+)\\)").findAll(css).map { it.groupValues[1] }.toSet()
        assertTrue("roles read but never declared: " + (used - declared), (used - declared).isEmpty())
        assertTrue("roles declared but never read: " + (declared - used), (declared - used).isEmpty())

        // Any hex outside the two tables and the head's theme-color handshake is a hardcoded colour.
        val offenders = css.lines().filter { line ->
            Regex("#[0-9A-Fa-f]{3,8}").containsMatchIn(line) &&
                !line.trimStart().startsWith("--") &&
                !line.trimStart().startsWith("/*") &&
                !line.contains("stop-color")
        }
        assertEquals("styles.css paints outside its tokens: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `the browser chrome and the page background are the same colour`() {
        // The page sets this pair twice, and both times from the same table it paints with: once in
        // the pre-paint script in index.html, once in the toggle in app.js. A theme whose chrome
        // disagrees with its body shows as a strip of the wrong colour under the status bar.
        val html = sibling("index.html")
        val js = sibling("app.js")
        val hexes = Regex("\"#([0-9A-Fa-f]{6})\"").findAll(html + js).map { it.value }.toSet()
        assertEquals(
            "the only colours the page names outside its tokens are the two backgrounds",
            setOf("\"${dark.getValue("bg")}\"", "\"${light.getValue("bg")}\""),
            hexes
        )
        assertTrue("the handshake must set data-theme before paint", html.contains("setAttribute(\"data-theme\""))
    }

    private fun sibling(name: String): String {
        val candidates = listOf("site/public/$name", "../site/public/$name")
        val file = candidates.map(::File).firstOrNull { it.isFile }
            ?: error("site/public/$name is not reachable from " + File(".").absolutePath)
        return file.readText()
    }
}
