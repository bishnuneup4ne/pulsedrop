package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals

/**
 * The transport shared by every test that talks to a real [PulseDropServer].
 *
 * Raw sockets rather than `HttpURLConnection` because Robolectric shadows the URL handlers, and
 * because the exact bytes of a request — the header a browser really sends, a `Content-Length`
 * that lies about the body — are frequently the thing under test.
 *
 * Always loopback. The machine these run on has a public address and no inbound firewall I can
 * verify from inside, and a temporary listener that answers with a LAN room's file list is not
 * something to leave exposed for the length of a test run.
 */
class RoomHttp(private val port: Int) {

    class Answer(val status: Int, val body: String, val headers: String)

    /** Body bytes the last [streamBody] got the server to accept; meaningless before one. */
    var streamedBytes: Long = 0L
        private set

    fun call(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        /** Overrides the declared length only; the bytes actually written are `body`'s. */
        declareContentLength: Int? = null
    ): Answer {
        val payload = body?.toByteArray() ?: ByteArray(0)
        val request = StringBuilder()
            .append("$method $path HTTP/1.1\r\n")
            .append("Host: 127.0.0.1:$port\r\n")
        headers.forEach { (k, v) -> request.append("$k: $v\r\n") }
        if (body != null) {
            request.append("Content-Length: ${declareContentLength ?: payload.size}\r\n")
        }
        request.append("Connection: close\r\n\r\n")
        val bytes = request.toString().toByteArray() + payload
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 8000
            socket.getOutputStream().apply { write(bytes); flush() }
            val text = socket.getInputStream().reader().readText()
            // NanoHTTPD answers HTTP/1.0, so read the version-independent status field rather
            // than assuming a literal prefix.
            val statusLine = text.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            return Answer(code, text.substringAfter("\r\n\r\n", ""), text.substringBefore("\r\n\r\n"))
        }
    }

    /** A multipart POST, because `/upload` and `/chatupload` are the only routes that take one. */
    fun postMultipart(
        path: String,
        parts: Map<String, String>,
        token: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Answer {
        val boundary = "----pulsedroptestboundary"
        val body = StringBuilder()
        parts.forEach { (name, value) ->
            body.append("--$boundary\r\n")
                .append("Content-Disposition: form-data; name=\"$name\"; filename=\"$name\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n")
                .append(value).append("\r\n")
        }
        body.append("--$boundary--\r\n")
        return call(
            "POST", path,
            mapOf(
                "Content-Type" to "multipart/form-data; boundary=$boundary",
                "X-Pulse-Auth" to token
            ) + extraHeaders,
            body.toString()
        )
    }

    /**
     * A multipart body written in chunks rather than built in memory, because the only interesting
     * size for an upload ceiling test is one the phone cannot hold — and holding it in a `String`
     * first would test the test's heap instead of the server's.
     *
     * Writes stop on the first `IOException`: once the server has refused, the socket closing under
     * the client *is* the outcome, and reading the reply is what asserts it.
     */
    fun streamBody(path: String, token: String, totalBytes: Long, fieldName: String = "big.bin"): Answer {        val boundary = "----pulsedropstreamboundary"
        val header = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fieldName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val trailer = "\r\n--$boundary--\r\n".toByteArray()
        val request = StringBuilder()
            .append("POST $path HTTP/1.1\r\n")
            .append("Host: 127.0.0.1:$port\r\n")
            .append("Content-Type: multipart/form-data; boundary=$boundary\r\n")
            .append("X-Pulse-Auth: $token\r\n")
            .append("Content-Length: ${header.size + totalBytes + trailer.size}\r\n")
            .append("Connection: close\r\n\r\n")
            .toString().toByteArray()
        val chunk = ByteArray(64 * 1024)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 60_000
            val out = socket.getOutputStream()
            out.write(request)
            out.write(header)
            out.flush()
            var sent = 0L
            streamedBytes = 0L
            try {
                while (sent < totalBytes) {
                    val n = minOf(chunk.size.toLong(), totalBytes - sent).toInt()
                    out.write(chunk, 0, n)
                    out.flush()
                    sent += n
                }
                out.write(trailer)
                out.flush()
            } catch (e: IOException) {
                // The server stopped reading. Which is the point.
            }
            streamedBytes = sent
            val text = runCatching { socket.getInputStream().reader().readText() }.getOrDefault("")
            val statusLine = text.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            return Answer(code, text.substringAfter("\r\n\r\n", ""), text.substringBefore("\r\n\r\n"))
        }
    }

    /** The login a browser performs, returning whatever the phone gave it. */
    fun login(submitted: String): String? {
        val answer = call(
            "POST", "/api/auth",
            mapOf("Content-Type" to "application/json"),
            "{\"pin\":\"$submitted\"}"
        )
        assertEquals("login itself must always answer 200", 200, answer.status)
        return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(answer.body)?.groupValues?.get(1)
    }

    /**
     * A login that must succeed. `Assert.fail` returns void rather than Nothing, so an elvis on it
     * would leave the token nullable — this throws instead and hands back a real String.
     */
    fun requireLogin(submitted: String): String =
        login(submitted) ?: throw AssertionError("a correct PIN must return a session token")

    companion object {
        /** A free loopback port, asked for the way the server itself will ask for it. */
        fun freePort(): Int = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        fun appContext(): Context = ApplicationProvider.getApplicationContext()

        /**
         * Block until the listener answers, so a test cannot race the startup thread and read a
         * connection refusal as if it were the server's verdict.
         */
        fun awaitListening(port: Int) {
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

        /**
         * Open a request on a socket the caller keeps, and hand back the live response. Use this for
         * anything streaming: [RoomHttp.call] sends `Connection: close` and reads to EOF, so it can
         * only ever describe a response that has already ended.
         */
        fun live(port: Int, path: String, token: String, headers: Map<String, String> = emptyMap()): LiveStream {
            val socket = Socket("127.0.0.1", port)
            socket.soTimeout = 5000
            val request = StringBuilder("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
            request.append("x-pulse-auth: $token\r\n")
            headers.forEach { (k, v) -> request.append("$k: $v\r\n") }
            socket.getOutputStream().apply {
                write((request.append("\r\n").toString()).toByteArray())
                flush()
            }
            return LiveStream(socket)
        }
    }
}

/**
 * A streaming response read off a socket that is still open, with the transfer coding removed.
 *
 * NanoHTTPD answers a chunked response as `<hex length>\r\n<payload>\r\n` records, so the bytes on
 * the wire are not the frames the server queued. A test that searches the raw socket for
 * `event: message` finds the text inside a payload that begins with its own length — and then
 * reports the server silent because its reader could not parse it. Decode here instead, once.
 *
 * [nextFrame] skips the keep-alive comments (`:hb`) the event stream emits when nothing happened,
 * which is what a browser does; a comment is not delivery.
 */
class LiveStream(val socket: Socket) {

    val statusLine: String
    val headers: String
    private val input = socket.getInputStream()
    private var chunkLeft = 0
    private var ended = false

    init {
        val lines = StringBuilder()
        var first = ""
        while (true) {
            val line = readAsciiLine() ?: break
            if (line.isEmpty()) break
            if (first.isEmpty()) first = line
            lines.append(line).append('\n')
        }
        statusLine = first
        headers = lines.toString()
        // The header block deserved a generous wait; a body read should not block for seconds on a
        // stream that is merely quiet, because [nextFrame] holds the deadline. Poll instead.
        socket.soTimeout = 300
    }

    /** A line without its terminator, or null at end of stream. A blank line is `""`, not null. */
    private fun readAsciiLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /**
     * One decoded body byte, or null if nothing arrived this poll. A stream that ended or errored
     * latches [ended], and null then means null forever.
     */
    private fun readBodyByte(): Int? {
        if (ended) return null
        try {
            while (true) {
                if (chunkLeft > 0) {
                    val b = input.read()
                    if (b == -1) { ended = true; return null }
                    chunkLeft--
                    // The CRLF that closes a chunk record is framing, not payload.
                    if (chunkLeft == 0) { input.read(); input.read() }
                    return b
                }
                val size = readAsciiLine()?.substringBefore(';')?.trim()
                    ?: run { ended = true; return null }
                if (size.isEmpty()) return null
                val n = size.toIntOrNull(16)
                if (n == null || n == 0) { ended = true; return null }
                chunkLeft = n
            }
        } catch (e: java.net.SocketTimeoutException) {
            // A quiet stream, not a closed one. The caller's deadline decides whether quiet is over.
            return null
        } catch (e: IOException) {
            ended = true
        }
        return null
    }

    /**
     * The next `event:` frame, or null if the stream went away, the wait ran out, or nothing but
     * keep-alives came. A refusal is a fixed-length JSON body rather than a stream, so the decoder
     * finds no frame here and this returns null; assert on [statusCode] for that case.
     *
     * The deadline is load-bearing. A live stream emits a comment every twenty seconds, and each one
     * restarts the socket read, so "wait until the socket errors" is not a bounded wait: a delivery
     * that never arrives would keep the reader alive instead of failing the test that wants it.
     */
    fun nextFrame(withinMs: Long = 5_000): String? {
        val until = System.currentTimeMillis() + withinMs
        val block = StringBuilder()
        while (System.currentTimeMillis() < until) {
            val b = readBodyByte()
            if (b == null) {
                if (ended) return null
                continue
            }
            block.append(b.toChar())
            if (block.endsWith("\n\n")) {
                val text = block.toString()
                if (text.startsWith("event:")) return text
                block.setLength(0)
            }
        }
        return null
    }

    /** The status code, read the way [RoomHttp.Answer] reads it — NanoHTTPD answers HTTP/1.0. */
    fun statusCode(): Int = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1

    fun close() {
        runCatching { socket.close() }
        ended = true
    }
}

/** A listener that records nothing: the routes under test report, they do not transfer. */
object SilentListener : ServerTransferListener {
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
