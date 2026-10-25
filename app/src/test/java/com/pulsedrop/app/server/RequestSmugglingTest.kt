package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What one connection is allowed to carry, observed rather than inferred from NanoHTTPD's bytecode.
 *
 * NanoHTTPD reads exactly `Content-Length` bytes per request and then loops over the *same* stream,
 * so bytes a client appends after a short body become the next request. There is no proxy in this
 * deployment, which is why this was filed as a question rather than a vulnerability: the only author
 * of a smuggled request is the client itself. But a reply to a body-bearing request now ends the
 * connection, so those bytes die with the socket instead of being served as a request the tab never
 * sent. Bodyless polling keeps connection reuse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RequestSmugglingTest {

    private val pin = "424242"
    private lateinit var server: PulseDropServer
    private var port = 0

    @Before
    fun startServerOnLoopback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        port = RoomHttp.freePort()
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
        RoomHttp.awaitListening(port)
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private class Reply(val status: Int, val body: String)

    /** Null only at a clean end of stream; a timeout is a failed expectation, not an answer. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\r'.code) {
                input.read()
                return line.toString()
            }
            line.append(b.toChar())
        }
    }

    private fun readReply(input: InputStream): Reply? {
        val statusLine = readLine(input) ?: return null
        var length = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Reply(statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1, String(body, 0, read))
    }

    private fun secondReply(input: InputStream): Reply? = try {
        readReply(input)
    } catch (e: SocketTimeoutException) {
        throw AssertionError("the connection stayed open instead of ending after the reply", e)
    }

    private fun withSocket(block: (Socket) -> Unit) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            block(socket)
        }
    }

    @Test
    fun `two bodyless requests still share one connection`() {
        withSocket { socket ->
            val request = "GET /api/info HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
            socket.getOutputStream().apply {
                write((request + request).toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val first = readReply(input)
            assertNotNull("the first poll must be answered", first)
            assertEquals(200, first!!.status)
            val second = secondReply(input)
            assertNotNull("connection reuse for polls is kept on purpose", second)
            assertEquals(200, second!!.status)
            assertTrue(second.body.contains("deviceName"))
        }
    }

    @Test
    fun `bytes after a declared body are not served as a second request`() {
        val body = """{"pin":"$pin"}"""
        val smuggled = "GET /api/info HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
        withSocket { socket ->
            val request = "POST /api/auth HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n\r\n"
            socket.getOutputStream().apply {
                write((request + body + smuggled).toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val first = readReply(input)
            assertNotNull("the login must be answered before the connection ends", first)
            assertEquals(200, first!!.status)
            assertTrue("the login is the request whose body bounds this reply", first.body.contains("\"token\""))
            assertNull(
                "surplus bytes on a body-bearing connection became a request",
                secondReply(input)
            )
        }
    }

    @Test
    fun `a chunked declaration cannot smuggle a request past this server`() {
        withSocket { socket ->
            // NanoHTTPD 2.3.1 does not implement chunked bodies: it reads no body at all and the
            // chunk framing is left to be parsed as the next request line.
            val request = "POST /api/auth HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val first = readReply(input)
            assertNotNull("the declaration itself is still answered", first)
            assertEquals("this server has no chunked reader, so it refuses the request", 400, first!!.status)
            val second = secondReply(input)
            assertNull("chunk framing must not become a second request: ${second?.status} ${second?.body}", second)
        }
    }
}
