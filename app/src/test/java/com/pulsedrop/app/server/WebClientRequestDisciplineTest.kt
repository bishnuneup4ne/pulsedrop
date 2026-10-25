package com.pulsedrop.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request discipline in the browser client — the parts that were fixed against a measured cost.
 *
 * `WebClientSafetyTest` asks the page what it renders; this asks what it *sends*. The distinction
 * matters because the client is JavaScript inside a Kotlin string: nothing compiles it, no
 * extractor parses it, and no test here can run it. So these tests pin the shapes that produce the
 * saving, and the saving itself is measured server-side, where a number is actually obtainable.
 *
 * What is NOT proved by any of this: that a browser executes it the way it is written. A main-thread
 * parse of a large response, a layout cost, or a dropped frame is invisible from here — those need a
 * real tab on a real device, and this file does not pretend to substitute for one.
 */
class WebClientRequestDisciplineTest {

    private val html = WebClientHtml.getHtml("Test Phone", true)

    /** The text of a function, from its `name(` to the brace that closes it, counted not guessed. */
    private fun slice(from: String): String {
        val at = html.indexOf(from).takeIf { it >= 0 } ?: error("`$from` is not in the served page")
        val open = html.indexOf('{', at)
        var depth = 0
        for (i in open until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return html.substring(at, i + 1) }
            }
        }
        error("unbalanced braces reading `$from`")
    }

    private fun bodyOf(name: String): String = slice("function $name(")

    @Test
    fun `the whole-history read is serialised rather than stacked`() {
        val body = bodyOf("fetchMessages")
        assertTrue("a poll can start while one is in flight: $body", body.contains("if (messagesInFlight)"))
        assertTrue("the guard is never released", body.contains("messagesInFlight = false"))
        // Without the trailing re-run the guard would silently drop whatever arrived mid-read, which
        // is a worse bug than the duplicate requests it removes.
        assertTrue("nothing re-runs after a coalesced read", body.contains("messagesWanted = false"))
        assertTrue("the trailing re-run does not call back into the read", body.contains("fetchMessages();"))
    }

    @Test
    fun `a failed read cannot wedge the history forever`() {
        // The flag is cleared in `finally`: one thrown fetch with the flag left true stops every later
        // message update for the life of the tab, and nothing on screen would say so.
        val body = bodyOf("fetchMessages")
        val finallyAt = body.indexOf("finally {")
        assertTrue("the guard is released outside the success path", finallyAt > 0)
        assertTrue("the release is not inside finally", body.indexOf("messagesInFlight = false") > finallyAt)
    }

    @Test
    fun `every doorbell that touches chat goes through the serialised read`() {
        listOf("on('message'", "on('deleted'").forEach { doorbell ->
            val handler = slice(doorbell)
            assertTrue("$handler re-reads without going through the guard: $handler", handler.contains("fetchMessages()"))
            assertFalse("$handler issues its own history request", handler.contains("fetch('/api/messages'"))
        }
        // The route is written to (POST) from one place and read from exactly one place; a second
        // reader is how the stacked requests came back. The read's URL is a variable now, because a
        // room cursor has to ride on it as a query — so the check is one call site *and* that the
        // room path is the literal it builds that URL from.
        assertEquals(
            "/api/messages must be read by fetchMessages alone",
            1,
            Regex("fetch\\(url,\\s*\\{\\s*headers").findAll(html).count()
        )
        assertEquals(
            "the read must not have gained a second room URL",
            1,
            Regex("var url = roomCursor \\? '/api/messages\\?since='").findAll(html).count()
        )
    }

    @Test
    fun `a reconnect backs off and does not keep a fixed beat`() {
        val handler = slice("es.onerror = function")
        assertTrue("reconnect delay never grows", handler.contains("eventBackoffMs * 2"))
        assertTrue("reconnect delay is unbounded", handler.contains("Math.min(eventBackoffMs * 2, 20000)"))
        assertTrue("every tab reconnects on the same beat", handler.contains("Math.random()"))
        assertFalse("the old fixed eight-second retry is still reachable", Regex("setTimeout\\([^)]*, 8000\\)").containsMatchIn(html))
    }

    @Test
    fun `an immediately cut stream does not reset the backoff to one second`() {
        // The phone holds a limited number of streams, so a ninth tab can open one and see it closed
        // at once. A reset on `onopen` alone would turn that into a one-second thrash between them.
        val handler = slice("es.onerror = function")
        assertTrue("backoff resets without the connection having lasted", handler.contains("Date.now() - openedAt"))
        val open = slice("es.onopen = function")
        assertFalse("onopen resets the backoff directly", open.contains("eventBackoffMs = 1000"))
    }

    @Test
    fun `the event stream reuses the ticket the page already caches`() {
        val ticket = bodyOf("eventTicket")
        assertTrue("the stream mints its own ticket again: $ticket", ticket.contains("ticketFor('events')"))
        assertEquals(
            "only one place in the page may POST /api/ticket, or the cache is decorative",
            1,
            Regex("fetch\\('/api/ticket'").findAll(html).count()
        )
    }

    @Test
    fun `a page going away cancels its pending reconnect`() {
        val unload = slice("addEventListener('beforeunload'")
        assertTrue("a scheduled reconnect outlives the page", unload.contains("clearTimeout(eventRetryTimer)"))
        assertTrue("the stream outlives the page", unload.contains("eventsStream.close()"))
    }
}
