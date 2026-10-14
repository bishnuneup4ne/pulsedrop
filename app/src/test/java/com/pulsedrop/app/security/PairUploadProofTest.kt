package com.pulsedrop.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.IncomingPairRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the key-possession proof that lets a paired app upload to a phone with
 * "Require PIN" enabled. It is the only thing standing between a pairing key and a
 * PIN bypass, so its rejection paths are the point of these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairUploadProofTest {

    private lateinit var pairManager: PairManager
    private lateinit var pid: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        pairManager = PairManager(context, CoroutineScope(Dispatchers.IO))
        pairManager.acceptIncoming(
            IncomingPairRequest(
                reqId = "req-1",
                name = "Test peer",
                pubB64 = pairManager.myPubB64(),
                myIp = "192.0.2.7",
                myPort = 8080,
                safetyCode = "000000"
            )
        )
        pid = pairManager.paired.value.first().pid
    }

    @Test
    fun `a proof signed with the pairing key authorizes the matching upload`() {
        val proof = requireNotNull(pairManager.uploadProof(pid, 4096L))
        assertTrue(pairManager.verifyUploadProof(pid, 4096L, proof))
    }

    @Test
    fun `a captured proof cannot be replayed`() {
        val proof = requireNotNull(pairManager.uploadProof(pid, 4096L))
        assertTrue(pairManager.verifyUploadProof(pid, 4096L, proof))
        assertFalse("second use of the same proof must fail", pairManager.verifyUploadProof(pid, 4096L, proof))
    }

    @Test
    fun `a proof does not authorize a different payload size`() {
        val proof = requireNotNull(pairManager.uploadProof(pid, 4096L))
        assertFalse(pairManager.verifyUploadProof(pid, 8192L, proof))
    }

    @Test
    fun `a proof is meaningless for a pairing the sender is not part of`() {
        val proof = requireNotNull(pairManager.uploadProof(pid, 4096L))
        assertFalse(pairManager.verifyUploadProof("deadbeefcafe", 4096L, proof))
        assertNull(pairManager.uploadProof("deadbeefcafe", 4096L))
    }

    @Test
    fun `malformed proof headers are rejected`() {
        assertFalse(pairManager.verifyUploadProof(pid, 4096L, ""))
        assertFalse(pairManager.verifyUploadProof(pid, 4096L, "not-a-proof"))
        assertFalse(pairManager.verifyUploadProof(pid, 4096L, "aaaa.bbbb.cccc"))
        assertFalse(pairManager.verifyUploadProof(pid, 4096L, "!!!!.!!!!"))
    }
}
