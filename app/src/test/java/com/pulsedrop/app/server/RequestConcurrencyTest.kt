package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ceiling closes a connection rather than answering it. The test uses a limit of one and holds
 * that one slot with a request whose body never arrives, so the refusal is caused by a live handler
 * rather than by a timing guess. NanoHTTPD's 5 s socket read timeout bounds how long the hold lasts;
 * the test acts well inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RequestConcurrencyTest {

    private val port = RoomHttp.freePort()
    private lateinit var server: PulseDropServer

    @Before
    fun startServer() {
        server = PulseDropServer(
            port = port,
            bindAddress = "127.0.0.1",
            context = ApplicationProvider.getApplicationContext<Context>(),
            initialDeviceName = "Test Phone",
            initialPinCode = null,
            initialIsPinRequired = false,
            initialListener = SilentListener
        )
        server.setAsyncRunner(BoundedAsyncRunner(limit = 1))
        server.start()
        RoomHttp.awaitListening(port)
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun `a connection beyond the cap is closed instead of becoming a thread`() {
        val http = RoomHttp(port)
        assertTrue("the free slot must serve first", awaitStatus(http, 200))

        val held = Socket("127.0.0.1", port)
        try {
            held.soTimeout = 2000
            // A valid head with a body that never comes: the handler sits in NanoHTTPD's read until
            // the socket closes or the 5 s timeout fires, which is exactly a held connection.
            held.getOutputStream().apply {
                write(
                    ("POST /api/auth HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\n" +
                        "Content-Length: 64\r\n\r\n").toByteArray()
                )
                flush()
            }
            // Two consecutive refusals rule out a previous request's handler still finishing.
            assertTrue(
                "with the only slot held, the next connection must be refused",
                awaitRefusals(http, consecutive = 2)
            )

            held.close()
            assertTrue(
                "closing the held socket must release the slot, not leak the thread count",
                awaitStatus(http, 200)
            )
        } finally {
            runCatching { held.close() }
        }
    }

    /** -1 means the server closed the connection before writing a status line. */
    private fun statusOrRefusal(http: RoomHttp): Int =
        runCatching { http.call("GET", "/").status }.getOrNull() ?: -1

    private fun awaitStatus(http: RoomHttp, wanted: Int): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (statusOrRefusal(http) == wanted) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun awaitRefusals(http: RoomHttp, consecutive: Int): Boolean {
        var seen = 0
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (statusOrRefusal(http) == -1) {
                if (++seen >= consecutive) return true
            } else {
                seen = 0
            }
            Thread.sleep(20)
        }
        return false
    }
}
