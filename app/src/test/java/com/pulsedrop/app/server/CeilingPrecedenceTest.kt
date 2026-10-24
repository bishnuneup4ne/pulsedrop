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
 * Ceilings that have to sit in a particular order relative to something else.
 *
 * [UploadCeilingTest] bounds each body on its own, and that is the right question for a single
 * route. Three of these ceilings are not free-standing: one is shared by two routes with different
 * payloads inside, one is the difference between a refusal and a penalty, and one is which of two
 * refusals a stranger is told about first. Each of those breaks in a way no per-route size test
 * would notice.
 *
 * The tabs here are [TabSession]s rather than sockets for one reason: `bodyRead` can only be
 * observed by a session that reports whether anybody called `parseBody` on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CeilingPrecedenceTest {

    private val pin = "424242"
    private val tab = "127.0.0.1"
    private val stranger = "127.0.0.9"

    private lateinit var server: PulseDropServer
    private lateinit var token: String

    @Before
    fun openRoom() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // port 0, never started: the route chain runs in full with nothing on the wire.
        server = PulseDropServer(
            port = 0,
            bindAddress = tab,
            context = context,
            initialDeviceName = "Test Phone",
            initialPinCode = pin,
            initialIsPinRequired = true,
            initialListener = SilentListener
        )
        token = login(tab)
    }

    private fun ask(
        path: String,
        from: String,
        body: String,
        credentialed: Boolean = true,
        declare: Long? = null
    ): TabSession = TabSession(
        path, Method.POST, from,
        requestHeaders = (if (credentialed) mapOf("x-pulse-auth" to token) else emptyMap()) +
            mapOf("content-type" to "application/json"),
        json = body,
        declareContentLength = declare
    )

    private fun login(from: String): String {
        val answer = ask("/api/auth", from, "{\"pin\":\"$pin\"}", credentialed = false).sendTo(server)
        assertEquals("a correct PIN must return a session", 200, answer.status)
        return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(answer.body)?.groupValues?.get(1)
            ?: throw AssertionError("no token in the login answer: ${answer.body}")
    }

    @Test
    fun `the dispatcher leaves room for the wrapper around a maximal relay envelope`() {
        // The relay accepts an envelope up to MAX_RELAY_ENVELOPE. The POST that carries it is that
        // envelope plus `role`, `to`, `kind` and quoting, so it is strictly larger. Giving the
        // dispatcher the JSON ceiling — which is exactly equal to the envelope ceiling — refuses the
        // last few dozen bytes of the relay's own budget, and refuses them with a 413 the sender
        // cannot act on, instead of the relay's own "too large", which it can.
        assertTrue(
            "a wrapper must be allowed to be larger than the envelope it wraps",
            PulseDropServer.RELAY_BODY_LIMIT > MAX_RELAY_ENVELOPE
        )
        val wrapperForMaximalEnvelope = MAX_RELAY_ENVELOPE + 1024L
        val request = ask(
            "/api/relay/post", tab, "{}", credentialed = false, declare = wrapperForMaximalEnvelope
        )
        val answer = request.sendTo(server)
        assertTrue(
            "a $wrapperForMaximalEnvelope-byte wrapper for a $MAX_RELAY_ENVELOPE-byte envelope " +
                "was refused with ${answer.status}",
            answer.status != 413
        )
        assertTrue("the wrapper was admitted by the ceiling and then never read", request.bodyRead)
    }

    @Test
    fun `a body over the dispatcher ceiling is refused before it is read`() {
        // Generous is not unlimited: the dispatcher answers before the PIN for a pairing id it has
        // never heard of, so this ceiling is the only thing between a stranger and the phone's cache.
        val request = ask(
            "/api/relay/post", stranger, "A".repeat(1024), credentialed = false,
            declare = PulseDropServer.RELAY_BODY_LIMIT + 1
        )
        val answer = request.sendTo(server)
        assertEquals(413, answer.status)
        assertFalse("the phone buffered the body it refused", request.bodyRead)
    }

    @Test
    fun `refusing a body is not a wrong PIN guess`() {
        // A stranger must not be able to lock the room's real visitor out of the PIN by POSTing
        // oversized bodies at the login route. The refusal happens before the PIN is read, so no
        // guess was made and nothing may be counted — `recordPinFailure` is what turns a number of
        // tries into somebody else being unable to open the room.
        //
        // Each ask lies about a different size on purpose. Twelve asks of the same size are twelve
        // lines of one deduplicated guess, and the test would pass against a server that was
        // charging each refusal.
        repeat(12) { i ->
            val answer = ask(
                "/api/auth", stranger, "A".repeat(1024), credentialed = false,
                declare = PulseDropServer.LOGIN_BODY_LIMIT + 1 + i
            ).sendTo(server)
            assertEquals(413, answer.status)
        }
        assertFalse("a size refusal was counted as a guess", server.isLockedOut(stranger))
        assertTrue("the PIN stopped working after a stack of refusals", login(stranger).isNotEmpty())

        // The control, so the loop above is not vacuous: the same number of *wrong* PINs does burn
        // the budget. It is the refusal that keeps this stranger harmless, not the count.
        val guesser = "127.0.0.8"
        repeat(12) { i -> ask("/api/auth", guesser, "{\"pin\":\"00000$i\"}", credentialed = false).sendTo(server) }
        assertTrue("distinct wrong guesses must still lock out", server.isLockedOut(guesser))
    }

    @Test
    fun `a stranger is refused for credentials before being told about size`() {
        // `/api/delete` and `/api/messages` sit behind the room's credential check. If the size
        // guard answered first there, a 413 would tell any host on the subnet which routes are
        // sensitive to body length without ever asking for the PIN. The routes that answer before
        // the PIN anyway — login and the dispatcher — are the ones above. (Typing is no longer one
        // of them: P-2 put presence writes behind the room credential.)
        for (path in listOf("/api/delete", "/api/messages")) {
            val answer = ask(
                path, stranger, "A".repeat(1024), credentialed = false,
                declare = PulseDropServer.JSON_BODY_LIMIT + 1
            ).sendTo(server)
            assertEquals("$path answered a stranger about size before credentials", 401, answer.status)
        }
    }
}
