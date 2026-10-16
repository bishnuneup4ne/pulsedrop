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
 * The one long-lived secret of a PulseDrop phone: its P-256 identity key.
 *
 * The keystore alias and the prefs fallback keys are what every existing pairing points
 * at — renaming or re-issuing either one silently invalidates every paired phone. So this
 * is a rewrite of the *code*, and a frozen copy of the *material*.
 */
object IdentityKeyPair {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "localdrop_e2e_identity"
    private const val PREFS_NAME = "localdrop_identity"

    fun loadOrCreate(context: Context): KeyPair =
        (try { fromKeystore() } catch (e: Exception) { null }) ?: fromPrefsOrGenerate(context)

    private fun fromKeystore(): KeyPair? {
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

    /** Some devices refuse AndroidKeyStore outright; the identity must survive anyway. */
    private fun fromPrefsOrGenerate(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privB64 = prefs.getString("priv", null)
        val pubB64 = prefs.getString("pub", null)
        if (privB64 != null && pubB64 != null) {
            val kf = KeyFactory.getInstance("EC")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Crypto.unb64(pubB64))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Crypto.unb64(privB64)))
            )
        }
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        prefs.edit()
            .putString("priv", Crypto.b64(pair.private.encoded))
            .putString("pub", Crypto.b64(pair.public.encoded))
            .apply()
        return pair
    }
}
