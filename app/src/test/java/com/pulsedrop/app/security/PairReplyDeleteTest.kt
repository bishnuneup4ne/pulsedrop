package com.pulsedrop.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.ClipMessage
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
 * Replies and retractions travel on the same sealed envelope as the messages they talk about, so
 * these tests ride the real send path with the peer forced onto the relay hook. That hook only ever
 * sees ciphertext, which is also asserted here: a retraction must not be a way to read somebody's
 * conversation, and it must not hand a carrier anything to render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairReplyDeleteTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun pairedPhone(): PairManager {
        val pm = PairManager(context, CoroutineScope(Dispatchers.IO))
        pm.acceptIncoming(
            IncomingPairRequest(
                reqId = "req-wire",
                name = "Wire peer",
                pubB64 = pm.myPubB64(),
                myIp = "192.0.2.20",
                myPort = 8080,
                safetyCode = "000000"
            )
        )
        return pm
    }

    /** Everything the relay hook was handed, which is all a carrier can ever see. */
    private class Wire {
        val sealed = ArrayList<Pair<String, String>>()
    }

    /** Forces the fallback path by marking the peer unreachable, so nothing tries a socket. */
    private fun routeThroughRelay(pm: PairManager, pairing: PairedDevice, wire: Wire) {
        pm.markUnreachable(pairing.pid)
        pm.relaySender = { _, iv, ct ->
            wire.sealed.add(iv to ct)
            true
        }
    }

    private fun envelopeFor(pairing: PairedDevice, sealed: Pair<String, String>) =
        JSONObject().put("pid", pairing.pid).put("iv", sealed.first).put("ct", sealed.second)

    @Test
    fun `a reply carries its quote across the sealed wire`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        val original = ClipMessage(id = "o-1", text = "Meet at six", sender = "Ana")
        assertTrue(pm.sendPairedMessage(pairing, "Running ten late", "pair:${pairing.pid}", original))
        assertEquals(1, wire.sealed.size)

        val seen = ArrayList<ClipMessage>()
        pm.onPairedMessage = { seen.add(it) }
        assertTrue(pm.handleRelayMessage(envelopeFor(pairing, wire.sealed.first())))
        val got = seen.first()
        assertEquals("Running ten late", got.text)
        assertEquals("o-1", got.replyTo)
        assertEquals("Ana", got.replySender)
        assertEquals("Meet at six", got.replyText)
    }

    @Test
    fun `a carrier cannot read a reply it is carrying`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)
        pm.sendPairedMessage(
            pairing,
            "Running ten late",
            "pair:${pairing.pid}",
            ClipMessage(id = "o-1", text = "Meet at six", sender = "Ana")
        )
        val carried = wire.sealed.first()
        assertFalse(carried.second.contains("ten late"))
        assertFalse("a quote snapshot is as private as the message", carried.second.contains("six"))
    }

    @Test
    fun `a retraction tombstones the other side`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)

        assertEquals(1, pm.deleteEverywhere("gone-1", "pair:${pairing.pid}"))
        val retracted = ArrayList<String>()
        pm.onPairedDelete = { retracted.add(it) }
        assertTrue(pm.handleRelayMessage(envelopeFor(pairing, wire.sealed.first())))
        assertEquals(listOf("gone-1"), retracted)
    }

    @Test
    fun `a retraction carries nothing an older build would render`() {
        val pm = pairedPhone()
        val pairing = pm.paired.value.first()
        val wire = Wire()
        routeThroughRelay(pm, pairing, wire)
        pm.deleteEverywhere("gone-2", "pair:${pairing.pid}")

        // A build that predates retractions looks for "text" and returns false on this envelope,
        // so the worst it can do is keep showing a message its peer meant to retract.
        val plain = Crypto.decryptText(pm.secretKey(pairing), wire.sealed.first().first, wire.sealed.first().second)
        val payload = JSONObject(plain)
        assertEquals("del", payload.optString("op"))
        assertFalse(payload.has("text"))
    }

    @Test
    fun `a retraction addressed at a pairing we do not hold is ignored`() {
        val pm = pairedPhone()
        // A well-formed retraction, addressed at a pairing this phone does not hold. The pid is
        // what selects the key, so claiming another one has to buy nothing.
        val sealed = requireNotNull(pm.sealFor(pm.paired.value.first(), JSONObject().put("op", "del").put("del", "gone-3").toString()))
        val forged = JSONObject().put("pid", "ffffffffffff").put("iv", sealed.first).put("ct", sealed.second)

        val retracted = ArrayList<String>()
        pm.onPairedDelete = { retracted.add(it) }
        assertFalse("an envelope for a pairing we do not hold must not be able to erase anything", pm.handleRelayMessage(forged))
        assertTrue(retracted.isEmpty())
    }

    @Test
    fun `a thread nobody is paired with cannot be retracted`() {
        val pm = pairedPhone()
        assertEquals(0, pm.deleteEverywhere("x", "ip:192.0.2.44"))
        assertEquals(0, pm.deleteEverywhere("x", "group:missing"))
        assertEquals(0, pm.deleteEverywhere("", null))
    }
}
