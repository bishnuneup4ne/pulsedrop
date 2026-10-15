package com.pulsedrop.app.security

import android.content.Context
import android.util.Base64
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PendingPairRequest
import com.pulsedrop.app.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

private const val MAX_INCOMING_REQUESTS = 12
private const val INCOMING_REQUEST_TTL_MS = 120_000L
private const val PROOF_VERSION = "pulsedrop-upload-v1"
private const val RELAY_VERSION = "pulsedrop-relay-v1"
private const val PROOF_TTL_MS = 120_000L
private const val LATE_ANSWER_MS = 10 * 60_000L
private const val UNREACHABLE_MS = 90_000L
/** Wire value for the shared room; on receipt it maps back to the app's null thread. */
private const val ROOM_THREAD = "room"
// A quote shows enough of the original to identify it; the rest stays on the sender's device.
private const val REPLY_SNAPSHOT_CHARS = 200

class PairManager(private val context: Context, private val scope: CoroutineScope) {

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired.asStateFlow()

    private val _incomingRequests = MutableStateFlow<List<IncomingPairRequest>>(emptyList())
    val incomingRequests: StateFlow<List<IncomingPairRequest>> = _incomingRequests.asStateFlow()

    private val _pendingOutgoing = MutableStateFlow<PendingPairRequest?>(null)
    val pendingOutgoing: StateFlow<PendingPairRequest?> = _pendingOutgoing.asStateFlow()

    private val _pairEvent = MutableStateFlow<String?>(null)
    val pairEvent: StateFlow<String?> = _pairEvent.asStateFlow()

    private val _groups = MutableStateFlow<List<ChatGroup>>(emptyList())
    val groups: StateFlow<List<ChatGroup>> = _groups.asStateFlow()

    var nameProvider: () -> String = { "PulseDrop Phone" }
    var endpointProvider: () -> Pair<String, Int> = { "127.0.0.1" to 8080 }
    var onPairedMessage: ((ClipMessage) -> Unit)? = null

    /**
     * A message the sender retracted. It arrives on the same sealed channel as the message itself,
     * so only the two ends of that pairing learn that it went away.
     */
    var onPairedDelete: ((String) -> Unit)? = null

    /**
     * Set once a relay is attached. When a peer has no listener of its own — its server is off,
     * or it is a device we only ever reach through somebody else — the *sealed* envelope is handed
     * here instead of being POSTed at its address. Only the ciphertext crosses, never the plaintext,
     * so nothing downstream of this hook can forward what it cannot itself read.
     */
    var relaySender: ((pairing: PairedDevice, ivB64: String, ctB64: String) -> Boolean)? = null

    /**
     * False on a phone whose server is off. Every handshake that used to assume both devices are
     * listening now has somewhere to look instead.
     */
    var acceptsInbound: () -> Boolean = { true }

    /** Set by the relay once it exists; used to fetch a pairing answer we cannot be handed. */
    var relayPairPoller: ((ip: String, port: Int, reqId: String) -> JSONObject?)? = null

    /**
     * On the hosting side: where a relay-mode answer is left for its requester to pick up. The
     * server owns the store, so this is a callback rather than a dependency.
     */
    var pairAnswerSink: ((reqId: String, answer: JSONObject) -> Unit)? = null

    /** Seal a payload with the recipient's own key so a third party can carry it unread. */
    fun sealFor(pairing: PairedDevice, payload: String): Pair<String, String>? =
        try {
            Crypto.encryptText(secretKey(pairing), payload)
        } catch (e: Exception) {
            null
        }

    private val secretCache = mutableMapOf<String, SecretKeySpec>()
    private val usedProofs = LinkedHashMap<String, Long>()
    private val prefs = context.getSharedPreferences("localdrop_pairs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        load()
        // Prompts otherwise sit in the UI until some other device happens to ask again.
        scope.launch {
            while (isActive) {
                delay(INCOMING_REQUEST_TTL_MS / 2)
                pruneIncomingRequests()
            }
        }
    }

    private fun pruneIncomingRequests() {
        val now = System.currentTimeMillis()
        _incomingRequests.update { list ->
            val kept = list.filter { now - it.timestamp < INCOMING_REQUEST_TTL_MS }
            if (kept.size == list.size) list else kept
        }
    }

    fun myPubB64(): String = Crypto.publicKeyB64(context)

