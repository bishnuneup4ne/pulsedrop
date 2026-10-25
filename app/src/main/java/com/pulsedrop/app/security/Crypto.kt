package com.pulsedrop.app.security

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2E primitives for paired-device channels: identity key (EC P-256) -> ECDH -> KDF ->
 * AES-256-GCM. The derivation itself lives in [Kdf] — v1 stays byte-frozen for existing
 * pairs, v2 is HKDF for new ones; everything here either agrees with a golden vector
 * (`CryptoGoldenVectorsTest`) or is structurally free to change.
 */
object Crypto {

    const val CHUNK_PLAIN_SIZE = 64 * 1024
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** One instance is fine — SecureRandom is thread-safe and reseeding costs entropy. */
    private val random = SecureRandom()

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    // ---- identity ----------------------------------------------------------

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    /** Stable device identity. Keystore-backed; falls back to prefs-stored raw keys. */
    fun getIdentity(context: Context): KeyPair {
        cachedKeyPair?.let { return it }
        synchronized(this) {
            cachedKeyPair?.let { return it }
            val pair = IdentityKeyPair.loadOrCreate(context)
            cachedKeyPair = pair
            return pair
        }
    }

    fun publicKeyB64(context: Context): String = b64(getIdentity(context).public.encoded)

    fun parsePublicKey(spkiB64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(spkiB64)))

    private val P256_PARAMS: ECParameterSpec? = runCatching {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }.getOrNull()

    /**
     * Whether [spkiB64] is a P-256 key this phone can actually agree with.
     *
     * Pairing stores the peer's key before any message is exchanged, so a key on another curve or a
     * truncated encoding would not fail until the first send — after the user has confirmed the
     * pairing and seen a safety code computed from bytes that cannot work. Ingest checks this
     * instead; it is not used on the send path, where the key came from storage and a failure is a
     * different (already surfaced) condition.
     */
    fun isP256PublicKey(spkiB64: String): Boolean = try {
        val key = parsePublicKey(spkiB64) as? ECPublicKey ?: return false
        val expected = P256_PARAMS ?: return false
        val p = key.params
        p.curve.field.fieldSize == expected.curve.field.fieldSize &&
            p.curve.a == expected.curve.a &&
            p.curve.b == expected.curve.b &&
            p.order == expected.order &&
            p.cofactor == expected.cofactor &&
            p.generator == expected.generator
    } catch (e: Exception) {
        false
    }

    // ---- shared secrets ------------------------------------------------------

    /** ECDH to the raw shared point, without deriving anything yet. */
    private fun ecdh(myPrivate: PrivateKey, peerPublic: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(peerPublic, true)
        return ka.generateSecret()
    }

    /** Deterministic AES-256 key from ECDH, symmetric on both sides, by frozen version. */
    fun sharedKey(
        myPrivate: PrivateKey,
        peerPublic: PublicKey,
        myPub: ByteArray,
        peerPub: ByteArray,
        version: Int = Kdf.V1
    ): SecretKeySpec = Kdf.derive(ecdh(myPrivate, peerPublic), Kdf.orderedSpki(myPub, peerPub), version)

    fun sharedKeyFor(context: Context, peerSpkiB64: String, version: Int = Kdf.V1): SecretKeySpec {
        val me = getIdentity(context)
        val peer = parsePublicKey(peerSpkiB64)
        return sharedKey(me.private, peer, me.public.encoded, peer.encoded, version)
    }

    // ---- symmetric identifiers -----------------------------------------------

    /** Symmetric pairing id: same value computed on both devices. */
    fun pairId(myPubB64: String, peerPubB64: String): String {
        val a = unb64(myPubB64)
        val b = unb64(peerPubB64)
        return sha256(Kdf.orderedSpki(a, b)).takeFirstHex(12)
    }

    /**
     * Stable id for one *device* rather than one *pair*. Derived from the identity public
     * key alone, so anyone holding that key can compute it and nothing is trusted to a host.
     */
    fun deviceId(pubB64: String): String = sha256(unb64(pubB64)).takeFirstHex(16)

    /** Human-verifiable short code shown during pairing confirmation. */
    fun safetyCode(myPubB64: String, peerPubB64: String): String {
        val order = Kdf.orderedSpki(unb64(myPubB64), unb64(peerPubB64))
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

    // ---- AES-GCM records ------------------------------------------------------

    fun encrypt(key: SecretKeySpec, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
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

    // ---- chunked file crypto ----------------------------------------------------

    /**
     * Chunked stream encryption for file transfer: each plaintext chunk (<= 64 KiB)
     * becomes [u32 recordLen][iv 12][gcm ct+tag]. Layout frozen since v1.7; both ends
     * must keep producing and reading exactly this.
     */
    fun encryptChunked(key: SecretKeySpec, input: InputStream, output: OutputStream) {
        val dout = DataOutputStream(output)
        // Buffer is per-call: two transfers may seal at the same time.
        val plain = ByteArray(CHUNK_PLAIN_SIZE)
        while (true) {
            var read = 0
            while (read < CHUNK_PLAIN_SIZE) {
                val n = input.read(plain, read, CHUNK_PLAIN_SIZE - read)
                if (n == -1) break
                read += n
            }
            if (read == 0) break
            val iv = ByteArray(IV_SIZE).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val ct = cipher.doFinal(plain, 0, read)
            dout.writeInt(iv.size + ct.size)
            dout.write(iv)
            dout.write(ct)
            // A short fill means EOF mid-chunk: this was the last piece.
            if (read < CHUNK_PLAIN_SIZE) break
        }
        dout.flush()
    }

    fun decryptChunked(key: SecretKeySpec, input: InputStream, output: OutputStream) {
        val din = DataInputStream(input)
        // One tag block and one IV of slack past the plaintext cap is the widest legal record.
        val record = ByteArray(CHUNK_PLAIN_SIZE + TAG_BITS / 8 + IV_SIZE)
        while (true) {
            val len = try {
                din.readInt()
            } catch (e: java.io.EOFException) {
                break
            }
            if (len <= 0 || len > record.size) throw java.io.IOException("Bad encrypted record")
            din.readFully(record, 0, len)
            val iv = record.copyOfRange(0, IV_SIZE)
            val plain = decrypt(key, iv, record.copyOfRange(IV_SIZE, len))
            output.write(plain)
        }
        output.flush()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun ByteArray.takeFirstHex(n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(n / 2, size)) sb.append("%02x".format(this[i]))
        return sb.toString()
    }
}
