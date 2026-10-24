package com.pulsedrop.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Where this phone's identity key lives, as of the last load. The words are a diagnosis, not a
 * severity: only [HEALTHY] means nothing readable is stored about this identity.
 */
enum class IdentityState {
    /** Not looked at yet. Reported by a phone that has not paired, signed or been asked. */
    NOT_LOADED,

    /** AndroidKeyStore holds the key. */
    HEALTHY,

    /** A raw PKCS#8 key in SharedPreferences is in use. Either this device cannot do keystore at all,
     * or it failed often enough to be believed. These prefs are excluded from every backup path. */
    LEGACY_FILE,

    /** Preferences hold something that will not parse as a key, and the keystore has none either.
     * The identity is gone; it is reported rather than replaced. */
    CORRUPT,

    /** The keystore refused this time and nothing was stored. Nothing was written in its place, and the
     * next launch asks again — which is the whole point: a temporary keystore failure must not turn a
     * hardware-backed identity into a readable one. */
    UNAVAILABLE
}

/**
 * This phone has no usable identity key, and minting one was the worse option.
 *
 * A fresh key is not a recovery. Every pairing on every peer points at the public key this phone
 * already advertised, so a new identity keeps the app working — it pairs, it chats, transfers complete —
 * while every existing pairing fails to decrypt, with nothing on screen to say why. A refusal is
 * inconvenient and honest; a replacement is neither.
 */
class IdentityKeyUnavailableException(reason: String) : Exception(reason)

/**
 * The one long-lived secret of a PulseDrop phone: its P-256 identity key.
 *
 * The keystore alias and the prefs fallback keys are what every existing pairing points
 * at — renaming or re-issuing either one silently invalidates every paired phone. So this
 * is a rewrite of the *code*, and a frozen copy of the *material*.
 *
 * The rule the whole file follows from that: **one identity per phone, whichever was written first,
 * and never a second one generated while the first still exists.** Reading order is prefs, then
 * keystore — so a device whose keystore hiccups once and fell back to a file key keeps that key after
 * the keystore recovers rather than silently swapping identities. The cost of that choice is written
 * up in docs/operations/Issue-Backlog.md under S-6: a legacy key stays file-backed until something
 * moves it into a working keystore, which is what [KEY_STORE_PREF] is there to make findable.
 */
object IdentityKeyPair {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "localdrop_e2e_identity"
    private const val PREFS_NAME = "localdrop_identity"

    private const val PRIV_PREF = "priv"
    private const val PUB_PREF = "pub"

    /** Which store this identity came from, written so a later version can offer a migration without
     * guessing. A marker, never key material. */
    private const val KEY_STORE_PREF = "identity_store"
    private const val MARKER_KEYSTORE = "keystore"
    private const val MARKER_LEGACY = "legacy-file"

    /** Launches on which the keystore was asked and refused, before the device is believed to lack it.
     * Some hardware genuinely cannot do this, and a phone that can never pair is the worse failure. */
    private const val FAILURES_PREF = "keystore_failures"
    private const val PERMANENT_AFTER = 3

    /**
     * Whether this process has recorded a refusal already, which is what makes [FAILURES_PREF] count
     * launches rather than taps: a locked keystore is one refusal, and someone pressing Accept three
     * times has not handed the app three pieces of evidence about their hardware. Internal so the
     * counter can be driven from a test, which is the only reason it is not private.
     */
    @Volatile
    internal var countedRefusalThisLaunch = false

    /** The last load's outcome, for diagnostics. Set on every path, including the ones that throw. */
    @Volatile
    var state: IdentityState = IdentityState.NOT_LOADED
        private set

    /**
     * The sentence to show when something could not be done because this phone has no identity key.
     *
     * One place, because the answer differs by failure and a caller cannot know which: a keystore that
     * is locked comes back, a key that cannot be parsed does not. Written for the person holding the
     * phone, since it arrives when they tap Accept or Send.
     */
    fun explain(): String = when (state) {
        IdentityState.CORRUPT ->
            "This phone's saved identity key cannot be read, and the Android keystore has none either. " +
                "Nothing was regenerated — a new key would break every existing pairing without saying " +
                "so. Pair the other devices again."
        else ->
            "This phone cannot read its identity key right now; the Android keystore refused it and " +
                "nothing was written in its place. Unlock the device and try again."
    }

    /**
     * The one production entry point. Whether this environment offers an AndroidKeyStore at all is
     * asked once, here, and carried down: on a phone the provider is registered by the framework at
     * any API level that has one, so the answer is always yes and the counter in [decline] is what
     * decides a device's fate. Anywhere else the answer is no, and it means "no keystore exists",
     * which is not a failure to wait out.
     */
    fun loadOrCreate(context: Context): KeyPair =
        load(context, { fromKeystore() }, java.security.Security.getProvider(ANDROID_KEYSTORE) != null)

