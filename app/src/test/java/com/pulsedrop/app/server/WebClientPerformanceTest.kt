package com.pulsedrop.app.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page rebuilds itself from a timer, and every one of these rebuilds used to happen whether or
 * not anything had changed. A few thousand hosted files turned a routine poll into a task longer
 * than a frame, and a signed-out tab asked the phone for four things it could not read.
 *
 * The measurements live in the browser; these tests pin the shape that made them stop, because
 * one careless `applyFilter()` in a patch would put the cost back with no compiler to catch it.
 */
class WebClientPerformanceTest {

    private val html = WebClientHtml.getHtml("Test Phone", true)

    /** The source of a top-level JS function, indentation and all. */
    private fun js(name: String): String {
        val body = Regex("""function ${Regex.escape(name)}\((?:[^)]*)\) \{[\s\S]*?\n    \}""")
            .find(html)?.value
        assertTrue("$name is gone from the client", body != null)
        return body!!
    }

    @Test
    fun `the search boxes repaint on a timer rather than per keystroke`() {
        val handlers = Regex("""oninput="([^"]*)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue("the page lost its search boxes", handlers.isNotEmpty())
        handlers.forEach {
            assertTrue("a keystroke repaints synchronously: $it", it.startsWith("queue"))
        }
        listOf("queueFileFilter", "queueRailFilter").forEach { name ->
            val body = js(name)
            assertTrue("$name would stack one repaint per key: $body", body.contains("clearTimeout"))
            assertTrue("$name repaints before the typing settles: $body", body.contains("setTimeout"))
            assertFalse("$name fires on a zero delay: $body", Regex(""",\s*0\);""").containsMatchIn(body))
        }
    }

    @Test
    fun `the shelf and the chat rail only rebuild when their data changed`() {
        listOf("renderFiles" to "grid._fp", "renderThreadRail" to "host._fp").forEach { (name, marker) ->
            val body = js(name)
            val cache = body.indexOf(marker)
            val clear = body.indexOf("textContent = ''")
            assertTrue("$name rebuilds unconditionally", cache >= 0 && clear >= 0)
            assertTrue("$name touches the DOM before it compares: $name", cache < clear)
        }
    }

    @Test
    fun `opening a folder does not repaint the whole shelf`() {
        val toggle = Regex("""toggle\.addEventListener\('click'[\s\S]*?\n      \};""")
            .find(html)?.value
        assertTrue("the folder toggle is gone", toggle != null)
        assertTrue(toggle!!.contains("classList.toggle('open'"))
        assertFalse("a folder click throws away every other card: $toggle", toggle.contains("applyFilter()"))
    }

    @Test
    fun `a signed-out tab asks the phone for nothing`() {
        val boot = html.substringAfter("/* ===== Boot ===== */")
        val raw = boot.substringAfter("if (REQUIRES_PIN && !authToken) {").substringBefore("} else {")
        // Comments are not requests, and this one explains why there are none.
        val branch = raw.lines().joinToString("\n") { it.substringBefore("//") }
        listOf("fetch", "connectEvents", "/api/").forEach {
            assertFalse("the signed-out boot path does $it", branch.contains(it))
        }
        // The one interval that survives the gate must respect it too.
        val poller = Regex("""setInterval\(function \(\) \{[\s\S]*?\}, 1000\);""")
            .findAll(html).map { it.value }.first { it.contains("tickCount") }
        assertTrue("the shared poller runs while signed out: $poller",
            poller.contains("if (REQUIRES_PIN && !authToken) return;"))
        assertTrue("the shared poller ignores a buried tab: $poller", poller.contains("if (document.hidden) return;"))
    }

    @Test
    fun `the call poll waits for the tab to be looked at`() {
        // A hidden tab only learns about a call through the event stream or on being reopened, and
        // both paths already exist; the interval was four doomed requests every eight seconds.
        val visible = Regex("""setInterval\(function \(\) \{ if \(!document\.hidden && !call\.cid\) pollCall\(\); \}, 2000\);""")
            .containsMatchIn(html)
        val onReturn = Regex("""visibilitychange[\s\S]{0,120}pollCall\(\)""").containsMatchIn(html)
        assertTrue("a background tab polls for calls", visible)
        assertTrue("coming back does not re-check for a call", onReturn)
    }

    @Test
    fun `a stream this session cannot power is never opened`() {
        val body = js("connectEvents")
        assertTrue("a session without a ticket still opens a doomed stream: $body",
            body.contains("if (!t) { scheduleEventRetry(); return; }"))
        assertFalse("the stream opens before anyone signs in",
            Regex("""\n    connectEvents\(\);\n""").containsMatchIn(html))
        // One timer for the whole page, cancelled by the three things that end a stream.
        val helper = js("scheduleEventRetry")
        assertTrue("a second reconnect can be scheduled beside the first: $helper",
            helper.contains("if (eventRetryTimer || eventsStopped) return;"))
        listOf(js("lockOut"), html.substringAfter("beforeunload")).forEach {
            assertTrue("a reconnect scheduled by a dead session survives it", it.contains("clearTimeout(eventRetryTimer)"))
        }
    }

    @Test
    fun `the file read runs one at a time but never drops a trigger`() {
        val body = js("fetchFiles")
        assertTrue("a second read starts while one is running: $body",
            body.contains("if (filesBusy) { filesRerun = true; return; }"))
        assertTrue("an early return can leave the read stuck busy: $body", body.contains("finally {"))
        assertTrue("a trigger arriving mid-read is lost: $body", Regex("""if \(rerun\) fetchFiles\(\)""").containsMatchIn(body))
    }
}
