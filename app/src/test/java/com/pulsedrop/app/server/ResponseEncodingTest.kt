package com.pulsedrop.app.server

import fi.iki.elonen.NanoHTTPD.Method
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What leaves the phone when a tab asks for the same bytes twice.
 *
 * A tab polls the room every two seconds and most polls change nothing. Before this, every one of
 * those reads paid for the whole answer again — measured at 517 KB of JSON for a room holding 2,000
 * messages — so the two mechanisms here answer the two halves of that waste: `gzip` for the bytes
 * that must be sent, and an `ETag` for the ones that must not. Both are asserted on the wire rather
 * than on a header map, because a header that is set and mis-framed is worse than no header at all.
 *
 * The framing is the part worth testing. NanoHTTPD compresses by switching the response to chunked
 * transfer, and a `304` must carry neither a body nor a `Content-Length` describing one. Get either
 * wrong and the tab does not see a saving; it sees a broken connection, which is the cure killing
 * the patient.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResponseEncodingTest {

    private val pin = "424242"
    private var port = 0
    private lateinit var server: PulseDropServer
    private lateinit var http: RoomHttp
    private lateinit var token: String

    @Before
    fun startServer() {
        port = RoomHttp.freePort()
        server = PulseDropServer(
            port = port,
            bindAddress = "127.0.0.1",
            context = RoomHttp.appContext(),
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

    /** A history long enough that its JSON is worth compressing, and short enough to build fast. */
    private fun seedRoom(count: Int) {
        repeat(count) { i ->
            server.addMessage(
                "note $i " + "padding-padding-padding-padding-padding-padding-padding".repeat(3),
                "another device",
                clientIp = "10.0.0.9"
            )
        }
    }

    private fun read(path: String, vararg headers: Pair<String, String>): Wire =
        http.raw("GET", path, mapOf("x-pulse-auth" to token) + headers.toMap())

    @Test
    fun `a room worth compressing arrives compressed`() {
        seedRoom(8)
        val plain = read("/api/messages")
        assertTrue("the fixture is below the floor: ${plain.text.length} bytes", plain.text.length > 1024)
        assertFalse("an unasked-for encoding was applied", plain.compressed)

        val packed = read("/api/messages", "Accept-Encoding" to "gzip")
        assertEquals("gzip", packed.header("Content-Encoding"))
        assertNotNull(
            "chunked transfer is how NanoHTTPD frames a compressed body",
            packed.header("Transfer-Encoding")
        )
        assertEquals("compression changed the room's contents", plain.text, packed.text)
        assertTrue(
            "compressing made the answer larger, so the floor is wrong: ${packed.onTheWire} vs ${plain.onTheWire}",
            packed.onTheWire < plain.onTheWire
        )
    }

    @Test
    fun `a body too small to pay for compression is sent as it is`() {
        val answer = read("/api/typing", "Accept-Encoding" to "gzip")
        assertEquals(200, answer.status)
        assertFalse("a handful of bytes spent the phone's CPU to save nothing", answer.compressed)
    }

    @Test
    fun `an event stream is never buffered to be compressed`() {
        // The stream answers with a MIME type of its own and a body that has no end until the tab
        // goes away; a compression decision that looked only at "is this text" would have tried to
        // hold it. The reader in the admission suite would then see nothing at all.
        val stream = RoomHttp.live(port, "/api/events", token)
        assertEquals(200, stream.statusCode())
        assertFalse(stream.headers.lineSequence().any { it.lowercase().startsWith("content-encoding") })
        stream.close()
    }

    @Test
    fun `a tab that already has the bytes is told so without being sent them`() {
        seedRoom(8)
        val first = read("/api/messages")
        val etag = first.etag
        assertNotNull("no validator was offered, so the next read pays in full", etag)

        val again = read("/api/messages", "If-None-Match" to etag!!)
        assertEquals(304, again.status)
        assertEquals("a 304 must repeat the validator it accepted", etag, again.etag)
        assertEquals("", again.text)
        // RFC 9110 forbids a Content-Length on a 304, and a strict client answers one with a
        // protocol error rather than an empty body — which the tab would read as the phone dying.
        assertFalse("a Content-Length on a 304 is a protocol error waiting to happen:\n${again.head}", again.hasHeader("Content-Length"))
        assertFalse("a 304 with framing has no framing to undo:\n${again.head}", again.hasHeader("Transfer-Encoding"))
        assertTrue("the revalidation arrived stripped of the policy that produced the copy", again.hasHeader("Cache-Control"))
        assertTrue(again.head.contains("X-Content-Type-Options: nosniff"))
    }

    @Test
    fun `a changed room is never answered with a 304`() {
        seedRoom(4)
        val etag = requireNotNull(read("/api/messages").etag)

        server.addMessage("a new voice", "another device", clientIp = "10.0.0.9")

        val after = read("/api/messages", "If-None-Match" to etag)
        assertEquals("the room changed and the tab was told nothing happened", 200, after.status)
        assertTrue("the validator survived the content it describes changing", after.etag != etag)
        assertTrue(after.text.contains("a new voice"))
    }

    @Test
    fun `one tab's validator does not describe another tab's room`() {
        // Two direct threads, one per address: the same route answers these two tabs with two
        // different bodies, which is the only thing a per-address validator can honestly describe.
        server.addMessage("only mine", "me", clientIp = "127.0.0.1", threadId = "ip:127.0.0.1")
        server.addMessage("a private thread", "another device", clientIp = "10.0.0.9", threadId = "ip:10.0.0.9")

        val mine = TabSession(
            "/api/messages", Method.GET, "127.0.0.1",
            requestHeaders = mapOf("x-pulse-auth" to token)
        ).sendTo(server)
        val theirs = TabSession(
            "/api/messages", Method.GET, "10.0.0.9",
            requestHeaders = mapOf("x-pulse-auth" to token)
        ).sendTo(server)

        assertTrue("the fixture does not scope the two tabs: ${mine.body}", mine.body.contains("only mine") && !mine.body.contains("a private thread"))
        assertTrue("the fixture does not scope the two tabs: ${theirs.body}", theirs.body.contains("a private thread") && !theirs.body.contains("only mine"))

        val mineTag = mine.headers["etag"]
        val theirsTag = theirs.headers["etag"]
        assertNotNull("the route is not offering a validator at all", mineTag)
        assertNotNull(theirsTag)
        assertTrue(
            "the validator says nothing about the encoding it was offered beside it: ${mine.headers["vary"]}",
            mine.headers["vary"]?.contains("Accept-Encoding") == true
        )
        assertTrue(
            "one shared validator would let either tab answer the other's cache: $mineTag == $theirsTag",
            mineTag != theirsTag
        )
    }

    @Test
    fun `a 304 leaves the connection it arrived on usable`() {
        seedRoom(2)
        val etag = requireNotNull(read("/api/messages").etag)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 8000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            // Both requests on one connection: a 304 that mis-frames its lack of a body desyncs
            // whatever the tab sends next, and the tab cannot tell that from a dead phone.
            out.write(
                "GET /api/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nx-pulse-auth: $token\r\nIf-None-Match: $etag\r\n\r\n".toByteArray()
            )
            out.flush()
            val first = readBlock(socket)
            out.write(
                "GET /api/typing HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nx-pulse-auth: $token\r\nConnection: close\r\n\r\n".toByteArray()
            )
            out.flush()
            val second = readBlock(socket)
            assertEquals("HTTP/1.1 304 Not Modified", first.lineSequence().first().trim())
            assertTrue(
                "the request after a 304 reached the 304's own leftovers instead: $second",
                second.startsWith("HTTP/1.1 200 OK")
            )
        }
    }

    /** A whole header block. Reading only the status line would leave the rest of the response in
     * the socket to be mistaken for the next one. */
    private fun readBlock(socket: Socket): String {
        val block = StringBuilder()
        while (!block.endsWith("\r\n\r\n")) {
            val b = socket.getInputStream().read()
            if (b == -1) break
            block.append(b.toChar())
        }
        return block.toString()
    }

    @Test
    fun `the page offers its validator and does not read the answer as an outage`() {
        val page = WebClientHtml.getHtml("Test Phone", true)
        val at = page.indexOf("async function fetchMessages(").takeIf { it >= 0 }
            ?: error("the served page has no fetchMessages")
        val open = page.indexOf('{', at)
        var depth = 0
        var body = page.substring(at)
        for (i in open until page.length) {
            when (page[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        body = page.substring(at, i + 1)
                        break
                    }
                }
            }
        }

        val offer = body.indexOf("if-none-match")
        val notModified = body.indexOf("res.status === 304")
        val ok = body.indexOf("!res.ok")
        val parse = body.indexOf("res.json()")
        assertTrue("the poll never offers a validator: $body", offer in 0 until notModified)
        // `Response.ok` is false for a 304, so a handler that tests it first reports a healthy
        // phone as a disconnected one on every unchanged poll.
        assertTrue("a 304 is tested after the not-ok branch, which reads as an outage", notModified in 0 until ok)
        assertTrue("the tab re-reads the body it was just told it already has", parse > ok)
        assertTrue("nothing remembers the validator between polls", body.contains("messagesEtag = res.headers.get('etag')"))
    }
}
