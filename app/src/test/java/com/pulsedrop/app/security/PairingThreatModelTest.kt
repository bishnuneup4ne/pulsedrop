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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pairing handshake against an active attacker, not just a malformed message.
 *
 * The LAN is the trust boundary this app was built for, and pairing is the one moment two strangers
 * agree on a key. The design accepts that the first exchange is unauthenticated — that is what the
 * six-digit safety code a human compares is for — so these tests pin what the machinery guarantees
 * around it and document where the guarantee moves to the two people holding the phones:
 *
 *  * the code shown belongs to the key that asked, so a substituted key changes it;
 *  * the derivation is chosen once, by the acceptor, and a later request cannot silently re-key an
 *    existing pair; a request from a peer already paired under the same key heals without a prompt;
 *  * duplicate request ids collapse to one prompt, a flood is bounded, and an answer for a request
 *    this phone is not waiting for changes nothing;
 *  * a peer that comes back under a new key is kept beside the old trust and called out, never
 *    silently substituted;
 *  * a phone that cannot read its identity key queues nothing and declines, rather than pairing
 *    with a promise it cannot keep.
 *
 * What these tests cannot decide: the 120-second prompt TTL (it needs clock control), and the
 * human step itself. A key substitution is only caught when the two codes are actually compared;
 * the code is six digits, which is a deliberate usability trade, not an authentication protocol.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairingThreatModelTest {

    private lateinit var context: Context
    private lateinit var pairManager: PairManager
    private val servers = ArrayList<Closeable>()
    private val answers = LinkedBlockingQueue<JSONObject>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric keeps the application's files (and this class's static key cache) between test
        // methods, so each case starts with no identity, no pairings and no cached key.
        context.getSharedPreferences("localdrop_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("localdrop_pairs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        forgetCachedIdentity()

        pairManager = PairManager(context, CoroutineScope(Dispatchers.IO))
        pairManager.nameProvider = { "Test Phone" }
        pairManager.versionProvider = { "2.1.1" }
        pairManager.pairAnswerSink = { _, answer -> answers.add(answer) }
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        forgetCachedIdentity()
    }

    /**
     * `Crypto` keeps the process's identity in a static so it is read once; a case that rewrites the
     * store needs the next read to look again, and a corrupt identity must not outlive its case.
     */
    private fun forgetCachedIdentity() {
        Crypto::class.java.getDeclaredField("cachedKeyPair")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun spkiOf(curve: String = "secp256r1"): String {
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(curve)) }
            .generateKeyPair()
        return Crypto.b64(pair.public.encoded)
    }

    private fun request(pub: String, reqId: String, version: String, relay: Boolean = false) = JSONObject()
        .put("reqId", reqId)
        .put("name", "Answering phone")
        .put("pub", pub)
        .put("myIp", "192.0.2.10")
        .put("myPort", 8080)
        .put("appVersion", version)
        .put("relay", relay)

    private fun incoming(pub: String, reqId: String, version: String) = IncomingPairRequest(
        reqId = reqId,
        name = "Answering phone",
        pubB64 = pub,
        myIp = "192.0.2.10",
        myPort = 8080,
        safetyCode = "000000", // acceptIncoming recomputes what it shows; this value is not trusted
        viaRelay = true,
        appVersion = version
    )

    private fun answer(reqId: String, pub: String, kdf: Int? = null) = JSONObject()
        .put("reqId", reqId)
        .put("accept", true)
        .put("name", "Answering phone")
        .put("pub", pub)
        .put("myIp", "192.0.2.10")
        .put("myPort", 8080)
        .put("appVersion", "2.1.1")
        .apply { if (kdf != null) put("kdf", kdf) }

    private fun awaitAnswer(): JSONObject {
        val emitted = answers.poll(3, TimeUnit.SECONDS)
        assertNotNull("an answer should have been emitted", emitted)
        return emitted!!
    }

    private fun accept(request: IncomingPairRequest): JSONObject {
        answers.clear()
        pairManager.acceptIncoming(request)
        return awaitAnswer()
    }

    /** A peer listening just long enough for our outgoing request to succeed, so it stays pending. */
    private class AnswerServer : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        fun port(): Int = socket.localPort
        private val worker = thread(isDaemon = true, name = "pair-threat-answer-stub") {
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
                    conn.getOutputStream().run {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                        flush()
                    }
                }
            } catch (e: IOException) {
                // Nothing is asserted on this end; the socket closing first is a fine finish.
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

    private fun peer() = PeerDevice("peer-1", "Answering phone", "192.0.2.10", 8080, requiresPin = false)

    // ---- what the two humans compare --------------------------------------------

    @Test
    fun `the code shown belongs to the key that asked, so a substituted key changes it`() {
        val genuine = spkiOf()
        val impostor = spkiOf()

        assertTrue(pairManager.handleIncomingRequest(request(genuine, "genuine", "2.1.1"), "192.0.2.10"))
        assertTrue(pairManager.handleIncomingRequest(request(impostor, "impostor", "192.0.2.10"), "192.0.2.10"))

        val byId = pairManager.incomingRequests.value.associateBy { it.reqId }
        val myPub = pairManager.myPubB64()
        assertEquals("the prompt must show the code for the key it actually carries",
            Crypto.safetyCode(myPub, genuine), byId.getValue("genuine").safetyCode)
        assertEquals(Crypto.safetyCode(myPub, impostor), byId.getValue("impostor").safetyCode)
        assertNotEquals(
            "a substituted key that produced the same code would make the human check useless",
            byId.getValue("genuine").safetyCode,
            byId.getValue("impostor").safetyCode,
        )
    }

    // ---- how the derivation is chosen, and how it stays chosen ---------------------

    @Test
    fun `the acceptor chooses the derivation for a new pair and stores what it chose`() {
        val peerPub = spkiOf()
        val emitted = accept(incoming(peerPub, "new-pair", "2.1.1"))

        assertEquals("two 2.x phones use HKDF", Kdf.V2, emitted.optInt("kdf", Kdf.V1))
        val pairing = pairManager.paired.value.single()
        assertEquals(Kdf.V2, pairing.kdfVersion)
        assertEquals(
            "the code on the Devices screen must be the one for the stored key",
            Crypto.safetyCode(pairManager.myPubB64(), peerPub),
            pairManager.safetyCodeFor(pairing.pid),
        )
        assertNotNull("the chosen derivation must actually produce a key", pairManager.secretKey(pairing))
    }

    @Test
    fun `an older peer is paired with the frozen derivation and not told a version it cannot read`() {
        val emitted = accept(incoming(spkiOf(), "old-peer", "1.9.0"))

        assertFalse("a pre-v2 requester would ignore a kdf field at best", emitted.has("kdf"))
        assertEquals(Kdf.V1, pairManager.paired.value.single().kdfVersion)
    }

    @Test
    fun `an older phone does not choose v2 for a newer peer`() {
        pairManager.versionProvider = { "1.9.0" }
        val emitted = accept(incoming(spkiOf(), "newer-peer", "2.1.1"))

        assertFalse(emitted.has("kdf"))
        assertEquals(Kdf.V1, pairManager.paired.value.single().kdfVersion)
    }

    @Test
    fun `a paired device that asks again is answered with the stored derivation, not a new one`() {
        val peerPub = spkiOf()
        pairManager.versionProvider = { "1.9.0" }
        accept(incoming(peerPub, "first-pair", "2.1.1"))
        val pid = pairManager.paired.value.single().pid
        assertEquals(Kdf.V1, pairManager.paired.value.single().kdfVersion)

        // Both ends now run a release that would choose v2, and the peer lost its half.
        pairManager.versionProvider = { "2.1.1" }
        answers.clear()
        assertTrue(pairManager.handleIncomingRequest(request(peerPub, "retry", "2.1.1", relay = true), "192.0.2.10"))

        val heal = awaitAnswer()
        assertTrue("a device that lost its half must be re-answered, not prompted again", heal.getBoolean("accept"))
        assertFalse("the existing pair must not be silently re-keyed", heal.has("kdf"))
        assertEquals(Kdf.V1, pairManager.paired.value.single().kdfVersion)
        assertEquals(pid, pairManager.paired.value.single().pid)
        assertTrue("no second prompt for a pair both sides already hold", pairManager.incomingRequests.value.isEmpty())
    }

    @Test
    fun `an answer that names no derivation is the frozen one, even from a new peer`() {
        // An answerer from before v2 omits the field, so its absence has to mean v1 or old pairs
        // stop working. The cost is that an on-path attacker can strip it and leave two ends on
        // different derivations; both are strong, and the mismatch fails closed on the first message.
        val reqId = pendingFor(peer())
        pairManager.handleIncomingResponse(answer(reqId, spkiOf()), null)

        assertEquals(Kdf.V1, pairManager.paired.value.single().kdfVersion)
    }

    // ---- repeated, stale and concurrent requests ----------------------------------

    @Test
    fun `a repeated request id replaces the prompt instead of adding one`() {
        val first = spkiOf()
        val second = spkiOf()
        assertTrue(pairManager.handleIncomingRequest(request(first, "same-id", "2.1.1"), "192.0.2.9"))
        assertTrue(pairManager.handleIncomingRequest(request(second, "same-id", "2.1.1"), "192.0.2.9"))

        val queued = pairManager.incomingRequests.value
        assertEquals("one request id is one prompt", 1, queued.size)
        assertEquals("the newest copy is the one the user is asked about", second, queued.single().pubB64)
    }

    @Test
    fun `a flood of distinct requests cannot grow the prompt list without bound`() {
        repeat(13) { i ->
            assertTrue(pairManager.handleIncomingRequest(request(spkiOf(), "req-$i", "2.1.1"), "192.0.2.9"))
        }

        val queued = pairManager.incomingRequests.value
        assertEquals("the prompt list is capped at twelve", 12, queued.size)
        assertFalse("the oldest prompt is the one dropped", queued.any { it.reqId == "req-0" })
        assertTrue(queued.any { it.reqId == "req-12" })
    }

    @Test
    fun `an answer for another request id leaves the real request pending`() {
        val reqId = pendingFor(peer())
        pairManager.handleIncomingResponse(answer("not-$reqId", spkiOf()), null)

        assertEquals("a guessed request id must not cancel the one we are waiting for",
            reqId, pairManager.pendingOutgoing.value?.reqId)
        assertTrue(pairManager.paired.value.isEmpty())

        val peerPub = spkiOf()
        pairManager.handleIncomingResponse(answer(reqId, peerPub), null)
        assertEquals(peerPub, pairManager.paired.value.single().pubB64)
    }

    @Test
    fun `an answer arriving after the request was cancelled is ignored`() {
        val reqId = pendingFor(peer())
        pairManager.cancelPendingRequest()
        pairManager.handleIncomingResponse(answer(reqId, spkiOf()), null)

        assertNull(pairManager.pendingOutgoing.value)
        assertTrue(pairManager.paired.value.isEmpty())
    }

    // ---- a peer that comes back different ------------------------------------------

    @Test
    fun `re-pairing under a new key keeps the old trust and says the key changed`() {
        val firstPub = spkiOf()
        pairManager.handleIncomingResponse(answer(pendingFor(peer()), firstPub), null)
        val oldPid = pairManager.paired.value.single().pid

        val newPub = spkiOf()
        pairManager.handleIncomingResponse(answer(pendingFor(peer()), newPub), null)

        val pairings = pairManager.paired.value
        assertEquals("a new key must not silently replace the old pairing", 2, pairings.size)
        assertEquals(firstPub, pairManager.findPairing(oldPid)?.pubB64)
        assertTrue(pairings.any { it.pubB64 == newPub })
        val event = pairManager.pairEvent.value ?: ""
        assertTrue("the user must be told this is a new key: $event", event.contains("new key"))
    }

    // ---- no identity, no promise ---------------------------------------------------

    @Test
    fun `a phone with no readable identity queues nothing and tells the requester why`() {
        // Half a stored key and no keystore in this environment is the CORRUPT path: the identity
        // is reported lost rather than replaced, because a fresh key would break every existing pair.
        context.getSharedPreferences("localdrop_identity", Context.MODE_PRIVATE)
            .edit().putString("pub", spkiOf()).commit()
        forgetCachedIdentity()

        val peerPub = spkiOf()
        assertFalse(pairManager.handleIncomingRequest(request(peerPub, "lost", "2.1.1", relay = true), "192.0.2.9"))
        assertTrue("there is no key to confirm, so there must be no prompt", pairManager.incomingRequests.value.isEmpty())
        assertTrue(
            "the person holding the phone must be told what happened: ${pairManager.pairEvent.value}",
            pairManager.pairEvent.value?.contains("identity key") == true,
        )
        assertFalse(
            "a requester must be declined rather than left waiting a minute",
            awaitAnswer().optBoolean("accept", true),
        )

        pairManager.acceptIncoming(incoming(peerPub, "late", "2.1.1"))
        assertTrue("accepting without an identity must store nothing", pairManager.paired.value.isEmpty())
    }
}
