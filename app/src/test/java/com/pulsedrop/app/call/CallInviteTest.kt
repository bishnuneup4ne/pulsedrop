package com.pulsedrop.app.call

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.security.PairManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `/api/call/invite` is PIN-exempt, so the pairing id it presents is the whole claim of who is
 * calling. An id this phone does not hold must never raise the ringing screen - otherwise any LAN
 * host could put a name and a story in front of the user and ask them to accept a call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CallInviteTest {

    private lateinit var pairManager: PairManager
    private lateinit var engine: CallEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(Dispatchers.IO)
        pairManager = PairManager(context, scope)
        engine = CallEngine(
            context = context,
            pairManager = { pairManager },
            nameProvider = { "Test Phone" },
            endpointProvider = { "127.0.0.1" to 8080 },
            scope = scope
        )
    }

    private fun invite(pid: String) = JSONObject()
        .put("cid", "call-1")
        .put("pid", pid)
        .put("name", "Caller")
        .put("video", false)
        .put("myIp", "127.0.0.1")
        .put("myPort", 1)

    private fun pairWithSelf(): String {
        pairManager.acceptIncoming(
            IncomingPairRequest(
                reqId = "req-call",
                name = "Peer",
                pubB64 = pairManager.myPubB64(),
                myIp = "127.0.0.1",
                myPort = 1,
                safetyCode = "000000"
            )
        )
        return pairManager.paired.value.single().pid
    }

    @Test
    fun `an invite naming no pairing never rings`() {
        val reply = engine.handleInvite(invite("no-such-pid"), "127.0.0.1")
        assertNull("a stranger must not reach the ringing screen", engine.incomingCall.value)
        assertFalse("the caller is told the call was not taken", reply.optBoolean("busy", true))
    }

    @Test
    fun `an invite from a paired device still rings`() {
        val pid = pairWithSelf()
        val reply = engine.handleInvite(invite(pid), "127.0.0.1")
        assertTrue(reply.optBoolean("success", false))
        val ringing = engine.incomingCall.value
        assertNotNull("the control: the same path does ring for a real pairing", ringing)
        assertEquals("call-1", ringing!!.cid)
        assertEquals(pid, ringing.pid)
        engine.respondIncoming(false)
    }
}
