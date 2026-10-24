package com.pulsedrop.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.iki.elonen.NanoHTTPD.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Presence is the smallest write in the app and it used to be the least guarded: any host could
 * post a name, and a second host could remove the first's entry by repeating that name. Nothing
 * here is confidential — the fix is about who may speak for whom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TypingPresenceTest {

    private val pin = "424242"
    private val tabA = "127.0.0.1"
    private val tabB = "127.0.0.2"

    private lateinit var server: PulseDropServer
    private lateinit var token: String

    @Before
    fun openRoom() {
        val context: Context = ApplicationProvider.getApplicationContext()
        server = PulseDropServer(
            port = 0,
            bindAddress = tabA,
            context = context,
            initialDeviceName = "Test Phone",
            initialPinCode = pin,
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        token = login()
    }

    private fun login(): String {
        val answer = TabSession(
            "/api/auth", Method.POST, tabA,
            requestHeaders = mapOf("content-type" to "application/json"),
            json = "{\"pin\":\"$pin\"}"
        ).sendTo(server)
        assertEquals(200, answer.status)
        return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(answer.body)?.groupValues?.get(1)
            ?: throw AssertionError("no token: ${answer.body}")
    }

    private fun ping(from: String, sender: String, typing: Boolean = true, credential: String? = token) =
        TabSession(
            "/api/typing", Method.POST, from,
            requestHeaders = buildMap {
                put("content-type", "application/json")
                credential?.let { put("x-pulse-auth", it) }
            },
            json = "{\"sender\":\"$sender\",\"typing\":$typing}"
        ).sendTo(server)

    private fun present(): String =
        TabSession("/api/typing", Method.GET, tabA).sendTo(server).body

    @Test
    fun `a presence write needs a credential, a read does not`() {
        assertEquals("a stranger wrote presence", 401, ping(tabB, "Mallory", credential = null).status)
        assertEquals("[]", present())
        assertEquals(200, TabSession("/api/typing", Method.GET, tabB).sendTo(server).status)
    }

    @Test
    fun `an authenticated tab can say it is typing`() {
        assertEquals(200, ping(tabA, "Alice").status)
        assertTrue("Alice should be present: ${present()}", present().contains("Alice"))
    }

    @Test
    fun `one address cannot clear another address's presence by naming it`() {
        // The original hole: `typingSenders.remove(sender)` was keyed by the name the caller sent,
        // so a second host only had to know what the first one called itself.
        assertEquals(200, ping(tabA, "Alice").status)
        assertEquals(200, ping(tabB, "Alice", typing = false).status)
        assertTrue("B removed A's presence by repeating its name: ${present()}", present().contains("Alice"))
    }

    @Test
    fun `two addresses that claim one name are shown once`() {
        assertEquals(200, ping(tabA, "Alice").status)
        assertEquals(200, ping(tabB, "Alice").status)
        val body = present()
        assertEquals("the name should not be repeated: $body", 1, Regex("Alice").findAll(body).count())
    }

    @Test
    fun `a heartbeat that outruns the typed interval is refused`() {
        // The tab pings every 2 s; the server allows one write per 400 ms per address, so the
        // second and third writes of a loop are 429 rather than free map churn.
        assertEquals(200, ping(tabA, "Alice").status)
        assertEquals(429, ping(tabA, "Alice").status)
        assertEquals(429, ping(tabA, "Alice").status)
        // Another address is unaffected by A's burst.
        assertEquals(200, ping(tabB, "Bob").status)
    }
}