    fun consumeEvent() {
        _pairEvent.value = null
    }

    fun secretKey(p: PairedDevice): SecretKeySpec = synchronized(secretCache) {
        secretCache.getOrPut(p.pid) { Crypto.sharedKeyFor(context, p.pubB64) }
    }

    fun pairForPeer(peer: PeerDevice): PairedDevice? =
        _paired.value.firstOrNull { it.originIp == peer.ip || it.pid == peer.id }

    fun findPairing(pid: String): PairedDevice? = _paired.value.firstOrNull { it.pid == pid }

    fun myDeviceId(): String = Crypto.deviceId(myPubB64())

    /** The device id of whoever is on the other end of this pairing. */
    fun deviceIdOf(pairing: PairedDevice): String = Crypto.deviceId(pairing.pubB64)

    /**
     * Which pairing connects us to the device with this id. Used to pick the key a relay
     * request must be signed with, and to seal traffic for a peer we are not hosting.
     */
    fun findPairingByDeviceId(deviceId: String): PairedDevice? =
        _paired.value.firstOrNull { deviceIdOf(it) == deviceId }

    /**
     * A peer that refused a direct connection recently is not worth re-dialling for every
     * message; its traffic goes straight to the relay until the entry ages out.
     */
    private val unreachableUntil = HashMap<String, Long>()

    fun isDirectlyReachable(pid: String): Boolean =
        (unreachableUntil[pid] ?: 0L) <= System.currentTimeMillis()

    fun markUnreachable(pid: String) {
        synchronized(unreachableUntil) {
            val now = System.currentTimeMillis()
            unreachableUntil.entries.filter { it.value <= now }.forEach { unreachableUntil.remove(it.key) }
            while (unreachableUntil.size > 32) unreachableUntil.remove(unreachableUntil.keys.first())
            unreachableUntil[pid] = now + UNREACHABLE_MS
        }
    }

    fun clearUnreachable(pid: String) {
        synchronized(unreachableUntil) { unreachableUntil.remove(pid) }
    }

    /**
     * A peer's LAN address changes with DHCP. Any traffic that already proved it holds the
     * pairwise key tells us where that device is now, so chat and sends keep working.
     */
    fun notePeerAddress(pid: String, ip: String) {
        if (ip.isBlank() || ip.startsWith("127.") || ip == "::1" || ip == "0.0.0.0") return
        val current = _paired.value.firstOrNull { it.pid == pid } ?: return
        if (current.originIp == ip) return
        _paired.update { list -> list.map { if (it.pid == pid) it.copy(originIp = ip) else it } }
        save()
    }

    // ---- upload authorization ----------------------------------------------

    /**
     * Proves the sender holds the pairwise key, so a paired app can push a file to a phone
     * that has "Require PIN" turned on without ever learning the PIN. The pairing id itself
     * is public (both public keys travel in the clear during pairing), so it authenticates
     * nothing on its own; this does. The request length is part of the message so one valid
     * proof cannot be reused to push a different payload.
     */
    fun uploadProof(pid: String, contentLength: Long): String? {
        val pairing = findPairing(pid) ?: return null
        return try {
            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val payload = "$PROOF_VERSION|$pid|$contentLength|${System.currentTimeMillis()}|${Base64.encodeToString(nonce, Base64.NO_WRAP)}"
            val (iv, ct) = Crypto.encryptText(secretKey(pairing), payload)
            "$iv.$ct"
        } catch (e: Exception) {
            null
        }
    }

