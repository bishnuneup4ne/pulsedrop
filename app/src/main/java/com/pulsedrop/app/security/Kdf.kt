package com.pulsedrop.app.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Key derivation for paired-device channels. Two versions coexist on purpose:
 *
 * - **v1** is the formula every pairing made before v2.0 was built with. It is frozen the
 *   same way a wire format is: existing pairs keep using it forever, because their secret
 *   is re-derived at runtime from the ECDH point, never stored.
 * - **v2** replaces the ad-hoc concat-hash with HKDF-SHA256 (RFC 5869) and domain separation
 *   via the `info` label. Only pairs created after both ends advertise `kdf-v2` use it.
 */
object Kdf {

    const val V1 = 1
    const val V2 = 2

    private const val V1_LABEL = "LocalDrop-v1"
    private const val V2_INFO = "pulsedrop-kdf-v2|pair"

    /** Ordered SPKI concatenation both ends can compute without agreeing on an order first. */
    fun orderedSpki(a: ByteArray, b: ByteArray): ByteArray =
        if (compareUnsigned(a, b) <= 0) a + b else b + a

    fun derive(sharedSecret: ByteArray, ordered: ByteArray, version: Int): SecretKeySpec = when (version) {
        V1 -> v1(sharedSecret, ordered)
        V2 -> v2(sharedSecret, ordered)
        else -> throw IllegalArgumentException("unknown kdf version $version")
    }

    /** Frozen: sha256(secret) || sha256(ordered) || "LocalDrop-v1", hashed once. */
    fun v1(sharedSecret: ByteArray, ordered: ByteArray): SecretKeySpec {
        val salted = sha256(sharedSecret) + sha256(ordered) + V1_LABEL.toByteArray(Charsets.UTF_8)
        return SecretKeySpec(sha256(salted), "AES")
    }

    /** HKDF-SHA256: extract(salt=sha256(ordered), ikm=secret) then one 32-byte expand block. */
    fun v2(sharedSecret: ByteArray, ordered: ByteArray): SecretKeySpec {
        val prk = hmac(sha256(ordered), sharedSecret)
        val okm = hmac(prk, V2_INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
        return SecretKeySpec(okm, "AES")
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
