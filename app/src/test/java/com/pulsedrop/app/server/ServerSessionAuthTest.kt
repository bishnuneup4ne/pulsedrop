package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a browser is allowed to carry, proved over a real socket instead of by reading the chain.
 *
 * The server used to answer `/api/auth` with the PIN itself and then accept that value as the
 * credential, in a header or in a query string. These tests are the difference between "I changed
 * the code" and "a stale tab, a browser history entry and a referrer header no longer hold the room
 * secret": every protected route is asked for a credential in every state a credential can be in.
 *
 * Raw sockets rather than `HttpURLConnection` because Robolectric shadows the URL handlers, and
 * because the header a browser actually sends is the thing under test.
 *
 * Bound to loopback on a port nobody else is using: this box has a public address and no verifiable
 * inbound firewall, and a test listener that answered with a LAN room's file list is not something I
 * am willing to leave exposed for the length of a test run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerSessionAuthTest {

    private val pin = "424242"
    private lateinit var server: PulseDropServer
    private var port = 0

    private object SilentListener : ServerTransferListener {
        override fun onTransferStarted(
            transferId: String, fileName: String, fileSize: Long,
            direction: com.pulsedrop.app.model.TransferDirection, remoteDevice: String, clientIp: String
        ) {}

        override fun onTransferProgress(transferId: String, bytes: Long, total: Long, speed: Long) {}

        override fun onTransferCompleted(
            transferId: String, fileName: String, fileSize: Long, localFilePath: String?,
            direction: com.pulsedrop.app.model.TransferDirection, remoteDevice: String, clientIp: String
        ) {}

        override fun onTransferFailed(
            transferId: String, fileName: String, error: String,
            direction: com.pulsedrop.app.model.TransferDirection
        ) {}

        override fun onNewMessage(message: com.pulsedrop.app.model.ClipMessage) {}
        override fun onWebCall(call: WebCall) {}
    }

    @Before
    fun startServerOnLoopback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        port = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        server = PulseDropServer(
            port = port,
            bindAddress = "127.0.0.1",
            context = context,
            initialDeviceName = "Test Phone",
            initialPinCode = pin,
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        server.start()
        awaitListening()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    // -- the transport --------------------------------------------------------

    private class Answer(val status: Int, val body: String, val headers: String)

    private fun call(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): Answer {
        val payload = body?.toByteArray() ?: ByteArray(0)
        val request = StringBuilder()
            .append("$method $path HTTP/1.1\r\n")
            .append("Host: 127.0.0.1:$port\r\n")
        headers.forEach { (k, v) -> request.append("$k: $v\r\n") }
        if (body != null) request.append("Content-Length: ${payload.size}\r\n")
        request.append("Connection: close\r\n\r\n")
        val bytes = request.toString().toByteArray() + payload
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 8000
            socket.getOutputStream().apply { write(bytes); flush() }
            val text = socket.getInputStream().reader().readText()
            // NanoHTTPD answers HTTP/1.0, so read the version-independent status field rather than
            // assuming a literal prefix.
            val statusLine = text.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            val head = text.substringBefore("\r\n\r\n")
            return Answer(code, text.substringAfter("\r\n\r\n", ""), head)
        }
    }

    private fun awaitListening() {
        var waited = 0
        while (waited < 5000) {
            try {
                Socket("127.0.0.1", port).close()
                return
            } catch (e: IOException) {
                Thread.sleep(50); waited += 50
            }
        }
        throw AssertionError("the test server never bound to 127.0.0.1:$port")
    }

    /** The login a browser performs, returning whatever the phone gave it. */
    private fun login(submitted: String): String? {
        val answer = call("POST", "/api/auth", mapOf("Content-Type" to "application/json"), "{\"pin\":\"$submitted\"}")
        assertEquals("login itself must always answer 200", 200, answer.status)
        val token = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(answer.body)?.groupValues?.get(1)
        return token
    }

    /**
     * A login that must succeed. `Assert.fail` returns void rather than Nothing, so an elvis on it
     * would leave the token nullable — this throws instead and hands back a real String.
     */
    private fun requireLogin(submitted: String): String =
        login(submitted) ?: throw AssertionError("a correct PIN must return a session token")

    // -- S-1: the PIN must stop being the credential --------------------------

    @Test
    fun `a correct pin returns a session and never the pin`() {
        val token = requireLogin(pin)
        assertNotEquals("the room PIN must never be handed back as a token", pin, token)
        assertTrue("a token is 256 bits of randomness, hex: 64 chars", token.length == 64)
        assertTrue("and it is plain hex, so it needs no encoding anywhere", token.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `a wrong pin returns no credential at all`() {
        assertNull("a guess must not be rewarded with a session", login("000000"))
    }

    @Test
    fun `the session is what authenticates a protected route`() {
        val token = requireLogin(pin)
        val files = call("GET", "/api/files", mapOf("X-Pulse-Auth" to token))
        assertTrue("a live session reads the room, got ${files.status}", files.status in 200..299)
    }

    @Test
    fun `logout revokes the session it was offered`() {
        val token = requireLogin(pin)
        assertTrue(call("GET", "/api/files", mapOf("X-Pulse-Auth" to token)).status in 200..299)
        val out = call("POST", "/api/auth/logout", mapOf("X-Pulse-Auth" to token))
        assertEquals(200, out.status)
        assertTrue("and it says so", out.body.contains("\"revoked\":true"))
        assertEquals("a logged-out token is dead", 401, call("GET", "/api/files", mapOf("X-Pulse-Auth" to token)).status)
    }

    @Test
    fun `rotating the pin revokes every browser that held the old one`() {
        val first = requireLogin(pin)
        val second = requireLogin(pin)
        server.updatePinSecurity(true, "999999")
        assertEquals("a PIN change logs every tab out", 401, call("GET", "/api/files", mapOf("X-Pulse-Auth" to first)).status)
        assertEquals(401, call("GET", "/api/files", mapOf("X-Pulse-Auth" to second)).status)
        assertTrue("the new PIN still works", login("999999") != null)
    }

    @Test
    fun `a minted session is not cacheable`() {
        val answer = call("POST", "/api/auth", mapOf("Content-Type" to "application/json"), "{\"pin\":\"$pin\"}")
        assertEquals(200, answer.status)
        assertTrue("a token response must not be stored by a browser or a shared proxy", answer.headers.contains("no-store"))
    }

    // -- S-2: the room secret must stop travelling in URLs -------------------

    @Test
    fun `the pin is no longer accepted in a query string`() {
        // This is the half of the fix a browser cannot be blamed for: history, the SSE URL, link
        // markup and referrer headers all kept whatever was in `?auth=`, so the server stops taking
        // the PIN there and a stale tab is pushed back to the login it can still complete.
        assertEquals(401, call("GET", "/api/files?auth=$pin").status)
        val session = requireLogin(pin)
        val withSession = call("GET", "/api/files", mapOf("X-Pulse-Auth" to session))
        assertTrue("a session in a header still works", withSession.status in 200..299)
    }

    @Test
    fun `a phone authenticating app to app keeps using the pin header`() {
        // TransferManager presents the room PIN in a header when a phone uploads to a peer, and an
        // older phone in someone's pocket has no way to negotiate a session first. That path is
        // deliberately kept: header-only, never a query string, never returned to a browser.
        val answer = call("GET", "/api/files", mapOf("X-Pulse-Auth" to pin))
        assertTrue("app-to-app compatibility must not break", answer.status in 200..299)
    }

    // -- S-3: the policy has to hold for every route, not the one I tested ----

    @Test
    fun `every protected route demands a credential`() {
        val forged = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        listOf(
            "GET" to "/api/files",
            "GET" to "/api/peers",
            "GET" to "/api/threads",
            "GET" to "/api/call/web",
            "GET" to "/download/1",
            "GET" to "/preview/1",
            "POST" to "/api/delete",
            "POST" to "/upload?filename=x.txt"
        ).forEach { (method, path) ->
            assertEquals("$method $path must reject no credential", 401, call(method, path).status)
            assertEquals("$method $path must reject a forged token", 401, call(method, path, mapOf("X-Pulse-Auth" to forged)).status)
        }
    }

    @Test
    fun `a live session reaches every protected route`() {
        val token = requireLogin(pin)
        listOf(
            "GET" to "/api/files",
            "GET" to "/api/peers",
            "GET" to "/api/threads",
            "GET" to "/api/call/web"
        ).forEach { (method, path) ->
            assertNotEquals("$method $path must not reject a live session", 401, call(method, path, mapOf("X-Pulse-Auth" to token)).status)
        }
    }

    @Test
    fun `the routes that are public on purpose stay public`() {
        // Not every endpoint is meant to be private: the page, the room's advertisement and typing
        // presence are what let a device find the room at all. Making them private would break the
        // product, so they are pinned as public here rather than left to a future sweep.
        //
        // "Public" is asserted as *not refused*, not as 200: once the secure listener is up the page
        // redirects to https on purpose, and a background TLS thread that finishes binding between
        // two requests must not turn this into a flaky test.
        listOf("GET" to "/", "GET" to "/index.html", "GET" to "/api/info", "GET" to "/api/typing").forEach { (method, path) ->
            val status = call(method, path).status
            assertTrue("$method $path should be public, got $status", status !in 401..403)
        }
    }

    @Test
    fun `re-asserting the same pin keeps every session alive`() {
        // MainViewModel re-states the current PIN each time a ViewModel takes over a live server, so
        // revoking on that call would log out every tab on a screen rotation. A rotation is not a
        // credential rotation, and a transfer interrupted by one is a bug someone would report.
        val token = requireLogin(pin)
        server.updatePinSecurity(true, pin)
        server.updatePinSecurity(true, pin)
        val answer = call("GET", "/api/files", mapOf("X-Pulse-Auth" to token))
        assertTrue("the same PIN re-asserted must not revoke anything", answer.status in 200..299)
    }

    @Test
    fun `pin disabled leaves the room open, as documented`() {
        server.updatePinSecurity(false, null)
        // With no PIN the browser never logs in; this is the trust-everyone-on-my-network mode, and
        // it is the mode the dashboard has to label honestly (see the PRD's UX-2).
        assertTrue(call("GET", "/api/files").status in 200..299)
    }
}
