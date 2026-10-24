package com.pulsedrop.app.security

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/** Everything that happens before a pairing exists: requests, prompts, answers, retries. */
class PairHandshake(
    private val repo: PairRepository,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val events: MutableStateFlow<String?>,
    private val deps: PairingDeps
) {

    private val _incomingRequests = MutableStateFlow<List<IncomingPairRequest>>(emptyList())
    val incomingRequests: StateFlow<List<IncomingPairRequest>> = _incomingRequests.asStateFlow()

    private val _pendingOutgoing = MutableStateFlow<PendingPairRequest?>(null)
    val pendingOutgoing: StateFlow<PendingPairRequest?> = _pendingOutgoing.asStateFlow()

    init {
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

    /** The acceptor picks the KDF for a new pair; both ends then keep it forever. */
    private fun negotiateKdf(peerAppVersion: String): Int =
        if (PairRepository.versionAtLeast(peerAppVersion, 2, 0) &&
            PairRepository.versionAtLeast(deps.appVersion(), 2, 0)
        ) Kdf.V2 else Kdf.V1

    fun sendPairRequest(peer: PeerDevice) {
        // Checked before anything is remembered as "pending": a request carrying no key would sit
        // there waiting for an answer to a pairing this phone cannot take part in.
        val myPub = myPubOrNull() ?: return
        val reqId = UUID.randomUUID().toString()
        val (myIp, myPort) = deps.endpoint()
        val relayed = !deps.acceptsInbound()
        _pendingOutgoing.value = PendingPairRequest(peer, reqId, viaRelay = relayed)
        scope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("reqId", reqId)
                    .put("name", deps.deviceName())
                    .put("pub", myPub)
                    .put("appVersion", deps.appVersion())
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
                        events.value = "Pair request did not reach ${peer.name}"
                    }
                }
            } catch (e: Exception) {
                _pendingOutgoing.value = null
                events.value = "Pair request failed: ${e.message}"
            }
        }
        if (relayed) pollRelayedPairAnswer(peer, reqId)
        scope.launch {
            delay(60_000L)
            val pending = _pendingOutgoing.value
            if (pending?.reqId == reqId) {
                _pendingOutgoing.value = null
                rememberLateAnswer(pending)
                events.value = "No pairing answer from ${peer.name} yet — try again later"
            }
        }
    }

    /**
     * A phone with its server off has no socket for the answer to arrive on, so it asks for it.
     * The request id is the only thing that authorizes the fetch, and it is a uuid this device
     * invented, so nothing new is exposed that the direct POST back did not already rely on.
     */
    private fun pollRelayedPairAnswer(peer: PeerDevice, reqId: String) {
        val poller = deps.relayAnswerPoller() ?: return
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

    /**
     * Our identity public key, or null with the reason already told to the user.
     *
     * Pairing is the one feature that cannot proceed without a key to hand over, and the outcome to
     * avoid is a half-pairing: one side storing a device whose key the other side never sent. So
     * every point that would have stamped our own key passes through here, and an attempt that
     * cannot be signed leaves the world as it found it — the request still on screen, Accept still
     * pressable once the key can be read again.
     */
    private fun myPubOrNull(): String? = try {
        repo.myPubB64()
    } catch (e: IdentityKeyUnavailableException) {
        events.value = IdentityKeyPair.explain()
        null
    }

    /** [myPubOrNull] without the sentence: for the answer that is sendable either way, where a
     * missing key is not the thing the user asked about. */
    private fun readMyPubOrNull(): String? = try {
        repo.myPubB64()
    } catch (e: IdentityKeyUnavailableException) {
        null
    }

    fun acceptIncoming(request: IncomingPairRequest) {
        // Stamped with our key, so without one there is nothing to hand over: the request stays on
        // the screen and Accept can be pressed again once the key can be read.
        val myPub = myPubOrNull() ?: return
        val (myIp, myPort) = deps.endpoint()
        val paired = PairedDevice(
            pid = Crypto.pairId(myPub, request.pubB64),
            name = request.name,
            pubB64 = request.pubB64,
            originIp = request.myIp,
            originPort = request.myPort,
            appVersion = request.appVersion,
            kdfVersion = negotiateKdf(request.appVersion),
        )
        // Keep the key the user already confirmed; accepting again from the same device is a
        // network hiccup far more often than it is a genuine rekey. The stored KDF version is
        // kept for the same reason: it was decided once and both ends must keep using it.
        val existing = repo.findPairing(paired.pid)
        val device = if (existing != null) {
            paired.copy(verified = existing.verified, kdfVersion = existing.kdfVersion)
        } else {
            paired
        }
        val replaced = repo.addPairing(device)
        _incomingRequests.update { list -> list.filterNot { it.reqId == request.reqId } }
        events.value = pairingOutcome(request.name, replaced)
        scope.launch(Dispatchers.IO) {
            respondToPair(request.reqId, true, request.myIp, request.myPort, myIp, myPort, request.viaRelay, device.kdfVersion)
        }
    }

    fun declineIncoming(request: IncomingPairRequest) {
        val (myIp, myPort) = deps.endpoint()
        _incomingRequests.update { list -> list.filterNot { it.reqId == request.reqId } }
        events.value = "Declined pairing from ${request.name}"
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
        viaRelay: Boolean = false,
        kdfVersion: Int? = null
    ) {
        // An acceptance is a promise about a key, so it cannot go without one; a decline says only
        // "no", which is why a phone with no identity can still send it.
        val myPub = if (accept) myPubOrNull() ?: return else readMyPubOrNull()
        val body = JSONObject()
            .put("reqId", reqId)
            .put("type", "pair-response")
            .put("accept", accept)
            .put("name", deps.deviceName())
            .put("appVersion", deps.appVersion())
            .put("myIp", myIp)
            .put("myPort", myPort)
        if (myPub != null) body.put("pub", myPub)
        // Additive: old requesters never see this key (we only ever send V1 to them) and
        // would ignore it anyway; v2 requesters store whatever we decided.
        if (kdfVersion != null && kdfVersion != Kdf.V1) body.put("kdf", kdfVersion)
        // A phone with its server off cannot be handed this; it comes and takes it.
        if (viaRelay) {
            val sink = deps.answerSink()
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

    /**
     * Handles POST /api/pair/request arriving at our server.
     *
     * False when nothing was queued, so the route never reports a request this phone did not take.
     */
    fun handleIncomingRequest(json: JSONObject, clientIp: String): Boolean {
        val peerPub = json.optString("pub")
        if (peerPub.isBlank()) return false
        // Prefer the address the requester advertises as reachable for replies;
        // the socket peer IP is a fallback (differs under port-forwarding / NAT).
        val reachIp = json.optString("myIp").ifBlank { clientIp }
        val reachPort = json.optInt("myPort", 8080)
        val myPub = myPubOrNull()
        if (myPub == null) {
            // Answer rather than stall: a requester that hears nothing waits out its whole minute,
            // and a decline is the one pairing message this phone can send without an identity.
            val (myIp, myPort) = deps.endpoint()
            scope.launch(Dispatchers.IO) {
                respondToPair(
                    json.optString("reqId", ""), false, reachIp, reachPort, myIp, myPort,
                    json.optBoolean("relay", false)
                )
            }
            return false
        }
        // Already paired with this exact key: re-answer silently so a device that lost its
        // half of the pairing (missed response, reinstall of state) heals without a prompt.
        val healPid = Crypto.pairId(myPub, peerPub)
        if (repo.paired.value.any { it.pid == healPid }) {
            val (myIp, myPort) = deps.endpoint()
            val existingKdf = repo.findPairing(healPid)?.kdfVersion
            scope.launch(Dispatchers.IO) {
                respondToPair(
                    json.optString("reqId", ""), true, reachIp, reachPort, myIp, myPort,
                    json.optBoolean("relay", false), existingKdf
                )
            }
            return true
        }
        val req = IncomingPairRequest(
            reqId = json.optString("reqId", UUID.randomUUID().toString()),
            name = json.optString("name", "Device $clientIp"),
            pubB64 = peerPub,
            myIp = reachIp,
            myPort = reachPort,
            safetyCode = Crypto.safetyCode(myPub, peerPub),
            viaRelay = json.optBoolean("relay", false),
            appVersion = json.optString("appVersion", "")
        )
        _incomingRequests.update { list ->
            val now = System.currentTimeMillis()
            // Any LAN host may POST here, so the pending list must stay bounded:
            // drop expired entries and keep only the newest requests.
            (list.filterNot { it.reqId == req.reqId }
                .filter { now - it.timestamp < INCOMING_REQUEST_TTL_MS } + req)
                .takeLast(MAX_INCOMING_REQUESTS)
        }
        return true
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
            events.value = "${pending.peer.name} declined pairing"
            return
        }
        val peerPub = json.optString("pub")
        if (peerPub.isBlank()) return
        // A relayed answer comes from the host's store, so the socket belongs to neither side;
        // the address the peer itself advertised is all there is to go on.
        val reachIp = json.optString("myIp").ifBlank { clientIp ?: pending.peer.ip }
        val reachPort = json.optInt("myPort", pending.peer.port)
        val myPub = myPubOrNull() ?: return
        val replaced = repo.addPairing(
            PairedDevice(
                pid = Crypto.pairId(myPub, peerPub),
                name = json.optString("name", pending.peer.name),
                pubB64 = peerPub,
                originIp = reachIp,
                originPort = reachPort,
                appVersion = json.optString("appVersion", ""),
                kdfVersion = json.optInt("kdf", Kdf.V1)
            )
        )
        events.value = pairingOutcome(pending.peer.name, replaced)
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

    /** The 6-digit code to read aloud on both devices; equal on both sides for a given pair. */
    fun safetyCodeFor(pid: String): String? {
        val device = repo.findPairing(pid) ?: return null
        return try {
            Crypto.safetyCode(repo.myPubB64(), device.pubB64)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Callers share one message builder so a success toast can never stomp the rekey warning:
     * re-pairing under a new identity is exactly what a downgrade looks like from here.
     */
    private fun pairingOutcome(name: String, clash: PairedDevice?): String =
        if (clash == null) "Paired with $name — end-to-end encrypted"
        else "Paired with $name, but this is a new key. " +
            "Check the safety code matches the old one before trusting it."

    companion object {
        private const val MAX_INCOMING_REQUESTS = 12
        private const val INCOMING_REQUEST_TTL_MS = 120_000L
        private const val LATE_ANSWER_MS = 10 * 60_000L
    }
}

/**
 * What the handshake needs to know about this device and the world outside it, supplied by
 * whoever owns the PairManager so the handshake never reaches into app state itself.
 */
interface PairingDeps {
    fun deviceName(): String
    fun appVersion(): String
    fun endpoint(): Pair<String, Int>
    fun acceptsInbound(): Boolean
    fun relayAnswerPoller(): ((ip: String, port: Int, reqId: String) -> JSONObject?)?
    fun answerSink(): ((reqId: String, answer: JSONObject) -> Unit)?
}
