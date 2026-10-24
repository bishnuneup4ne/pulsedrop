package com.pulsedrop.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What this phone does when it cannot get its identity key.
 *
 * Before this, any exception out of the keystore fell through to generating a raw P-256 private key
 * into SharedPreferences and carrying on. That is the behaviour these tests exist to keep: a keystore
 * that is merely locked — before first unlock, after a biometric re-enrollment, for one launch — used
 * to trade a hardware-backed identity for a readable file, permanently and silently, while every
 * pairing on every peer kept pointing at the public key this phone had already handed out.
 *
 * The three outcomes are genuinely different and must not be conflated, so each gets its own case:
 * a refusal to wait out, a device that cannot do keystore at all, and a stored key that will not
 * parse. The seam is [IdentityKeyPair.load], which takes the keystore and the question of whether one
 * exists as parameters — a device cannot be conjured in a unit test, but its answers can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IdentityKeyFallbackTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("localdrop_identity", Context.MODE_PRIVATE)

    @Before
    fun `a launch that has not been refused yet`() {
        // Robolectric keeps one IdentityKeyPair across the class's tests, and the refusal count is
        // process state by design; without this the second test would inherit the first one's launch.
        IdentityKeyPair.countedRefusalThisLaunch = false
        prefs.edit().clear().commit()
    }

    private fun freshKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private fun storeAsLegacy(pair: KeyPair) {
        prefs.edit()
            .putString("priv", Crypto.b64(pair.private.encoded))
            .putString("pub", Crypto.b64(pair.public.encoded))
            .commit()
    }

    /** A keystore that is there and says no — the case the old code got wrong. */
    private fun refusing(): () -> KeyPair =
        { throw IllegalStateException("keystore locked until first unlock") }

    private fun loadWith(pair: KeyPair): () -> KeyPair = { pair }

    // ---- the guarantee -------------------------------------------------------

    @Test
    fun `one keystore refusal writes no private key anywhere`() {
        val failure = assertThrows(IdentityKeyUnavailableException::class.java) {
            IdentityKeyPair.load(context, refusing(), keystoreOffered = true)
        }
        assertNull(
            "a locked keystore must not buy a readable private key",
            prefs.getString("priv", null)
        )
        assertNull(prefs.getString("pub", null))
        assertEquals(IdentityState.UNAVAILABLE, IdentityKeyPair.state)
        // The user is told what to do, because the app is now unable to do the thing they asked.
        assertTrue(failure.message, failure.message!!.contains("Unlock the device"))
    }

    @Test
    fun `three taps on accept are one refusal`() {
        // The count is of launches, not of attempts: three taps on a locked phone are one piece of
        // evidence about the hardware, and the old fall-through made the third one a permanent
        // downgrade.
        repeat(3) {
            assertThrows(IdentityKeyUnavailableException::class.java) {
                IdentityKeyPair.load(context, refusing(), keystoreOffered = true)
            }
        }
        assertEquals(1, prefs.getInt("keystore_failures", 0))
        assertNull(prefs.getString("priv", null))
    }

    @Test
    fun `a launch that answers clears the count`() {
        prefs.edit().putInt("keystore_failures", 2).commit()
        val keystorePair = freshKeyPair()
        val pair = IdentityKeyPair.load(context, loadWith(keystorePair), keystoreOffered = true)
        assertEquals(Crypto.b64(keystorePair.public.encoded), Crypto.b64(pair.public.encoded))
        assertEquals(IdentityState.HEALTHY, IdentityKeyPair.state)
        assertEquals(0, prefs.getInt("keystore_failures", -1))
        assertNull("a hardware-backed identity is never copied into prefs", prefs.getString("priv", null))
        assertEquals("keystore", prefs.getString("identity_store", null))
    }

    // ---- which store wins ----------------------------------------------------

    @Test
    fun `a stored key is the identity even when the keystore would answer`() {
        val legacy = freshKeyPair()
        storeAsLegacy(legacy)
        var asked = false
        val pair = IdentityKeyPair.load(context, { asked = true; freshKeyPair() }, keystoreOffered = true)
        assertEquals(
            "two identities on one phone means every existing pairing fails with nothing on screen to say why",
            Crypto.b64(legacy.public.encoded),
            Crypto.b64(pair.public.encoded)
        )
        assertEquals(IdentityState.LEGACY_FILE, IdentityKeyPair.state)
        assertEquals("legacy-file", prefs.getString("identity_store", null))
        assertTrue("prefs are read before the keystore, and stop there", asked.not())
        assertEquals(Crypto.b64(legacy.private.encoded), prefs.getString("priv", null))
    }

    @Test
    fun `a device believed unable is file backed once and stays that way`() {
        // Two launches already refused. This is the third: the device is believed, and pairing has to
        // work on hardware that genuinely cannot do keystore.
        prefs.edit().putInt("keystore_failures", 2).commit()
        val first = IdentityKeyPair.load(context, refusing(), keystoreOffered = true)
        assertEquals(IdentityState.LEGACY_FILE, IdentityKeyPair.state)
        assertNotNull(prefs.getString("priv", null))
        val storedPub = prefs.getString("pub", null)
        // A later launch where the keystore has come back must not swap the identity out from under
        // the pairings that were made with the file key.
        val second = IdentityKeyPair.load(context, loadWith(freshKeyPair()), keystoreOffered = true)
        assertEquals(storedPub, Crypto.b64(second.public.encoded))
        assertEquals(storedPub, prefs.getString("pub", null))
        assertEquals(IdentityState.LEGACY_FILE, IdentityKeyPair.state)
    }

    @Test
    fun `an environment with no keystore at all is not a failure to wait out`() {
        // Robolectric and any JVM-ish place with no AndroidKeyStore: the provider is simply absent, so
        // counting refusals would throw twice for no reason before making the same key anyway.
        val pair = IdentityKeyPair.load(context, refusing(), keystoreOffered = false)
        assertEquals(IdentityState.LEGACY_FILE, IdentityKeyPair.state)
        assertEquals("no keystore was refused, so nothing was counted", 0, prefs.getInt("keystore_failures", 0))
        assertNotNull(prefs.getString("priv", null))
        assertEquals(Crypto.b64(pair.public.encoded), prefs.getString("pub", null))
    }

    // ---- what a bad stored key does ------------------------------------------

    @Test
    fun `half a stored key is reported lost rather than replaced`() {
        val other = freshKeyPair()
        prefs.edit().putString("pub", Crypto.b64(other.public.encoded)).commit()
        val failure = assertThrows(IdentityKeyUnavailableException::class.java) {
            IdentityKeyPair.load(context, refusing(), keystoreOffered = true)
        }
        assertEquals(IdentityState.CORRUPT, IdentityKeyPair.state)
        assertEquals(
            "one half of a pair is not nothing; overwriting it makes the loss permanent",
            Crypto.b64(other.public.encoded),
            prefs.getString("pub", null)
        )
        assertNull(prefs.getString("priv", null))
        assertTrue(failure.message, failure.message!!.contains("Nothing was regenerated"))
        assertTrue(failure.message, failure.message!!.contains("Pair the other devices again"))
    }

    @Test
    fun `an unreadable entry does not cost a keystore identity`() {
        prefs.edit().putString("priv", "not base64 at all").commit()
        IdentityKeyPair.load(context, loadWith(freshKeyPair()), keystoreOffered = true)
        assertEquals(IdentityState.HEALTHY, IdentityKeyPair.state)
        assertEquals(
            "the keystore answered, so the junk in prefs is not this phone's identity and stays put",
            "not base64 at all",
            prefs.getString("priv", null)
        )
        assertEquals("keystore", prefs.getString("identity_store", null))
    }

    @Test
    fun `both halves present and both garbage still reach the keystore`() {
        // "neither string parsed" has to be read the same way as "only one string present": both are an
        // entry this phone cannot use, and neither is a reason to generate a second identity.
        prefs.edit().putString("priv", "zzzz").putString("pub", "zzzz").commit()
        val keystorePair = freshKeyPair()
        val pair = IdentityKeyPair.load(context, loadWith(keystorePair), keystoreOffered = true)
        assertEquals(Crypto.b64(keystorePair.public.encoded), Crypto.b64(pair.public.encoded))
        assertEquals(IdentityState.HEALTHY, IdentityKeyPair.state)
        assertEquals("zzzz", prefs.getString("priv", null))
        assertEquals("zzzz", prefs.getString("pub", null))
    }
}
