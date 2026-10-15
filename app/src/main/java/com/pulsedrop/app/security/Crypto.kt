package com.pulsedrop.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2E primitives for paired-device channels:
 * identity key (EC P-256) -> ECDH -> SHA-256 -> AES-256-GCM.
 */
object Crypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "localdrop_e2e_identity"
    const val CHUNK_PLAIN_SIZE = 64 * 1024
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    /** Stable device identity. Keystore-backed; falls back to prefs-stored raw keys. */
    fun getIdentity(context: Context): KeyPair {
        cachedKeyPair?.let { return it }
        synchronized(this) {
            cachedKeyPair?.let { return it }
            val fromKeystore = try {
                loadOrCreateKeystorePair()
            } catch (e: Exception) {
                null
            }
            val pair = fromKeystore ?: loadOrCreateFallbackPair(context)
            cachedKeyPair = pair
            return pair
        }
    }

    private fun loadOrCreateKeystorePair(): KeyPair? {
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val aliasEntry = ks.getEntry(KEY_ALIAS, null)
        if (aliasEntry is java.security.KeyStore.PrivateKeyEntry) {
            return KeyPair(ks.getCertificate(KEY_ALIAS).publicKey, aliasEntry.privateKey)
        }
        val gen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY
        ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        gen.initialize(spec)
        return gen.generateKeyPair()
    }

    private fun loadOrCreateFallbackPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences("localdrop_identity", Context.MODE_PRIVATE)
        val privB64 = prefs.getString("priv", null)
        val pubB64 = prefs.getString("pub", null)
        if (privB64 != null && pubB64 != null) {
            val kf = KeyFactory.getInstance("EC")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(unb64(pubB64))),
                kf.generatePrivate(PKCS8EncodedKeySpec(unb64(privB64)))
            )
        }
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        prefs.edit()
            .putString("priv", b64(pair.private.encoded))
            .putString("pub", b64(pair.public.encoded))
            .apply()
        return pair
    }

    fun publicKeyB64(context: Context): String = b64(getIdentity(context).public.encoded)

    fun parsePublicKey(spkiB64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(spkiB64)))

    /** Deterministic AES-256 key from ECDH secret, symmetric on both sides. */
    fun sharedKey(myPrivate: PrivateKey, peerPublic: PublicKey, myPub: ByteArray, peerPub: ByteArray): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(peerPublic, true)
        val secret = ka.generateSecret()
        val order = if (compareUnsigned(myPub, peerPub) <= 0) myPub + peerPub else peerPub + myPub
        val salted = sha256(secret) + sha256(order) + "LocalDrop-v1".toByteArray()
        return SecretKeySpec(sha256(salted), "AES")
    }

    fun sharedKeyFor(context: Context, peerSpkiB64: String): SecretKeySpec {
        val me = getIdentity(context)
        val peer = parsePublicKey(peerSpkiB64)
        return sharedKey(me.private, peer, me.public.encoded, peer.encoded)
    }

    /** Symmetric pairing id: same value computed on both devices. */
    fun pairId(myPubB64: String, peerPubB64: String): String {
        val a = unb64(myPubB64)
        val b = unb64(peerPubB64)
        val order = if (compareUnsigned(a, b) <= 0) a + b else b + a
        return sha256(order).takeFirstHex(12)
    }

    /**
     * Stable id for one *device* rather than one *pair*. A relay has to address a mailbox by
     * who the mail is for, and pair ids are pairwise — a device has a different one with every
     * peer. Derived from the identity public key, so anyone who has that key (which pairing
     * hands over) can compute it for themselves and nothing has to be trusted to the host.
     */
    fun deviceId(pubB64: String): String = sha256(unb64(pubB64)).takeFirstHex(16)

    /** Human-verifiable short code shown during pairing confirmation. */
    fun safetyCode(myPubB64: String, peerPubB64: String): String {
        val a = unb64(myPubB64)
        val b = unb64(peerPubB64)
        val order = if (compareUnsigned(a, b) <= 0) a + b else b + a
        return safetyCodeFromHash(sha256(order))
    }

    internal fun safetyCodeFromHash(h: ByteArray): String {
        // `shl` and `or` are same-precedence infix functions, so without these groups the
        // expression folds left and h[0] shifts out of the Int entirely.
        val n = (((h[0].toInt() and 0xFF) shl 20) or
            ((h[1].toInt() and 0xFF) shl 12) or
            ((h[2].toInt() and 0xFF) shl 4) or
            (h[3].toInt() and 0x0F)) % 1000000
        return "%06d".format(n)
    }

    fun encrypt(key: SecretKeySpec, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv to cipher.doFinal(plain)
    }

    fun decrypt(key: SecretKeySpec, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun encryptText(key: SecretKeySpec, text: String): Pair<String, String> {
        val (iv, ct) = encrypt(key, text.toByteArray(Charsets.UTF_8))
        return b64(iv) to b64(ct)
    }

    fun decryptText(key: SecretKeySpec, ivB64: String, ctB64: String): String =
        String(decrypt(key, unb64(ivB64), unb64(ctB64)), Charsets.UTF_8)

    /**
     * Chunked stream encryption for file transfer: each plaintext chunk (<= 64 KiB)
     * becomes [u32 recordLen][iv 12][gcm ct+tag]. Receiver can decrypt streaming.
     */
    fun encryptChunked(key: SecretKeySpec, input: InputStream, output: OutputStream) {
        val dout = DataOutputStream(output)
        val plain = ByteArray(CHUNK_PLAIN_SIZE)
        val random = SecureRandom()
        while (true) {
            var read = 0
            while (read < CHUNK_PLAIN_SIZE) {
                val n = input.read(plain, read, CHUNK_PLAIN_SIZE - read)
                if (n == -1) break
                read += n
            }
            if (read == 0) break
            val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val ct = cipher.doFinal(plain, 0, read)
            dout.writeInt(iv.size + ct.size)
            dout.write(iv)
            dout.write(ct)
            if (read < CHUNK_PLAIN_SIZE) break
        }
        dout.flush()
    }

    fun decryptChunked(key: SecretKeySpec, input: InputStream, output: OutputStream) {
        val din = DataInputStream(input)
        val buf = ByteArray(CHUNK_PLAIN_SIZE + 32 + IV_SIZE)
        while (true) {
            val len = try {
                din.readInt()
            } catch (e: java.io.EOFException) {
                break
            }
            if (len <= 0 || len > buf.size) throw java.io.IOException("Bad encrypted record")
            din.readFully(buf, 0, len)
            val iv = buf.copyOfRange(0, IV_SIZE)
            val ct = buf.copyOfRange(IV_SIZE, len)
            val plain = decrypt(key, iv, ct)
            output.write(plain)
        }
        output.flush()
    }

    fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun ByteArray.takeFirstHex(n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(n / 2, size)) sb.append("%02x".format(this[i]))
        return sb.toString()
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
