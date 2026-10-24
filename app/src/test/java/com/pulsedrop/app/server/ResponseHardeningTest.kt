package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two headers every reply gets, and the one every reply stopped sending.
 *
 * `Access-Control-Allow-Origin: *` was on roughly every response, including the 401 that says a PIN
 * is required. Nothing in this repo is a cross-origin client — the page is served from the same
 * origin and every URL in it is relative — so the wildcard granted a capability no shipped client
 * used, to any web page the user happened to have open. It is gone rather than narrowed.
 *
 * The pair that replaced it is deliberately unconditional. Attaching `nosniff` only where the MIME
 * type is remembered to be dangerous would leave the next route to remember, so it is applied once
 * at the `serve()` boundary, after the route has built its answer and before NanoHTTPD writes it.
 * These tests therefore assert on the wire, not on a helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResponseHardeningTest {

    private val pin = "424242"
    private lateinit var server: PulseDropServer
    private lateinit var http: RoomHttp
    private lateinit var token: String

    @Before
    fun startServer() {
        val port = RoomHttp.freePort()
        server = PulseDropServer(
            port = port,
            bindAddress = "127.0.0.1",
            context = ApplicationProvider.getApplicationContext<Context>(),
            initialDeviceName = "Test Phone",
            initialPinCode = pin,
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        server.start()
        RoomHttp.awaitListening(port)
        http = RoomHttp(port)
        token = http.requireLogin(pin)
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun `the page is typed as bytes, not as whatever the browser guesses`() {
        val answer = http.call("GET", "/")
        assertEquals(200, answer.status)
        assertTrue("a page from this origin may not be sniffed into another type", answer.body.isNotEmpty())
        assertEquals(listOf("nosniff"), valuesOf(answer, "X-Content-Type-Options"))
        assertEquals(listOf("no-referrer"), valuesOf(answer, "Referrer-Policy"))
        assertNoCrossOriginGrant(answer)
    }

    @Test
    fun `the 401 that asks for a PIN makes no cross-origin exception`() {
        val answer = http.call("GET", "/api/files")
        assertEquals(401, answer.status)
        assertEquals(listOf("nosniff"), valuesOf(answer, "X-Content-Type-Options"))
        assertEquals(listOf("no-referrer"), valuesOf(answer, "Referrer-Policy"))
        assertNoCrossOriginGrant(answer)
    }

    @Test
    fun `a route that does not exist is answered the same way`() {
        val answer = http.call("GET", "/nothing-here", mapOf("X-Pulse-Auth" to token))
        assertEquals(404, answer.status)
        assertEquals(listOf("nosniff"), valuesOf(answer, "X-Content-Type-Options"))
        assertEquals(listOf("no-referrer"), valuesOf(answer, "Referrer-Policy"))
        assertNoCrossOriginGrant(answer)
    }

    @Test
    fun `a font keeps its cache but gains the same pair`() {
        val answer = http.call("GET", "/fonts/google-sans-regular.ttf")
        assertEquals(200, answer.status)
        assertEquals(listOf("public, max-age=604800"), valuesOf(answer, "Cache-Control"))
        assertEquals(listOf("nosniff"), valuesOf(answer, "X-Content-Type-Options"))
        assertEquals(listOf("no-referrer"), valuesOf(answer, "Referrer-Policy"))
        assertNoCrossOriginGrant(answer)
    }

    @Test
    fun `a preflight is answered without promising another origin access`() {
        // The handler is kept because non-browser clients have always been able to ask; what it may
        // not do is keep the old answer, which told every page on the web it could read this room.
        val answer = http.call("OPTIONS", "/api/files")
        assertEquals(200, answer.status)
        assertEquals(listOf("nosniff"), valuesOf(answer, "X-Content-Type-Options"))
        assertNoCrossOriginGrant(answer)
    }

    @Test
    fun `a successful mutation carries the pair too`() {
        val answer = http.call(
            "POST", "/api/auth/logout", mapOf("X-Pulse-Auth" to token)
        )
        assertEquals(200, answer.status)
        assertEquals(listOf("nosniff"), valuesOf(answer, "X-Content-Type-Options"))
        assertEquals(listOf("no-referrer"), valuesOf(answer, "Referrer-Policy"))
        assertNoCrossOriginGrant(answer)
    }

    /** Header values in wire order; empty when the reply never declared the header at all. */
    private fun valuesOf(answer: RoomHttp.Answer, name: String): List<String> =
        answer.headers.lines()
            .filter { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            .map { it.substringAfter(':').trim() }

    private fun assertNoCrossOriginGrant(answer: RoomHttp.Answer) {
        for (header in listOf(
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Methods",
            "Access-Control-Allow-Headers"
        )) {
            assertFalse(
                "$header survived on a ${answer.status} response: ${answer.headers}",
                answer.headers.contains(header, ignoreCase = true)
            )
        }
    }
}
