package com.pulsedrop.app.security

import android.content.Context
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PendingPairRequest
import com.pulsedrop.app.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/**
 * The entry point everything else uses for pairing: who we are paired with, the handshake that
 * creates a pairing, the sealed mail that travels over one, and the proof tokens that authorize
 * uploads and relay use.
 *
 * It is a facade over four collaborators — [PairRepository] (the store), [PairHandshake]
 * (request/accept/decline), [PairedMail] (sealed transport) and [ProofTokens] (key-proof
 * authorization) — kept so callers see one object with one set of providers attached to it.
 */
class PairManager(context: Context, scope: CoroutineScope) {

    private val _pairEvent = MutableStateFlow<String?>(null)
    val pairEvent: StateFlow<String?> = _pairEvent.asStateFlow()

    fun consumeEvent() {
        _pairEvent.value = null
    }

    // ---- what this device says about itself -----------------------------------

    var nameProvider: () -> String = { "PulseDrop Phone" }

    /**
     * The release this app runs, exchanged at pairing so both halves of a connection know what
     * the other can actually do. An empty string is what an app from before this handshake has.
     */
    var versionProvider: () -> String = { "" }
    var endpointProvider: () -> Pair<String, Int> = { "127.0.0.1" to 8080 }
    var onPairedMessage: ((ClipMessage) -> Unit)? = null

    /**
     * A message the sender retracted. It arrives on the same sealed channel as the message itself,
     * so only the two ends of that pairing learn that it went away.
     */
    var onPairedDelete: ((String) -> Unit)? = null

    /**
     * A reaction added to, or removed from, a message this device holds. Same sealed channel as the
     * message, so a reaction is only ever visible to the two ends of the pairing.
     */
    var onPairedReaction: ((messageId: String, reaction: String?, byName: String) -> Unit)? = null

    /** One of the peer's own messages, rewritten. Carries the device id that wrote it. */
    var onPairedEdit: ((messageId: String, text: String, at: Long, byDeviceId: String) -> Unit)? = null

    /** The peer has read this thread up to the given point. */
    var onPairedRead: ((threadId: String?, through: Long) -> Unit)? = null

    /**
     * Set once a relay is attached. When a peer has no listener of its own — its server is off,
     * or it is a device we only ever reach through somebody else — the *sealed* envelope is handed
     * here instead of being POSTed at its address. Only the ciphertext crosses, never the plaintext,
     * so nothing downstream of this hook can forward what it cannot itself read.
     */
    var relaySender: ((pairing: PairedDevice, ivB64: String, ctB64: String) -> Boolean)? = null

    /**
     * Set while this device is hosting. A phone that reads its mail from our own mailbox is not
     * remote — the sealed envelope can be queued here directly, which is what lets a browser's
     * room message reach an attached phone that runs no server of its own.
     */
    var hostMailDropper: ((pairing: PairedDevice, ivB64: String, ctB64: String) -> Boolean)? = null

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

    // ---- collaborators ----------------------------------------------------------

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val repo = PairRepository(context, _pairEvent)

    private val handshake = PairHandshake(
        repo = repo,
        client = client,
        scope = scope,
        events = _pairEvent,
        deps = object : PairingDeps {
            override fun deviceName() = nameProvider()
            override fun appVersion() = versionProvider()
            override fun endpoint() = endpointProvider()
            override fun acceptsInbound(): Boolean = this@PairManager.acceptsInbound()
            override fun relayAnswerPoller() = relayPairPoller
            override fun answerSink() = pairAnswerSink
        }
    )

    private val mail = PairedMail(
        context = context,
        repo = repo,
        client = client,
        deps = object : MailDeps {
            override fun deviceName() = nameProvider()
            override fun onMessage(message: ClipMessage) { onPairedMessage?.invoke(message) }
            override fun onDelete(messageId: String) { onPairedDelete?.invoke(messageId) }
            override fun onReact(messageId: String, reaction: String?, byName: String) {
                onPairedReaction?.invoke(messageId, reaction, byName)
            }
            override fun onEdited(messageId: String, text: String, at: Long, byDeviceId: String) {
                onPairedEdit?.invoke(messageId, text, at, byDeviceId)
            }
            override fun onRead(threadId: String?, through: Long) {
                onPairedRead?.invoke(threadId, through)
            }
            override fun relaySend(pairing: PairedDevice, ivB64: String, ctB64: String) = try {
                relaySender?.invoke(pairing, ivB64, ctB64) == true
            } catch (e: Exception) {
                false
            }
            override fun hostDrop(pairing: PairedDevice, ivB64: String, ctB64: String) = try {
                hostMailDropper?.invoke(pairing, ivB64, ctB64) == true
            } catch (e: Exception) {
                false
            }
        }
    )

    private val tokens = ProofTokens(repo)

    // ---- store passthrough -------------------------------------------------------

    val paired: StateFlow<List<PairedDevice>> get() = repo.paired
    val groups: StateFlow<List<ChatGroup>> get() = repo.groups
    val incomingRequests: StateFlow<List<IncomingPairRequest>> get() = handshake.incomingRequests
    val pendingOutgoing: StateFlow<PendingPairRequest?> get() = handshake.pendingOutgoing

    fun myPubB64(): String = repo.myPubB64()
    fun myDeviceId(): String = repo.myDeviceId()
    fun myDeviceIdOrNull(): String? = repo.myDeviceIdOrNull()
    fun deviceIdOf(pairing: PairedDevice): String = repo.deviceIdOf(pairing)
    fun secretKey(p: PairedDevice): SecretKeySpec = repo.secretKey(p)
    fun pairForPeer(peer: PeerDevice): PairedDevice? = repo.pairForPeer(peer)
    fun findPairing(pid: String): PairedDevice? = repo.findPairing(pid)
    fun findPairingByDeviceId(deviceId: String): PairedDevice? = repo.findPairingByDeviceId(deviceId)

