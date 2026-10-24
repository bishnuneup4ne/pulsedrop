package com.pulsedrop.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The browser client is the one part of the app that turns server-side strings into a document,
 * and it is a JavaScript string inside Kotlin, where no compiler and no extractor looks at it.
 *
 * These tests read the page exactly as the phone serves it. They pin two properties that are easy
 * to lose in a patch: caller-controlled text only ever reaches a text node, and the only markup
 * written with `innerHTML` is SVG this file owns.
 */
class WebClientSafetyTest {

    private val html = WebClientHtml.getHtml("Test Phone", true)

    @Test
    fun `the device name is escaped everywhere the page prints it`() {
        val hostile = "<img src=x onerror=alert(1)>"
        val page = WebClientHtml.getHtml(hostile, true)
        assertFalse("the raw name reached the document", page.contains(hostile))
        assertTrue("the name was not escaped into text", page.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }

    @Test
    fun `a quote in the device name cannot leave its text node`() {
        val hostile = "\" onmouseover=\"alert(1)"
        val page = WebClientHtml.getHtml(hostile, true)
        assertFalse("the raw name reached the document", page.contains(hostile))
        assertTrue("the quote was not escaped", page.contains("&quot; onmouseover=&quot;alert(1)"))
    }

    @Test
    fun `innerHTML only ever receives markup this file owns`() {
        // Captured to `;` rather than to the end of the line, because one of the static fragments
        // is written across two lines.
        val assignments = Regex("""\.innerHTML\s*=\s*([^;]*);""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("the page no longer injects markup at all; did the audit lose its target?", assignments.size >= 5)

        val literal = Regex("""'(?:[^'\\]|\\.)*'""")
        val constant = Regex("""\b(?:ICON\.[A-Za-z]+|meta\.svg|iconMeta\([^;]*?\)\.svg|svg)\b""")
        assignments.forEach { rhs ->
            val leftover = rhs.replace(literal, "").replace(constant, "").replace(Regex("""[\s+]"""), "")
            assertEquals("innerHTML receives something other than constant markup: $rhs", "", leftover)
        }
    }

    @Test
    fun `the one innerHTML helper is only ever handed an icon constant`() {
        // `feBtn(…, svg, …)` takes its markup as an argument, so the allow-list above cannot see
        // the value; this is where that argument is pinned.
        // Call sites pass a literal class first; the function definition starts with `cls`.
        val calls = Regex("""feBtn\(['"][^'"]*['"],\s*([A-Za-z_][\w.]*),""")
            .findAll(html).map { it.groupValues[1] }.toList()
        assertTrue("feBtn is no longer called", calls.isNotEmpty())
        calls.forEach { assertEquals("feBtn was handed $it, not an icon constant", true, it.startsWith("FE_")) }
    }

    @Test
    fun `the page has no script-writing sink`() {
        listOf(
            "document.write(", "insertAdjacentHTML", "outerHTML", ".srcdoc",
            "eval(", "new Function(", "javascript:"
        ).forEach { sink ->
            assertFalse("the client uses `$sink`", html.contains(sink))
        }
    }

    @Test
    fun `a secure-port failure shown to a browser never names a private path`() {
        val e = IOException("failed to open /data/user/0/com.pulsedrop.app/files/pulse.p12")
        val shown = describeTlsFailure(e)
        assertTrue("the class name is the diagnosis", shown.startsWith("IOException"))
        assertFalse("a private path reached the browser: $shown", shown.contains("/data/user"))
        assertTrue("the path was not redacted: $shown", shown.contains("<path>"))
    }

    @Test
    fun `a secure-port failure is bounded and survives an empty message`() {
        assertEquals("RuntimeException", describeTlsFailure(RuntimeException()))
        val huge = describeTlsFailure(IllegalStateException("x".repeat(10_000)))
        assertTrue("the toast would carry a novel: ${huge.length} chars", huge.length <= 240)
        assertTrue(huge.startsWith("IllegalStateException: "))
    }
}
