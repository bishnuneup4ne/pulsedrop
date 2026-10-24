package com.pulsedrop.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Enumeration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which store the phone's identity comes from, and what never gets written.
 *
 * One rule runs through this file: a raw private key may reach disk only on a device that has no
 * keystore to hold it, and an identity that already exists is never replaced by a fresh one. The
 * code used to answer both questions with `catch (Exception)`, which cannot tell a device with no
 * `AndroidKeyStore` from a device whose keystore would not answer just now — so a transient refusal
 * wrote a new PKCS#8 key into preferences, presented it as the phone, and shadowed the hardware key
 * it replaced.
 *
 * The four states arrive through [IdentityKeyPair.Hardware] rather than a real keystore, because
 * Robolectric registers no `AndroidKeyStore` provider to refuse on command — [probeReportsAbsenceForThisDevice]
 * pins what the production probe actually sees here, which is why injecting the answer is the honest
 * way to reach the other three. Same reason [com.pulsedrop.app.server.TabSession] exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IdentityKeyPairTest {

    /** The file every existing install's identity lives in. Renaming it here would break pairings. */
    private val prefsName = "localdrop_identity"

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun emptyRoom() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun storedIdentity(): KeyPair? {
        val priv = prefs.getString("priv", null) ?: return null
        val pub = prefs.getString("pub", null) ?: return null
        val kf = java.security.KeyFactory.getInstance("EC")
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(Crypto.unb64(pub))),
            kf.generatePrivate(PKCS8EncodedKeySpec(Crypto.unb64(priv)))
        )
    }

    /** Stand-in for a key the hardware is holding, and for one the prefs fallback once wrote. */
    private fun ecPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun plantInPrefs(): KeyPair {
        val pair = ecPair()
        prefs.edit()
            .putString("priv", Crypto.b64(pair.private.encoded))
            .putString("pub", Crypto.b64(pair.public.encoded))
            .apply()
        return pair
    }

    private fun load(hardware: IdentityKeyPair.Hardware): KeyPair =
        IdentityKeyPair.loadOrCreate(context) { hardware }

    // ---- the finding: a refusal is not an absence -------------------------------------------

    @Test
    fun aKeystoreThatRefusesWritesNoPrivateKeyToDisk() {
        val identity = load(IdentityKeyPair.Hardware.Refused(KeyStoreException("service dead")))

        assertNotNull("the phone still needs an identity to run on", identity)
        assertFalse("a transient refusal must not become a raw key at rest", prefs.contains("priv"))
        assertFalse("a transient refusal must not become a raw key at rest", prefs.contains("pub"))
    }

    @Test
    fun aRefusalWithAnIdentityAlreadyStoredKeepsServingThatIdentity() {
        val stored = plantInPrefs()

        val identity = load(IdentityKeyPair.Hardware.Refused(KeyStoreException("service dead")))

        assertArrayEquals(stored.public.encoded, identity.public.encoded)
        assertNotNull("the stored key is still there to be read next launch", storedIdentity())
    }

    @Test
    fun theEphemeralIdentityIsNotWrittenAndTheHealedHardwareWinsAfterIt() {
        // Two launches either side of a keystore that came back: the first gets a key it never writes
        // down, the second gets the hardware one, and nothing on disk let the first outlive the process.
        load(IdentityKeyPair.Hardware.Refused(KeyStoreException("service dead")))
        val hardware = ecPair()

        val second = load(IdentityKeyPair.Hardware.Present(hardware))

        assertArrayEquals(hardware.public.encoded, second.public.encoded)
        assertFalse(prefs.contains("priv"))
    }

    // ---- the residue the bug left behind ----------------------------------------------------

    @Test
    fun theHardwareIdentityWinsAndDeletesTheCopyThatCouldOnlyHaveComeFromTheBug() {
        val orphan = plantInPrefs()
        val hardware = ecPair()

        val identity = load(IdentityKeyPair.Hardware.Present(hardware))

        assertArrayEquals(hardware.public.encoded, identity.public.encoded)
        assertFalse("a raw key with no reader: the pairing points at the hardware alias", prefs.contains("priv"))
        assertFalse(prefs.contains("pub"))
        assertNull("the copy that went away is not what is being served now", storedIdentity())
        assertFalse(
            "the hardware key and the deleted copy were two different identities",
            java.util.Arrays.equals(orphan.public.encoded, identity.public.encoded)
        )
    }

    @Test
    fun aWorkingKeystoreWithNothingStoredLeavesPreferencesEmpty() {
        load(IdentityKeyPair.Hardware.Present(ecPair()))

        assertFalse("reading the hardware must not write prefs while deleting", prefs.contains("priv"))
        assertFalse(prefs.contains("pub"))
    }

    // ---- the fallback the legacy path still needs -------------------------------------------

    @Test
    fun aDeviceWithNoProviderStoresTheIdentityInPreferencesAndKeepsIt() {
        val first = load(IdentityKeyPair.Hardware.Absent)

        val raw = storedIdentity()
        assertNotNull("a phone with no keystore has nowhere else to put this", raw)
        assertArrayEquals(first.public.encoded, raw!!.public.encoded)
        val second = load(IdentityKeyPair.Hardware.Absent)
        assertArrayEquals("the identity survives a restart", first.public.encoded, second.public.encoded)
    }

    @Test
    fun aDeviceWithNoProviderKeepsAnIdentityAlreadyInPreferences() {
        val stored = plantInPrefs()

        val identity = load(IdentityKeyPair.Hardware.Absent)

        assertArrayEquals(stored.public.encoded, identity.public.encoded)
    }

    @Test
    fun anEmptyAliasDoesNotMintASecondIdentityOverAStoredOne() {
        val stored = plantInPrefs()

        val identity = load(
            IdentityKeyPair.Hardware.Empty {
                throw AssertionError("generating here would invalidate every pairing this phone has")
            }
        )

        assertArrayEquals(stored.public.encoded, identity.public.encoded)
    }

    @Test
    fun aPhoneWithNoIdentityAnywhereGeneratesIntoTheHardwareNotIntoPreferences() {
        val hardware = ecPair()
        var generated = 0

        val identity = load(
            IdentityKeyPair.Hardware.Empty {
                generated++
                hardware
            }
        )

        assertArrayEquals(hardware.public.encoded, identity.public.encoded)
        assertEquals(1, generated)
        assertFalse("a capable device must end up with no raw key at all", prefs.contains("priv"))
    }

    // ---- what the production probe can actually see here ------------------------------------

    @Test
    fun probeReportsAbsenceForThisDevice() {
        val providerIsMissing = runCatching { KeyStore.getInstance("AndroidKeyStore") }.isFailure

        val identity = IdentityKeyPair.loadOrCreate(context)

        // Robolectric registers no AndroidKeyStore, so the only path the real probe can take here is
        // the legacy one — which is also why the other states are driven through Hardware above.
        assertTrue("this test only means something while the provider is missing", providerIsMissing)
        assertNotNull(identity)
        val raw = storedIdentity()
        assertNotNull("and the legacy path is the one that ran", raw)
        assertArrayEquals(identity.public.encoded, raw!!.public.encoded)
    }

    /**
     * The finding, through the production entry point: a device that *has* an `AndroidKeyStore` and
     * cannot read its own alias.
     *
     * [probeReportsAbsenceForThisDevice] shows Robolectric has no such provider to refuse, so this
     * installs one that does — and that is what makes the test worth more than the injected seam:
     * the classification inside `probeHardware` is the half that decided the old behavior, and it is
     * the half an injected `Hardware` value cannot be wrong about. If the provider were never
     * consulted the code would take the legacy branch and write the key, and the assertion below
     * would go red for exactly that reason.
     */
    @Test
    fun aDeviceWithAKeystoreThatCannotReadItsOwnAliasWritesNothing() {
        val provider = RefusingAndroidKeyStoreProvider()
        check(Security.addProvider(provider) > 0) { "the test provider would not install" }
        try {
            // Consulted, and refusing: which is what the probe has to tell apart from absence.
            assertNotNull(KeyStore.getInstance("AndroidKeyStore"))

            val first = IdentityKeyPair.loadOrCreate(context)
            val second = IdentityKeyPair.loadOrCreate(context)

            assertNotNull("the phone still has an identity to pair with", first)
            assertNotNull("and it still has one the next time the hardware refuses", second)
            assertFalse("no raw private key on a device whose keystore merely had a bad moment", prefs.contains("priv"))
            assertFalse(prefs.contains("pub"))
        } finally {
            Security.removeProvider(provider.name)
        }
    }

    class RefusingAndroidKeyStoreProvider : Provider("refusing-android-keystore", 1.0, "test only") {
        init {
            put("KeyStore.AndroidKeyStore", RefusingKeyStoreSpi::class.java.name)
        }
    }

    /** A keystore that opens and then will not give up the entry — the transient failure shape. */
    class RefusingKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetEntry(alias: String, param: KeyStore.ProtectionParameter?): KeyStore.Entry =
            throw UnrecoverableEntryException("keystore service went away")

        override fun engineGetKey(alias: String, password: CharArray?): Key =
            throw UnrecoverableKeyException("keystore service went away")

        override fun engineContainsAlias(alias: String) = true
        override fun engineGetCertificate(alias: String): java.security.cert.Certificate? = null
        override fun engineGetCertificateChain(alias: String): Array<java.security.cert.Certificate>? = null
        override fun engineGetCreationDate(alias: String): java.util.Date = java.util.Date(0)
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out java.security.cert.Certificate>?) =
            throw KeyStoreException("read-only in this test")
        override fun engineSetKeyEntry(alias: String, data: ByteArray, chain: Array<out java.security.cert.Certificate>?) =
            throw KeyStoreException("read-only in this test")
        override fun engineSetCertificateEntry(alias: String, cert: java.security.cert.Certificate?) =
            throw KeyStoreException("read-only in this test")
        override fun engineDeleteEntry(alias: String) = throw KeyStoreException("read-only in this test")
        override fun engineAliases(): Enumeration<String> = java.util.Collections.emptyEnumeration()
        override fun engineSize() = 0
        override fun engineIsKeyEntry(alias: String) = true
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: java.security.cert.Certificate?) = null as String?
        override fun engineStore(stream: OutputStream?, password: CharArray?) =
            throw KeyStoreException("nothing is stored in this test")
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }
}
