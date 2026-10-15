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
private const val PROOF_TTL_MS = 120_000L
private const val LATE_ANSWER_MS = 10 * 60_000L

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
            val seen = synchronized(usedProofs) {
                val now = System.currentTimeMillis()
                usedProofs.entries.filter { it.value <= now }.map { it.key }.forEach { usedProofs.remove(it) }
                while (usedProofs.size > 64) usedProofs.remove(usedProofs.keys.first())
                val nonceKey = parts[0] + '|' + fields[4]
                if (usedProofs.containsKey(nonceKey)) true else {
                    usedProofs[nonceKey] = now + PROOF_TTL_MS
                    false
                }
            }
            !seen
        } catch (e: Exception) {
            false
        }
    }

    fun sendPairRequest(peer: PeerDevice) {
        val reqId = UUID.randomUUID().toString()
        val (myIp, myPort) = endpointProvider()
        _pendingOutgoing.value = PendingPairRequest(peer, reqId)
        scope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("reqId", reqId)
                    .put("name", nameProvider())
                    .put("pub", myPubB64())
                    .put("myIp", myIp)
                    .put("myPort", myPort)
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
            respondToPair(request.reqId, true, request.myIp, request.myPort, myIp, myPort)
        }
    }

    fun declineIncoming(request: IncomingPairRequest) {
        val (myIp, myPort) = endpointProvider()
        _incomingRequests.update { list -> list.filterNot { it.reqId == request.reqId } }
        _pairEvent.value = "Declined pairing from ${request.name}"
        scope.launch(Dispatchers.IO) {
            respondToPair(request.reqId, false, request.myIp, request.myPort, myIp, myPort)
        }
    }

    private fun respondToPair(reqId: String, accept: Boolean, peerIp: String, peerPort: Int, myIp: String, myPort: Int) {
        val body = JSONObject()
            .put("reqId", reqId)
            .put("accept", accept)
            .put("name", nameProvider())
            .put("pub", myPubB64())
            .put("myIp", myIp)
            .put("myPort", myPort)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        // A single blip here used to leave us paired and the peer not, with no way to notice.
        for (attempt in 0 until 3) {
            if (attempt > 0) try { Thread.sleep(if (attempt == 1) 3_000L else 8_000L) } catch (e: InterruptedException) { return }
            try {
                client.newCall(
                    Request.Builder().url("http://$peerIp:$peerPort/api/pair/response")
                        .post(body).build()
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
                respondToPair(json.optString("reqId", ""), true, reachIp, reachPort, myIp, myPort)
            }
            return
        }
        val req = IncomingPairRequest(
            reqId = json.optString("reqId", UUID.randomUUID().toString()),
            name = json.optString("name", "Device $clientIp"),
            pubB64 = peerPub,
            myIp = reachIp,
            myPort = reachPort,
            safetyCode = Crypto.safetyCode(myPub, peerPub)
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
    fun handleIncomingResponse(json: JSONObject, clientIp: String) {
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
        val replaced = addPairing(
            PairedDevice(
                pid = Crypto.pairId(myPubB64(), peerPub),
                name = json.optString("name", pending.peer.name),
                pubB64 = peerPub,
                originIp = json.optString("myIp", clientIp),
                originPort = json.optInt("myPort", pending.peer.port)
            )
        )
        _pairEvent.value = pairingOutcome(pending.peer.name, replaced)
    }

    /** Handles POST /api/pair/msg: decrypt with stored pairing key, route to chat. */
    fun handleIncomingMessage(json: JSONObject, clientIp: String): Boolean {
        val pid = json.optString("pid")
        val pairing = findPairing(pid) ?: return false
        return try {
            val plain = Crypto.decryptText(secretKey(pairing), json.optString("iv"), json.optString("ct"))
            val payload = JSONObject(plain)
            // Decryption succeeded, so this connection really is the paired device.
            notePeerAddress(pid, clientIp)
            val text = payload.optString("text")
            val sender = payload.optString("sender", pairing.name)
            if (text.isBlank()) return false
            onPairedMessage?.invoke(
                ClipMessage(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    sender = sender,
                    clientIp = clientIp,
                    encrypted = true,
                    threadId = payload.optString("thread").takeIf { it.isNotBlank() }
                        ?: "pair:${pairing.pid}"
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendPairedMessage(pairing: PairedDevice, text: String, threadId: String? = null): Boolean {
        return try {
            val payload = JSONObject()
                .put("text", text)
                .put("sender", nameProvider())
                .put("ts", System.currentTimeMillis())
                .put("thread", threadId ?: "pair:${pairing.pid}")
                .toString()
            deliverEncrypted(pairing, payload)
        } catch (e: Exception) {
            false
        }
    }

    private fun deliverEncrypted(pairing: PairedDevice, payload: String): Boolean {
        val (ivB64, ctB64) = Crypto.encryptText(secretKey(pairing), payload)
        val body = JSONObject()
            .put("pid", pairing.pid)
            .put("iv", ivB64)
            .put("ct", ctB64)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return client.newCall(
            Request.Builder()
                .url("http://${pairing.originIp}:${pairing.originPort}/api/pair/msg")
                .post(body).build()
        ).execute().use {
            it.isSuccessful && JSONObject(it.body?.string() ?: "{}").optBoolean("success", false)
        }
    }

    /**
     * Fans a group message out to every member, each copy sealed with that
     * member's own pairwise key. No single ciphertext is shared, so a member
     * cannot read traffic to a device they are not paired with.
     */
    fun sendGroupMessage(group: ChatGroup, text: String): Int {
        val payload = JSONObject()
            .put("text", text)
            .put("sender", nameProvider())
            .put("ts", System.currentTimeMillis())
            .put("thread", "group:${group.id}")
            .toString()
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