    /** Validates a proof against the pairwise key and binds it to this exact upload. */
    fun verifyUploadProof(pid: String, contentLength: Long, proof: String): Boolean {
        val pairing = findPairing(pid) ?: return false
        return try {
            val parts = proof.split('.')
            if (parts.size != 2) return false
            val plain = Crypto.decryptText(secretKey(pairing), parts[0], parts[1])
            val fields = plain.split('|')
            if (fields.size != 5 || fields[0] != PROOF_VERSION) return false
            if (fields[1] != pid || fields[2] != contentLength.toString()) return false
            val issuedAt = fields[3].toLongOrNull() ?: return false
            val age = System.currentTimeMillis() - issuedAt
            if (age > PROOF_TTL_MS || age < -PROOF_TTL_MS) return false
            // A captured proof is otherwise replayable inside its window, so each nonce is
            // single-use for as long as it stays valid.
            if (consumeNonce(parts[0] + '|' + fields[4])) return false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendPairRequest(peer: PeerDevice) {
        val reqId = UUID.randomUUID().toString()
        val (myIp, myPort) = endpointProvider()
        val relayed = !acceptsInbound()
        _pendingOutgoing.value = PendingPairRequest(peer, reqId, viaRelay = relayed)
        scope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("reqId", reqId)
                    .put("name", nameProvider())
                    .put("pub", myPubB64())
                    .put("myIp", myIp)
                    .put("myPort", myPort)
                    .put("relay", relayed)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                client.newCall(
                    Request.Builder()
                        .url("http://${peer.ip}:${peer.port}/api/pair/request")
                        .post(body).build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        _pendingOutgoing.value = null
                        _pairEvent.value = "Pair request did not reach ${peer.name}"
                    }
                }
            } catch (e: Exception) {
                _pendingOutgoing.value = null
                _pairEvent.value = "Pair request failed: ${e.message}"
            }
        }
        if (relayed) pollRelayedPairAnswer(peer, reqId)
        scope.launch {
            delay(60_000L)
            val pending = _pendingOutgoing.value
            if (pending?.reqId == reqId) {
                _pendingOutgoing.value = null
                rememberLateAnswer(pending)
                _pairEvent.value = "No pairing answer from ${peer.name} yet — try again later"
            }
        }
    }

    /**
     * A phone with its server off has no socket for the answer to arrive on, so it asks for it.
     * The request id is the only thing that authorizes the fetch, and it is a uuid this device
     * invented, so nothing new is exposed that the direct POST back did not already rely on.
     */
    private fun pollRelayedPairAnswer(peer: PeerDevice, reqId: String) {
        val poller = relayPairPoller ?: return
        scope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 60_000L + LATE_ANSWER_MS
            while (System.currentTimeMillis() < deadline && isAwaitingAnswer(reqId)) {
                val answer = try {
                    poller(peer.ip, peer.port, reqId)
                } catch (e: Exception) {
                    null
                }
                if (answer != null) {
                    handleIncomingResponse(answer, null)
                    return@launch
                }
                Thread.sleep(2_000L)
            }
        }
    }

    /** Still waiting, either live or inside the late-answer grace the direct path also gets. */
    private fun isAwaitingAnswer(reqId: String): Boolean =
        _pendingOutgoing.value?.reqId == reqId || synchronized(lateAnswers) { lateAnswers.containsKey(reqId) }

    // An answer that lands after the 60 s window used to be dropped, leaving one device
    // paired and the other not. Keep the request a while longer so a late accept still heals.
    private val lateAnswers = LinkedHashMap<String, Pair<PendingPairRequest, Long>>()

    private fun rememberLateAnswer(pending: PendingPairRequest) {
        synchronized(lateAnswers) {
            val now = System.currentTimeMillis()
            lateAnswers.entries.filter { it.value.second <= now }.forEach { lateAnswers.remove(it.key) }
            while (lateAnswers.size > 8) lateAnswers.remove(lateAnswers.keys.first())
            lateAnswers[pending.reqId] = pending to (now + LATE_ANSWER_MS)
        }
    }

    private fun takeLateAnswer(reqId: String): PendingPairRequest? = synchronized(lateAnswers) {
        val entry = lateAnswers.remove(reqId) ?: return null
        if (entry.second <= System.currentTimeMillis()) null else entry.first
    }

    fun cancelPendingRequest() {
        _pendingOutgoing.value = null
    }

    fun acceptIncoming(request: IncomingPairRequest) {
        val (myIp, myPort) = endpointProvider()
        val paired = PairedDevice(
            pid = Crypto.pairId(myPubB64(), request.pubB64),
            name = request.name,
            pubB64 = request.pubB64,
            originIp = request.myIp,
            originPort = request.myPort,
        )
        // Keep the key the user already confirmed; accepting again from the same device is a
        // network hiccup far more often than it is a genuine rekey.
        val existing = _paired.value.firstOrNull { it.pid == paired.pid }
        val device = if (existing != null) paired.copy(verified = existing.verified) else paired
        val replaced = addPairing(device)
        _incomingRequests.update { list -> list.filterNot { it.reqId == request.reqId } }
        _pairEvent.value = pairingOutcome(request.name, replaced)
        scope.launch(Dispatchers.IO) {
            respondToPair(request.reqId, true, request.myIp, request.myPort, myIp, myPort, request.viaRelay)
        }
    }

    fun declineIncoming(request: IncomingPairRequest) {
        val (myIp, myPort) = endpointProvider()
        _incomingRequests.update { list -> list.filterNot { it.reqId == request.reqId } }
        _pairEvent.value = "Declined pairing from ${request.name}"
        scope.launch(Dispatchers.IO) {
            respondToPair(request.reqId, false, request.myIp, request.myPort, myIp, myPort, request.viaRelay)
        }
    }

    private fun respondToPair(
        reqId: String,
        accept: Boolean,
        peerIp: String,
        peerPort: Int,
        myIp: String,
        myPort: Int,
        viaRelay: Boolean = false
    ) {
        val body = JSONObject()
            .put("reqId", reqId)
            .put("type", "pair-response")
            .put("accept", accept)
            .put("name", nameProvider())
            .put("pub", myPubB64())
            .put("myIp", myIp)
            .put("myPort", myPort)
        // A phone with its server off cannot be handed this; it comes and takes it.
        if (viaRelay) {
            val sink = pairAnswerSink
            if (sink != null) {
                sink(reqId, body)
                return
            }
        }
        val payload = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        // A single blip here used to leave us paired and the peer not, with no way to notice.
        for (attempt in 0 until 3) {
            if (attempt > 0) try { Thread.sleep(if (attempt == 1) 3_000L else 8_000L) } catch (e: InterruptedException) { return }
            try {
                client.newCall(
                    Request.Builder().url("http://$peerIp:$peerPort/api/pair/response")
                        .post(payload).build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) return
                }
            } catch (e: Exception) {
                // retry
            }
        }
        // The accept state is already stored locally; the peer falls back to its late-answer window.
    }

    fun unpair(pid: String) {
        val p = _paired.value.firstOrNull { it.pid == pid } ?: return
        _paired.update { list -> list.filterNot { it.pid == pid } }
        save()
        // A group cannot hold an unpaired device — drop it and retire empty groups.
        _groups.update { list ->
            list.map { g -> g.copy(memberPids = g.memberPids.filterNot { it == pid }) }
                .filter { it.memberPids.size >= 2 }
        }
        saveGroups()
        synchronized(secretCache) { secretCache.remove(pid) }
        _pairEvent.value = "Unpaired ${p.name}"
    }

    /**
     * Stores a pairing and returns the previously-trusted device it appears to displace, if any.
     * The callers share one message builder so a success toast can never stomp the rekey warning.
     */
    private fun addPairing(device: PairedDevice): PairedDevice? {
        // Re-pairing under a new identity is exactly what a downgrade looks like from here, so
        // say so out loud instead of silently replacing the trusted key. Same name or same
        // address, different pairwise id: the device changed keys and needs re-verifying.
        val clash = _paired.value.firstOrNull {
            it.pid != device.pid && (
                it.originIp == device.originIp ||
                    it.name.equals(device.name, ignoreCase = true)
                )
        }
        _paired.update { list ->
            if (list.any { it.pid == device.pid }) {
                list.map { if (it.pid == device.pid) device else it }
            } else {
                list + device
            }
        }
        save()
        return clash
    }

    private fun pairingOutcome(name: String, clash: PairedDevice?): String =
        if (clash == null) "Paired with $name — end-to-end encrypted"
        else "Paired with $name, but this is a new key. " +
            "Check the safety code matches the old one before trusting it."

    /** The 6-digit code to read aloud on both devices; equal on both sides for a given pair. */
    fun safetyCodeFor(pid: String): String? {
        val device = findPairing(pid) ?: return null
        return try {
            Crypto.safetyCode(myPubB64(), device.pubB64)
        } catch (e: Exception) {
            null
        }
    }

    fun setVerified(pid: String, verified: Boolean) {
        val current = findPairing(pid) ?: return
        if (current.verified == verified) return
        _paired.update { list -> list.map { if (it.pid == pid) it.copy(verified = verified) else it } }
        save()
    }

    // ---- relay authorization ------------------------------------------------

    /**
     * Proves to somebody else's server that we hold one of its pairings, so a host will carry
     * our mail without becoming a party to it. The claimed device id travels *inside* the
     * ciphertext, so the host learns who is speaking from something only that device could
     * have produced — the `from` on an envelope is never taken on trust. `pull` is signed
     * separately from `post` so a stolen post token cannot empty a mailbox.
     */
    private fun relayProof(pid: String, role: String): String? {
        val pairing = findPairing(pid) ?: return null
        return try {
            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val payload = "$RELAY_VERSION|$pid|$role|${myDeviceId()}|${System.currentTimeMillis()}|" +
                Base64.encodeToString(nonce, Base64.NO_WRAP)
            val (iv, ct) = Crypto.encryptText(secretKey(pairing), payload)
            "$iv.$ct"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Signs a relay request with the key shared with [hostDeviceId] — the host whose server we
     * are talking to. Returns its pairing id and the token, or null when we are not paired with
     * that host, since an unpaired device has nothing to say to its mailbox.
     */
    fun relayAuth(hostDeviceId: String, role: String): Pair<String, String>? {
        val pairing = findPairingByDeviceId(hostDeviceId) ?: return null
        val proof = relayProof(pairing.pid, role) ?: return null
        return pairing.pid to proof
    }

    /**
     * Server side: validates a relay token and returns the device id it was signed for, or null.
     * This is the only place the claimed sender identity is believed.
     */
    fun verifyRelayAuth(pid: String, role: String, proof: String): String? {
        val pairing = findPairing(pid) ?: return null
        return try {
            val parts = proof.split('.')
            if (parts.size != 2) return null
            val plain = Crypto.decryptText(secretKey(pairing), parts[0], parts[1])
            val fields = plain.split('|')
            // version|pid|role|deviceId|issuedAt|nonce
            if (fields.size != 6 || fields[0] != RELAY_VERSION) return null
            if (fields[1] != pid || fields[2] != role) return null
            if (fields[3] != deviceIdOf(pairing)) return null
            val issuedAt = fields[4].toLongOrNull() ?: return null
            val age = System.currentTimeMillis() - issuedAt
            if (age > PROOF_TTL_MS || age < -PROOF_TTL_MS) return null
            if (consumeNonce(parts[0] + "|r|" + fields[5])) return null
            fields[3]
        } catch (e: Exception) {
            null
        }
    }

    /** True when the nonce had already been seen, i.e. this is a replay. */
    private fun consumeNonce(nonceKey: String): Boolean = synchronized(usedProofs) {
        val now = System.currentTimeMillis()
        usedProofs.entries.filter { it.value <= now }.map { it.key }.forEach { usedProofs.remove(it) }
        while (usedProofs.size > 64) usedProofs.remove(usedProofs.keys.first())
        if (usedProofs.containsKey(nonceKey)) true else {
            usedProofs[nonceKey] = now + PROOF_TTL_MS
            false
        }
    }

    // ---- Server hooks -------------------------------------------------------

    /** Handles POST /api/pair/request arriving at our server. */
    fun handleIncomingRequest(json: JSONObject, clientIp: String) {
        val myPub = myPubB64()
        val peerPub = json.optString("pub")
        if (peerPub.isBlank()) return
        // Prefer the address the requester advertises as reachable for replies;
        // the socket peer IP is a fallback (differs under port-forwarding / NAT).
        val reachIp = json.optString("myIp").ifBlank { clientIp }
        val reachPort = json.optInt("myPort", 8080)
        // Already paired with this exact key: re-answer silently so a device that lost its
        // half of the pairing (missed response, reinstall of state) heals without a prompt.
        if (_paired.value.any { it.pid == Crypto.pairId(myPub, peerPub) }) {
            val (myIp, myPort) = endpointProvider()
            scope.launch(Dispatchers.IO) {
                respondToPair(
                    json.optString("reqId", ""), true, reachIp, reachPort, myIp, myPort,
                    json.optBoolean("relay", false)
                )
            }
            return
        }
        val req = IncomingPairRequest(
            reqId = json.optString("reqId", UUID.randomUUID().toString()),
            name = json.optString("name", "Device $clientIp"),
            pubB64 = peerPub,
            myIp = reachIp,
            myPort = reachPort,
            safetyCode = Crypto.safetyCode(myPub, peerPub),
            viaRelay = json.optBoolean("relay", false)
        )
        _incomingRequests.update { list ->
            val now = System.currentTimeMillis()
            // Any LAN host may POST here, so the pending list must stay bounded:
            // drop expired entries and keep only the newest requests.
            (list.filterNot { it.reqId == req.reqId }
                .filter { now - it.timestamp < INCOMING_REQUEST_TTL_MS } + req)
                .takeLast(MAX_INCOMING_REQUESTS)
        }
    }

    /** Handles POST /api/pair/response arriving at our server. */
    fun handleIncomingResponse(json: JSONObject, clientIp: String?) {
        val reqId = json.optString("reqId")
        var pending = _pendingOutgoing.value?.takeIf { it.reqId == reqId }
        if (pending != null) _pendingOutgoing.value = null
        else {
            pending = takeLateAnswer(reqId) ?: return
        }
        if (!json.optBoolean("accept", false)) {
            _pairEvent.value = "${pending.peer.name} declined pairing"
            return
        }
        val peerPub = json.optString("pub")
        if (peerPub.isBlank()) return
        // A relayed answer comes from the host's store, so the socket belongs to neither side;
        // the address the peer itself advertised is all there is to go on.
        val reachIp = json.optString("myIp").ifBlank { clientIp ?: pending.peer.ip }
        val reachPort = json.optInt("myPort", pending.peer.port)
        val replaced = addPairing(
            PairedDevice(
                pid = Crypto.pairId(myPubB64(), peerPub),
                name = json.optString("name", pending.peer.name),
                pubB64 = peerPub,
                originIp = reachIp,
                originPort = reachPort
            )
        )
        _pairEvent.value = pairingOutcome(pending.peer.name, replaced)
    }

    /**
     * One envelope pulled from a host's relay that is not chat: currently only the pairing answer
     * a server-less phone cannot be handed. Signals carry no secret — the same public keys the
     * direct endpoint already sends in the clear over the LAN.
     */
    fun handleRelaySignal(envelope: JSONObject): Boolean {
        val sig = envelope.optJSONObject("sig") ?: return false
        return when (sig.optString("type")) {
            "pair-response" -> {
                handleIncomingResponse(sig, null)
                true
            }
            else -> false
        }
    }

    /** Handles POST /api/pair/msg: decrypt with stored pairing key, route to chat. */
    fun handleIncomingMessage(json: JSONObject, clientIp: String): Boolean =
        routeEnvelope(json, clientIp)

    /**
     * Handles one mailbox envelope fetched from a host's relay. The socket belongs to the host,
     * not the sender, so the address is deliberately not learned from it — otherwise every
     * relayed message would re-point the pairing at the wrong phone.
     */
    fun handleRelayMessage(json: JSONObject): Boolean = routeEnvelope(json, null)

    private fun routeEnvelope(json: JSONObject, sourceIp: String?): Boolean {
        val pid = json.optString("pid")
        val pairing = findPairing(pid) ?: return false
        return try {
            val plain = Crypto.decryptText(secretKey(pairing), json.optString("iv"), json.optString("ct"))
            val payload = JSONObject(plain)
            // Decryption succeeded, so this connection really is the paired device.
            if (sourceIp != null) notePeerAddress(pid, sourceIp)
            // A retraction is addressed by message id, not by thread: ids are unique enough that
            // the recipient finds the one bubble this sender wrote, wherever it rendered it.
            if (payload.optString("op") == "del") {
                val retracted = payload.optString("del")
                if (retracted.isBlank()) return false
                onPairedDelete?.invoke(retracted)
                return true
            }
            val text = payload.optString("text")
            val sender = payload.optString("sender", pairing.name)
            if (text.isBlank()) return false
            val threadField = payload.optString("thread")
            val reply = payload.optJSONObject("reply")
            onPairedMessage?.invoke(
                ClipMessage(
                    id = payload.optString("mid").takeIf { it.isNotBlank() && it != "null" }
                        ?: UUID.randomUUID().toString(),
                    text = text,
                    sender = sender,
                    clientIp = sourceIp,
                    timestamp = payload.optLong("ts", System.currentTimeMillis()),
                    encrypted = true,
                    replyTo = reply?.optString("id")?.takeIf { it.isNotBlank() },
                    replySender = reply?.optString("sender")?.takeIf { it.isNotBlank() },
                    replyText = reply?.optString("text")?.takeIf { it.isNotBlank() },
                    // "room" is the shared conversation, which the app spells as a null thread;
                    // anything missing entirely is a plain one-to-one.
                    threadId = when {
                        threadField == ROOM_THREAD -> null
                        threadField.isNotBlank() -> threadField
                        else -> "pair:${pairing.pid}"
                    }
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendPairedMessage(
        pairing: PairedDevice,
        text: String,
        threadId: String? = null,
        reply: ClipMessage? = null
    ): Boolean {
        return try {
            val payload = messagePayload(text, threadId ?: "pair:${pairing.pid}", reply)
            deliverEncrypted(pairing, payload)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The message id travels inside the ciphertext rather than on the envelope, so a carrier
     * cannot renumber or duplicate mail, and both ends land on the same thread entry.
     */
    private fun messagePayload(text: String, threadId: String, reply: ClipMessage? = null): String {
        val json = JSONObject()
            .put("mid", UUID.randomUUID().toString())
            .put("text", text)
            .put("sender", nameProvider())
            .put("from", myDeviceId())
            .put("ts", System.currentTimeMillis())
            .put("thread", threadId)
        if (reply != null) json.put("reply", replySnapshot(reply))
        return json.toString()
    }

    /**
     * A quote is carried as a snapshot of what was answered, not just an id. The original may be
     * deleted, or may never reach this device at all, and the reply should still read correctly.
     */
    private fun replySnapshot(reply: ClipMessage): JSONObject = JSONObject()
        .put("id", reply.id)
        .put("sender", reply.sender)
        .put("text", reply.text.take(REPLY_SNAPSHOT_CHARS).ifBlank { "[attachment]" })

    private fun deliverEncrypted(pairing: PairedDevice, payload: String): Boolean {
        val (ivB64, ctB64) = try {
            Crypto.encryptText(secretKey(pairing), payload)
        } catch (e: Exception) {
            return false
        }
        val body = JSONObject()
            .put("pid", pairing.pid)
            .put("iv", ivB64)
            .put("ct", ctB64)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        if (isDirectlyReachable(pairing.pid)) {
            val direct = try {
                client.newCall(
                    Request.Builder()
                        .url("http://${pairing.originIp}:${pairing.originPort}/api/pair/msg")
                        .post(body).build()
                ).execute().use {
                    it.isSuccessful && JSONObject(it.body?.string() ?: "{}").optBoolean("success", false)
                }
            } catch (e: Exception) {
                // No listener of its own, or it is asleep. Not an error — the relay is for exactly this.
                markUnreachable(pairing.pid)
                false
            }
            if (direct) {
                clearUnreachable(pairing.pid)
                return true
            }
        }
        val sender = relaySender ?: return false
        return try {
            sender(pairing, ivB64, ctB64)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fans a group message out to every member, each copy sealed with that
     * member's own pairwise key. No single ciphertext is shared, so a member
     * cannot read traffic to a device they are not paired with.
     */
    fun sendGroupMessage(group: ChatGroup, text: String, reply: ClipMessage? = null): Int {
        val payload = messagePayload(text, "group:${group.id}", reply)
        var delivered = 0
        for (pid in group.memberPids) {
            val pairing = findPairing(pid) ?: continue
            try {
                if (deliverEncrypted(pairing, payload)) delivered++
            } catch (e: Exception) {
                // member offline
            }
        }
        return delivered
    }

    /**
     * Posts one message to every paired device's private copy of the shared room.
     *
     * Without this, "Everyone" only meant *our* room: browsers attached to this phone saw it, a
     * second phone in the same room did not, because that phone keeps its own app-side list. Two
     * phones each hosting a server were two islands; this is what stitches the room together. Each
     * copy is sealed with that device's own key and the senders id is carried along so every device
     * renders one bubble rather than one per recipient.
     */
    fun broadcastToRoom(text: String, mid: String? = null, reply: ClipMessage? = null): Int {
        val json = JSONObject()
            .put("mid", mid ?: UUID.randomUUID().toString())
            .put("text", text)
            .put("sender", nameProvider())
            .put("from", myDeviceId())
            .put("ts", System.currentTimeMillis())
            .put("thread", ROOM_THREAD)
        if (reply != null) json.put("reply", replySnapshot(reply))
        val payload = json.toString()
        var delivered = 0
        for (pairing in _paired.value) {
            try {
                if (deliverEncrypted(pairing, payload)) delivered++
            } catch (e: Exception) {
                // that device is offline
            }
        }
        return delivered
    }

    /**
     * Retracts one message on the devices it originally reached. The tombstone travels on exactly
     * the paths the message did, sealed the same way, so nobody gains a way to unilaterally erase
     * a conversation they were only listening to. Returns how many devices were told.
     */
    fun deleteEverywhere(messageId: String, threadId: String?): Int {
        if (messageId.isBlank()) return 0
        val targets = when {
            threadId == null -> _paired.value
            threadId.startsWith("pair:") -> listOfNotNull(findPairing(threadId.removePrefix("pair:")))
            threadId.startsWith("group:") -> {
                val group = findGroup(threadId.removePrefix("group:"))
                    ?: return 0
                group.memberPids.mapNotNull { findPairing(it) }
            }
            // An "ip:" thread lives in the browser room, which the server marks directly.
            else -> return 0
        }
        val payload = JSONObject()
            .put("op", "del")
            .put("del", messageId)
            .put("from", myDeviceId())
            .put("ts", System.currentTimeMillis())
            .toString()
        var delivered = 0
        for (pairing in targets) {
            try {
                if (deliverEncrypted(pairing, payload)) delivered++
            } catch (e: Exception) {
                // that device is offline
            }
        }
        return delivered
    }

    fun createGroup(name: String, memberPids: List<String>): ChatGroup? {
        val trimmed = name.trim()
        if (trimmed.isBlank() || memberPids.size < 2) return null
        val group = ChatGroup(id = UUID.randomUUID().toString().take(8), name = trimmed, memberPids = memberPids)
        _groups.update { it + group }
        saveGroups()
        _pairEvent.value = "Group \"${group.name}\" created with ${memberPids.size} devices"
        return group
    }

    fun deleteGroup(id: String) {
        val g = _groups.value.firstOrNull { it.id == id } ?: return
        _groups.update { list -> list.filterNot { it.id == id } }
        saveGroups()
        _pairEvent.value = "Group \"${g.name}\" removed"
    }

    fun findGroup(id: String): ChatGroup? = _groups.value.firstOrNull { it.id == id }

    /** Encrypts a content-uri file to a temp cipher file; returns it with its size. */
    suspend fun prepareEncryptedUpload(pairing: PairedDevice, uri: android.net.Uri): File? {
        return withContext(Dispatchers.IO) {
            try {
                val out = File.createTempFile("ld-pair-", ".enc", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { fo ->
                        Crypto.encryptChunked(secretKey(pairing), input, fo)
                    }
                } ?: return@withContext null
                out
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Called by the server when an upload arrives flagged with a pairing id.
     * Decrypts the cipher temp file into [finalFile].
     */
    fun decryptUpload(pid: String, cipherFile: File, finalFile: File): Boolean {
        val pairing = findPairing(pid) ?: return false
        return try {
            FileInputStream(cipherFile).use { input ->
                FileOutputStream(finalFile).use { out ->
                    Crypto.decryptChunked(secretKey(pairing), input, out)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun load() {
        val raw = prefs.getString("pairs", "[]") ?: "[]"
        val list = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PairedDevice(
                    pid = o.getString("pid"),
                    name = o.getString("name"),
                    pubB64 = o.getString("pub"),
                    originIp = o.optString("ip"),
                    originPort = o.optInt("port", 8080),
                    createdAt = o.optLong("created", System.currentTimeMillis()),
                    verified = o.optBoolean("verified", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        _paired.value = list
        loadGroups()
    }

    private fun loadGroups() {
        val raw = prefs.getString("groups", "[]") ?: "[]"
        _groups.value = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val members = o.getJSONArray("members")
                ChatGroup(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    memberPids = (0 until members.length()).map { members.getString(it) },
                    createdAt = o.optLong("created", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveGroups() {
        val arr = JSONArray()
        _groups.value.forEach { g ->
            arr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("members", JSONArray(g.memberPids))
                    .put("created", g.createdAt)
            )
        }
        prefs.edit().putString("groups", arr.toString()).apply()
    }

    private fun save() {
        val arr = JSONArray()
        _paired.value.forEach { p ->
            arr.put(
                JSONObject()
                    .put("pid", p.pid)
                    .put("name", p.name)
                    .put("pub", p.pubB64)
                    .put("ip", p.originIp)
                    .put("port", p.originPort)
                    .put("created", p.createdAt)
                    .put("verified", p.verified)
            )
        }
        prefs.edit().putString("pairs", arr.toString()).apply()
    }
}