    /**
     * Whether a paired device has announced a release at least [major].[minor]. A device that
     * paired before versions were exchanged reports nothing, and nothing is treated honestly:
     * as older than anything we could ask of it.
     */
    fun peerAtLeast(pairing: PairedDevice, major: Int, minor: Int): Boolean =
        repo.peerAtLeast(pairing, major, minor)

    /** Same rule for a bare version string: unknown or missing counts as oldest. */
    fun versionAtLeast(version: String, major: Int, minor: Int): Boolean =
        PairRepository.versionAtLeast(version, major, minor)

    fun isDirectlyReachable(pid: String): Boolean = repo.isDirectlyReachable(pid)
    fun markUnreachable(pid: String) = repo.markUnreachable(pid)
    fun clearUnreachable(pid: String) = repo.clearUnreachable(pid)
    fun notePeerAddress(pid: String, ip: String) = repo.notePeerAddress(pid, ip)

    fun setVerified(pid: String, verified: Boolean) = repo.setVerified(pid, verified)

    // ---- handshake ------------------------------------------------------------------

    fun sendPairRequest(peer: PeerDevice) = handshake.sendPairRequest(peer)
    fun cancelPendingRequest() = handshake.cancelPendingRequest()
    fun acceptIncoming(request: IncomingPairRequest) = handshake.acceptIncoming(request)
    fun declineIncoming(request: IncomingPairRequest) = handshake.declineIncoming(request)
    fun safetyCodeFor(pid: String): String? = handshake.safetyCodeFor(pid)
    fun handleIncomingRequest(json: JSONObject, clientIp: String) =
        handshake.handleIncomingRequest(json, clientIp)
    fun handleIncomingResponse(json: JSONObject, clientIp: String?) =
        handshake.handleIncomingResponse(json, clientIp)
    fun handleRelaySignal(envelope: JSONObject): Boolean = handshake.handleRelaySignal(envelope)

    fun unpair(pid: String) {
        val p = repo.removePairing(pid) ?: return
        _pairEvent.value = "Unpaired ${p.name}"
    }

    // ---- proof tokens ----------------------------------------------------------------

    fun uploadProof(pid: String, contentLength: Long): String? = tokens.uploadProof(pid, contentLength)
    fun verifyUploadProof(pid: String, contentLength: Long, proof: String): Boolean =
        tokens.verifyUploadProof(pid, contentLength, proof)
    fun relayAuth(hostDeviceId: String, role: String): Pair<String, String>? =
        tokens.relayAuth(hostDeviceId, role)
    fun verifyRelayAuth(pid: String, role: String, proof: String): String? =
        tokens.verifyRelayAuth(pid, role, proof)

    // ---- sealed mail -------------------------------------------------------------------

    fun sealFor(pairing: PairedDevice, payload: String): Pair<String, String>? =
        mail.sealFor(pairing, payload)
    fun handleIncomingMessage(json: JSONObject, clientIp: String): Boolean =
        mail.handleIncomingMessage(json, clientIp)
    fun handleRelayMessage(json: JSONObject): Boolean = mail.handleRelayMessage(json)
    fun sendPairedMessage(pairing: PairedDevice, text: String, threadId: String? = null, reply: ClipMessage? = null): Boolean =
        mail.sendPairedMessage(pairing, text, threadId, reply)
    fun sendGroupMessage(group: ChatGroup, text: String, reply: ClipMessage? = null): Int =
        mail.sendGroupMessage(group, text, reply)
    fun broadcastToRoom(text: String, mid: String? = null, reply: ClipMessage? = null, senderName: String? = null): Int =
        mail.broadcastToRoom(text, mid, reply, senderName)
    fun deleteEverywhere(messageId: String, threadId: String?): Int =
        mail.deleteEverywhere(messageId, threadId)
    fun reactEverywhere(messageId: String, reaction: String?, threadId: String?): Int =
        mail.reactEverywhere(messageId, reaction, threadId)
    fun editEverywhere(messageId: String, text: String, threadId: String?): Int =
        mail.editEverywhere(messageId, text, threadId)
    fun noteRead(threadId: String?, through: Long): Int = mail.noteRead(threadId, through)
    suspend fun prepareEncryptedUpload(pairing: PairedDevice, uri: android.net.Uri): File? =
        mail.prepareEncryptedUpload(pairing, uri)
    fun decryptUpload(pid: String, cipherFile: File, finalFile: File): Boolean =
        mail.decryptUpload(pid, cipherFile, finalFile)

    // ---- groups ---------------------------------------------------------------------------

    fun createGroup(name: String, memberPids: List<String>): ChatGroup? {
        val trimmed = name.trim()
        // One other device is already a group: this is a phone, a tablet and a browser tab on one
        // LAN, and a two-person group is the first case anybody actually tries to make. Requiring
        // three participants made the feature unreachable for most setups.
        if (trimmed.isBlank() || memberPids.isEmpty()) return null
        val group = ChatGroup(id = UUID.randomUUID().toString().take(8), name = trimmed, memberPids = memberPids)
        repo.addGroup(group)
        return group
    }

    fun deleteGroup(id: String) {
        repo.removeGroup(id)
    }

    fun findGroup(id: String): ChatGroup? = repo.findGroup(id)
}
