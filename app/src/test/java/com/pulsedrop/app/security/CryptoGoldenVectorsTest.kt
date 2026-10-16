package com.pulsedrop.app.security

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The compatibility contract for every pairing made before v2.0.
 *
 * These vectors were produced by the v1.7 implementation on fixed keys and are pinned here
 * so the rewritten security core must reproduce them byte-for-byte. If this test fails,
 * existing pairings can no longer derive their shared secrets — phones would silently stop
 * reading each other's messages instead of failing loudly.
 *
 * The vectors are the only place a frozen formula may change meaning: extend, never edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CryptoGoldenVectorsTest {

    private companion object {
        const val PRIV_A = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxnhw/Luoq/upT3SYyrAjDyfiaL5fqFUSE3iIXdp27P2hRANCAAQM4cLTeKt93O6VmlvTBy0lGXY+SSS2P0oNWl2tKjKQ6o8LysIPrr/0T5tYSg3m1SWSctaiQ3j5zy5I9nBuifeA"
        const val PRIV_B = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgK8z5KiE/XNbBzX5CtQg+uqTBC4Fo8QCam3x+Kzr/PSWhRANCAATrgFgzcweruC7KO+a0M6I9Iq/1/9+tUZ8ihYtaEha65pcd7YoHEV3MIwYKsNVRlIZouBycJEp6r45AGxMbpU4x"
        const val PUB_A = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDOHC03irfdzulZpb0wctJRl2Pkkktj9KDVpdrSoykOqPC8rCD66/9E+bWEoN5tUlknLWokN4+c8uSPZwbon3gA=="
        const val PUB_B = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE64BYM3MHq7guyjvmtDOiPSKv9f/frVGfIoWLWhIWuuaXHe2KBxFdzCMGCrDVUZSGaLgcnCRKeq+OQBsTG6VOMQ=="

        const val SHARED_KEY_HEX = "1125818dfd03c3c31889389fd1ee8db0c844e26cdf9af7ca018dd2a15bac8ca4"
        const val PAIR_ID = "9ed39e88257f"
        const val SAFETY_CODE = "541800"
        const val DEVICE_ID_A = "415087d09de89b12"
        const val DEVICE_ID_B = "d16c73ce5417304b"
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)

    @Test
    fun kdfV1ReproducesTheFrozenKey() {
        val kf = KeyFactory.getInstance("EC")
        val pa = kf.generatePrivate(PKCS8EncodedKeySpec(b64d(PRIV_A)))
        val pA = kf.generatePublic(X509EncodedKeySpec(b64d(PUB_A)))
        val pB = kf.generatePublic(X509EncodedKeySpec(b64d(PUB_B)))
        val key = Crypto.sharedKey(pa, pB, pA.encoded, pB.encoded)
        assertEquals("v1 KDF output drifts — old pairings would break", SHARED_KEY_HEX, hex(key.encoded))
    }

    @Test
    fun identifiersAreFrozen() {
        assertEquals(PAIR_ID, Crypto.pairId(PUB_A, PUB_B))
        assertEquals(SAFETY_CODE, Crypto.safetyCode(PUB_A, PUB_B))
        assertEquals(DEVICE_ID_A, Crypto.deviceId(PUB_A))
        assertEquals(DEVICE_ID_B, Crypto.deviceId(PUB_B))
    }

    @Test
    fun chunkedFileRecordLayoutIsFrozen() {
        // 70,000 bytes of plaintext must seal as two records: [u32 len][iv 12][ct+tag].
        // Lengths are deterministic (IV 12 + chunk + 16-byte tag); the IV bytes are not.
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val sealed = ByteArrayOutputStream()
        Crypto.encryptChunked(key, ByteArrayInputStream(ByteArray(70_000) { (it % 251).toByte() }), sealed)
        val bytes = sealed.toByteArray()
        assertEquals(65564, ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(70064, bytes.size)
        // and it still round-trips through the reader
        val restored = ByteArrayOutputStream()
        Crypto.decryptChunked(key, ByteArrayInputStream(bytes), restored)
        assertEquals(70_000, restored.size())
    }
}
