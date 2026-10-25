package com.pulsedrop.app.security

import android.content.Context
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.PairedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Traffic that travels *after* a pairing exists: sealed envelopes in both directions, group
 * fan-out, room broadcasts, retractions, and the chunked crypto around file uploads.
 */
class PairedMail(
    private val context: Context,
    private val repo: PairRepository,
    private val client: OkHttpClient,
    private val deps: MailDeps
) {

    /** Seal a payload with the recipient's own key so a third party can carry it unread. */
    fun sealFor(pairing: PairedDevice, payload: String): Pair<String, String>? =
        try {
            Crypto.encryptText(repo.secretKey(pairing), payload)
        } catch (e: Exception) {
            null
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
        val pairing = repo.findPairing(pid) ?: return false
        return try {
            val plain = Crypto.decryptText(repo.secretKey(pairing), json.optString("iv"), json.optString("ct"))
            val payload = JSONObject(plain)
            // Decryption succeeded, so this connection really is the paired device.
            if (sourceIp != null) repo.notePeerAddress(pid, sourceIp)
            // Everything below talks about a message that already exists, by its id rather than by
            // thread: ids are unique enough that the recipient finds the one bubble the sender wrote,
            // wherever it rendered. An unknown or absent `op` falls through to the message path, so a
            // build older than any of these signals reads a plain message exactly as it always did.
            when (payload.optString("op")) {
                OP_DELETE -> {
                    val retracted = payload.optString("del")
                    if (retracted.isBlank()) return false
                    deps.onDelete(retracted)
                    return true
                }
                OP_REACT -> {
                    val target = payload.optString("rea")
                    if (target.isBlank()) return false
                    deps.onReact(
                        target,
                        payload.optString("value").takeIf { !payload.optBoolean("clear") },
                        payload.optString("sender", pairing.name)
                    )
                    return true
                }
                OP_EDIT -> {
                    val target = payload.optString("edi")
                    val text = payload.optString("body")
                    if (target.isBlank() || text.isBlank()) return false
                    // The signal carries its author, so a peer that edits somebody else's bubble is
                    // refused on the receiving device rather than trusted by the one that sent it.
                    deps.onEdited(
                        target,
                        text,
                        payload.optLong("at", System.currentTimeMillis()),
                        payload.optString("from")
                    )
                    return true
                }
                OP_READ -> {
                    val threadField = payload.optString("thread")
                    deps.onRead(
                        if (threadField.isBlank() || threadField == ROOM_THREAD) null else threadField,
                        payload.optLong("through")
                    )
                    return true
                }
            }
            val text = payload.optString("text")
            val sender = payload.optString("sender", pairing.name)
            if (text.isBlank()) return false
            val threadField = payload.optString("thread")
            val reply = payload.optJSONObject("reply")
            deps.onMessage(
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
                ?: return false
            deliverEncrypted(pairing, payload)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The message id travels inside the ciphertext rather than on the envelope, so a carrier
     * cannot renumber or duplicate mail, and both ends land on the same thread entry.
     *
     * Null when this phone has no device id to claim: mail signed by nobody is not mail.
     */
    private fun messagePayload(
        text: String,
        threadId: String,
        reply: ClipMessage? = null
    ): String? {
        val from = repo.myDeviceIdOrNull() ?: return null
        val json = JSONObject()
            .put("mid", UUID.randomUUID().toString())
            .put("text", text)
            .put("sender", deps.deviceName())
            .put("from", from)
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
            Crypto.encryptText(repo.secretKey(pairing), payload)
        } catch (e: Exception) {
            return false
        }
        val body = JSONObject()
            .put("pid", pairing.pid)
            .put("iv", ivB64)
            .put("ct", ctB64)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        if (repo.isDirectlyReachable(pairing.pid)) {
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
                repo.markUnreachable(pairing.pid)
                false
            }
            if (direct) {
                repo.clearUnreachable(pairing.pid)
                return true
            }
        }
        if (deps.relaySend(pairing, ivB64, ctB64)) return true
        return deps.hostDrop(pairing, ivB64, ctB64)
    }

    /**
     * Fans a group message out to every member, each copy sealed with that
     * member's own pairwise key. No single ciphertext is shared, so a member
     * cannot read traffic to a device they are not paired with.
     */
    fun sendGroupMessage(group: ChatGroup, text: String, reply: ClipMessage? = null): Int {
        // `from` is what makes a sealed copy attributable, and an envelope we cannot sign is not
        // mail: it is a message from nowhere that the reader has no way to disbelieve.
        val payload = messagePayload(text, "group:${group.id}", reply) ?: return 0
        var delivered = 0
        for (pid in group.memberPids) {
            val pairing = repo.findPairing(pid) ?: continue
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
    fun broadcastToRoom(
        text: String,
        mid: String? = null,
        reply: ClipMessage? = null,
        /** A browser's message carried through our hub keeps its author instead of ours. */
        senderName: String? = null
    ): Int {
        val from = repo.myDeviceIdOrNull() ?: return 0
        val json = JSONObject()
            .put("mid", mid ?: UUID.randomUUID().toString())
            .put("text", text)
            .put("sender", senderName ?: deps.deviceName())
            .put("from", from)
            .put("ts", System.currentTimeMillis())
            .put("thread", ROOM_THREAD)
        if (reply != null) json.put("reply", replySnapshot(reply))
        val payload = json.toString()
        var delivered = 0
        for (pairing in repo.paired.value) {
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
    fun deleteEverywhere(messageId: String, threadId: String?): Int =
        signal(messageId, threadId) { it.put("op", OP_DELETE).put("del", messageId) }

    /**
     * Puts, or removes, one reaction on a message everywhere it went.
     *
     * A reaction is a signal about an existing message rather than a message: it names the id it
     * answers instead of carrying its own, so a device that never saw the original ignores it rather
     * than inventing a bubble. Null [reaction] clears it.
     */
    fun reactEverywhere(messageId: String, reaction: String?, threadId: String?): Int =
        signal(messageId, threadId) {
            it.put("op", OP_REACT).put("rea", messageId)
            if (reaction == null) it.put("clear", true) else it.put("value", reaction)
        }

    /** Replaces the text of one of our own messages on every device holding it. */
    fun editEverywhere(messageId: String, text: String, threadId: String?): Int =
        signal(messageId, threadId) {
            // The new words go under "body", never "text": a build from before edits falls through
            // the op dispatch to the message path, and a payload with a "text" and no "mid" would
            // render as a brand-new bubble rather than being ignored.
            it.put("op", OP_EDIT).put("edi", messageId).put("body", text)
                .put("at", System.currentTimeMillis())
        }

    /**
     * Tells the other side how far we have read.
     *
     * A watermark rather than a per-message ack: the peer marks everything at or before [through]
     * seen, so a reader that was offline for forty messages sends one signal instead of forty, and
     * the sender's ticks cannot disagree about which half of the thread is read.
     */
    fun noteRead(threadId: String?, through: Long): Int =
        signal(through.toString(), threadId) {
            it.put("op", OP_READ).put("thread", threadId ?: ROOM_THREAD).put("through", through)
        }

    /**
     * Sends one small payload about an existing message along the paths that message travelled.
     *
     * Shared with the retraction deliberately: a signal must reach exactly the devices that hold the
     * message, and the rule for that - the pairing for a one-to-one, every member for a group, every
     * paired phone for the room - is the one rule both features already had to get right.
     */
    private fun signal(messageId: String, threadId: String?, build: (JSONObject) -> JSONObject): Int {
        if (messageId.isBlank()) return 0
        val targets = when {
            threadId == null -> repo.paired.value
            threadId.startsWith("pair:") -> listOfNotNull(repo.findPairing(threadId.removePrefix("pair:")))
            threadId.startsWith("group:") -> {
                val group = repo.findGroup(threadId.removePrefix("group:"))
                    ?: return 0
                group.memberPids.mapNotNull { repo.findPairing(it) }
            }
            // An "ip:" thread lives in the browser room, which the server marks directly.
            else -> return 0
        }
        val from = repo.myDeviceIdOrNull() ?: return 0
        val payload = build(
            JSONObject()
                .put("from", from)
                // Signals carry the author's name as well as the device id: a reaction is shown to
                // the reader as a person, while an edit is checked against the id, which is the only
                // half a renamed peer cannot forge.
                .put("sender", deps.deviceName())
                .put("ts", System.currentTimeMillis())
        ).toString()
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

    /** Encrypts a content-uri file to a temp cipher file; returns it with its size. */
    suspend fun prepareEncryptedUpload(pairing: PairedDevice, uri: android.net.Uri): File? {
        return withContext(Dispatchers.IO) {
            try {
                val out = File.createTempFile("ld-pair-", ".enc", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { fo ->
                        Crypto.encryptChunked(repo.secretKey(pairing), input, fo)
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
        val pairing = repo.findPairing(pid) ?: return false
        return try {
            FileInputStream(cipherFile).use { input ->
                FileOutputStream(finalFile).use { out ->
                    Crypto.decryptChunked(repo.secretKey(pairing), input, out)
                }
            }
            true
        } catch (e: Exception) {
            // Records are written as they authenticate, so a stream that turns bad halfway has left
            // its healthy prefix on disk under a name the received listing reads. Refusing the upload
            // is only fail-closed if the plaintext the refusal describes goes with it, and that cannot
            // be left to whichever caller happens to remember a cleanup.
            finalFile.delete()
            false
        }
    }

    companion object {
        /** Wire value for the shared room; on receipt it maps back to the app's null thread. */
        private const val ROOM_THREAD = "room"
        // A quote shows enough of the original to identify it; the rest stays on the sender's device.
        private const val REPLY_SNAPSHOT_CHARS = 200

        /**
         * The `op` values a sealed payload can carry, three letters each.
         *
         * Short because these ride inside ciphertext the peer AES-GCMs open on arrival, and long
         * because a wire name outlives the code that invented it: a device three versions old still
         * has to tell a retraction from a read receipt, so the strings are part of the protocol and
         * not of this file.
         */
        private const val OP_DELETE = "del"
        private const val OP_REACT = "rea"
        private const val OP_EDIT = "edi"
        private const val OP_READ = "red"
    }
}

/**
 * The outside world mail can reach: who we are called, where delivered messages go, and which
 * carriers are attached to carry a sealed envelope when the recipient has no listener.
 */
interface MailDeps {
    fun deviceName(): String
    fun onMessage(message: ClipMessage)
    fun onDelete(messageId: String)

    /** A reaction on the message with this id; null clears it. [byName] is who put it there. */
    fun onReact(messageId: String, reaction: String?, byName: String)

    /** An edit of a message we hold, with the device id that wrote it. */
    fun onEdited(messageId: String, text: String, at: Long, byDeviceId: String)

    /** The peer has read everything in [threadId] up to [through]. */
    fun onRead(threadId: String?, through: Long)
    fun relaySend(pairing: PairedDevice, ivB64: String, ctB64: String): Boolean
    fun hostDrop(pairing: PairedDevice, ivB64: String, ctB64: String): Boolean
}
