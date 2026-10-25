package com.pulsedrop.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.model.PeerDevice
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Key material is the one thing a peer gets to hand this phone, and a pairing remembers it forever.
 * These tests cover the gate in front of that: only a P-256 key and a KDF this build can perform
 * are stored, on both the request and the answer path, so a confirmed pairing can never turn into
 * an ECDH or derivation failure on the first message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairKeyIngestTest {

    private lateinit var context: Context
    private lateinit var pairManager: PairManager
    private val servers = ArrayList<Closeable>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        pairManager = PairManager(context, CoroutineScope(Dispatchers.IO))
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun spkiOf(curve: String): String {
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(curve)) }
            .generateKeyPair()
        return Crypto.b64(pair.public.encoded)
    }

    private fun request(pub: String, reqId: String = "req-1") = JSONObject()
        .put("reqId", reqId)
        .put("name", "Impostor phone")
        .put("pub", pub)
        .put("myIp", "192.0.2.9")
        .put("myPort", 8080)
        .put("appVersion", "2.1.1")

    /** Stands in for the peer listening for our outgoing request, so it stays pending. */
    private class AnswerServer : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        fun port(): Int = socket.localPort
        private val worker = thread(isDaemon = true, name = "pair-answer-stub") {
            try {
                socket.accept().use { conn ->
                    conn.soTimeout = 5_000
                    val input = conn.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    var left = contentLength
                    val buffer = CharArray(1024)
                    while (left > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size, left))
                        if (n < 0) break
                        left -= n
                    }
                    val out = conn.getOutputStream()
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                            .toByteArray()
                    )
                    out.flush()
                }
            } catch (e: IOException) {
                // The socket closing first is a fine end for a stub nobody asserts on.
            }
        }

        override fun close() {
            socket.close()
            worker.join(2_000)
        }
    }

    private fun pendingFor(peer: PeerDevice): String {
        val server = AnswerServer()
        servers.add(server)
        pairManager.sendPairRequest(peer.copy(ip = "127.0.0.1", port = server.port()))
        return requireNotNull(pairManager.pendingOutgoing.value).reqId
    }

    private fun answer(reqId: String, pub: String, kdf: Int? = null): JSONObject {
        val json = JSONObject()
            .put("reqId", reqId)
            .put("accept", true)
            .put("name", "Answering phone")
            .put("pub", pub)
            .put("myIp", "192.0.2.10")
            .put("myPort", 8080)
            .put("appVersion", "2.1.1")
        if (kdf != null) json.put("kdf", kdf)
        return json
    }

    private fun peer() = PeerDevice("peer-1", "Answering phone", "192.0.2.10", 8080, requiresPin = false)

    // ---- what counts as a usable key --------------------------------------------

    @Test
    fun `only a P-256 key is usable for a pairing`() {
        assertTrue(Crypto.isP256PublicKey(spkiOf("secp256r1")))
        assertFalse("a P-384 key cannot complete the ECDH this app performs", Crypto.isP256PublicKey(spkiOf("secp384r1")))
        assertFalse(Crypto.isP256PublicKey(""))
        assertFalse(Crypto.isP256PublicKey("not-a-key"))
        assertFalse(Crypto.isP256PublicKey("AAAA"))
    }

    @Test
    fun `only the two derivations this build performs are known`() {
        assertTrue(Kdf.isKnown(Kdf.V1))
        assertTrue(Kdf.isKnown(Kdf.V2))
        assertFalse(Kdf.isKnown(0))
        assertFalse(Kdf.isKnown(3))
        assertFalse(Kdf.isKnown(99))
    }

    // ---- requests --------------------------------------------------------------

    @Test
    fun `a request with an unusable key is never queued for confirmation`() {
        assertFalse(pairManager.handleIncomingRequest(request(spkiOf("secp384r1")), "192.0.2.9"))
        assertFalse(pairManager.handleIncomingRequest(request("not-a-key", "req-2"), "192.0.2.9"))
        assertTrue(pairManager.incomingRequests.value.isEmpty())
    }

    @Test
    fun `a valid request still reaches the confirmation screen`() {
        assertTrue(pairManager.handleIncomingRequest(request(spkiOf("secp256r1")), "192.0.2.9"))
        assertEquals(1, pairManager.incomingRequests.value.size)
        assertEquals("req-1", pairManager.incomingRequests.value.first().reqId)
    }

    @Test
    fun `accepting a request rebuilt with an unusable key stores no pairing`() {
        pairManager.acceptIncoming(
            IncomingPairRequest(
                reqId = "rebuilt",
                name = "Impostor phone",
                pubB64 = spkiOf("secp384r1"),
                myIp = "192.0.2.9",
                myPort = 8080,
                safetyCode = "000000"
            )
        )
        assertTrue(pairManager.paired.value.isEmpty())
        assertEquals("That device sent a pairing key this phone cannot use", pairManager.pairEvent.value)
    }

    // ---- answers ---------------------------------------------------------------

    @Test
    fun `an answer with an unusable key stores no pairing`() {
        val reqId = pendingFor(peer())
        pairManager.handleIncomingResponse(answer(reqId, spkiOf("secp384r1")), null)
        assertTrue(pairManager.paired.value.isEmpty())
        assertEquals("Answering phone sent a pairing key this phone cannot use", pairManager.pairEvent.value)
    }

    @Test
    fun `an answer naming an unknown derivation stores no pairing`() {
        val reqId = pendingFor(peer())
        pairManager.handleIncomingResponse(answer(reqId, spkiOf("secp256r1"), kdf = 3), null)
        assertTrue(pairManager.paired.value.isEmpty())
        assertEquals("Answering phone sent a pairing this phone does not understand", pairManager.pairEvent.value)
    }

    @Test
    fun `a well-formed answer still pairs with the derivation it chose`() {
        val peerPub = spkiOf("secp256r1")
        val reqId = pendingFor(peer())
        pairManager.handleIncomingResponse(answer(reqId, peerPub, kdf = Kdf.V2), null)
        val pairing = pairManager.paired.value.singleOrNull()
        assertNotNull(pairing)
        assertEquals(peerPub, pairing!!.pubB64)
        assertEquals(Kdf.V2, pairing.kdfVersion)
        assertNull(pairManager.pendingOutgoing.value)
        assertNotNull(pairManager.secretKey(pairing))
    }
}
