package com.pulsedrop.app.security

import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The properties that make the two derivation versions usable at the same time: both are
 * order-independent, both are deterministic, and they never produce the same key from the
 * same material — a v1 peer must not accidentally speak v2 and vice versa.
 */
class KdfTest {

    private val secret = ByteArray(32) { (it * 3 + 1).toByte() }
    private val spkiA = ByteArray(91) { (it % 251).toByte() }
    private val spkiB = ByteArray(91) { (it % 197).toByte() }

    @Test
    fun orderedSpkiIsSymmetric() {
        assertArrayEquals(Kdf.orderedSpki(spkiA, spkiB), Kdf.orderedSpki(spkiB, spkiA))
    }

    @Test
    fun bothVersionsAreDeterministicAndVersionedApart() {
        val ordered = Kdf.orderedSpki(spkiA, spkiB)
        assertArrayEquals(Kdf.v1(secret, ordered).encoded, Kdf.derive(secret, ordered, Kdf.V1).encoded)
        assertArrayEquals(Kdf.v2(secret, ordered).encoded, Kdf.derive(secret, ordered, Kdf.V2).encoded)
        // SecretKeySpec has no equals(), so compare the bytes the phones will compare.
        assertNotEquals(
            "v1 and v2 must never collide",
            Kdf.v1(secret, ordered).encoded.joinToString(""),
            Kdf.v2(secret, ordered).encoded.joinToString("")
        )
        assertEquals(32, Kdf.v2(secret, ordered).encoded.size)
    }

    @Test
    fun v1MatchesTheFormulaEveryOldBuildComputes() {
        val ordered = Kdf.orderedSpki(spkiA, spkiB)
        val sha = MessageDigest.getInstance("SHA-256")
        val expected = sha.digest(
            sha.digest(secret) + sha.digest(ordered) + "LocalDrop-v1".toByteArray()
        )
        assertArrayEqualsHex(expected, Kdf.v1(secret, ordered).encoded)
    }

    /** HKDF-SHA256 cross-checked against an independent one-shot computation. */
    @Test
    fun v2IsHkdfExtractThenExpand() {
        val ordered = Kdf.orderedSpki(spkiA, spkiB)
        val salt = MessageDigest.getInstance("SHA-256").digest(ordered)
        val prk = hmac(salt, secret)
        val okm = hmac(prk, "pulsedrop-kdf-v2|pair".toByteArray() + byteArrayOf(0x01))
        assertArrayEqualsHex(okm, Kdf.v2(secret, ordered).encoded)
    }

    @Test
    fun unknownVersionIsRefusedLoudly() {
        try {
            Kdf.derive(secret, Kdf.orderedSpki(spkiA, spkiB), 3)
            throw AssertionError("unknown kdf version must not be silently accepted")
        } catch (expected: IllegalArgumentException) {
            // the only safe outcome
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun assertArrayEqualsHex(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.joinToString("") { "%02x".format(it) }, actual.joinToString("") { "%02x".format(it) })
    }
}
