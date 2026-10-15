package com.pulsedrop.app.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.security.PairManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.json.JSONObject

/**
 * A relay is the one piece of this app that carries other people's conversations, so the two
 * properties that matter are: you can only post or read if you hold a key with the host, and the
 * host cannot tell what it is carrying. These cover the token rules and the mailbox lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RelayMailboxTest {

    private lateinit var context: Context

    private fun mailbox() = RelayMailbox(context)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun `mail waits for the addressee and disappears once acknowledged`() {
        val box = mailbox()
        val eid = box.post("abcd", JSONObject().put("ct", "opaque"))
        assertNotNull("post must accept mail for a known-shaped address", eid)
        val pending = box.pending("abcd")
        assertEquals(1, pending.size)
        assertEquals(eid, pending.first().optString("eid"))
        assertEquals("a letter must never be delivered to the neighbour's mailbox", 0, box.pending("wxyz").size)

        box.ack("abcd", listOf(requireNotNull(eid)))
        assertEquals(0, box.pending("abcd").size)
    }

    @Test
    fun `unacknowledged mail is handed out again rather than lost`() {
        val box = mailbox()
        // A poll takes a lease, not the letter itself. Once it lapses the mail has to be
        // fetchable again, otherwise a phone that crashed mid-poll loses the conversation.
        box.leaseMs = 0L
        box.post("me", JSONObject().put("ct", "one"))
        assertEquals(1, box.pending("me").size)
        assertEquals(1, box.pending("me").size)
    }

    @Test
    fun `the host stamps the addressee so a spoofed field cannot redirect mail`() {
        val box = mailbox()
        val stored = box.post("target", JSONObject().put("to", "victim").put("ct", "x"))
        val json = box.pending("target").first()
        assertEquals("target", json.optString("to"))
        assertEquals(stored, json.optString("eid"))
    }

    @Test
    fun `a mailbox that never gets emptied is bounded, oldest first`() {
        val box = mailbox()
        repeat(RelayMailbox.MAX_PER_DEVICE + 25) { i ->
            box.post("slowpoke", JSONObject().put("n", i))
        }
        val kept = box.pending("slowpoke", limit = RelayMailbox.MAX_PER_DEVICE)
        assertEquals(RelayMailbox.MAX_PER_DEVICE, kept.size)
        assertEquals("the tail of a chat matters more than its head", 25, kept.first().optInt("n"))
    }

    @Test
    fun `carried mail survives a host restart`() {
        val first = mailbox()
        first.post("phone", JSONObject().put("pid", "ab").put("ct", "sealed"))
        first.flush()

        val second = mailbox()
        second.load()
        val pending = second.pending("phone")
        assertEquals(1, pending.size)
        assertEquals("sealed", pending.first().optString("ct"))
    }

    @Test
    fun `a blank or absurd address is not a mailbox`() {
        val box = mailbox()
        assertNull(box.post("", JSONObject().put("ct", "x")))
        assertNull(box.post("a".repeat(33), JSONObject().put("ct", "x")))
    }

    @Test
    fun `carried mail is dropped once it is too old to be worth carrying`() {
        var ageMs = 0L
        val box = RelayMailbox(context, nowMs = { System.currentTimeMillis() + ageMs })
        box.post("stale", JSONObject().put("ct", "yesterday"))
        assertEquals(1, box.pending("stale").size)
        ageMs = 25L * 60 * 60 * 1000
        assertEquals("a phone that never comes back is eventually forgotten", 0, box.pending("stale").size)
    }

    @Test
    fun `the host gives up the oldest mail when its overall budget runs out`() {
        val box = RelayMailbox(context, maxCarriageBytes = 4_000L)
        repeat(20) { i -> box.post("chatty", JSONObject().put("n", i).put("ct", "x".repeat(200))) }
        val kept = box.pending("chatty")
        assertTrue("the host must shed mail rather than grow without bound", kept.size < 20)
        assertEquals("the newest message is the one worth carrying", 19, kept.last().optInt("n"))
        assertTrue(box.stats().optLong("bytes") <= 4_000L)
    }

    @Test
    fun `nothing is left behind when a whole mailbox is acknowledged`() {
        val box = mailbox()
        val eids = (1..3).mapNotNull { box.post("trip", JSONObject().put("n", it)) }
        box.ack("trip", eids)
        assertEquals(0, box.stats().optInt("devices"))
        assertEquals(0, box.stats().optInt("messages"))
        assertEquals(0L, box.stats().optLong("bytes"))
    }

    @Test
    fun `relay tokens are bound to their role, their pairing and their nonce`() {
        val pm = pairManagerWithPeer()
        val hostDid = pm.myDeviceId()
        val pid = pm.paired.value.first().pid

        val post = pm.relayAuth(hostDid, "post")!!
        assertEquals(pid, post.first)
        assertEquals(hostDid, pm.verifyRelayAuth(pid, "post", post.second))

        val replay = pm.verifyRelayAuth(pid, "post", post.second)
        assertNull("a captured token must not be reusable", replay)

        // A fresh token: the only reason a post signature must not pass as a pull one is the
        // role itself, so the nonce has to be unused here.
        val freshPost = pm.relayAuth(hostDid, "post")!!
        assertNull("a post token cannot empty a mailbox", pm.verifyRelayAuth(pid, "pull", freshPost.second))
        val pull = pm.relayAuth(hostDid, "pull")!!
        assertEquals(hostDid, pm.verifyRelayAuth(pid, "pull", pull.second))

        assertNull(pm.verifyRelayAuth("nosuchpid", "post", pm.relayAuth(hostDid, "post")!!.second))
        assertNull("an unpaired host has nothing to sign with", pm.relayAuth("ffffffffffffffff", "post"))
    }

    @Test
    fun `mail sealed for the recipient stays unreadable to the carrier`() {
        val pm = pairManagerWithPeer()
        val pairing = pm.paired.value.first()
        val sealed = pm.sealFor(pairing, JSONObject().put("text", "hello").toString())!!
        val envelope = JSONObject().put("pid", pairing.pid).put("iv", sealed.first).put("ct", sealed.second)
        val body = envelope.toString()
        assertFalse("the plaintext must not appear anywhere the host can see", body.contains("hello"))
        val opened = com.pulsedrop.app.security.Crypto.decryptText(
            pm.secretKey(pairing), envelope.optString("iv"), envelope.optString("ct")
        )
        assertEquals("hello", JSONObject(opened).optString("text"))
    }

    @Test
    fun `a room message pulled from the relay lands in the room and not in a private thread`() {
        val pm = pairManagerWithPeer()
        val seen = ArrayList<com.pulsedrop.app.model.ClipMessage>()
        pm.onPairedMessage = { seen.add(it) }
        val pairing = pm.paired.value.first()

        // Exactly what the sending phone puts in the envelope for a broadcast.
        val room = JSONObject()
            .put("mid", "m-1")
            .put("text", "everyone look")
            .put("sender", "Ana")
            .put("thread", "room")
        pm.handleRelayMessage(sealedEnvelope(pm, pairing, room))
        assertEquals(1, seen.size)
        assertNull("thread null is the app's shared room", seen[0].threadId)
        assertEquals("m-1", seen[0].id)
        assertEquals("everyone look", seen[0].text)

        // A message with no thread at all is a plain one-to-one, which must not leak into the room.
        val bare = JSONObject().put("mid", "m-2").put("text", "just you").put("sender", "Ana")
        pm.handleRelayMessage(sealedEnvelope(pm, pairing, bare))
        assertEquals(2, seen.size)
        assertEquals("pair:${pairing.pid}", seen[1].threadId)
    }

    @Test
    fun `mail pulled through a relay never re-points the pairing at the host`() {
        val pm = pairManagerWithPeer()
        val pairing = pm.paired.value.first()
        pm.onPairedMessage = { }
        pm.handleRelayMessage(
            sealedEnvelope(pm, pairing, JSONObject().put("text", "x").put("thread", "room"))
        )
        assertEquals(
            "the host's address is not the sender's address",
            "192.0.2.10",
            pm.paired.value.first().originIp
        )
    }

    private fun sealedEnvelope(
        pm: PairManager,
        pairing: com.pulsedrop.app.model.PairedDevice,
        payload: JSONObject
    ): JSONObject {
        val (iv, ct) = pm.sealFor(pairing, payload.toString())!!
        return JSONObject().put("pid", pairing.pid).put("iv", iv).put("ct", ct)
    }

    private fun pairManagerWithPeer(): PairManager {
        val pm = PairManager(context, CoroutineScope(Dispatchers.IO))
        pm.acceptIncoming(
            IncomingPairRequest(
                reqId = "relay-req",
                name = "Relay peer",
                pubB64 = pm.myPubB64(),
                myIp = "192.0.2.10",
                myPort = 8080,
                safetyCode = "000000"
            )
        )
        return pm
    }
}
