package com.pulsedrop.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.model.PairedDevice
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What happens when the two halves of a pair disagree about which derivation they use.
 *
 * The safety code a human compares covers the two identity keys and not the chosen KDF, so an
 * on-path attacker can strip `kdf` from an answer and leave the acceptor on V2 while the requester
 * defaults to V1. The backlog says that mismatch "fails closed on the first message" — which is a
 * claim about every channel at once, and until here was only prose. These cases seal real traffic
 * with the other derivation and require each channel to refuse it: no message, no file, no receipt
 * of an upload that was never authenticated. A silent fall-back to a weaker read, or a half-written
 * plaintext file left on disk, would be the actual vulnerability, and neither is visible from the
 * handshake tests alone.
 *
 * One phone stands in for both ends: `Crypto.sharedKeyFor` will derive either version from the same
 * ECDH point, which is exactly the pair of keys a stripped answer produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DerivationMismatchTest {

    private lateinit var context: Context
    private lateinit var pairManager: PairManager
    private lateinit var pairing: PairedDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("localdrop_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("localdrop_pairs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        Crypto::class.java.getDeclaredField("cachedKeyPair")
            .apply { isAccessible = true }
            .set(null, null)

        pairManager = PairManager(context, CoroutineScope(Dispatchers.IO))
        pairManager.versionProvider = { "2.1.1" }
    }

    /** Pair at [version] by asking for the derivation the way each build would announce it. */
    private fun pairAt(version: Int): PairedDevice {
        val pub = pairManager.myPubB64()
        pairManager.acceptIncoming(
            IncomingPairRequest(
                reqId = "req-$version",
                name = if (version == Kdf.V2) "2.x peer" else "1.x peer",
                pubB64 = pub,
                myIp = "192.0.2.40",
                myPort = 8080,
                safetyCode = "000000",
                appVersion = if (version == Kdf.V2) "2.1.1" else "1.9.0"
            )
        )
        val held = pairManager.paired.value.first { it.pubB64 == pub }
        assertEquals("the pairing did not land on the derivation under test", version, held.kdfVersion)
        return held
    }

    /** Seal one envelope the way a peer would — but with the derivation this phone does not hold. */
    private fun envelopeFor(held: PairedDevice, payload: JSONObject, sealedWith: Int): JSONObject {
        val key = Crypto.sharedKeyFor(context, held.pubB64, sealedWith)
        val (iv, ct) = Crypto.encryptText(key, payload.toString())
        return JSONObject().put("pid", held.pid).put("iv", iv).put("ct", ct)
    }

    private fun mail(text: String) = JSONObject()
        .put("text", text)
        .put("sender", "The other phone")
        .put("mid", "mid-" + text.hashCode())

    /**
     * The control the rest of this file leans on: the same envelope, sealed with the derivation the
     * pairing actually holds, is routed. Without this a green refusal would only prove the plumbing
     * is broken.
     */
    @Test
    fun `mail sealed under the derivation the pair agreed on is delivered`() {
        val held = pairAt(Kdf.V2)
        val seen = ArrayList<String>()
        pairManager.onPairedMessage = { seen.add(it.text) }

        assertTrue(
            "a matching derivation was not routed: is the envelope format the one the channel reads?",
            pairManager.handleRelayMessage(envelopeFor(held, mail("words that belong"), Kdf.V2))
        )
        assertEquals(listOf("words that belong"), seen)
    }

    @Test
    fun `mail sealed under the other derivation reaches no screen`() {
        val held = pairAt(Kdf.V2)
        val seen = ArrayList<String>()
        pairManager.onPairedMessage = { seen.add(it.text) }

        assertFalse(
            "a V1-sealed envelope was accepted by a V2 pairing",
            pairManager.handleRelayMessage(envelopeFor(held, mail("secret words"), Kdf.V1))
        )
        assertTrue("the refused envelope still wrote a bubble: $seen", seen.isEmpty())
    }

    /** Both directions, because a stripped answer can leave either end holding the newer version. */
    @Test
    fun `the refusal is the same whichever half ended on v1`() {
        val held = pairAt(Kdf.V1)
        val seen = ArrayList<String>()
        pairManager.onPairedMessage = { seen.add(it.text) }

        assertFalse(
            "a V2-sealed envelope was accepted by a V1 pairing",
            pairManager.handleRelayMessage(envelopeFor(held, mail("secret words"), Kdf.V2))
        )
        assertTrue(seen.isEmpty())
        // And the derivation this pair does hold still works, so the case above failed for one reason.
        assertTrue(pairManager.handleRelayMessage(envelopeFor(held, mail("v1 belongs"), Kdf.V1)))
        assertEquals(listOf("v1 belongs"), seen)
    }

    /**
     * A retraction rides the same envelope as a message, so it has to be refused the same way: an
     * attacker who cannot read the channel must not be able to empty a screen through it either.
     */
    @Test
    fun `a retraction sealed under the other derivation retracts nothing`() {
        val held = pairAt(Kdf.V2)
        var deleted: String? = null
        pairManager.onPairedDelete = { deleted = it }

        val forged = envelopeFor(
            held,
            JSONObject().put("op", "del").put("del", "bubble-1"),
            Kdf.V1
        )
        assertFalse(pairManager.handleRelayMessage(forged))
        assertNull("the refused envelope still retracted a bubble", deleted)
    }

    /**
     * Files are the place a fail-closed claim can quietly stop being true: chunked decryption writes
     * each record as it authenticates it, so a stream that turns bad halfway can leave plaintext from
     * the healthy prefix lying on disk under the name the received listing reads.
     */
    @Test
    fun `an upload sealed under the other derivation lands no file`() {
        val held = pairAt(Kdf.V2)
        val dir = context.cacheDir
        val cipher = File(dir, "mismatch-cipher.bin")
        val final = File(dir, "mismatch-final.txt")
        final.delete()
        val words = "the file that must not land"
        cipher.outputStream().use { out ->
            Crypto.encryptChunked(Crypto.sharedKeyFor(context, held.pubB64, Kdf.V1),
                words.byteInputStream(), out)
        }

        assertFalse(pairManager.decryptUpload(held.pid, cipher, final))
        if (final.exists()) {
            val bytes = FileInputStream(final).use { it.readBytes() }
            assertFalse("decrypted plaintext reached the landing file",
                String(bytes, Charsets.UTF_8).contains(words))
            assertEquals("a partial plaintext file was left behind", 0L, final.length())
        }
        // A pairing this phone does not hold buys the sender nothing either.
        assertFalse(pairManager.decryptUpload("not-a-pairing", cipher, final))
        cipher.delete()
    }

    /**
     * The half-way case, which is where a fail-closed claim can stop being true without anyone
     * noticing: chunked decryption writes each record as it authenticates it, so a stream whose
     * first 64 KiB were sealed with the pair's key and whose next record was not leaves that prefix
     * sitting on disk under the name the caller chose. Refusing the upload is only a refusal if the
     * plaintext it already wrote goes with it.
     */
    @Test
    fun `a stream that turns bad halfway leaves no plaintext behind`() {
        val held = pairAt(Kdf.V2)
        val cipher = File(context.cacheDir, "half-cipher.bin")
        val final = File(context.cacheDir, "half-final.txt")
        final.delete()
        val prefix = "A".repeat(64 * 1024)
        val secret = "the part that must never land"
        cipher.outputStream().use { out ->
            Crypto.encryptChunked(pairManager.secretKey(held), prefix.byteInputStream(), out)
            Crypto.encryptChunked(
                Crypto.sharedKeyFor(context, held.pubB64, Kdf.V1), secret.byteInputStream(), out
            )
        }

        assertFalse(pairManager.decryptUpload(held.pid, cipher, final))
        assertFalse(
            "a rejected upload left its authenticated prefix on disk: ${if (final.exists()) final.length() else 0} bytes",
            final.exists()
        )
        cipher.delete()
    }

    @Test
    fun `a sealed upload under the agreed derivation still lands`() {
        val held = pairAt(Kdf.V2)
        val dir = context.cacheDir
        val cipher = File(dir, "match-cipher.bin")
        val final = File(dir, "match-final.txt")
        final.delete()
        cipher.outputStream().use { out ->
            Crypto.encryptChunked(pairManager.secretKey(held), "words that belong".byteInputStream(), out)
        }

        assertTrue(pairManager.decryptUpload(held.pid, cipher, final))
        assertEquals("words that belong", FileInputStream(final).use { it.readBytes() }.toString(Charsets.UTF_8))
        cipher.delete()
        final.delete()
    }
}
