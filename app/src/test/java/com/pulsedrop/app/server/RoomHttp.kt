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
        token: String
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
            ),
            body.toString()
        )
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
