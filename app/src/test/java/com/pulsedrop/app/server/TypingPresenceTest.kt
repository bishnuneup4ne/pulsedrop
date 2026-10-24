package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.iki.elonen.NanoHTTPD.Method
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
 * Presence is the one thing the room lets an unauthenticated caller write. It is not secret, but it
 * is displayed to everyone, so the question is not who may write it — it is who may *erase* it.
 *
 * The old map was keyed by the name the caller claimed, which made "stop typing" a way to erase
 * anyone else's indicator by reusing their name. An address is the only field this route cannot be
 * talked out of, so it is the key now and the name is only what gets displayed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TypingPresenceTest {

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
            initialPinCode = null,
            initialIsPinRequired = false,
            initialListener = SilentListener
        )
        server.start()
        RoomHttp.awaitListening(port)
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun report(ip: String, sender: String, typing: Boolean): TabAnswer =
        TabSession(
            path = "/api/typing",
            verb = Method.POST,
            ip = ip,
            json = "{\"sender\":\"$sender\",\"typing\":$typing}"
        ).sendTo(server)

    private fun presence(ip: String = "192.168.1.9"): String =
        TabSession(path = "/api/typing", verb = Method.GET, ip = ip).sendTo(server).body

    @Test
    fun `a host cannot erase another host's presence by reusing its name`() {
        assertEquals(200, report("192.168.1.9", "Alice", true).status)
        assertTrue("the report was not recorded: ${presence()}", presence().contains("Alice"))

        // The second host claims to be Alice and says she stopped. Under the old name-keyed map
        // this removed Alice's entry.
        assertEquals(200, report("192.168.1.10", "Alice", false).status)
        assertTrue("another host erased her presence", presence().contains("Alice"))
    }

    @Test
    fun `a host can clear its own presence`() {
        report("192.168.1.9", "Alice", true)
        report("192.168.1.9", "Alice", false)
        assertFalse(presence().contains("Alice"))
    }

    @Test
    fun `a presence name is bounded before it is displayed`() {
        val long = "x".repeat(2000)
        assertEquals(200, report("192.168.1.9", long, true).status)
        val body = presence()
        assertTrue("the name was dropped entirely", body.contains("x".repeat(60)))
        assertFalse("a 2000-character name was stored whole", body.contains("x".repeat(61)))
    }

    @Test
    fun `a full room refuses a new reporter instead of evicting one`() {
        repeat(64) { i -> report("10.0.0.$i", "user$i", true) }
        report("10.0.1.1", "user64", true)
        val body = presence()
        assertTrue("the first reporter was evicted", body.contains("user0"))
        assertFalse("a 65th entry was admitted", body.contains("user64"))
    }
}