    /**
     * The load, with both the keystore and the question of whether one exists behind parameters, so
     * that a device which answers, a device which refuses for a launch and a place with no keystore
     * at all can each be tested without a device.
     */
    internal fun load(
        context: Context,
        keystore: () -> KeyPair,
        keystoreOffered: Boolean
    ): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (val stored = readStored(prefs)) {
            is Stored.Key -> {
                // This phone's identity was written to prefs once, so it is the identity — whatever the
                // keystore would say now. Nothing new is written here, and nothing is regenerated.
                state = IdentityState.LEGACY_FILE
                mark(prefs, MARKER_LEGACY)
                stored.pair
            }

            is Stored.Unusable -> loadFromKeystore(prefs, keystore, stored, keystoreOffered)

            Stored.Absent -> loadFromKeystore(prefs, keystore, null, keystoreOffered)
        }
    }

    private fun loadFromKeystore(
        prefs: android.content.SharedPreferences,
        keystore: () -> KeyPair,
        corrupt: Stored.Unusable?,
        keystoreOffered: Boolean
    ): KeyPair {
        val pair = try {
            keystore()
        } catch (keystoreFailure: Exception) {
            return decline(prefs, keystoreFailure, corrupt, keystoreOffered)
        }
        if (corrupt != null) {
            // The keystore answered, so the unreadable prefs entry is not this phone's identity and the
            // pairings still work. Left in place (`decline` explains why) but worth a line in logcat:
            // it is the one state here that no screen would otherwise show.
            android.util.Log.w("IdentityKeyPair", "Ignoring an unreadable stored identity (${corrupt.reason}); keystore answered")
        }
        state = IdentityState.HEALTHY
        mark(prefs, MARKER_KEYSTORE)
        prefs.edit().putInt(FAILURES_PREF, 0).apply()
        return pair
    }

    /**
     * Neither store produced a key. What happens next depends entirely on which of them failed and why,
     * which is the distinction this whole function exists to make: `loadOrCreate` used to swallow any
     * exception from the keystore and fall through to writing a raw private key, so a phone whose
     * keystore was merely locked — before first unlock, after a failed biometric enrollment, for one
     * launch — permanently traded a hardware-backed identity for a file anyone with the dump can read.
     */
    private fun decline(
        prefs: android.content.SharedPreferences,
        keystoreFailure: Exception,
        corrupt: Stored.Unusable?,
        keystoreOffered: Boolean
    ): KeyPair {
        val detail = keystoreFailure.javaClass.simpleName + ": " + keystoreFailure.message +
            (corrupt?.let { "; stored entry is unreadable (${it.reason})" } ?: "")
        android.util.Log.w("IdentityKeyPair", "No identity key available: $detail")
        if (corrupt != null) {
            // Both stores are out. The prefs entry is not overwritten, because "unreadable" is not the
            // same as "absent" and a repair that deletes the only copy of the old key makes the loss
            // permanent; the app reports the identity as gone instead of minting a working-looking one.
            state = IdentityState.CORRUPT
            throw IdentityKeyUnavailableException(explain())
        }
        if (!keystoreOffered) {
            // Not a refusal: this environment never offered a keystore to refuse. Counting here would
            // only mean three pointless throws before the one identity this phone gets is finally made.
            return generateInto(prefs, "this environment has no AndroidKeyStore provider")
        }
        if (!countedRefusalThisLaunch) {
            countedRefusalThisLaunch = true
            prefs.edit().putInt(FAILURES_PREF, prefs.getInt(FAILURES_PREF, 0) + 1).apply()
        }
        val failures = prefs.getInt(FAILURES_PREF, 0)
        if (failures < PERMANENT_AFTER) {
            state = IdentityState.UNAVAILABLE
            throw IdentityKeyUnavailableException(explain())
        }
        // Believed: this device cannot do keystore. Keep the identity file-backed and say so, rather
        // than leaving the phone unable to pair at all. `explain` has no branch for this, because there
        // is nothing left to explain: the phone has an identity, it is just readable.
        return generateInto(prefs, "the keystore refused it on $failures launches ($detail)")
    }

    private fun generateInto(prefs: android.content.SharedPreferences, why: String): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        prefs.edit()
            .putString(PRIV_PREF, Crypto.b64(pair.private.encoded))
            .putString(PUB_PREF, Crypto.b64(pair.public.encoded))
            .putString(KEY_STORE_PREF, MARKER_LEGACY)
            .apply()
        state = IdentityState.LEGACY_FILE
        android.util.Log.w("IdentityKeyPair", "Identity key is file-backed because $why")
        return pair
    }

    private fun mark(prefs: android.content.SharedPreferences, marker: String) {
        if (prefs.getString(KEY_STORE_PREF, null) != marker) {
            prefs.edit().putString(KEY_STORE_PREF, marker).apply()
        }
    }

    /** What prefs hold about an identity, before anything is generated. */
    private sealed interface Stored {
        data object Absent : Stored
        data class Key(val pair: KeyPair) : Stored
        /** Present but not a key pair: half written, tampered with, or restored from another device. */
        data class Unusable(val reason: String) : Stored
    }

    private fun readStored(prefs: android.content.SharedPreferences): Stored {
        val privB64 = prefs.getString(PRIV_PREF, null)
        val pubB64 = prefs.getString(PUB_PREF, null)
        if (privB64 == null && pubB64 == null) return Stored.Absent
        if (privB64 == null || pubB64 == null) {
            // One half of a pair is not a key, and it is not nothing either. It used to be treated as
            // nothing, and the code overwrote it with a freshly generated pair.
            return Stored.Unusable("only ${if (privB64 == null) PRIV_PREF else PUB_PREF} is stored")
        }
        return try {
            val kf = KeyFactory.getInstance("EC")
            Stored.Key(
                KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(Crypto.unb64(pubB64))),
                    kf.generatePrivate(PKCS8EncodedKeySpec(Crypto.unb64(privB64)))
                )
            )
        } catch (e: Exception) {
            Stored.Unusable(e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun fromKeystore(): KeyPair {
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val entry = ks.getEntry(KEY_ALIAS, null)
        if (entry is java.security.KeyStore.PrivateKeyEntry) {
            return KeyPair(ks.getCertificate(KEY_ALIAS).publicKey, entry.privateKey)
        }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        gen.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return gen.generateKeyPair()
    }
}
