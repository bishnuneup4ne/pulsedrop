package com.pulsedrop.app.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.IncomingPairRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Verification is the step that turns "we exchanged keys" into "we know nobody is in the
 * middle", and it is the part a user can actually check. These cover that the code shown is
 * derived from the stored key, that confirming it survives a restart, and that a device
 * silently swapping its key is announced instead of accepted quietly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairVerificationTest {

    private lateinit var context: Context
    private lateinit var pairManager: PairManager
    private lateinit var pid: String

    private fun acceptRequest(reqId: String, name: String, pubB64: String, ip: String) {
        pairManager.acceptIncoming(
            IncomingPairRequest(
                reqId = reqId,
                name = name,
                pubB64 = pubB64,
                myIp = ip,
                myPort = 8080,
                safetyCode = "000000"
            )
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pairManager = PairManager(context, CoroutineScope(Dispatchers.IO))
        acceptRequest("req-1", "Test peer", pairManager.myPubB64(), "192.0.2.7")
        pid = pairManager.paired.value.first().pid
    }

    @Test
    fun `a fresh pairing starts unverified`() {
        assertFalse(pairManager.findPairing(pid)!!.verified)
    }

    @Test
    fun `the code offered for comparison is the code derived from the stored key`() {
        val device = pairManager.findPairing(pid)!!
        assertEquals(
            Crypto.safetyCode(pairManager.myPubB64(), device.pubB64),
            pairManager.safetyCodeFor(pid)
        )
    }

    @Test
    fun `a code is a six digit group and unknown pairings have none`() {
        assertTrue(pairManager.safetyCodeFor(pid)!!.matches(Regex("\\d{6}")))
        assertNull(pairManager.safetyCodeFor("nope-not-a-pair"))
    }

    @Test
    fun `confirming a code sticks across a restart`() {
        pairManager.setVerified(pid, true)
        assertTrue(pairManager.findPairing(pid)!!.verified)

        // Same preferences file, brand new manager: this is what happens when the app reopens.
        val reopened = PairManager(context, CoroutineScope(Dispatchers.IO))
        assertTrue(reopened.findPairing(pid)?.verified == true)

        reopened.setVerified(pid, false)
        assertFalse(PairManager(context, CoroutineScope(Dispatchers.IO)).findPairing(pid)!!.verified)
    }

    @Test
    fun `verifying an unknown pairing changes nothing`() {
        pairManager.setVerified("nope-not-a-pair", true)
        assertEquals(1, pairManager.paired.value.size)
        assertFalse(pairManager.paired.value.first().verified)
    }

    @Test
    fun `a device re-pairing under a new key is announced`() {
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().public.encoded

        acceptRequest("req-2", "Test peer", Base64.encodeToString(other, Base64.NO_WRAP), "192.0.2.7")

        assertNotEquals("a new key must produce a different pairwise id", 1, pairManager.paired.value.size)
        assertTrue(
            "re-pairing on the same address has to surface a warning",
            pairManager.pairEvent.value?.contains("new key") == true
        )
    }

    @Test
    fun `a genuinely new device does not trip the re-pair warning`() {
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().public.encoded

        acceptRequest("req-3", "Some other phone", Base64.encodeToString(other, Base64.NO_WRAP), "192.0.2.99")

        assertEquals(2, pairManager.paired.value.size)
        assertFalse(pairManager.pairEvent.value?.contains("new key") == true)
    }
}
