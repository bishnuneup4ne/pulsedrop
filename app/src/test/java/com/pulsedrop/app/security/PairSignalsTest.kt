package com.pulsedrop.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.model.PairedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat signals - reactions, edits and read receipts - on the sealed wire.
 *
 * These ride the same envelope as a retraction, so the properties that made retraction safe have to
 * hold for them too: a carrier learns nothing, an envelope addressed to a pairing we do not hold buys
 * the sender nothing, and a build from before the signal exists ignores it rather than mis-rendering
 * it. That last one is the reason an edit's new words are not sent as `text`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairSignalsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun pairedPhone(): PairManager {
        val pm = PairManager(context, CoroutineScope(Dispatchers.IO))
        pm.acceptIncoming(
            IncomingPairRequest(
                reqId = "req-signals",
                name = "Signal peer",
                pubB64 = pm.myPubB64(),
                myIp = "192.0.2.30",
                myPort = 8080,
                safetyCode = "000000"
            )
        )
        return pm
    }

    private class Wire {
        val sealed = ArrayList<Pair<String, String>>()
    }

    private fun routeThroughRelay(pm: PairManager, pairing: PairedDevice, wire: Wire) {
        pm.markUnreachable(pairing.pid)
        pm.relaySender = { _, iv, ct ->
            wire.sealed.add(iv to ct)
            true
        }
    }

    private fun envelopeFor(pairing: PairedDevice, sealed: Pair<String, String>) =
        JSONObject().put("pid", pairing.pid).put("iv", sealed.first).put("ct", sealed.second)

    /** The plaintext a peer would read, which is only ever reached by a device holding the key. */
    private fun open(pm: PairManager, pairing: PairedDevice, sealed: Pair<String, String>) =
        JSONObject(Crypto.decryptText(pm.secretKey(pairing), sealed.first, sealed.second))

    @Test
    fun `a reaction reaches the other side named`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        assertEquals(1, pm.reactEverywhere("m-1", "heart", "pair:${pairing.pid}"))
        val seen = ArrayList<Triple<String, String?, String>>()
        pm.onPairedReaction = { id, reaction, by -> seen.add(Triple(id, reaction, by)) }
        assertTrue(pm.handleRelayMessage(envelopeFor(pairing, wire.sealed.first())))
        assertEquals(Triple("m-1", "heart", pm.nameProvider()), seen.first())
    }

    @Test
    fun `clearing a reaction says so rather than sending an empty one`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        pm.reactEverywhere("m-2", null, "pair:${pairing.pid}")
        val seen = ArrayList<Triple<String, String?, String>>()
        pm.onPairedReaction = { id, reaction, by -> seen.add(Triple(id, reaction, by)) }
        assertTrue(pm.handleRelayMessage(envelopeFor(pairing, wire.sealed.first())))
        assertEquals(Triple("m-2", null, pm.nameProvider()), seen.first())
    }

    @Test
    fun `an edit arrives with the words and the author`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        pm.editEverywhere("m-3", "meeting at ten", "pair:${pairing.pid}")
        var got: Quadruple? = null
        pm.onPairedEdit = { id, text, at, by -> got = Quadruple(id, text, at, by) }
        assertTrue(pm.handleRelayMessage(envelopeFor(pairing, wire.sealed.first())))
        assertEquals("m-3", got!!.id)
        assertEquals("meeting at ten", got!!.text)
        assertEquals(pm.myDeviceId(), got!!.by)
        assertTrue(got!!.at > 0L)
    }

    @Test
    fun `a read receipt is a thread and a watermark and nothing else`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        pm.noteRead("pair:${pairing.pid}", 1_700_000_000_123L)
        val seen = ArrayList<Pair<String?, Long>>()
        pm.onPairedRead = { thread, through -> seen.add(thread to through) }
        assertTrue(pm.handleRelayMessage(envelopeFor(pairing, wire.sealed.first())))
        assertEquals("pair:${pairing.pid}", seen.first().first)
        assertEquals(1_700_000_000_123L, seen.first().second)
    }

    @Test
    fun `the room watermark arrives as the room`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        pm.noteRead(null, 42L)
        var thread: String? = "unset"
        var through = 0L
        pm.onPairedRead = { t, n -> thread = t; through = n }
        assertTrue(pm.handleRelayMessage(envelopeFor(pairing, wire.sealed.first())))
        assertEquals(null, thread)
        assertEquals(42L, through)
    }

    @Test
    fun `a carrier cannot read a reaction it is carrying`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)
        pm.reactEverywhere("m-4", "heart", "pair:${pairing.pid}")

        assertFalse(wire.sealed.first().second.contains("heart"))
        assertFalse("a message id is as private as the message", wire.sealed.first().second.contains("m-4"))
    }

    @Test
    fun `a pre-signal build ignores every one of these`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        assertEquals(1, pm.reactEverywhere("m-5", "like", "pair:${pairing.pid}"))
        assertEquals(1, pm.editEverywhere("m-6", "corrected text", "pair:${pairing.pid}"))
        assertEquals(1, pm.noteRead("pair:${pairing.pid}", 7L))
        assertEquals(3, wire.sealed.size)

        // A build from before the signals dispatches on `op == "del"`, then reads `text` and `mid`.
        // A payload with neither is one it returns false for; one with words in `text` would render
        // a phantom bubble under an id the old build invented.
        wire.sealed.forEach { sealed ->
            val payload = open(pm, pairing, sealed)
            assertTrue(payload.has("op"))
            assertFalse("an old build would render this as a new message", payload.has("text"))
            assertFalse("...under an id it made up", payload.has("mid"))
        }
        assertEquals("rea", open(pm, pairing, wire.sealed[0]).optString("op"))
        assertEquals("edi", open(pm, pairing, wire.sealed[1]).optString("op"))
        assertEquals("red", open(pm, pairing, wire.sealed[2]).optString("op"))
        assertEquals("corrected text", open(pm, pairing, wire.sealed[1]).optString("body"))
    }

    @Test
    fun `a signal for a pairing we do not hold changes nothing`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val sealed = requireNotNull(
            pm.sealFor(pairing, JSONObject().put("op", "rea").put("rea", "m-7").put("value", "heart").toString())
        )
        val forged = JSONObject().put("pid", "ffffffffffff").put("iv", sealed.first).put("ct", sealed.second)

        var reactions = 0
        pm.onPairedReaction = { _, _, _ -> reactions++ }
        assertFalse(pm.handleRelayMessage(forged))
        assertEquals(0, reactions)
    }

    @Test
    fun `a thread nobody is paired with takes no signal`() {
        val pm = pairedPhone()
        assertEquals(0, pm.reactEverywhere("x", "heart", "ip:192.0.2.44"))
        assertEquals(0, pm.editEverywhere("x", "new", "group:missing"))
        assertEquals(0, pm.reactEverywhere("", "heart", null))
    }

    /** Four values, which is what an edit signal carries; a Pair would not hold them. */
    private data class Quadruple(val id: String, val text: String, val at: Long, val by: String)
}
