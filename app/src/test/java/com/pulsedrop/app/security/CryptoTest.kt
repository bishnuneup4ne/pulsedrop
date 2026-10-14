package com.pulsedrop.app.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pairing channel between two phones is built here, so these tests cover the properties that
 * make it worth trusting: that both ends land on the same key without any agreement round, that
 * the safety code a human compares actually covers the whole exchanged key material, and that a
 * wrong key or edited ciphertext fails instead of returning garbage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CryptoTest {

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    @Test
    fun `every one of the four hash bytes changes the safety code`() {
        val base = ByteArray(32) { (it * 7 + 3).toByte() }
        val reference = Crypto.safetyCodeFromHash(base)
        for (i in 0..3) {
            // Only the low nibble of the fourth byte survives the truncation, so flip that bit.
            val mask = if (i == 3) 0x01 else 0x40
            val altered = base.copyOf().also { it[i] = (it[i].toInt() xor mask).toByte() }
            assertNotEquals(
                "hash byte $i must participate in the code, or a forger gets it for free",
                reference,
                Crypto.safetyCodeFromHash(altered),
            )
        }
    }

    @Test
    fun `both peers show the same code and pairing id without exchanging an order`() {
        val a = Crypto.b64(ecKeyPair().public.encoded)
        val b = Crypto.b64(ecKeyPair().public.encoded)
        assertEquals(Crypto.safetyCode(a, b), Crypto.safetyCode(b, a))
        assertEquals(Crypto.pairId(a, b), Crypto.pairId(b, a))
        assertTrue(Crypto.safetyCode(a, b).matches(Regex("\\d{6}")))
        assertEquals(12, Crypto.pairId(a, b).length)
    }

    @Test
    fun `ecdh agreement gives both ends the same aes key`() {
        val alice = ecKeyPair()
        val bob = ecKeyPair()
        val apub = alice.public.encoded
        val bpub = bob.public.encoded
        val kA = Crypto.sharedKey(alice.private, bob.public, apub, bpub)
        val kB = Crypto.sharedKey(bob.private, alice.public, bpub, apub)
        assertEquals("derived keys must be byte-identical", kA, kB)
        assertEquals(32, kA.encoded.size)
    }

    @Test
    fun `a channel sealed by one pair is unreadable to another`() {
        val alice = ecKeyPair()
        val bob = ecKeyPair()
        val eve = ecKeyPair()
        val key = keyOf(alice, bob)
        val wrongKey = keyOf(alice, eve)
        val (iv, ct) = Crypto.encrypt(key, "launch codes".toByteArray())
        assertEquals("launch codes", String(Crypto.decrypt(key, iv, ct)))
        try {
            Crypto.decrypt(wrongKey, iv, ct)
            fail("a third keypair must not be able to decrypt")
        } catch (expected: AEADBadTagException) {
            // the only acceptable outcome
        }
    }

    @Test
    fun `a flipped ciphertext bit fails the gcm tag instead of returning junk`() {
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val (iv, ct) = Crypto.encrypt(key, ByteArray(64) { 7 })
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 0x01).toByte()
        try {
            val out = Crypto.decrypt(key, iv, ct)
            fail("tampered ciphertext must be rejected, got ${out.size} bytes back")
        } catch (expected: AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `chunked file crypto round trips across the 64 KiB boundary`() {
        for (size in listOf(1, Crypto.CHUNK_PLAIN_SIZE - 1, Crypto.CHUNK_PLAIN_SIZE,
                Crypto.CHUNK_PLAIN_SIZE + 1, 3 * Crypto.CHUNK_PLAIN_SIZE + 5)) {
            val plain = ByteArray(size) { (it * 31).toByte() }
            val sealed = ByteArrayOutputStream().also {
                Crypto.encryptChunked(chunkKey(), ByteArrayInputStream(plain), it)
            }
            val restored = ByteArrayOutputStream().also {
                Crypto.decryptChunked(chunkKey(), ByteArrayInputStream(sealed.toByteArray()), it)
            }
            assertArrayEquals("round trip failed at plaintext size $size", plain, restored.toByteArray())
        }
    }

    @Test
    fun `an oversized record length stops the reader before it fills the buffer`() {
        val wire = ByteArrayOutputStream()
        DataOutputStream(wire).apply { writeInt(1 shl 24); flush() }
        try {
            Crypto.decryptChunked(chunkKey(), ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream())
            fail("a hostile length must not be accepted")
        } catch (expected: java.io.IOException) {
            // expected: the reader refuses anything longer than its buffer
        }
    }

    private fun keyOf(a: KeyPair, b: KeyPair): SecretKeySpec =
        Crypto.sharedKey(a.private, b.public, a.public.encoded, b.public.encoded)

    private fun chunkKey(): SecretKeySpec = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
}
