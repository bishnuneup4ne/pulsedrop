package com.pulsedrop.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The one long-lived secret of a PulseDrop phone: its P-256 identity key.
 *
 * The keystore alias and the prefs fallback keys are what every existing pairing points
 * at — renaming or re-issuing either one silently invalidates every paired phone. So this
 * is a rewrite of the *code*, and a frozen copy of the *material*.
 *
 * The material lives in exactly one of two places, and which one is not a preference:
 *
 * - **The keystore**, on every device that has one. Nothing of ours touches disk.
 * - **Preferences**, as base64 PKCS#8 — only on a device with no usable `AndroidKeyStore`
 *   provider, which is the device the fallback was written for.
 *
 * What used to decide between them was `catch (Exception)`, which cannot tell "this phone has
 * no keystore" from "this phone's keystore would not answer just now", and the second case fell
 * through to the first case's store. A transient refusal therefore wrote a brand new private key
 * to disk and presented it as the phone's identity: permanent, because the hardware key it
 * replaced is never read again once a prefs copy exists to shadow it. The refusal is now a state
 * of its own, and the rule it answers to is that an unreadable hardware key may come back, while
 * a raw key written in its place never does.
 */
object IdentityKeyPair {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "localdrop_e2e_identity"
    private const val PREFS_NAME = "localdrop_identity"
    private const val PRIV_PREF = "priv"
    private const val PUB_PREF = "pub"
    private const val TAG = "PulseDropIdentity"

    /**
     * What the hardware had to say, as four answers rather than two, because the fallback policy
     * turns on the difference between them: `Absent` may write prefs, `Refused` may not.
     *
     * `Empty` carries the generator instead of the key it would produce: writing the alias is the
     * keystore's to do, and whether it should be done at all depends on what prefs already holds —
     * a decision the caller makes, not this type.
     */
    internal sealed interface Hardware {

        /** The alias already held a key. That is this phone's identity. */
        data class Present(val keyPair: KeyPair) : Hardware

        /** The keystore works and has no alias yet. */
        data class Empty(val generate: () -> KeyPair) : Hardware

        /** No usable `AndroidKeyStore` provider: prefs is the only store this device has. */
        object Absent : Hardware

        /** The provider was there and then would not give up the key. Nothing durable may be written. */
        data class Refused(val cause: Exception) : Hardware
    }

    fun loadOrCreate(context: Context): KeyPair = loadOrCreate(context) { probeHardware() }

    internal fun loadOrCreate(context: Context, hardware: () -> Hardware): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (val probe = hardware()) {
            is Hardware.Present -> {
                // A raw copy beside the hardware key is not a backup. It is what one bad launch left
                // behind, and the pairing does not point at it — every launch since has served the
                // hardware key. Carrying it is a private key at rest for no reader at all.
                dropRawCopy(prefs)
                probe.keyPair
            }

            is Hardware.Empty -> {
                // No alias yet. If prefs holds an identity, this install has been presenting that key
                // and a raw key cannot be imported into the keystore, so minting a hardware one would
                // invalidate every pairing to buy nothing. Generate only where there is no identity.
                storedIdentity(prefs) ?: probe.generate()
            }

            Hardware.Absent -> {
                Log.w(TAG, "no $ANDROID_KEYSTORE provider on this device; the identity is a raw key in preferences")
                storedIdentity(prefs) ?: generateIntoPrefs(prefs)
            }

            is Hardware.Refused -> {
                Log.w(TAG, "$ANDROID_KEYSTORE refused $KEY_ALIAS; no key was written", probe.cause)
                // Serve the stored identity if there is one — replacing it here is how a fault that
                // heals by morning becomes a re-rooted pairing. If there is none, this process gets a
                // key it never writes down, and the next launch asks the hardware again.
                storedIdentity(prefs) ?: generateEphemeral()
            }
        }
    }

    /**
     * Asks the hardware, and reports which of the four states it is in.
     *
     * `getInstance` is the lookup that decides `Absent` from everything else, and it is the only
     * call here that is pure provider-table resolution — no daemon, no file, no binder — so
     * failing it means the implementation is not on this device, which is the fact the prefs
     * fallback exists for. Everything after it talks to the keystore service, so an exception from
     * those is a refusal, not an absence.
     */
    private fun probeHardware(): Hardware {
        val keystore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE)
        } catch (e: Exception) {
            return Hardware.Absent
        }
        return try {
            keystore.load(null)
            val entry = keystore.getEntry(KEY_ALIAS, null)
            if (entry is KeyStore.PrivateKeyEntry) {
                // The entry can exist with no certificate chain, and `publicKey` on null is the NPE
                // the old blanket catch read as "no keystore here".
                val certificate = keystore.getCertificate(KEY_ALIAS)
                    ?: throw IllegalStateException("$KEY_ALIAS has no certificate")
                Hardware.Present(KeyPair(certificate.publicKey, entry.privateKey))
            } else {
                Hardware.Empty { generateInKeystore(keystore) }
            }
        } catch (e: Exception) {
            Hardware.Refused(e)
        }
    }

    private fun generateInKeystore(keystore: KeyStore): KeyPair {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        gen.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return gen.generateKeyPair()
    }

    private fun storedIdentity(prefs: SharedPreferences): KeyPair? {
        val privB64 = prefs.getString(PRIV_PREF, null) ?: return null
        val pubB64 = prefs.getString(PUB_PREF, null) ?: return null
        val kf = KeyFactory.getInstance("EC")
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(Crypto.unb64(pubB64))),
            kf.generatePrivate(PKCS8EncodedKeySpec(Crypto.unb64(privB64)))
        )
    }

    private fun dropRawCopy(prefs: SharedPreferences) {
        if (!prefs.contains(PRIV_PREF) && !prefs.contains(PUB_PREF)) return
        prefs.edit().remove(PRIV_PREF).remove(PUB_PREF).apply()
    }

    /** The last resort for a phone that has never had an identity anywhere. */
    private fun generateEphemeral(): KeyPair = generateEc().also {
        Log.w(TAG, "serving a process-lifetime identity: already-paired devices will not recognise it")
    }

    private fun generateIntoPrefs(prefs: SharedPreferences): KeyPair {
        val pair = generateEc()
        prefs.edit()
            .putString(PRIV_PREF, Crypto.b64(pair.private.encoded))
            .putString(PUB_PREF, Crypto.b64(pair.public.encoded))
            .apply()
        return pair
    }

    private fun generateEc(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
}
