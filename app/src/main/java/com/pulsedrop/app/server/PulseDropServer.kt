package com.pulsedrop.app.server

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.R
import com.pulsedrop.app.discovery.PeerCapabilityRegistry
import com.pulsedrop.app.model.ChatAttachment
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.SharedFile
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.util.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID

/** A chat thread the embedded web client is allowed to list and post into. */
data class WebThread(val id: String, val title: String, val kind: String, val members: List<String>)

/** A browser session currently pointed at this server, as the phone sees it. */
data class WebClientInfo(
    val ip: String,
    val name: String,
    val userAgent: String,
    val lastSeen: Long,
    /** Reached over loopback: a browser on the phone itself, or one tunnelled in by adb. */
    val local: Boolean = false
)

/** A call requested from a browser tab. This record drives the ringing/answered UI on both ends. */
data class WebCall(
    val cid: String,
    val name: String,
    val ip: String,
    val video: Boolean,
    val state: String,
    val updatedAt: Long = System.currentTimeMillis(),
    /** True when the phone dialled the browser, so each side knows which end it is. */
    val outgoing: Boolean = false,
    /** Paired devices to invite once this is answered - a group call bridged through a browser tab. */
    val groupPids: List<String> = emptyList()
)

interface ServerTransferListener {
    fun onTransferStarted(transferId: String, fileName: String, fileSize: Long, direction: TransferDirection, remoteDevice: String, clientIp: String)
    fun onTransferProgress(transferId: String, bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long)
    fun onTransferCompleted(transferId: String, fileName: String, fileSize: Long, localFilePath: String?, direction: TransferDirection, remoteDevice: String, clientIp: String)
    fun onTransferFailed(transferId: String, fileName: String, error: String, direction: TransferDirection)
    fun onNewMessage(message: ClipMessage)
    /** A room message was deleted; every listener must drop it the same way, by id. */
    fun onMessageDeleted(id: String) {}
    /**
     * A browser posted to the shared room. The room is not just this phone's tabs: the hub has to
     * carry the message to every paired phone too, or a phone would read a different room than the
     * browser sitting next to it.
     */
    fun onRoomMessageFromWeb(message: ClipMessage) {}
    /** A browser retracted one of its own room messages; the tombstone travels with the message. */
    fun onRoomDeleteFromWeb(id: String) {}
    /**
     * An upload arrived while the phone insists on seeing files first. It sits in a pending
     * folder until somebody taps Accept or Decline on this device — the sending tab is already
     * told it landed; only keeping it is what waits for permission.
     */
    fun onPendingUpload(id: String, fileName: String, fileSize: Long, fromDevice: String) {}
    fun onWebCall(call: WebCall)
}

private const val PIN_MAX_ATTEMPTS = 8
private const val TLS_MAX_ATTEMPTS = 20
private const val PIN_LOCKOUT_MS = 60_000L
private const val PIN_LOCKOUT_MAX_STEPS = 4
private const val SERVER_CHAT_RETENTION = 2000
private const val TYPING_SENDER_LIMIT = 64
// Under this many bytes, compressing a body spends more of the phone's CPU than the radio time it
// saves, and a fingerprint nobody revalidates is only a header.
private const val BODY_COMPRESS_MIN_BYTES = 1_024
private const val WEB_CLIENT_TTL = 90_000L
// Matches CallEngine.RING_TIMEOUT_MS so a browser and a paired app are given up on alike.
private const val WEB_RING_TIMEOUT_MS = 35_000L
// How long a relay-mode phone may come back for the pairing answer it was never handed.
private const val PAIR_ANSWER_TTL_MS = 10 * 60_000L
// A chat envelope is text; anything larger belongs in a file transfer, not carried mail.
// Internal so a test can assert the dispatcher's ceiling clears the envelope it carries.
internal const val MAX_RELAY_ENVELOPE = 64 * 1024
private const val RELAY_VISITOR_TTL_MS = 90_000L
// The event stream's ticket scope; the other families are named after the object they open, so a
// ticket minted for one scope cannot spend it on another.
private const val EVENT_TICKET_SCOPE = "events"
private const val MAX_TLS_FAILURE_CHARS = 200

/**
 * What the browser client is allowed to load. It is one inline page plus its own API, fonts and
 * previews, so `default-src 'none'` is the accurate starting point rather than a restrictive one:
 * every fetch, EventSource, XHR, `<img>`, `<audio>`, `<video>` and PDF `<iframe>` in the client names
 * a path on this origin, and the only foreign scheme is the `blob:` URL that carries a remote call's
 * JPEG frame. The two `'unsafe-inline'` grants are the page's own shape, not an oversight: its style
 * block and every button's handler are inline, and a nonce cannot cover handler attributes. What the
 * policy still buys is that an injected string cannot load script from another host, call `eval`,
 * retarget `<base>`, post a form off-origin, or frame the room — the sinks `WebClientSafetyTest`
 * already refuses, now with a second line behind them.
 */
private const val WEB_CLIENT_CSP =
    "default-src 'none'; " +
        "script-src 'self' 'unsafe-inline'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob:; " +
        "media-src 'self' blob:; " +
        "frame-src 'self'; " +
        "connect-src 'self'; " +
        "font-src 'self'; " +
        "base-uri 'none'; " +
        "form-action 'none'; " +
        "frame-ancestors 'none'"

/**
 * NanoHTTPD accepts a socket and hands it to its own thread, then that thread lives for the whole
 * connection (5 s idle after the last response, and as long as an SSE tab stays open). A LAN peer
 * that opens connections and never writes would otherwise turn each one into a thread; the
 * per-address limiter counts requests and cannot see a socket that never sends one. This ceiling is
 * far above what a household of tabs and calls holds at once and far below what exhausts a phone.
 */
internal const val MAX_CONCURRENT_REQUESTS = 256

/**
 * Replaces [NanoHTTPD.DefaultAsyncRunner] only to refuse a connection once [MAX_CONCURRENT_REQUESTS]
 * are already open. The refusal is a close, not an HTTP answer: building a response would need the
 * thread the cap exists to avoid, and overload is the one case where the client is not owed a body.
 * Below the ceiling this behaves exactly like the default runner, and `closed` runs from the
 * handler's own `finally`, so a slot is released when the socket is.
 */
internal class BoundedAsyncRunner(
    private val limit: Int = MAX_CONCURRENT_REQUESTS
) : NanoHTTPD.AsyncRunner {

    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<NanoHTTPD.ClientHandler>()
    private var spawned = 0L

    override fun exec(code: NanoHTTPD.ClientHandler) {
        // `exec` is called from the single accept thread, so the size check and the add are not
        // racing each other; `closed` removing concurrently is what the set is for.
        if (running.size >= limit) {
            code.close()
            return
        }
        running.add(code)
        val number = ++spawned
        Thread(code, "PulseDrop-http-$number").apply { isDaemon = true }.start()
    }

    override fun closed(code: NanoHTTPD.ClientHandler) {
        running.remove(code)
    }

    override fun closeAll() {
        val open = running.toList()
        for (handler in open) handler.close()
        running.clear()
    }
}

/** Types a browser renders as a document, where script would run if the file were navigated to. */
private val ACTIVE_DOCUMENT_TYPES = setOf(
    "text/html",
    "application/xhtml+xml",
    "image/svg+xml",
    "text/xml",
    "application/xml"
)

// Anything that looks like an absolute path, including the Android schemes that carry one.
private val ABSOLUTE_PATH = Regex("""(?:[A-Za-z]:)?(?:/[^\s:,'"]+)+""")

/**
 * What `/api/info` may say about a secure port that never bound.
 *
 * The class name is diagnosis. The message is not a string this app wrote: a keystore or socket
 * exception can name a file under the app's private storage, and this text is shown in the tab's
 * toast. Redact path-shaped runs and bound the length; the full exception still reaches Logcat.
 */
internal fun describeTlsFailure(e: Exception): String {
    val kind = e.javaClass.simpleName.ifBlank { "Exception" }
    val message = (e.message ?: "").replace(ABSOLUTE_PATH, "<path>")
        .take(MAX_TLS_FAILURE_CHARS)
        .trim()
    return if (message.isEmpty()) kind else "$kind: $message"
}

class PulseDropServer(
    val port: Int,
    /**
     * `null` binds every interface, which is what a room served to a whole LAN needs from a phone —
     * and what a unit test must not do on a machine that has a public address. Tests pass loopback.
     */
    private val bindAddress: String? = null,
    private val context: Context,
    initialDeviceName: String,
    initialPinCode: String?,
    initialIsPinRequired: Boolean,
    initialListener: ServerTransferListener
) : NanoHTTPD(bindAddress, port) {

    /** Renaming the phone must not cost a live transfer, so the name is swapped in place. */
    @Volatile
    var deviceName: String = initialDeviceName
        private set

    fun rename(newName: String) {
        if (newName.isNotBlank()) deviceName = newName
    }

    /**
     * The server outlives a ViewModel (the foreground service keeps it serving), so the
     * callbacks are re-attached whenever a new ViewModel takes ownership of a live server.
     */
    @Volatile
    private var listener: ServerTransferListener = initialListener

    fun attachListener(newListener: ServerTransferListener) {
        listener = newListener
    }

    // Both listeners get their own counter, because a browser on the TLS port opens its own sockets.
    init {
        setAsyncRunner(BoundedAsyncRunner())
    }

    @Volatile
    private var pinCode: String? = initialPinCode
    @Volatile
    private var isPinRequired: Boolean = initialIsPinRequired

    /**
     * What a browser holds after it types the PIN. The PIN used to be handed back as the token, so
     * every tab kept the room secret in `localStorage` and in its download URLs with no expiry and no
     * way to pull one tab without changing the PIN for everyone. Sessions expire, can be revoked one
     * at a time, and die with the PIN or the process.
     */
    private val sessions = com.pulsedrop.app.security.SessionStore()

    /**
     * The one credential a URL may carry. `EventSource` cannot attach a header, and `<img>` and
     * `<a download>` cannot either, so those requests ask for a minutes-long ticket bound to their
     * address instead of putting a session token into history and referrers.
     */
    private val urlTickets = com.pulsedrop.app.security.UrlTicket()

    /**
     * A flood guard per address. The body ceilings bound one request's cost; this bounds how many
     * a tab may make. Generous enough for a call at 10 Hz plus every poll, tight enough that a loop
     * is told to slow down.
     */
    private val requestLimiter = com.pulsedrop.app.security.RequestRateLimiter()

    /** Presence is a 2 s heartbeat, so a write faster than one per 400 ms is not a typist. */
    private val typingLimiter = com.pulsedrop.app.security.RequestRateLimiter(
        capacity = 1.0, refillPerSecond = 2.5, maxKeys = 256
    )

    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    /** Keeps the idle watchdog from retiring the server while work is live. */
    fun markActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    @Volatile
    var pairManager: com.pulsedrop.app.security.PairManager? = null

    /**
     * Mail for paired devices that keep no listener of their own. Holding it here is what lets one
     * phone run the server for a whole room instead of every device having to host its own.
     */
    private val relayMailbox = com.pulsedrop.app.relay.RelayMailbox(context)

    /**
     * Pairing answers for relay-mode requesters, which have no socket to be answered on. Keyed by
     * the requester's own random request id, which is the only thing that authorizes the fetch.
     */
    private val pairAnswers = LinkedHashMap<String, Pair<JSONObject, Long>>()

    /** Device ids that have pulled or posted recently, i.e. phones reachable through us. */
    private val relayVisitors = LinkedHashMap<String, Long>()

    /**
     * When false, an incoming file waits on the phone for a decision instead of simply arriving.
     * Set from Settings; the pending folder, not the received one, holds anything waiting.
     */
    @Volatile
    var autoAcceptTransfers: Boolean = true

    /** One upload parked in the pending folder, with everything re-deriving needs to keep it. */
    private class StagedUpload(
        val id: String,
        val file: File,
        val name: String,
        val size: Long,
        val fromIp: String,
        val fromLabel: String,
        val toIp: String?,
        val transferId: String
    )

    private val pendingUploads = ArrayList<StagedUpload>()

    /** What the Settings sheet lists: id, name, size and who sent it, newest last. */
    fun pendingUploadsSnapshot(): List<com.pulsedrop.app.model.PendingUploadInfo> =
        synchronized(pendingUploads) {
            pendingUploads.map {
                com.pulsedrop.app.model.PendingUploadInfo(it.id, it.name, it.size, it.fromLabel)
            }
        }

    /** Keeps the file and hands it to the received folder; returns its name or null if unknown id. */
    fun acceptPendingUpload(id: String): String? {
        val staged = synchronized(pendingUploads) { pendingUploads.firstOrNull { it.id == id } ?: return null }
        val receivedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "WebDrop_Received").apply {
            if (!exists()) mkdirs()
        }
        // The name was free when the upload parked; re-check at accept, because anything could
        // have taken it in between. Accepting must never overwrite what is already on disk.
        var acceptedName = staged.name
        var target = File(receivedDir, acceptedName)
        if (target.exists()) {
            val nameWithoutExt = acceptedName.substringBeforeLast(".")
            val ext = acceptedName.substringAfterLast(".", "")
            val extSuffix = if (ext.isNotEmpty()) ".$ext" else ""
            acceptedName = "${nameWithoutExt}_${System.currentTimeMillis()}$extSuffix"
            target = File(receivedDir, acceptedName)
        }
        target.parentFile?.mkdirs()
        val moved = staged.file.renameTo(target) || run {
            staged.file.copyTo(target, overwrite = true)
            staged.file.delete()
            true
        }
        if (!moved) return null
        synchronized(pendingUploads) { pendingUploads.remove(staged) }
        val sharedItem = SharedFile(
            id = UUID.randomUUID().toString(),
            name = target.name,
            size = target.length(),
            mimeType = guessMimeType(target.name),
            uriString = target.absolutePath,
            uploadedByIp = staged.fromIp,
            targetClientIp = staged.toIp
        )
        synchronized(sharedFiles) { sharedFiles.add(sharedItem) }
        listener.onTransferStarted(staged.transferId, acceptedName, staged.size, TransferDirection.INCOMING, staged.fromLabel, staged.fromIp)
        listener.onTransferCompleted(staged.transferId, acceptedName, staged.size, target.absolutePath, TransferDirection.INCOMING, staged.fromLabel, staged.fromIp)
        pushWebEvent("file") {
            put("name", acceptedName); put("size", staged.size); put("from", staged.fromLabel)
            staged.toIp?.let { put("to", it) }
        }
        return acceptedName
    }

    /** Throws the file away; the sender is not told it arrived at all. */
    fun declinePendingUpload(id: String): Boolean {
        val staged = synchronized(pendingUploads) {
            val found = pendingUploads.firstOrNull { it.id == id } ?: return false
            pendingUploads.remove(found)
            try { found.file.delete() } catch (e: Exception) { }
            true
        }
        return staged
    }

    init {
        relayMailbox.load()
    }

    fun setPairAnswer(reqId: String, answer: JSONObject) {
        if (reqId.isBlank()) return
        synchronized(pairAnswers) {
            val now = System.currentTimeMillis()
            pairAnswers.entries.filter { it.value.second <= now }.map { it.key }.forEach { pairAnswers.remove(it) }
            while (pairAnswers.size > 32) pairAnswers.remove(pairAnswers.keys.first())
            pairAnswers[reqId] = answer to (now + PAIR_ANSWER_TTL_MS)
        }
    }

    fun takePairAnswer(reqId: String): JSONObject? = synchronized(pairAnswers) {
        val entry = pairAnswers.remove(reqId) ?: return null
        if (entry.second <= System.currentTimeMillis()) null else entry.first
    }

    /**
     * Carries sealed traffic between devices that pair with this host but do not host anything
     * themselves. The role is taken from the path rather than the body, so a client cannot present
     * a "post" token to empty somebody else's mailbox, and the sender identity comes from the key
     * it signed with rather than from whatever it claims in a `from` field.
     */
    private fun handleRelay(uri: String, json: JSONObject, clientIp: String): JSONObject {
        if (uri == "/api/relay/pair-poll") {
            val answer = takePairAnswer(json.optString("reqId"))
            return JSONObject()
                .put("found", answer != null)
                .apply { if (answer != null) put("answer", answer) }
        }
        val pm = pairManager
            ?: return JSONObject().put("success", false).put("error", "pairing off")
        val role = if (uri == "/api/relay/post") "post" else "pull"
        val senderDid = pm.verifyRelayAuth(json.optString("pid"), role, json.optString("proof"))
            ?: return JSONObject().put("success", false).put("error", "unauthorized")

        if (uri == "/api/relay/pull") {
            val acks = json.optJSONArray("ack")
            if (acks != null) {
                val ids = (0 until acks.length()).mapNotNull { acks.optString(it).takeIf { s -> s.isNotBlank() } }
                relayMailbox.ack(senderDid, ids)
            }
            val messages = JSONArray()
            relayMailbox.pending(senderDid).forEach { messages.put(it) }
            noteRelayActivity(senderDid, clientIp)
            return JSONObject().put("success", true).put("messages", messages)
        }

        val to = json.optString("to")
        val kind = json.optString("kind", "message")
        val envelope = JSONObject()
            .put("kind", kind)
            .put("from", senderDid)
            .put("pid", json.optString("pid"))
        if (kind == "signal") {
            val sig = json.optJSONObject("sig")
                ?: return JSONObject().put("success", false).put("error", "no signal")
            envelope.put("sig", sig)
        } else {
            envelope.put("iv", json.optString("iv")).put("ct", json.optString("ct"))
        }
        if (envelope.toString().toByteArray().size > MAX_RELAY_ENVELOPE) {
            return JSONObject().put("success", false).put("error", "too large")
        }
        // Addressed at us: no reason to queue what we can act on right now.
        if (to == pm.myDeviceIdOrNull()) {
            val handled = if (kind == "signal") {
                pm.handleRelaySignal(envelope)
            } else {
                pm.handleRelayMessage(envelope)
            }
            return JSONObject().put("success", true).put("queued", false).put("delivered", handled)
        }
        val eid = relayMailbox.post(to, envelope)
            ?: return JSONObject().put("success", false).put("error", "mailbox full")
        // We just told the sender this is safe; a process kill must not make that a lie.
        relayMailbox.flush()
        noteRelayActivity(to, clientIp)
        return JSONObject().put("success", true).put("queued", true).put("eid", eid)
    }

    /** So the UI can show which paired phones are actually reachable through this host. */
    private fun noteRelayActivity(deviceId: String, ip: String) {
        synchronized(relayVisitors) {
            val now = System.currentTimeMillis()
            relayVisitors.entries.filter { it.value <= now - RELAY_VISITOR_TTL_MS }
                .map { it.key }.forEach { relayVisitors.remove(it) }
            while (relayVisitors.size > 64) relayVisitors.remove(relayVisitors.keys.first())
            relayVisitors[deviceId] = now
        }
    }

    fun relayVisitorsSnapshot(): List<String> = synchronized(relayVisitors) {
        val now = System.currentTimeMillis()
        relayVisitors.entries.filter { it.value > now - RELAY_VISITOR_TTL_MS }.map { it.key }
    }

    /**
     * Queues an already-sealed envelope for a paired device that reads its mail from this host
     * instead of running a server of its own. Being the postbox does not make us the addressee:
     * the bytes stay sealed with that device's pairwise key, which we hold precisely because we
     * are paired with it — the same secret a direct POST would have used.
     */
    fun dropToRelayMailbox(
        toDeviceId: String,
        fromDeviceId: String,
        pid: String,
        ivB64: String,
        ctB64: String
    ): Boolean {
        val pm = pairManager ?: return false
        if (toDeviceId.isBlank() || toDeviceId == pm.myDeviceIdOrNull()) return false
        val envelope = JSONObject()
            .put("kind", "message")
            .put("from", fromDeviceId)
            .put("pid", pid)
            .put("iv", ivB64)
            .put("ct", ctB64)
        if (envelope.toString().toByteArray().size > MAX_RELAY_ENVELOPE) return false
        val eid = relayMailbox.post(toDeviceId, envelope) ?: return false
        // Told the sender it is safe; a process kill must not make that a lie.
        relayMailbox.flush()
        noteRelayActivity(toDeviceId, "")
        return true
    }

    @Volatile
    var callEngine: com.pulsedrop.app.call.CallEngine? = null

    /** Group chats the browser may join. Pair threads are end-to-end encrypted and never listed here. */
    @Volatile
    var webThreads: List<WebThread> = emptyList()

    @Volatile
    var webCall: WebCall? = null

    /**
     * Browsers currently looking at this server. A desktop is not a PulseDrop host — it has
     * no listener to dial — so it can only ever appear here, and the phone reaches it by
     * pushing files into its own hosted list.
     */
    private class WebClientSlot(var name: String, var ua: String, var lastSeen: Long)

    private val webClients = LinkedHashMap<String, WebClientSlot>()

    /** The server object is built when the session starts, so this doubles as "phone came online". */
    private val startedAtMs: Long = System.currentTimeMillis()

    private fun noteWebClient(ip: String, name: String?, userAgent: String?) {
        if (ip.isBlank()) return
        // The app polls its own server over loopback for typing state - that is not a browser.
        if (isLoopback(ip) && userAgent?.startsWith("okhttp") == true) return
        val now = System.currentTimeMillis()
        val local = isLoopback(ip)
        val fallback = if (local) "Browser tab" else "Browser"
        synchronized(webClients) {
            val slot = webClients[ip]
            if (slot == null) webClients[ip] = WebClientSlot(name ?: fallback, userAgent ?: "", now)
            else {
                slot.lastSeen = now
                if (!name.isNullOrBlank()) slot.name = name
                if (!userAgent.isNullOrBlank()) slot.ua = userAgent
            }
        }
    }

    private fun isLoopback(ip: String): Boolean =
        ip == "127.0.0.1" || ip == "::1" || ip.startsWith("::ffff:127.")

    /**
     * A backgrounded tab clamps `setInterval` to roughly one tick a minute, so a call dialled
     * from the phone could sit unseen for that long, which reads as "it never rang". These
     * streams push a doorbell the moment state changes instead; the tab reacts to network data,
     * which is not throttled the way its timers are.
     */
    private class EventStream(val clientIp: String) {
        val queue = java.util.concurrent.LinkedBlockingQueue<String>(64)
        @Volatile var closed = false
    }

    private val eventStreams = java.util.concurrent.CopyOnWriteArrayList<EventStream>()
    private val maxEventStreams = 8
    private val heartbeatMs = 20_000L

    /**
     * Doorbells only: the payload stays in the existing endpoints, so a tab that misses an
     * event merely falls back to the poll it already does rather than diverging.
     */
    private fun pushWebEvent(
        event: String,
        targetIp: String? = null,
        exceptIp: String? = null,
        fields: JSONObject.() -> Unit = {}
    ) {
        if (eventStreams.isEmpty()) return
        val o = JSONObject()
        o.fields()
        val frame = "event: $event\ndata: $o\n\n"
        for (s in eventStreams) {
            if (s.closed) continue
            if (targetIp != null && !sameAddr(s.clientIp, targetIp)) continue
            if (exceptIp != null && sameAddr(s.clientIp, exceptIp)) continue
            // A tab that stopped draining must not block the producer, so drop rather than wait.
            s.queue.offer(frame)
        }
    }

    /** SSE body: blocks on the queue, heartbeats to keep intermediaries from cutting it. */
    private inner class EventBody(private val stream: EventStream) : InputStream() {
        private var carry: ByteArray? = null
        private var at = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val c = carry
                if (c != null && at < c.size) {
                    val n = minOf(len, c.size - at)
                    System.arraycopy(c, at, b, off, n)
                    at += n
                    if (at >= c.size) { carry = null; at = 0 }
                    return n
                }
                if (stream.closed) return -1
                val frame = try {
                    stream.queue.poll(heartbeatMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (frame == null) {
                    if (!eventStreams.contains(stream)) return -1
                    carry = ":hb\n\n".toByteArray()
                } else {
                    carry = frame.toByteArray()
                }
            }
        }

        override fun close() {
            stream.closed = true
            eventStreams.remove(stream)
            super.close()
        }
    }

    /**
     * The address the phone dials was recorded from an earlier request, and NanoHTTPD reports
     * whatever family the socket happened to negotiate. `::ffff:192.168.1.9` and `192.168.1.9`
     * are the same tab, but a string compare says otherwise, and the call silently never rings.
     */
    private fun sameAddr(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        return normalizeAddr(a) == normalizeAddr(b)
    }

    private fun normalizeAddr(ip: String): String {
        var s = ip.trim()
        if (s.startsWith("::ffff:") || s.startsWith("::FFFF:")) s = s.substring(7)
        if (s.contains('.') && s.count { it == ':' } > 1) s = s.substringAfterLast(':')
        return if (isLoopback(s)) "127.0.0.1" else s.lowercase()
    }

    /**
     * Whether this client may read a file that was dropped into the room. A file with no target
     * is room-visible; a targeted one belongs to the client it was addressed to and to whoever
     * uploaded it.
     *
     * This rule used to be written four times — three copies of
     * `file.targetClientIp != clientIp && file.uploadedByIp != clientIp` and one inverted copy in the
     * file list — all comparing raw strings. `sameAddr` was written for exactly the dual-stack
     * mismatch described above and the file routes never started using it, so an owner addressed as
     * `::ffff:192.168.1.9` could be locked out of its own file by the code meant to protect it.
     */
    private fun receivedFileIsVisibleTo(file: SharedFile, clientIp: String): Boolean =
        file.targetClientIp == null ||
            sameAddr(file.targetClientIp, clientIp) ||
            sameAddr(file.uploadedByIp, clientIp)

    /**
     * Whether a browser may read a message in this thread — the room, an address book, or a group.
     *
     * Hoisted out of `/api/messages` because `/chatfile/{id}` has to answer the identical question,
     * and having the answer in one place is the point: the attachment store served any client any
     * attachment precisely because nobody had written this rule down for it.
     *
     * [hostIp] is passed in rather than looked up here because this runs per message, and
     * enumerating interfaces per message is a real cost on a phone.
     */
    private fun threadIsVisibleTo(threadId: String?, senderIp: String?, clientIp: String, hostIp: String): Boolean {
        // A message with no thread is the open room, which every client in it may read.
        if (threadId == null) return true
        // Anyone may read what they wrote, including a message addressed somewhere they cannot
        // otherwise see — that is what lets a tab retract its own send.
        if (sameAddr(senderIp, clientIp)) return true
        // The phone hosting this server is who every tab dials. A reply it wrote into its own
        // address book must reach the tab watching that conversation, and the phone is not a
        // browser, so it never matches the `ip:<tab>` test below by itself.
        if (threadId == "ip:$hostIp" && sameAddr(senderIp, hostIp)) return true
        // The thread key is the address as the writer saw it; compare normalised forms or a tab
        // loses its own conversation across an IPv4/IPv6 flip.
        if (threadId.startsWith("ip:") && normalizeAddr(threadId.substring(3)) == normalizeAddr(clientIp)) return true
        // A group the phone opened to browsers is open to the browsers it opened it for.
        return webThreads.any { it.id == threadId }
    }

    /**
     * Whether a browser may read a chat attachment's bytes: it uploaded them, or a message this
     * client is allowed to see carries that attachment id. An upload that has not been sent yet
     * belongs to its uploader alone.
     */
    private fun chatFileIsVisibleTo(id: String, clientIp: String): Boolean {
        val stored = synchronized(chatAttachments) { chatAttachments[id] } ?: return false
        if (sameAddr(stored.uploadedByIp, clientIp)) return true
        val hostIp = NetworkUtils.getLocalIpAddress()
        return synchronized(messages) {
            messages.any {
                it.attachment?.id == id && threadIsVisibleTo(it.threadId, it.clientIp, clientIp, hostIp)
            }
        }
    }

    /**
     * A response to send instead of reading this body, or null if it is small enough.
     *
     * This is a real ceiling rather than a polite request, and the reason is worth keeping straight:
     * NanoHTTPD 2.3.1's `HTTPSession.execute()` hands the session to `serve()` **before** the body is
     * read, so a route that answers here never touches the socket again. Once `parseBody` runs the
     * damage is already priced in — a JSON post is copied whole into a Java String, and a multipart
     * one is written to a file in `cacheDir` and memory-mapped back. Neither can be bounded from
     * outside: `getTmpBucket()` takes only `TempFile.getName()` and opens its own `RandomAccessFile`,
     * so wrapping the `TempFile` to count bytes would count nothing. The declared length is the one
     * number available before the read, which makes answering it early the whole job.
     *
     * A client that *under*-declares is not a disk-fill risk for the same reason: `parseBody` reads
     * exactly `Content-Length` bytes and stops, leaving the rest in the socket to be parsed as the
     * next pipelined request. That leftover is a request-smuggling question, not a storage one, and
     * is tracked separately.
     */
    private fun bodyTooLarge(session: IHTTPSession, maxBytes: Long): Response? {
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull() ?: return null
        if (declared in 0..maxBytes) return null
        val res = newFixedLengthResponse(
            Response.Status.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT, "Request body too large"
        )
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    /** What the browser called itself, if it ever told us. */
    private fun webClientName(ip: String): String =
        synchronized(webClients) { webClients[ip]?.name }
            ?: if (isLoopback(ip)) "Browser tab" else "Browser"

    fun webClientsSnapshot(ttlMs: Long = WEB_CLIENT_TTL): List<WebClientInfo> {
        val now = System.currentTimeMillis()
        return synchronized(webClients) {
            val stale = webClients.filterValues { now - it.lastSeen > ttlMs }.keys
            stale.forEach { webClients.remove(it) }
            webClients.entries.sortedByDescending { it.value.lastSeen }
                .map { (ip, s) -> WebClientInfo(ip, s.name, s.ua, s.lastSeen, isLoopback(ip)) }
        }
    }

    private val sharedFiles = mutableListOf<SharedFile>()
    private val messages = mutableListOf<ClipMessage>()
    /**
     * Presence keyed by the address that wrote it, not by the name it claimed. A name is a display
     * label; the address is the thing the server can actually check. Keying by name let any host
     * either impersonate a typist or remove one by sending `typing:false` under their name.
     */
    private data class TypingPresence(val name: String, val until: Long)
    private val typingSenders = mutableMapOf<String, TypingPresence>()

    /** Per-client PIN guess counter, so a 4-digit PIN cannot be swept brute-force. */
    private class PinAttempts {
        var wrongGuesses = 0
        var blockedUntil = 0L
        var lastWrongCredential: String? = null
    }

    private val pinAttempts = mutableMapOf<String, PinAttempts>()

    /** Shared by both listeners: the TLS delegate calls this instance's `serve`. */
    private val inFlightUploads = InFlightUploadBudget()

    /** What admitted uploads currently promise to write; a leak here would refuse later uploads. */
    internal fun uploadsInFlightBytes(): Long = inFlightUploads.reservedBytes()

    /**
     * Chat attachments live here instead of [sharedFiles]: a file sent in a conversation
     * stays in that conversation and never shows up in hosted files, history or stats.
     */
    private val chatAttachments = LinkedHashMap<String, ChatFile>()

    /** A file owned by a chat message. Chat history is restart-scoped, so is its store. */
    /**
     * A chat attachment and who may read it.
     *
     * `uploadedByIp` is the tab that POSTed the bytes, or null when the phone wrote them. It is not
     * enough on its own: an attachment the *phone* sends must reach the browser on the other side of
     * that thread, and an attachment a tab uploaded but never sent belongs to nobody else. The rest
     * of the answer is in `messages` — see [chatFileIsVisibleTo].
     */
    private data class ChatFile(val attachment: ChatAttachment, val file: File, val uploadedByIp: String?)

    private fun chatDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "WebDrop_Chat").apply { if (!exists()) mkdirs() }

    private val fontCache = HashMap<Int, ByteArray>()

    private fun fontBytes(resId: Int): ByteArray = synchronized(fontCache) {
        fontCache.getOrPut(resId) {
            context.resources.openRawResource(resId).use { it.readBytes() }
        }
    }

    /** The phone app answers or rejects a browser call; the browser polls for this. */
    fun updateWebCall(cid: String, state: String) {
        val c = webCall ?: return
        if (c.cid != cid) return
        val next = c.copy(state = state, updatedAt = System.currentTimeMillis())
        webCall = next
        syncWebCallMedia(next)
        listener.onWebCall(next)
        pushWebEvent("callstate") {
            put("cid", next.cid); put("state", next.state)
        }
    }

    /**
     * A browser tab is a media endpoint like any other device: 16 kHz PCM and JPEG frames both
     * ways. It cannot open the raw socket the apps dial, so its frames ride this server instead
     * — one chunked response down, one short POST up.
     */
    private fun syncWebCallMedia(call: WebCall) {
        val engine = callEngine ?: return
        when (call.state) {
            "accepted" -> if (!engine.state.value.active) {
                markActivity()
                if (call.groupPids.isNotEmpty()) {
                    engine.startWebCallGroup(call.video, webClientName(call.ip), call.groupPids)
                } else {
                    engine.startWebCall(call.video, webClientName(call.ip))
                }
            }
            "ended", "declined" -> if (engine.state.value.peerPid ==
                com.pulsedrop.app.call.CallEngine.WEB_PID
            ) {
                engine.stopWebCall(if (call.state == "declined") "Call declined" else "Call ended")
            }
        }
    }

    /** Frames headed to the tab, as one endless body. Ends with the leg. */
    private inner class WebMediaStream(private val cid: String) : InputStream() {
        private var carry: ByteArray? = null
        private var at = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val c = carry
                if (c != null && at < c.size) {
                    val n = minOf(len, c.size - at)
                    System.arraycopy(c, at, b, off, n)
                    at += n
                    if (at >= c.size) { carry = null; at = 0 }
                    return n
                }
                val call = webCall
                if (call == null || call.cid != cid || call.state != "accepted") return -1
                val engine = callEngine ?: return -1
                val frame = engine.nextWebOutFrame(2000)
                if (frame == null) {
                    if (!engine.hasWebLeg()) return -1
                    continue
                }
                carry = frame
            }
        }
    }

    /** The phone dials a browser tab: same channel, opposite direction. */
    fun ringWebClient(ip: String, name: String, video: Boolean, groupPids: List<String> = emptyList()): WebCall {
        val target = resolveWebClient(ip)
        val call = WebCall(
            cid = UUID.randomUUID().toString(),
            name = name,
            ip = target,
            video = video,
            state = "ringing",
            outgoing = true,
            groupPids = groupPids
        )
        webCall = call
        listener.onWebCall(call)
        pushWebEvent("call", targetIp = call.ip) {
            put("cid", call.cid); put("video", call.video); put("name", call.name)
        }
        armWebRingExpiry(call)
        return call
    }

    /**
     * The address a thread remembered may no longer be the address the tab is connected on
     * (renewed DHCP, an IPv4/IPv6 flip). If it is not live but exactly one browser is, ring that
     * one - otherwise a stale record means a call the user can never see.
     */
    private fun resolveWebClient(ip: String): String {
        val clients = webClientsSnapshot()
        clients.firstOrNull { sameAddr(it.ip, ip) }?.let { return it.ip }
        // Only re-point when there is one candidate; guessing among several tabs would ring a
        // browser the user never dialled.
        return if (clients.size == 1) clients.first().ip else ip
    }

    /**
     * A tab that rings the phone and then vanishes — closed, crashed, walked off the Wi-Fi — has
     * nobody left to poll this record, so nothing would ever clear the ringing screen. CallEngine
     * solves the same problem for app-to-app calls with a coroutine; the server has no scope.
     */
    private fun armWebRingExpiry(call: WebCall) {
        Thread {
            val woken = try {
                Thread.sleep(WEB_RING_TIMEOUT_MS); true
            } catch (e: InterruptedException) {
                false
            }
            val current = if (woken) webCall else null
            if (current != null && current.cid == call.cid && current.state == "ringing") {
                updateWebCall(current.cid, "ended")
            }
        }.apply { isDaemon = true }.start()
    }

    fun updatePinSecurity(required: Boolean, pin: String?) {
        val previousPin = pinCode
        isPinRequired = required
        pinCode = pin
        // A freshly generated PIN invalidates any earlier lockout.
        synchronized(pinAttempts) { pinAttempts.clear() }
        // And every browser that logged in under the old one — but only when the PIN genuinely
        // changed. `MainViewModel` re-asserts the current PIN every time a ViewModel takes over a
        // live server, so revoking unconditionally would log out every tab whenever the phone
        // rotates. A rotation is not a credential rotation.
        if (required && pin != null && pin.isNotBlank() && previousPin != null && previousPin != pin) {
            sessions.revokeAll()
            // A ticket outlives the request that minted it; revoking sessions must not leave a
            // five-minute tail of stream access behind.
            urlTickets.rotate()
        }
    }

    init {
        // Essential on Android: redirect temp files away from read-only /data/local/tmp to context.cacheDir
        tempFileManagerFactory = androidTempFiles()
        // Messages live in memory, so a previous run's attachments are unreachable orphans.
        File(context.getExternalFilesDir(null) ?: context.filesDir, "WebDrop_Chat").deleteRecursively()
    }

    private fun androidTempFiles(): TempFileManagerFactory = TempFileManagerFactory {
        object : TempFileManager {
            private val tempFiles = ArrayList<TempFile>()
            override fun createTempFile(filename_hint: String?): TempFile {
                val file = File.createTempFile("WebDrop-", ".tmp", context.cacheDir)
                val tempFile = object : TempFile {
                    override fun delete() {
                        try { file.delete() } catch (ignored: Exception) {}
                    }
                    override fun getName(): String = file.absolutePath
                    override fun open(): java.io.OutputStream = java.io.FileOutputStream(file)
                }
                tempFiles.add(tempFile)
                return tempFile
            }
            override fun clear() {
                for (file in tempFiles) {
                    try { file.delete() } catch (ignored: Exception) {}
                }
                tempFiles.clear()
            }
        }
    }

    // ---- secure side listener (browser media needs a https origin) --------------

    /** The https counterpart of [port], where browsers get full mic/camera/screen access. */
    val tlsPort: Int = port + 1

    @Volatile private var tlsServer: NanoHTTPD? = null
    @Volatile private var tlsHosts: List<String> = emptyList()
    @Volatile private var tlsStopped = false
    private var tlsRetryAtMs = 0L
    private var tlsFactory: javax.net.ssl.SSLServerSocketFactory? = null
    private var tlsFactoryHosts: List<String> = emptyList()

    // Why the secure port is not up. Without this the whole browser-call feature fails silently:
    // the tab polls /api/info, sees tlsUp=false, and waits forever on a "Secure mode" button that
    // never appears, while the phone's screen says nothing either.
    @Volatile private var tlsFailure: String? = null

    // NanoHTTPD's legacy session carries no secure flag, so the TLS delegate marks its
    // own worker threads and the redirect below asks them instead.
    private val secureOrigin = ThreadLocal.withInitial { false }

    override fun start() {
        // Before the listener exists, so nothing can be uploading: a process killed mid-copy never
        // runs the `finally` that removes its landing file. This name cannot come from a caller —
        // `sanitizeFileName` trims leading dots — so anything matching it is a leftover of ours.
        sweepPartialUploads()
        super.start()
        current = this
        tlsStopped = false
        ensureTls()
    }

    private fun sweepPartialUploads() {
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, RECEIVED_DIR_NAME)
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && PARTIAL_UPLOAD_NAME.matches(it.name) }
            .forEach { it.delete() }
    }

    override fun stop() {
        // Flag first: the TLS builder thread checks this, otherwise a delegate that
        // finishes binding after stop() would orphan port 8081 on a dead server.
        tlsStopped = true
        relayMailbox.flush()
        synchronized(this) { tlsServer?.stop(); tlsServer = null }
        if (current === this) current = null
        super.stop()
    }

    /** Call whenever the LAN address changes; the certificate carries the SAN list. */
    fun refreshTls(hosts: List<String>) {
        if (hosts.filter { it.isNotBlank() } == tlsHosts.filter { it.isNotBlank() }) return
        restartTls()
    }

    private fun restartTls() {
        if (tlsStopped) return
        synchronized(this) {
            tlsServer?.stop()
            tlsServer = null
        }
        ensureTls()
    }

    private fun ensureTls() {
        if (tlsServer != null || tlsStopped) return
        // RSA keypair generation is not free, and start() can be reached from a click handler.
        Thread {
            // A restart can collide with the previous delegate's closing socket; settle
            // and retry instead of silently losing the secure origin. A browser on plain http
            // has no mic, camera or screen access at all, so keep trying long after the
            // first few ports free up rather than giving up for the rest of the session.
            var attempt = 0
            while (!tlsStopped && ++attempt <= TLS_MAX_ATTEMPTS) {
                try {
                    val hosts = listOf(NetworkUtils.getLocalIpAddress()).filter { it.isNotBlank() } + tlsHosts
                    // Reuse the keypair across retries so the browser's cert warning
                    // doesn't reset every time the listener bounces.
                    var factory = tlsFactory
                    if (factory == null || tlsFactoryHosts != hosts) {
                        factory = WebTls.socketFactory(hosts)
                        tlsFactory = factory
                        tlsFactoryHosts = hosts
                    }
                    if (tlsStopped) return@Thread
                    val delegate = object : NanoHTTPD(bindAddress, tlsPort) {
                        override fun serve(session: IHTTPSession): Response {
                            secureOrigin.set(true)
                            try {
                                return this@PulseDropServer.serve(session)
                            } finally {
                                secureOrigin.set(false)
                            }
                        }
                    }
                    delegate.tempFileManagerFactory = androidTempFiles()
                    delegate.setAsyncRunner(BoundedAsyncRunner())
                    delegate.makeSecure(factory, null)
                    delegate.start(SOCKET_READ_TIMEOUT, false)
                    if (tlsStopped) {
                        try { delegate.stop() } catch (ignored: Exception) {}
                        return@Thread
                    }
                    synchronized(this) {
                        tlsHosts = hosts
                        tlsServer = delegate
                        tlsFailure = null
                    }
                    return@Thread
                } catch (e: Exception) {
                    // Logcat under its own tag, and kept for /api/info: a browser locked out of the
                    // camera has no other way to learn that the phone's https port never bound.
                    tlsFailure = describeTlsFailure(e)
                    Log.w("PulseDropTls", "secure port $tlsPort would not start (attempt $attempt)", e)

                    try {
                        Thread.sleep(minOf(400L * attempt, 4_000L))
                    } catch (ie: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** True once the https counterpart is actually listening. */
    fun tlsUp(): Boolean = tlsServer != null

    /** Why it is not listening, or null when it is (or has not been tried yet). */
    fun tlsError(): String? = if (tlsServer != null) null else tlsFailure

    /**
     * A visitor stuck on plain http has no mic, camera or screen access, so their request is a
     * good moment to try again - the port that refused us earlier may have freed up by now.
     */
    fun retryTls() {
        if (tlsServer != null || tlsStopped) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now < tlsRetryAtMs) return
            tlsRetryAtMs = now + 5_000L
        }
        ensureTls()
    }

    fun setSharedFiles(files: List<SharedFile>) {
        synchronized(sharedFiles) {
            sharedFiles.clear()
            sharedFiles.addAll(files)
        }
    }

    fun addMessage(
        text: String,
        sender: String,
        clientIp: String? = null,
        threadId: String? = null,
        attachment: ChatAttachment? = null,
        replyTo: ClipMessage? = null,
        id: String? = null,
        /** The browser's own post to the room, which the hub must carry to every paired phone too. */
        fromWeb: Boolean = false
    ): ClipMessage {
        val msg = ClipMessage(
            id = id ?: UUID.randomUUID().toString(),
            text = text,
            sender = sender,
            clientIp = clientIp,
            threadId = threadId,
            attachment = attachment,
            replyTo = replyTo?.id,
            replySender = replyTo?.sender,
            replyText = replyTo?.text?.take(200)
        )
        synchronized(messages) {
            messages.add(msg)
            // Chat is restart-scoped but a long-lived server must not grow without bound.
            while (messages.size > SERVER_CHAT_RETENTION) messages.removeAt(0)
        }
        listener.onNewMessage(msg)
        pushWebEvent("message", exceptIp = clientIp) {
            put("id", msg.id); put("threadId", msg.threadId ?: "")
        }
        if (fromWeb && msg.threadId == null) listener.onRoomMessageFromWeb(msg)
        return msg
    }

    /** The room message with this id, as a browser would have seen it. */
    fun findMessage(id: String): ClipMessage? = synchronized(messages) { messages.lastOrNull { it.id == id } }

    /**
     * Marks one room message deleted and tells every tab watching it. Deletion is a tombstone
     * rather than a removal so the thread keeps its shape on devices that already rendered it.
     */
    fun markMessageDeleted(id: String, fromWeb: Boolean = false): Boolean {
        val found = synchronized(messages) {
            val index = messages.indexOfLast { it.id == id }
            if (index < 0) null else {
                messages[index] = messages[index].copy(deleted = true, text = "")
                messages[index]
            }
        }
        // Ring the doorbell whether or not the rolling room list still holds it. A message older
        // than SERVER_CHAT_RETENTION is gone from here but still on every screen that rendered it,
        // and a delete that is never announced is a bubble that keeps its words until the visitor
        // reloads the tab.
        listener.onMessageDeleted(id)
        pushWebEvent("deleted") { put("id", id); put("threadId", found?.threadId ?: "") }
        if (fromWeb && found != null && found.threadId == null) listener.onRoomDeleteFromWeb(found.id)
        return found != null
    }

    /**
     * Brings a room message that arrived from a paired phone into the shared room, under the id its
     * sender chose. Keeping that id is what lets every device agree on which bubbles are the same
     * message instead of rendering one arrival twice under two ids.
     */
    fun ingestRoomMessage(msg: ClipMessage) {
        val added = synchronized(messages) {
            if (messages.any { it.id == msg.id }) false else {
                messages.add(msg)
                while (messages.size > SERVER_CHAT_RETENTION) messages.removeAt(0)
                true
            }
        }
        if (!added) return
        listener.onNewMessage(msg)
        pushWebEvent("message") {
            put("id", msg.id); put("threadId", msg.threadId ?: "")
        }
    }

    /**
     * Copies a locally picked file into the chat store so it can travel with a message.
     * Returns null when the file cannot be read or written.
     */
    fun storeChatAttachment(uri: Uri, displayName: String): ChatAttachment? {
        val safeName = sanitizeFileName(displayName).ifBlank { "attachment_${System.currentTimeMillis()}" }
        val id = UUID.randomUUID().toString()
        val target = File(chatDir(), "${id.take(8)}-$safeName")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
            val size = target.length()
            if (size <= 0L) {
                target.delete()
                return null
            }
            val attachment = ChatAttachment(id, safeName, size, guessMimeType(safeName))
            synchronized(chatAttachments) { chatAttachments[id] = ChatFile(attachment, target, null) }
            attachment
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** The on-disk file behind a chat attachment, for the app's own previewer. */
    fun chatAttachmentFile(id: String): File? =
        synchronized(chatAttachments) { chatAttachments[id] }?.file?.takeIf { it.isFile }

    private fun attachmentJson(attachment: ChatAttachment): JSONObject = JSONObject().apply {
        put("id", attachment.id)
        put("name", attachment.name)
        put("size", attachment.size)
        put("mime", attachment.mime)
    }

    /**
     * The caller a route is about to serve. [access] records *how* it was allowed, so a handler
     * that ever needs to distinguish a ticket from a session has the answer without re-reading
     * headers; [clientIp] is the address the scope rules use.
     */
    internal data class Principal(val clientIp: String, val access: RouteAccess)

    /**
     * Apply [rule] to this request. Null means "no answer but 401"; the route does not run.
     *
     * A ticket is only consulted for the five GET families that name an object — the ones the
     * browser cannot put a header on. `ticketAuthenticates` re-derives the scope from the URI, so a
     * ticket cannot be stretched to a different object by a caller-chosen parameter.
     */
    private fun authorize(session: IHTTPSession, rule: RouteRule): Principal? {
        val clientIp = session.remoteIpAddress ?: "127.0.0.1"
        val access = rule.accessFor(session.method.name)
        val allowed = when (access) {
            RouteAccess.PUBLIC, RouteAccess.SELF, RouteAccess.PAIRING -> true
            RouteAccess.SESSION -> isAuthorized(session)
            RouteAccess.SESSION_OR_TICKET ->
                isAuthorized(session) || ticketAuthenticates(session, clientIp)
        }
        return if (allowed) Principal(clientIp, access) else null
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (!isPinRequired || pinCode.isNullOrBlank()) return true
        val clientIp = session.remoteIpAddress ?: "127.0.0.1"
        if (isLockedOut(clientIp)) return false
        val headerAuth = session.headers["x-pulse-auth"]
        // A session token is accepted in the header only. It used to be accepted in a query string
        // too, for the event stream, which put a 12-hour bearer credential in history, in referrers
        // and in link previews. `/api/events` now takes a short-lived ticket instead, and no route
        // reads a credential from the URL. The PIN itself is accepted in the header because
        // `TransferManager` authenticates a phone-to-phone upload that way and an older phone must
        // still reach a newer one.
        if (sessions.isValid(headerAuth ?: "")) return true
        if (headerAuth != null && headerAuth == pinCode) return true
        // A paired app authenticates with the end-to-end key it already shares with us, so
        // paired sends keep working against a phone that requires a PIN.
        if (pairedSenderIsAuthorized(session)) return true
        // Only a *wrong* credential counts as a guess: visitors that have not been asked
        // for the PIN yet send nothing, so they must not be penalised.
        val submitted = headerAuth?.takeIf { it.isNotBlank() }
        if (submitted != null) recordPinFailure(clientIp, submitted)
        return false
    }

    private fun ticketAllows(session: IHTTPSession, scope: String, clientIp: String): Boolean {
        val ticket = session.parameters["ticket"]?.firstOrNull() ?: return false
        return urlTickets.allows(ticket, scope, normalizeAddr(clientIp))
    }

    /** The scope families [ticketAuthenticates] will spend; minting anything else is a mistake. */
    private fun ticketScopeIsServable(scope: String): Boolean =
        scope == EVENT_TICKET_SCOPE ||
            scope.startsWith("download:") ||
            scope.startsWith("folder:") ||
            scope.startsWith("chatfile:") ||
            scope.startsWith("preview:")

    /**
     * A URL credential for the four things a browser cannot put a header on: `<img>` and media
     * sources, `<a download>` links, and the event stream. The scope names the exact object the
     * route is about to read, so a ticket for one file cannot open another, and only these GET
     * routes accept one — a ticket is never a way into an API that changes state.
     */
    private fun ticketAuthenticates(session: IHTTPSession, clientIp: String): Boolean {
        if (session.method != Method.GET) return false
        val scope = when {
            session.uri == "/api/events" -> EVENT_TICKET_SCOPE
            session.uri.startsWith("/download/") ->
                "download:" + session.uri.removePrefix("/download/").substringBefore("?")
            session.uri == "/download-folder" ->
                "folder:" + (session.parameters["path"]?.firstOrNull() ?: "")
            session.uri.startsWith("/chatfile/") ->
                "chatfile:" + session.uri.removePrefix("/chatfile/").substringBefore("?")
            session.uri.startsWith("/preview/") ->
                "preview:" + session.uri.removePrefix("/preview/").substringBefore("?")
            else -> return false
        }
        return ticketAllows(session, scope, clientIp)
    }

    private fun unauthorized(): Response {
        val res = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "application/json",
            "{\"error\":\"Unauthorized. PIN required.\"}"
        )
        return res
    }

    private fun tooManyRequests(): Response {
        val res = newFixedLengthResponse(
            Response.Status.TOO_MANY_REQUESTS,
            "application/json",
            "{\"success\":false,\"error\":\"Too many requests\"}"
        )
        res.addHeader("Retry-After", "1")
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    /**
     * The volume cannot spare this body while the uploads already admitted are still paying for
     * theirs. This is not the sender's fault, so it is a retry rather than a refusal.
     */
    private fun uploadsBusy(): Response {
        val res = newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            "application/json",
            "{\"success\":false,\"error\":\"Another upload is already using the free space\"}"
        )
        res.addHeader("Retry-After", "5")
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun pairedSenderIsAuthorized(session: IHTTPSession): Boolean {
        if (session.uri != "/upload") return false
        val pm = pairManager ?: return false
        val pid = session.headers["x-pair-id"]?.takeIf { it.isNotBlank() } ?: return false
        val proof = session.headers["x-pair-auth"]?.takeIf { it.isNotBlank() } ?: return false
        val contentLength = session.headers["content-length"]?.trim()?.toLongOrNull() ?: return false
        if (!pm.verifyUploadProof(pid, contentLength, proof)) return false
        pm.notePeerAddress(pid, session.remoteIpAddress ?: "")
        return true
    }

    fun isLockedOut(clientIp: String): Boolean {
        val attempt = synchronized(pinAttempts) { pinAttempts[clientIp] } ?: return false
        return attempt.blockedUntil > System.currentTimeMillis()
    }

    fun lockoutRemainingSeconds(clientIp: String): Long {
        val attempt = synchronized(pinAttempts) { pinAttempts[clientIp] } ?: return 0
        return ((attempt.blockedUntil - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
    }

    fun recordPinFailure(clientIp: String, credential: String) {
        synchronized(pinAttempts) {
            val attempt = pinAttempts.getOrPut(clientIp) { PinAttempts() }
            // A browser left polling with an obsolete token repeats the same wrong value
            // forever; that is a stale client, not a guesser. Only distinct guesses count.
            if (attempt.lastWrongCredential == credential) return
            attempt.lastWrongCredential = credential
            attempt.wrongGuesses++
            if (attempt.wrongGuesses >= PIN_MAX_ATTEMPTS) {
                val steps = ((attempt.wrongGuesses - PIN_MAX_ATTEMPTS) / PIN_MAX_ATTEMPTS)
                    .coerceAtMost(PIN_LOCKOUT_MAX_STEPS)
                attempt.blockedUntil = System.currentTimeMillis() + PIN_LOCKOUT_MS * (1L shl steps)
            }
        }
    }

    fun clearPinFailures(clientIp: String) {
        synchronized(pinAttempts) { pinAttempts.remove(clientIp) }
    }

    override fun serve(session: IHTTPSession): Response {
        val response = dispatch(session)
        // Every reply, including 401, 404 and the inline previews: a page served by this phone
        // must not have its bytes re-typed into something executable, and following a link off
        // this origin must not tell the next host which LAN address it came from. Inline views
        // below set the same values; NanoHTTPD replaces a header by name, so this stays one.
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Referrer-Policy", "no-referrer")
        // JSON is the room's state, not a document: a tab that logs out must not leave the file
        // list or message page reachable from the back button. Routes that already chose a cache
        // policy (SSE, media, login) keep it; the page and its fonts are deliberately cacheable.
        if (response.getHeader("Cache-Control") == null &&
            response.mimeType?.substringBefore(';')?.trim().equals("application/json", ignoreCase = true) == true
        ) {
            response.addHeader("Cache-Control", "no-store")
        }
        // NanoHTTPD reads exactly Content-Length and then loops over the same stream, so bytes
        // appended after a short body are parsed as the next request. Nothing upstream can inject
        // them in this deployment, but a second request on a connection the tab already used is a
        // desync primitive with no legitimate user. Ending the connection here discards the
        // surplus; bodyless polling keeps connection reuse.
        if (session.headers.keys.any { it == "content-length" || it == "transfer-encoding" }) {
            response.closeConnection(true)
        }
        return revalidate(session, response)
    }

    /**
     * The text bodies a tab reads again and again: the page, and the room's JSON.
     *
     * Deliberately narrow. A media download, a range request and a zipped export are already
     * compressed bytes, and the event stream is a live sequence of frames small enough that
     * buffering them to save a few of their own would only delay delivery.
     */
    private val compressibleText = setOf("application/json", "text/html")

    /**
     * Whether to gzip this response, asked by NanoHTTPD itself once the handler has returned; when
     * this says yes the library adds `Content-Encoding: gzip` and switches the response to chunked
     * transfer, so the framing stays the library's rather than ours.
     *
     * A body that mixes attacker-chosen text with a secret is the standing argument against
     * compressing at all (BREACH), and this one does mix: message text from any sender goes out
     * inside JSON another tab can request repeatedly. What it never contains is a secret — the PIN,
     * the session token and the pairing proof are not in any response body, and the only reader who
     * can ask for these bytes is a tab that already presented one of them. A peer close enough to
     * time the compressed length of a room's messages is a peer already inside the room.
     */
    override fun useGzipWhenAccepted(r: Response): Boolean {
        val mime = r.mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (mime !in compressibleText) return false
        // Only a body the handler already holds in memory: measuring a stream would mean draining
        // it, and every streaming route in this file is one we just excluded on purpose.
        val body = r.data as? ByteArrayInputStream ?: return false
        return body.available() >= BODY_COMPRESS_MIN_BYTES
    }

    /**
     * Answer a repeated read of unchanged bytes with a fingerprint of them instead of the bytes.
     *
     * A tab polls the room every couple of seconds and most polls change nothing, so the body it
     * pays for — 517 KB of JSON at a 2,000-message history, before compression — is spent proving
     * that nothing happened. An `ETag` over the exact bytes lets a matching `If-None-Match` return
     * no body at all.
     *
     * The fingerprint is of the bytes this client would receive, not of the room: these routes
     * answer per-address, and a validator shared across two tabs that see different histories
     * would hand one tab the other's cached page. `Vary: Accept-Encoding` says the same thing
     * about the gzip half, which is a second representation of the same content rather than
     * different content.
     */
    private fun revalidate(session: IHTTPSession, response: Response): Response {
        if (response.status.requestStatus != 200) return response
        val mime = response.mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime !in compressibleText) return response
        val body = response.data as? ByteArrayInputStream ?: return response
        val etag = fingerprint(body) ?: return response
        response.addHeader("ETag", etag)
        response.addHeader("Vary", "Accept-Encoding")
        val offered = session.headers["if-none-match"] ?: return response
        if (offered.trim() != "*" && offered.split(",").none { it.trim().removePrefix("W/") == etag }) {
            return response
        }
        val notModified = NotModified(
            listOf(
                "Date" to httpDate(),
                "ETag" to etag,
                "Vary" to "Accept-Encoding",
                // The policy that produced the stored copy is the policy that keeps it; a 304 that
                // disagreed would be the one thing a cache cannot merge predictably.
                "Cache-Control" to (response.getHeader("Cache-Control") ?: "no-cache"),
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "no-referrer"
            ),
            closeAfter = response.isCloseConnection
        )
        response.getHeader("Content-Security-Policy")?.let { notModified.addHeader("Content-Security-Policy", it) }
        return notModified
    }

    /** A strong validator over the exact bytes, leaving the body where it was. */
    private fun fingerprint(body: ByteArrayInputStream): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        body.mark(body.available())
        digest.update(body.readBytes())
        body.reset()
        "\"" + digest.digest().take(8).joinToString("") { "%02x".format(it) } + "\""
    }.getOrNull()

    private fun httpDate(): String =
        java.text.SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date())

    /**
     * A `304` that carries nothing but headers.
     *
     * Hand-written rather than handed to `Response.send()`, because that method prints
     * `Content-Length: 0` for every fixed-length response and RFC 9110 forbids a `Content-Length`
     * on a `304` outright. A length header describing a body that must not exist is precisely what
     * a strict client answers with a protocol error, and a protocol error on the revalidation would
     * make every later poll look like the phone had vanished — the cure would be worse than a
     * repeated body.
     */
    private class NotModified(
        private val fields: List<Pair<String, String>>,
        private val closeAfter: Boolean
    ) : Response(Response.Status.NOT_MODIFIED, null, ByteArrayInputStream(ByteArray(0)), 0L) {

        override fun send(output: java.io.OutputStream) {
            val head = StringBuilder("HTTP/1.1 304 Not Modified\r\n")
            fields.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
            head.append("Connection: ").append(if (closeAfter) "close" else "keep-alive").append("\r\n\r\n")
            output.write(head.toString().toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    private fun dispatch(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val requestIp = session.remoteIpAddress ?: "127.0.0.1"

        // Every HTTP hit is a browser still sitting on the page, so the phone can list the
        // desktops connected to it. A browser has no listener, so this is the only way in.
        if (method != Method.OPTIONS) {
            noteWebClient(
                requestIp,
                session.headers["x-pulse-client"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } },
                session.headers["user-agent"]
            )
        }

        // Passive browser polling (page, info, file list, beam polling) must not keep the server alive
        val isPassivePolling = uri == "/" || uri == "/index.html" ||
                (uri == "/api/info" && method == Method.GET) ||
                (uri == "/api/files" && method == Method.GET) ||
                (uri == "/api/messages" && method == Method.GET) ||
                (uri == "/api/typing" && method == Method.GET)
        if (!isPassivePolling) lastActivityAt = System.currentTimeMillis()

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            return res
        }

        // A flood guard before anything reads a body or a credential: the ceilings bound what one
        // request costs, and this bounds how many of them an address may make.
        if (!requestLimiter.allow(normalizeAddr(requestIp))) return tooManyRequests()

        // The one authorization decision. Every route's requirement is in the table beside this
        // file; the dispatcher below no longer decides by position whether a route is protected.
        val rule = AuthorizationPolicy.forRequest(uri, method.name)
        val principal = authorize(session, rule) ?: return unauthorized()
        val clientIp = principal.clientIp

        // Google Sans, served by this device so the browser client is typed identically
        // to the app on a network with no upstream.
        WEB_FONTS[uri]?.let { resId ->
            val bytes = fontBytes(resId)
            val res = newFixedLengthResponse(
                Response.Status.OK,
                "font/ttf",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
            res.addHeader("Cache-Control", "public, max-age=604800")
            return res
        }

        // Web Client HTML served to browsers on the LAN
        if (uri == "/" || uri == "/index.html") {
            // Browsers only hand out mic/camera/screen on a secure origin, so lift plain
            // http visits onto the TLS port whenever that listener is actually up.
            if (!secureOrigin.get() && tlsServer != null) {
                val host = session.headers["host"]?.substringBefore(':')
                    ?.takeIf { it.isNotBlank() }
                    ?: NetworkUtils.getLocalIpAddress()
                val res = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
                res.addHeader("Location", "https://$host:$tlsPort/")
                return res
            }
            // Landing here means the secure port is not up yet; a fresh page load is a good
            // moment to try, and the tab's own poll will offer the switch once it binds.
            if (!secureOrigin.get()) retryTls()
            val html = WebClientHtml.getHtml(deviceName, isPinRequired && !pinCode.isNullOrBlank())
            val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
            res.addHeader("Content-Security-Policy", WEB_CLIENT_CSP)
            return res
        }

        // API Info
        if (uri == "/api/info" && method == Method.GET) {
            // Plain http has no mic/camera/screen, so a tab polling this is telling us it is
            // locked out: use the chance to bring the secure listener back if it never bound.
            if (!secureOrigin.get()) retryTls()
            val json = JSONObject().apply {
                put("deviceName", deviceName)
                put("requiresPin", isPinRequired && !pinCode.isNullOrBlank())
                put("port", port)
                put("clientIp", clientIp)
                // The same string the pairing handshake announces, not a literal: `negotiateKdf` asks
                // whether BOTH ends are >= 2.0, and it reads one answer from here and the other from
                // `BuildConfig.VERSION_NAME`. A hardcoded value lets one half of a build claim 2.0.0
                // while the other admits 1.7, and HKDF v2 then silently never gets negotiated.
                put("version", BuildConfig.VERSION_NAME)
                put("secure", secureOrigin.get())
                put("tlsUp", tlsUp())
                put("tlsPort", tlsPort)
                // Empty while the secure port is up. A tab on http that cannot reach it has to say
                // which of the two failures it is looking at, because the advice is opposite.
                put("tlsError", tlsError() ?: "")
                // Lets a client-only phone pick which pairing to sign relay traffic with, and tells
                // an older build that this host will carry mail for devices with no server.
                put("deviceId", pairManager?.myDeviceIdOrNull() ?: "")
                put("relay", true)
                // What this phone can really do on the media wire. A peer uses a cap only when both
                // ends list it, so this is a hardware claim: `LanScanner` records it by address and a
                // call consults it before putting H.264 on the socket.
                put("caps", JSONArray(PeerCapabilityRegistry.localCaps()))
            }
            val res = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            return res
        }

        // Typing presence. Reading it stays public like /api/info; writing it now needs the room
        // credential, because a write is the half a stranger could use to impersonate or evict.
        if (uri == "/api/typing") {
            if (method == Method.GET) {
                val now = System.currentTimeMillis()
                val active = synchronized(typingSenders) {
                    typingSenders.entries.filter { it.value.until > now }
                        .map { it.value.name }
                        .distinct()
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", JSONArray(active).toString())
                return res
            } else if (method == Method.POST) {
                // A tab pings every 2 s while typing, so anything faster is a loop, not a typist.
                if (!typingLimiter.allow(normalizeAddr(clientIp))) return tooManyRequests()
                return try {
                    bodyTooLarge(session, TYPING_BODY_LIMIT)?.let { return it }
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val json = JSONObject(map["postData"] ?: "{}")
                    // The name is a label; the address is the key. A caller can rename itself but
                    // cannot remove or overwrite the presence of a different address.
                    val sender = json.optString("sender", "")
                        .takeIf { it.isNotBlank() } ?: "Client $clientIp"
                    val typing = json.optBoolean("typing", true)
                    val key = normalizeAddr(clientIp)
                    synchronized(typingSenders) {
                        val now = System.currentTimeMillis()
                        typingSenders.entries.removeAll { it.value.until <= now }
                        if (typing) {
                            if (typingSenders.size < TYPING_SENDER_LIMIT || typingSenders.containsKey(key)) {
                                typingSenders[key] = TypingPresence(sender, now + 6000)
                            }
                        } else {
                            typingSenders.remove(key)
                        }
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
                    res
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                }
            }
        }

        // Group threads the browser can join (names only, no pairing secrets)
        if (uri == "/api/threads" && method == Method.GET) {
            val arr = JSONArray()
            webThreads.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("kind", t.kind)
                    put("members", JSONArray(t.members))
                })
            }
            val res = newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
            return res
        }

        // Browser-initiated call: drives the ringing popup on the phone.
        if (uri == "/api/events" && method == Method.GET) {
            markActivity()
            // One stream is one handler thread held for the life of the tab, so the listener caps
            // how many it takes. The cap is answered by refusing whoever is asking, not by cutting
            // a tab that already has a stream: evicting the oldest meant the ninth page silenced
            // the first, the first's reconnect silenced the second, and a room with one tab too
            // many ended up with nine tabs cycling through a stream none of them kept. The refused
            // page is not left dead — it keeps the 2 s poll, so it loses the fast path, not the
            // chat, and every page that already had push keeps it.
            eventStreams.removeAll { it.closed }
            if (eventStreams.size >= maxEventStreams) {
                // Every seat looks taken until a write proves otherwise, and a handler parked on its
                // queue does not learn for up to a heartbeat that its tab closed. So look properly
                // before turning someone away: hand each stream a comment frame — not delivery, a
                // browser ignores it — which wakes that handler and makes it write to whoever is
                // really still there. A dead socket fails the write (the first attempt after a close
                // can still be swallowed by the kernel buffer, so this earns its keep on the retry),
                // the handler closes its body and the seat comes back — in the next second rather
                // than in the next twenty.
                eventStreams.forEach { it.queue.offer(":probe\n\n") }
                val settle = System.currentTimeMillis() + 300
                while (eventStreams.size >= maxEventStreams && System.currentTimeMillis() < settle) {
                    Thread.sleep(50)
                    eventStreams.removeAll { it.closed }
                }
            }
            if (eventStreams.size >= maxEventStreams) {
                val busy = newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    """{"success":false,"error":"This phone is already streaming every other tab"}"""
                )
                busy.addHeader("Retry-After", "15")
                busy.addHeader("Cache-Control", "no-store")
                return busy
            }
            val stream = EventStream(clientIp)
            eventStreams.add(stream)
            // Anything that changed while the tab was reconnecting is delivered on subscribe.
            val pending = webCall
            if (pending != null && pending.state != "ended" && pending.state != "declined") {
                stream.queue.offer(
                    "event: call\ndata: " + JSONObject().apply {
                        put("cid", pending.cid); put("video", pending.video); put("state", pending.state)
                    } + "\n\n"
                )
            }
            val res = newChunkedResponse(Response.Status.OK, "text/event-stream", EventBody(stream))
            res.addHeader("Cache-Control", "no-store")
            res.addHeader("X-Accel-Buffering", "no")
            return res
        }

        if (uri == "/api/call/web") {
            // Ringing the phone is not free: any LAN host could otherwise pop a fake
            // incoming-call screen over the visitor's own session. The table in
            // AuthorizationPolicy requires a session for this path, before either verb runs.
            if (method == Method.GET) {
                var c = webCall
                var live = c != null && c.state != "ended" && c.state != "declined"
                // The phone hanging up ends the live call, not this record; without the
                // cross-check the tab would sit on a call screen with no media behind it.
                if (c != null && c.state == "accepted" && callEngine?.hasWebLeg() != true) {
                    c = c.copy(state = "ended", updatedAt = System.currentTimeMillis())
                    webCall = c
                    live = false
                }
                val json = JSONObject().apply {
                    put("success", true)
                    put("active", live)
                    if (c != null) {
                        put("cid", c.cid); put("state", c.state); put("video", c.video)
                        // One shared record, two ends: tell this tab whether it is the caller
                        // or the one being rung, so neither renders the other's screen.
                        val mine = sameAddr(c.ip, clientIp)
                        put("incoming", live && c.outgoing && mine)
                        put("self", live && !c.outgoing && mine)
                        put("media", live && c.state == "accepted")
                    }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                return res
            }
            if (method == Method.POST) {
                return try {
                    bodyTooLarge(session, JSON_BODY_LIMIT)?.let { return it }
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val json = JSONObject(map["postData"] ?: "{}")
                    when (json.optString("action")) {
                        "ring" -> {
                            if (webCall?.state == "ringing" || webCall?.state == "accepted" ||
                                callEngine?.state?.value?.active == true
                            ) {
                                jsonResponse(false, "error" to "A call is already up")
                            } else {
                                // A tab dialling from inside a group thread asks the phone to
                                // bridge the whole group once it answers.
                                val gid = json.optString("group").removePrefix("group:")
                                val pids = if (gid.isNotBlank()) {
                                    pairManager?.groups?.value
                                        ?.firstOrNull { it.id == gid }?.memberPids
                                        .orEmpty()
                                } else {
                                    emptyList()
                                }
                                val call = WebCall(
                                    cid = json.optString("cid", UUID.randomUUID().toString()),
                                    name = json.optString("name", "Browser $clientIp"),
                                    ip = clientIp,
                                    video = json.optBoolean("video", false),
                                    state = "ringing",
                                    groupPids = pids
                                )
                                webCall = call
                                listener.onWebCall(call)
                                armWebRingExpiry(call)
                                jsonResponse(true, "cid" to call.cid)
                            }
                        }
                        "accept", "decline" -> {
                            val wanted = json.optString("action")
                            val c = webCall
                            if (c == null || !c.outgoing || !sameAddr(c.ip, clientIp) || c.state != "ringing") {
                                jsonResponse(false, "error" to "Nothing to answer")
                            } else {
                                updateWebCall(c.cid, if (wanted == "accept") "accepted" else "declined")
                                jsonResponse(true, "cid" to c.cid)
                            }
                        }
                        "end" -> {
                            val c = webCall
                            val wanted = json.optString("cid")
                            // `accept` and `decline` already insist the tab is the one the record
                            // was made for. `end` did not, and it also ended whatever call was
                            // running whenever `cid` arrived blank — while `/api/call/web` hands
                            // every authorized tab the live `cid`. Any second tab could therefore
                            // hang up a call it was not part of. A blank cid is now simply wrong:
                            // the client always knows the id it is hanging up.
                            when {
                                c == null || c.state == "ended" || c.state == "declined" ->
                                    jsonResponse(false, "error" to "No open call")
                                wanted.isBlank() || c.cid != wanted ->
                                    jsonResponse(false, "error" to "Unknown call")
                                !sameAddr(c.ip, clientIp) ->
                                    jsonResponse(false, "error" to "Not your call")
                                else -> {
                                    val ended = c.copy(state = "ended", updatedAt = System.currentTimeMillis())
                                    webCall = ended
                                    // The record alone would leave the phone talking to a tab that hung up.
                                    syncWebCallMedia(ended)
                                    listener.onWebCall(ended)
                                    jsonResponse(true, "cid" to ended.cid)
                                }
                            }
                        }
                        else -> jsonResponse(false, "error" to "Unknown action")
                    }
                } catch (e: Exception) {
                    jsonResponse(false, "error" to "Invalid request")
                }
            }
        }

        // Browser media legs. `down` is one long chunked body, `up` a ~10 Hz batch POST.
        if (uri == "/api/webcall/down" && method == Method.GET) {
            val cid = session.parameters["cid"]?.firstOrNull() ?: ""
            val call = webCall
            if (call == null || call.cid != cid || call.state != "accepted") {
                return jsonResponse(false, "error" to "No open call")
            }
            // This body is the live call. Matching the cid was not enough, because
            // `/api/call/web` gives every authorized tab that cid — so any tab could have
            // attached to a call between the phone and someone else's browser and listened.
            if (!sameAddr(call.ip, clientIp)) {
                return jsonResponse(false, "error" to "Not your call")
            }
            markActivity()
            val res = newChunkedResponse(
                Response.Status.OK, "application/octet-stream", WebMediaStream(cid)
            )
            res.addHeader("Cache-Control", "no-store")
            return res
        }
        if (uri == "/api/webcall/up" && method == Method.POST) {
            return try {
                // One batch of base64 media frames. Audio is a few kilobytes; a shared screen is
                // the big case, which is what sets the ceiling. Checked before parseBody:
                // NanoHTTPD reads the whole declared length into a String, and this route is
                // reachable by anyone who holds the room credential.
                bodyTooLarge(session, WEB_CALL_BODY_LIMIT)?.let { return it }
                val map = HashMap<String, String>()
                session.parseBody(map)
                val json = JSONObject(map["postData"] ?: "{}")
                val call = webCall
                val engine = callEngine
                when {
                    call == null || engine == null || call.cid != json.optString("cid") ||
                        call.state != "accepted" || !engine.hasWebLeg() ->
                        jsonResponse(false, "error" to "No open call")
                    // Without this, a second tab holding the room credential could feed its own
                    // voice and video into the phone's call with the real caller.
                    !sameAddr(call.ip, clientIp) -> jsonResponse(false, "error" to "Not your call")
                    else -> {
                        val frames = json.optString("frames")
                        if (frames.isNotEmpty()) {
                            engine.ingestWebFrames(
                                android.util.Base64.decode(frames, android.util.Base64.DEFAULT)
                            )
                            markActivity()
                        }
                        jsonResponse(true)
                    }
                }
            } catch (e: Exception) {
                jsonResponse(false, "error" to "Invalid frame batch")
            }
        }

        // Who is on this server right now, plus who has talked to it recently.
        if (uri == "/api/peers" && method == Method.GET) {
            val byIp = LinkedHashMap<String, JSONObject>()
            fun touch(ip: String, name: String?, ts: Long, files: Int, bytes: Long) {
                if (ip.isBlank()) return
                val cur = byIp[ip]
                if (cur == null) {
                    byIp[ip] = JSONObject().put("ip", ip).put("name", name ?: ip)
                        .put("lastSeen", ts).put("files", files).put("bytes", bytes)
                } else {
                    if (ts > cur.optLong("lastSeen")) cur.put("lastSeen", ts)
                    if ((cur.optString("name") == cur.optString("ip")) && name != null) cur.put("name", name)
                    cur.put("files", cur.optInt("files") + files).put("bytes", cur.optLong("bytes") + bytes)
                }
            }
            // The phone hosting this server is a device too. Its own messages carry no client
            // IP, so fold them under its LAN address — otherwise browsers cannot reply to it.
            val selfIp = NetworkUtils.getLocalIpAddress()
            var selfTs = 0L
            var selfFiles = 0
            var selfBytes = 0L
            synchronized(messages) {
                messages.forEach { m ->
                    if (m.clientIp.isNullOrEmpty()) { if (m.timestamp > selfTs) selfTs = m.timestamp }
                    else touch(m.clientIp, m.sender, m.timestamp, 0, 0)
                }
            }
            synchronized(sharedFiles) {
                sharedFiles.forEach { f ->
                    if (f.uploadedByIp.isNullOrEmpty()) {
                        selfFiles++; selfBytes += f.size
                        if (f.dateAdded > selfTs) selfTs = f.dateAdded
                    } else touch(f.uploadedByIp, null, f.dateAdded, 1, f.size)
                }
            }
            if (selfIp.isNotBlank()) touch(selfIp, deviceName, maxOf(selfTs, startedAtMs), selfFiles, selfBytes)
            // A browser that still has the page open is active, whether or not it has sent anything.
            val live = webClientsSnapshot().associateBy { it.ip }
            live.values.forEach { c ->
                touch(c.ip, c.name, c.lastSeen, 0, 0)
                byIp[c.ip]?.put("name", c.name)
                byIp[c.ip]?.put("lastSeen", maxOf(c.lastSeen, byIp.getValue(c.ip).optLong("lastSeen")))
            }
            byIp.values.forEach { o ->
                val c = live[o.optString("ip")]
                val self = o.optString("ip") == selfIp
                o.put("kind", if (c != null) "browser" else "app")
                o.put("active", c != null || self)
                if (self) o.put("self", true)
                if (c?.local == true) o.put("local", true)
            }
            val arr = JSONArray()
            byIp.values.sortedWith(
                compareByDescending<JSONObject> { it.optBoolean("active") }
                    .thenByDescending { it.optLong("lastSeen") }
            ).forEach { arr.put(it) }
            val res = newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
            return res
        }

        // API Auth
        if (uri == "/api/auth" && method == Method.POST) {
            return try {
                // The one route that must answer before any credential exists, so its ceiling is
                // not a nicety. A login body is a 6-digit PIN; 8 KiB already forgives a great deal.
                bodyTooLarge(session, LOGIN_BODY_LIMIT)?.let { return it }
                val map = HashMap<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: ""
                val json = JSONObject(postData)
                val submittedPin = json.optString("pin", "")

                val lockedOut = isLockedOut(clientIp)
                val success = !lockedOut && (!isPinRequired || submittedPin == pinCode)
                if (!lockedOut && !success && submittedPin.isNotBlank()) recordPinFailure(clientIp, submittedPin)
                if (success) clearPinFailures(clientIp)
                val resp = JSONObject().apply {
                    put("success", success)
                    // Never the PIN: a browser that logs in gets a random session it can expire and
                    // revoke, and a leaked `localStorage` value stops being the room secret.
                    if (success) put("token", sessions.issue())
                    if (isLockedOut(clientIp)) {
                        put("locked", true)
                        put("retryAfterSeconds", lockoutRemainingSeconds(clientIp))
                    }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
                // A minted session must not sit in a cache or a shared proxy.
                res.addHeader("Cache-Control", "no-store")
                res
            } catch (e: Exception) {
                val res = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                res
            }
        }

        // Logging out drops the session the caller is holding. It is scoped to that credential by
        // construction, so it needs no further authority, and an already-dead token is not an error.
        if (uri == "/api/auth/logout" && method == Method.POST) {
            val revoked = sessions.revoke(session.headers["x-pulse-auth"] ?: "")
            val res = newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("success", true).put("revoked", revoked).toString()
            )
            res.addHeader("Cache-Control", "no-store")
            return res
        }

        // A ticket is what a URL may carry when the request cannot carry a header. Minting one
        // requires an authenticated caller, so a URL lifted from history cannot mint more of them.
        if (uri == "/api/ticket" && method == Method.POST) {
            return try {
                bodyTooLarge(session, JSON_BODY_LIMIT)?.let { return it }
                val map = HashMap<String, String>()
                session.parseBody(map)
                val scope = JSONObject(map["postData"] ?: "{}").optString("scope", "")
                if (!ticketScopeIsServable(scope)) {
                    jsonResponse(false, "error" to "Unknown scope")
                } else {
                    val ticket = urlTickets.issue(scope, normalizeAddr(clientIp))
                    val res = jsonResponse(true, "ticket" to ticket)
                    res.addHeader("Cache-Control", "no-store")
                    res
                }
            } catch (e: Exception) {
                jsonResponse(false, "error" to "Invalid request")
            }
        }

        // Pairing + call signaling between PulseDrop apps — PIN-exempt but key-gated:
        // payloads without a known pairing id or valid ciphertext are rejected.
        if ((uri.startsWith("/api/pair/") || uri.startsWith("/api/call/") || uri.startsWith("/api/relay/")) && method == Method.POST) {
            return try {
                // Key-gated, not PIN-gated: whoever holds a pairing id — or nobody, for `poll` —
                // reaches `parseBody`. An SDP offer with a full candidate set is a few KiB, so the
                // JSON ceiling would be generous for `/api/call/*` and `/api/pair/*`. The relay
                // routes share this line and need more: see [RELAY_BODY_LIMIT].
                bodyTooLarge(session, RELAY_BODY_LIMIT)?.let { return it }
                val map = HashMap<String, String>()
                session.parseBody(map)
                val json = JSONObject(map["postData"] ?: "{}")
                val pm = pairManager
                val engine = callEngine
                val result: JSONObject = when {
                    uri.startsWith("/api/relay/") -> handleRelay(uri, json, clientIp)
                    uri == "/api/pair/request" && pm != null -> {
                        if (pm.handleIncomingRequest(json, clientIp)) {
                            pushWebEvent("pair", exceptIp = clientIp)
                            JSONObject().put("success", true).put("waiting", true)
                        } else {
                            // Nothing was queued, so nothing is waiting. The requester is also being
                            // handed a decline, but it may arrive after this response has already
                            // been read, and a `success: true` here would outlive both of them.
                            JSONObject().put("success", false).put("error", "not queued")
                        }
                    }
                    uri == "/api/pair/response" && pm != null -> {
                        pm.handleIncomingResponse(json, clientIp)
                        JSONObject().put("success", true)
                    }
                    uri == "/api/pair/msg" && pm != null ->
                        JSONObject().put("success", pm.handleIncomingMessage(json, clientIp))
                    uri == "/api/call/invite" && engine != null -> engine.handleInvite(json, clientIp)
                    uri == "/api/call/response" && engine != null -> {
                        engine.handleResponse(json)
                        JSONObject().put("success", true)
                    }
                    uri == "/api/call/end" && engine != null -> {
                        engine.handleEnd(json)
                        JSONObject().put("success", true)
                    }
                    else -> JSONObject().put("success", false)
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
                res
            } catch (e: Exception) {
                val res = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                res
            }
        }

        // API Files List with IP Isolation
        if (uri == "/api/files" && method == Method.GET) {
            val jsonArr = JSONArray()
            synchronized(sharedFiles) {
                for (file in sharedFiles) {
                    // IP Isolation: only show files marked public (targetClientIp == null)
                    // OR files explicitly sent to or uploaded by this specific client IP!
                    if (receivedFileIsVisibleTo(file, clientIp)) {
                        val obj = JSONObject().apply {
                            put("id", file.id)
                            put("name", file.name)
                            put("size", file.size)
                            put("mimeType", file.mimeType)
                            put("isMine", sameAddr(file.uploadedByIp, clientIp))
                            put("received", file.uriString.contains("WebDrop_Received"))
                            put("dateAdded", file.dateAdded)
                            put("from", file.uploadedByIp ?: "")
                            put("to", file.targetClientIp ?: "")
                        }
                        jsonArr.put(obj)
                    }
                }
            }
            val res = newFixedLengthResponse(Response.Status.OK, "application/json", jsonArr.toString())
            return res
        }

        // File Download
        if (uri.startsWith("/download/") && method == Method.GET) {
            val fileId = uri.removePrefix("/download/").substringBefore("?")
            val file = synchronized(sharedFiles) { sharedFiles.firstOrNull { it.id == fileId } }

            if (file == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
            }

            // IP check: if file is target specific, verify client IP
            if (!receivedFileIsVisibleTo(file, clientIp)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access forbidden for this device IP.")
            }

            return handleFileDownload(session, file)
        }

        // Download a whole received folder as a zip
        if (uri == "/download-folder" && method == Method.GET) {
            val rel = session.parameters["path"]?.firstOrNull() ?: ""
            return handleFolderDownload(rel, clientIp)
        }

        // File Upload (From Web or Peer App)
        if (uri == "/upload" && method == Method.POST) {
            return handleFileUpload(session)
        }

        // Chat attachment upload: same bytes, but never joins the hosted file list
        if (uri == "/chatupload" && method == Method.POST) {
            return handleChatUpload(session)
        }

        // Serve a chat attachment inline (preview) or as a download
        if (uri.startsWith("/chatfile/") && method == Method.GET) {
            val id = uri.removePrefix("/chatfile/").substringBefore("?")
            if (!chatFileIsVisibleTo(id, clientIp)) {
                // 404, not 403: the id is a UUID, and refusing to say whether it exists costs this
                // route nothing while telling a probing tab "that attachment is real and not yours"
                // is a disclosure of who is chatting.
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Attachment not found")
            }
            return handleChatFileDownload(session, id)
        }

        // Inline preview of a hosted file: same bytes, no forced download
        if (uri.startsWith("/preview/") && method == Method.GET) {
            val fileId = uri.removePrefix("/preview/").substringBefore("?")
            val file = synchronized(sharedFiles) { sharedFiles.firstOrNull { it.id == fileId } }
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
            if (!receivedFileIsVisibleTo(file, clientIp)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access forbidden for this device IP.")
            }
            return serveFileInline(session, file)
        }

        // Delete a received file (only files saved by the web upload flow)
        if (uri == "/api/delete" && method == Method.POST) {
            return try {
                bodyTooLarge(session, JSON_BODY_LIMIT)?.let { return it }
                val map = HashMap<String, String>()
                session.parseBody(map)
                val json = JSONObject(map["postData"] ?: "{}")
                val fileId = json.optString("id", "")
                deleteReceivedFile(fileId, clientIp)
            } catch (e: Exception) {
                jsonResponse(false, "error" to "Invalid request")
            }
        }

        // API Messages / Live Notes Beam with IP context
        if (uri == "/api/messages") {
            if (method == Method.GET) {
                val arr = JSONArray()
                // The phone hosting this server is the one every tab dials; a browser reads and
                // writes that conversation under the host's own address, "ip:<selfIp>".
                val hostIp = NetworkUtils.getLocalIpAddress()
                synchronized(messages) {
                    for (m in messages) {
                        // A browser sees the public room, its own address book, the groups
                        // the phone opened to browsers, and anything it sent itself - so a
                        // browser-to-browser thread reads the same on both sides. Never
                        // another host's direct thread, and never a pair conversation,
                        // which stays on the phones. Same rule `/chatfile/{id}` applies to
                        // the bytes a message points at.
                        if (!threadIsVisibleTo(m.threadId, m.clientIp, clientIp, hostIp)) continue
                        arr.put(JSONObject().apply {
                            put("id", m.id)
                            put("text", m.text)
                            put("sender", m.sender)
                            put("timestamp", m.timestamp)
                            put("clientIp", m.clientIp ?: "")
                            put("threadId", m.threadId ?: "")
                            put("deleted", m.deleted)
                            put("replyTo", m.replyTo ?: "")
                            put("replySender", m.replySender ?: "")
                            put("replyText", m.replyText ?: "")
                            // A tab may only delete what it wrote; the phone owns everything else.
                            put("mine", sameAddr(m.clientIp, clientIp))
                            val attachment = m.attachment
                            if (attachment != null &&
                                synchronized(chatAttachments) { chatAttachments.containsKey(attachment.id) }
                            ) {
                                put("attachment", attachmentJson(attachment))
                            }
                        })
                    }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
                return res
            } else if (method == Method.POST) {
                return try {
                    bodyTooLarge(session, JSON_BODY_LIMIT)?.let { return it }
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    val json = JSONObject(postData)
                    val text = json.optString("text", "")
                    val sender = json.optString("sender", "Client $clientIp")
                    // "to" turns the broadcast room into a per-IP direct thread.
                    val to = json.optString("to", "").trim()
                    val explicit = json.optString("thread", "").trim()
                    // Only threads this server actually offers: a group the phone opened, or
                    // a direct thread addressed to some host. Anything else lands in the room.
                    val knownGroup = explicit.startsWith("group:") &&
                            webThreads.any { it.id == explicit }
                    val threadId = when {
                        knownGroup || explicit.startsWith("ip:") -> explicit
                        explicit.isNotEmpty() || to.isEmpty() -> null
                        else -> "ip:$to"
                    }
                    val attachmentId = json.optString("attachmentId", "").trim()
                    // Naming an attachment is how its bytes reach people who never uploaded them:
                    // the download route asks whose attachment this is, and publishing one has to
                    // ask the same question of the same rule, or the refusal downstream is only a
                    // second way to deliver the file. An id this server has never seen stays
                    // ignored rather than refused, which is what it did before.
                    val held = attachmentId.takeIf { it.isNotEmpty() }
                        ?.let { id -> synchronized(chatAttachments) { chatAttachments[id] } }
                    if (held != null && !chatFileIsVisibleTo(attachmentId, clientIp)) {
                        return jsonResponse(false, "error" to "Attachment not yours")
                    }
                    val attachment = held?.attachment
                    val quoted = json.optString("replyTo").takeIf { it.isNotBlank() }?.let { findMessage(it) }
                    if (text.isNotBlank() || attachment != null) {
                        addMessage(
                            text.trim(), sender, clientIp, threadId, attachment, quoted,
                            fromWeb = threadId == null
                        )
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
                    res
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                }
            } else if (method == Method.DELETE) {
                return try {
                    // NanoHTTPD reads a body only for POST/PUT - a DELETE body is discarded before
                    // the handler sees it, so the id has to arrive in the query string.
                    val id = session.parms["id"].orEmpty()
                    val owner = findMessage(id)
                    // A browser may only retract what it wrote; the phone owns the room and reaches
                    // the same marking through the ViewModel instead.
                    if (id.isBlank() || owner == null || !sameAddr(owner.clientIp, clientIp)) {
                        val res = newFixedLengthResponse(
                            Response.Status.FORBIDDEN, "application/json", "{\"success\":false}"
                        )
                        return res
                    }
                    markMessageDeleted(id, fromWeb = true)
                    val res = newFixedLengthResponse(
                        Response.Status.OK, "application/json", "{\"success\":true}"
                    )
                    res
                } catch (e: Exception) {
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}"
                    )
                }
            }
        }

        val res = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        return res
    }

    private fun handleFolderDownload(rel: String, clientIp: String): Response {
        val receivedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "WebDrop_Received")
        val safeRel = sanitizeRelativePath(rel)
        if (safeRel.isBlank()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Folder not found")
        }
        val folder = File(receivedDir, safeRel)
        val canonical = folder.canonicalPath
        if (!canonical.startsWith(receivedDir.canonicalPath + File.separator) || !folder.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Folder not found")
        }

        // The path itself is chosen by whoever uploaded, and nothing records who owns a folder, so
        // ownership is derived from the files inside it: if any member is scoped to a different
        // client, this folder is not the requester's to take. Room-visible folders stay
        // downloadable, which is why the rule is "one scoped member forbids it" rather than
        // "prove you uploaded it" — the latter would lock out folders whose records expired.
        val foreign = synchronized(sharedFiles) {
            sharedFiles.any {
                it.targetClientIp != null &&
                    File(it.uriString).canonicalPath.startsWith(canonical + File.separator) &&
                    !receivedFileIsVisibleTo(it, clientIp)
            }
        }
        if (foreign) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access forbidden for this device IP.")
        }

        // Bounded, and bounded before anything is written: this walk used to zip every file under
        // the folder into cacheDir with no ceiling on count or bytes, so one visitor could fill the
        // phone's storage with a request. `take(MAX + 1)` stays lazy, so the oversized folder is
        // rejected without walking all of it.
        val members = folder.walkTopDown().filter { it.isFile }.take(FOLDER_ZIP_MAX_ENTRIES + 1).toList()
        if (members.size > FOLDER_ZIP_MAX_ENTRIES) {
            return folderTooLarge("Too many files to zip (limit $FOLDER_ZIP_MAX_ENTRIES)")
        }
        val totalBytes = members.sumOf { it.length() }
        if (totalBytes > FOLDER_ZIP_MAX_BYTES) {
            return folderTooLarge("Folder is too large to zip (${totalBytes / 1048576} MB)")
        }

        var zipFile: File? = null
        return try {
            zipFile = File.createTempFile("ld-folder-", ".zip", context.cacheDir)
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(zipFile))).use { zos ->
                members.forEach { f ->
                    zos.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(folder).path))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val size = zipFile.length()
            val toDelete = zipFile
            val stream = object : java.io.FileInputStream(zipFile) {
                override fun close() {
                    try { super.close() } finally { toDelete.delete() }
                }
            }
            val name = folder.name + ".zip"
            val encodedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val response = newFixedLengthResponse(Response.Status.OK, "application/zip", stream, size)
            response.addHeader("Content-Disposition", "attachment; filename=\"$name\"; filename*=UTF-8''$encodedName")
            response
        } catch (e: Exception) {
            zipFile?.delete()
            android.util.Log.w("PulseDropServer", "Folder zip failed", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Folder zip failed")
        }
    }

    /** A folder this endpoint will not zip. Refusing beats writing a body the phone cannot hold. */
    private fun folderTooLarge(reason: String): Response {
        val res = newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT, reason)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun handleFileDownload(session: IHTTPSession, file: SharedFile): Response {
        val transferId = UUID.randomUUID().toString()
        val clientIp = session.remoteIpAddress ?: "127.0.0.1"

        return try {
            val inputStream: InputStream = if (file.uriString.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(file.uriString))
                    ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open stream")
            } else {
                FileInputStream(File(file.uriString))
            }

            listener.onTransferStarted(transferId, file.name, file.size, TransferDirection.OUTGOING, webClientName(clientIp), clientIp)

            // Wrap stream with progress tracking
            val trackingStream = object : InputStream() {
                var bytesRead = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                override fun read(): Int {
                    val b = inputStream.read()
                    if (b != -1) {
                        bytesRead++
                        updateProgress()
                    }
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = inputStream.read(b, off, len)
                    if (count > 0) {
                        bytesRead += count
                        updateProgress()
                    }
                    return count
                }

                private fun updateProgress() {
                    val now = System.currentTimeMillis()
                    val diff = (now - lastTime)
                    if (diff >= 300 || bytesRead == file.size) {
                        val speed = if (diff > 0) ((bytesRead - lastBytes) * 1000L) / diff else 0L
                        listener.onTransferProgress(transferId, bytesRead, file.size, speed)
                        lastTime = now
                        lastBytes = bytesRead
                    }
                }

                override fun close() {
                    super.close()
                    inputStream.close()
                    if (bytesRead >= file.size || file.size == 0L) {
                        listener.onTransferCompleted(transferId, file.name, file.size, null, TransferDirection.OUTGOING, webClientName(clientIp), clientIp)
                    } else {
                        // A cancelled download must still settle its transfer, otherwise the
                        // entry stays IN_PROGRESS and pins the idle watchdog open.
                        listener.onTransferFailed(transferId, file.name, "Download cancelled after $bytesRead of ${file.size} bytes", TransferDirection.OUTGOING)
                    }
                }
            }

            val mimeType = if (file.mimeType.isNotBlank()) file.mimeType else "application/octet-stream"
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, trackingStream, file.size)
            val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            val fallback = file.name.replace(Regex("[^A-Za-z0-9 ._\\-()]"), "_")
            response.addHeader("Content-Disposition", "attachment; filename=\"$fallback\"; filename*=UTF-8''$encodedName")
            response.addHeader("Accept-Ranges", "bytes")
            response
        } catch (e: Exception) {
            listener.onTransferFailed(transferId, file.name, e.message ?: "Download error", TransferDirection.OUTGOING)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Download error: ${e.message}")
        }
    }

    /**
     * Admits the declared length before NanoHTTPD buffers anything and releases it on every exit.
     * The per-request ceiling alone lets two tabs each spend the same free space, because both see
     * the volume as it was before either wrote; this reservation is the sum that keeps the pair from
     * filling it. `0` for a request that is already refused by the ceiling, which is cheap.
     */
    private fun handleFileUpload(session: IHTTPSession): Response {
        // The body is buffered whole in `cacheDir` by `parseBody`, so this is the last moment the
        // phone can decline it cheaply. `hasFreeSpaceFor` below guards the *destination*; this
        // guards the temporary copy, which is a second full-size file on a different volume and
        // the one that was never bounded at all.
        val budget = uploadBudgetBytes(context.cacheDir)
        bodyTooLarge(session, budget)?.let { return it }
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (!inFlightUploads.reserve(declared, budget)) return uploadsBusy()
        return try {
            handleFileUploadBody(session)
        } finally {
            inFlightUploads.release(declared)
        }
    }

    private fun handleFileUploadBody(session: IHTTPSession): Response {
        val transferId = UUID.randomUUID().toString()
        val clientIp = session.remoteIpAddress ?: "127.0.0.1"
        // Declared outside the try so the catch can remove a partially written file.
        var finalFile: File? = null

        return try {
            val filesMap = HashMap<String, String>()
            session.parseBody(filesMap)

            val pairCrypt = session.headers["x-pair-crypt"] == "1"
            val pairId = session.headers["x-pair-id"]?.takeIf { it.isNotBlank() }
            val pairedSender = pairId?.let { pairManager?.findPairing(it) }

            val downloadsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "WebDrop_Received").apply {
                if (!exists()) mkdirs()
            }

            var savedFileName = "received_file_${System.currentTimeMillis()}"
            var fileSize = 0L

            for ((formKey, tempFilePath) in filesMap) {
                if (formKey == "postData") continue
                val relativePath = session.parameters["path"]?.firstOrNull()
                val originalName = relativePath
                    ?: session.parameters["filename"]?.firstOrNull()
                    ?: session.parameters[formKey]?.firstOrNull() ?: formKey
                savedFileName = sanitizeRelativePath(originalName)
                if (savedFileName.isBlank()) savedFileName = "file_${System.currentTimeMillis()}"

                val tempFile = File(tempFilePath)
                fileSize = tempFile.length()
                finalFile = File(downloadsDir, savedFileName)
                finalFile.parentFile?.mkdirs()

                // If file already exists, append timestamp
                if (finalFile.exists()) {
                    val nameWithoutExt = savedFileName.substringBeforeLast(".")
                    val ext = savedFileName.substringAfterLast(".", "")
                    val extSuffix = if (ext.isNotEmpty()) ".$ext" else ""
                    savedFileName = "${nameWithoutExt}_${System.currentTimeMillis()}$extSuffix"
                    finalFile = File(downloadsDir, savedFileName)
                    finalFile.parentFile?.mkdirs()
                }

                if (fileSize > 0 && !hasFreeSpaceFor(downloadsDir, fileSize)) {
                    tempFile.delete()
                    return jsonResponse(
                        false,
                        "error" to "Not enough free storage on this device for ${savedFileName}"
                    )
                }

                // Land the bytes under a name the received listing cannot match, then rename. Copied
                // straight onto the final name, an interrupted transfer leaves a file that looks
                // complete and is half empty — and nothing in this folder says which one it is.
                //
                // The landing name is random rather than derived from the final name, because `?path=` is
                // not unique: two tabs sending to one name both find it free, both compute the same
                // landing file, and two concurrent copies into one path interleave. A `renameTo` of the
                // result would then hand the room a whole file that is half of each send — and after the
                // first rename the second has no source left, which the catch below reports as a failed
                // upload of bytes that arrived fine. Whoever writes last wins the name; the bytes each
                // client sent stay whole either way.
                val partFile = File(finalFile.parentFile, ".${UUID.randomUUID()}.part")
                try {
                    if (pairCrypt && pairedSender != null) {
                        val pm = pairManager
                        val ok = pm != null && pm.decryptUpload(pairId!!, tempFile, partFile)
                        if (!ok) {
                            return jsonResponse(false, "error" to "Encrypted upload failed - unknown pairing")
                        }
                    } else if (pairCrypt) {
                        return jsonResponse(false, "error" to "Unknown pairing id")
                    } else {
                        tempFile.copyTo(partFile, overwrite = true)
                    }
                    if (!partFile.renameTo(finalFile)) {
                        // Same directory, so this only fails when the two names are on different
                        // volumes — the copy is the fallback, not a second chance at atomicity.
                        partFile.copyTo(finalFile, overwrite = true)
                    }
                } finally {
                    partFile.delete()
                    tempFile.delete()
                }
                break
            }

            if (finalFile != null && finalFile.exists()) {
                fileSize = finalFile.length()

                if (!autoAcceptTransfers) {
                    // Park it in a pending folder the received listing never reads; nothing is
                    // kept until a human on this phone says so.
                    val pendingDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "WebDrop_Pending").apply {
                        if (!exists()) mkdirs()
                    }
                    val stageId = UUID.randomUUID().toString().take(12)
                    val staged = File(pendingDir, "${stageId}_${savedFileName}")
                    if (!finalFile.renameTo(staged)) {
                        finalFile.copyTo(staged, overwrite = true)
                        finalFile.delete()
                    }
                    val fromLabel = pairedSender?.name ?: webClientName(clientIp)
                    synchronized(pendingUploads) {
                        pendingUploads.add(
                            StagedUpload(
                                id = stageId,
                                file = staged,
                                name = savedFileName,
                                size = staged.length(),
                                fromIp = clientIp,
                                fromLabel = fromLabel,
                                toIp = session.parameters["to"]?.firstOrNull()
                                    ?.takeIf { it.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}")) },
                                transferId = transferId
                            )
                        )
                    }
                    listener.onPendingUpload(stageId, savedFileName, fileSize, fromLabel)
                    // Owned by pendingUploads now; the catch must not delete a parked file.
                    finalFile = null
                    val res = newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        JSONObject().apply {
                            put("success", true)
                            put("pending", true)
                            put("name", savedFileName)
                            put("size", fileSize)
                        }.toString()
                    )
                    return res
                }

                // Register file with IP isolation
                val sharedItem = SharedFile(
                    id = UUID.randomUUID().toString(),
                    name = savedFileName,
                    size = fileSize,
                    mimeType = guessMimeType(savedFileName),
                    uriString = finalFile.absolutePath,
                    uploadedByIp = clientIp,
                    targetClientIp = session.parameters["to"]?.firstOrNull()
                        ?.takeIf { it.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}")) }
                )
                synchronized(sharedFiles) {
                    sharedFiles.add(sharedItem)
                }

                val remoteDeviceLabel = pairedSender?.name ?: webClientName(clientIp)
                listener.onTransferStarted(transferId, savedFileName, fileSize, TransferDirection.INCOMING, remoteDeviceLabel, clientIp)
                listener.onTransferProgress(transferId, fileSize, fileSize, fileSize)
                listener.onTransferCompleted(transferId, savedFileName, fileSize, finalFile.absolutePath, TransferDirection.INCOMING, remoteDeviceLabel, clientIp)
                // Every other tab that can see this file hears about it now, not on its next poll.
                pushWebEvent("file", exceptIp = clientIp) {
                    put("name", savedFileName); put("size", fileSize)
                    put("from", remoteDeviceLabel)
                    sharedItem.targetClientIp?.let { put("to", it) }
                }
                // Owned by sharedFiles now: the catch below must not delete a delivered file.
                finalFile = null

                val res = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    JSONObject().apply {
                        put("success", true)
                        put("name", savedFileName)
                        put("size", fileSize)
                    }.toString()
                )
                res
            } else {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false,\"error\":\"No file found in request\"}")
            }
        } catch (e: Exception) {
            // A failed copy/decrypt leaves a truncated file behind; it is not registered, so
            // delete it instead of leaking storage on every error.
            try { finalFile?.delete() } catch (ignored: Exception) { }
            listener.onTransferFailed(transferId, "Upload", e.message ?: "Failed", TransferDirection.INCOMING)
            // The wire gets a fixed sentence rather than `e.message`: an exception string from this
            // depth is a path, and a path is a small map of the device handed to whoever sent the
            // body. The phone's own UI still gets the real reason through the listener above.
            android.util.Log.w("PulseDropServer", "Upload failed", e)
            val res = newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().apply {
                    put("success", false)
                    put("error", "Upload failed")
                }.toString()
            )
            res
        }
    }

    /** Browser-side chat upload: identical bytes, but registered only as a chat attachment. */
    private fun handleChatUpload(session: IHTTPSession): Response {
        val budget = uploadBudgetBytes(context.cacheDir)
        bodyTooLarge(session, budget)?.let { return it }
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (!inFlightUploads.reserve(declared, budget)) return uploadsBusy()
        return try {
            handleChatUploadBody(session)
        } finally {
            inFlightUploads.release(declared)
        }
    }

    private fun handleChatUploadBody(session: IHTTPSession): Response {
        return try {
            val filesMap = HashMap<String, String>()
            session.parseBody(filesMap)
            val entry = filesMap.entries.firstOrNull { it.key != "postData" }
                ?: return jsonResponse(false, "error" to "No file found in request")

            val tempFile = File(entry.value)
            val originalName = session.parameters["filename"]?.firstOrNull()
                ?: session.parameters[entry.key]?.firstOrNull()
                ?: entry.key
            val safeName = sanitizeFileName(originalName).ifBlank { "attachment_${System.currentTimeMillis()}" }

            val id = UUID.randomUUID().toString()
            val target = File(chatDir(), "${id.take(8)}-$safeName")
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()

            val size = target.length()
            if (size <= 0L) {
                target.delete()
                return jsonResponse(false, "error" to "Empty upload")
            }
            val attachment = ChatAttachment(id, safeName, size, guessMimeType(safeName))
            // Recorded against the socket, not against anything the client declared: this is the only
            // route where the uploader gets to read the bytes before a message exists to scope them.
            val uploader = session.remoteIpAddress ?: "127.0.0.1"
            synchronized(chatAttachments) { chatAttachments[id] = ChatFile(attachment, target, uploader) }
            markActivity()
            jsonResponse(true, "attachment" to attachmentJson(attachment))
        } catch (e: Exception) {
            // As on /upload: no client needs an exception string that contains a path, and the
            // declared-length ceiling above already answered the one failure worth naming.
            android.util.Log.w("PulseDropServer", "Chat attachment upload failed", e)
            jsonResponse(false, "error" to "Upload failed")
        }
    }

    /** Serves a chat attachment so both clients can preview or download it. */
    private fun handleChatFileDownload(session: IHTTPSession, id: String): Response {
        val stored = synchronized(chatAttachments) { chatAttachments[id] }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Attachment not found")
        if (!stored.file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Attachment not found")
        }
        return serveLocalFile(
            session, stored.file,
            stored.attachment.mime.ifBlank { "application/octet-stream" },
            asAttachment = session.parameters["dl"]?.firstOrNull() == "1",
            displayName = stored.attachment.name
        )
    }

    /** Streams a hosted file for inline preview: content:// sources fall back to a plain body. */
    private fun serveFileInline(session: IHTTPSession, file: SharedFile): Response {
        val mime = file.mimeType.ifBlank { "application/octet-stream" }
        if (file.uriString.startsWith("content://")) {
            val input = context.contentResolver.openInputStream(Uri.parse(file.uriString))
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File is no longer available")
            val response = newFixedLengthResponse(Response.Status.OK, mime, input, file.size)
            addInlineSafety(response, mime)
            return response
        }
        val local = File(file.uriString)
        if (!local.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File is no longer available")
        }
        return serveLocalFile(session, local, mime, asAttachment = false)
    }

    /**
     * The file's own bytes are returned with the type the phone recorded, which may be HTML or SVG
     * from a share. In an `<img>` or `<video>` that is inert, but a browser navigated straight to
     * `/preview/{id}` would run it on this origin. `sandbox` is the directive that applies to a
     * document: no script, and a unique origin, so the file cannot read the room it sits in. It is
     * added only for types a browser would treat as a document, so the PDF viewer and media
     * elements see exactly the bytes and headers they did before.
     */
    private fun addInlineSafety(response: Response, mime: String) {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Referrer-Policy", "no-referrer")
        if (mime.substringBefore(';').trim().lowercase() in ACTIVE_DOCUMENT_TYPES) {
            response.addHeader("Content-Security-Policy", "sandbox")
        }
    }

    /** Range-aware body for previews, so video and audio can seek. */
    private fun serveLocalFile(
        session: IHTTPSession,
        file: File,
        mime: String,
        asAttachment: Boolean,
        displayName: String? = null
    ): Response {
        val length = file.length()
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=")
            val from = spec.substringBefore("-").toLongOrNull()
            if (from != null && from >= 0 && from < length) {
                val tail = spec.substringAfter("-", "").toLongOrNull()
                val to = (tail ?: length - 1).coerceIn(from, length - 1)
                val input = FileInputStream(file)
                var skipped = from
                while (skipped > 0) {
                    val n = input.skip(skipped)
                    if (n <= 0) break
                    skipped -= n
                }
                val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, input, to - from + 1)
                response.addHeader("Content-Range", "bytes $from-$to/$length")
                if (asAttachment) addDownloadDisposition(response, displayName ?: file.name) else addInlineSafety(response, mime)
                response.addHeader("Accept-Ranges", "bytes")
                return response
            }
        }
        val response = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), length)
        response.addHeader("Accept-Ranges", "bytes")
        if (asAttachment) addDownloadDisposition(response, displayName ?: file.name) else addInlineSafety(response, mime)
        return response
    }

    private fun addDownloadDisposition(response: Response, name: String) {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val fallback = name.replace(Regex("[^A-Za-z0-9 ._\\-()]"), "_")
        response.addHeader("Content-Disposition", "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded")
    }

    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast("/").substringAfterLast("\\")
        val cleaned = base.replace(Regex("[^A-Za-z0-9 ._\\-()\\[\\]{}]"), "_").trim('.', ' ')
        return if (cleaned.length > 120) {
            val ext = cleaned.substringAfterLast(".", "")
            // The budget is spent on the extension first, because the extension is what a receiver uses
            // to decide how to open the file. But an extension can be longer than the whole budget, and
            // then `120 - ext - 1` is negative: that threw here, and the throw was caught by the upload
            // handler as an ordinary failure — so a caller who named a file `a.<300 chars>` could not
            // send it at all. With no room left for a stem there is no name worth assembling, and the
            // plain truncation is what any other over-long name gets.
            val stem = cleaned.substringBeforeLast(".").take((120 - ext.length - 1).coerceAtLeast(0))
            if (ext.isEmpty() || stem.isBlank()) cleaned.take(120) else "$stem.$ext"
        } else cleaned
    }

    /** Keeps the folder structure of a web folder upload, sanitizing every path segment. */
    /**
     * Reject an upload that cannot fit. Writing past the end of the disk leaves a truncated
     * file and can take the whole app down, so the check happens before the copy.
     */
    private fun hasFreeSpaceFor(dir: File, bytes: Long): Boolean = try {
        val stat = android.os.StatFs(dir.absolutePath)
        stat.availableBytes > bytes + 16L * 1024 * 1024
    } catch (e: Exception) {
        true // if the check itself fails, do not block the transfer
    }

    private fun sanitizeRelativePath(raw: String): String {
        val segments = raw.replace("\\", "/")
            .split("/")
            .map { sanitizeFileName(it) }
            .filter { it.isNotBlank() && it != "." && it != ".." }
        return segments.takeLast(4).joinToString("/")
    }

    private fun guessMimeType(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun jsonResponse(success: Boolean, vararg fields: Pair<String, Any?>): Response {
        val obj = JSONObject().apply {
            put("success", success)
            fields.forEach { (k, v) -> put(k, v) }
        }
        val res = newFixedLengthResponse(
            if (success) Response.Status.OK else Response.Status.BAD_REQUEST,
            "application/json",
            obj.toString()
        )
        return res
    }

    private fun deleteReceivedFile(fileId: String, clientIp: String): Response {
        if (fileId.isBlank()) return jsonResponse(false, "error" to "Missing id")
        val file = synchronized(sharedFiles) { sharedFiles.firstOrNull { it.id == fileId } }
            ?: return jsonResponse(false, "error" to "Not found")
        if (!receivedFileIsVisibleTo(file, clientIp)) return jsonResponse(false, "error" to "Forbidden")
        val receivedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "WebDrop_Received").canonicalPath
        val target = File(file.uriString)
        if (!target.canonicalPath.startsWith(receivedDir + File.separator)) {
            return jsonResponse(false, "error" to "Only web uploads can be deleted here")
        }
        if (!target.delete() && target.exists()) {
            return jsonResponse(false, "error" to "Delete failed")
        }
        synchronized(sharedFiles) { sharedFiles.remove(file) }
        return jsonResponse(true, "name" to file.name)
    }

    companion object {
        /**
         * Where accepted uploads live. The Receive screen shows this path and its free space; no
         * screen enumerates the folder itself, which is why [PARTIAL_UPLOAD_NAME] leftovers used to
         * sit there unseen.
         */
        const val RECEIVED_DIR_NAME = "WebDrop_Received"

        /**
         * The landing name an upload copies into before renaming it into place. A killed process
         * never runs the cleanup `finally`, so `start()` collects whatever still matches. Only the
         * UUID shape is swept: [sanitizeFileName] trims leading dots, so a stored name can never
         * look like this, and a user's own `.something` file (if one ever predates that rule) is
         * left alone.
         */
        private val PARTIAL_UPLOAD_NAME = Regex(
            "^\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part$",
            RegexOption.IGNORE_CASE
        )

        /**
         * Ceiling for a JSON POST. The largest legitimate one carries a device name, a call id and
         * an SDP offer, so this is orders of magnitude above what any client sends; it exists because
         * `parseBody` reads whatever `Content-Length` claims and hands the result over as a Java
         * String. Covers every JSON route except the signaling dispatcher, which needs
         * [RELAY_BODY_LIMIT] instead.
         *
         * Internal so a test can drive a route to the byte either side of its ceiling: the numbers
         * are the contract, and literals repeated in the test would keep passing quietly if these
         * moved.
         */
        internal const val JSON_BODY_LIMIT = 64L * 1024

        /** Ceiling for `/api/auth`, whose whole body is `{"pin":"123456"}`. */
        internal const val LOGIN_BODY_LIMIT = 8L * 1024

        /** Ceiling for one typing-presence ping: a sender name and a boolean. */
        internal const val TYPING_BODY_LIMIT = 4L * 1024

        /**
         * Ceiling for one ~10 Hz batch of base64 media frames on `/api/webcall/up`.
         *
         * Sized against what the shipped tab produces, not against audio: a shared screen is sent
         * 1600 px wide at quality 0.75 and base64 costs a third more than the JPEG, so 256 KiB
         * refused ordinary dense desktop frames — the transfer just stopped, silently and inside
         * the call. The decoded limits that make a larger body safe (per-frame by type, per-batch
         * frame count, JPEG pixels) live in `MediaLimits` and are applied before anything decodes.
         */
        internal const val WEB_CALL_BODY_LIMIT = 2L * 1024 * 1024

        /**
         * Ceiling for the pair/call/relay dispatcher, and it has to be above [JSON_BODY_LIMIT] for
         * the relay half of that route. A relay POST is a *wrapper*: the envelope inside it is
         * accepted up to [MAX_RELAY_ENVELOPE] by the rule that knows how to say "too large" usefully,
         * and `role`, `to` and the quoting make the body carrying it strictly larger than the
         * envelope. Two equal ceilings therefore refuse the last few dozen bytes of the relay's own
         * budget before that rule ever runs. Four envelopes is room without being unlimited.
         */
        internal const val RELAY_BODY_LIMIT = 4L * MAX_RELAY_ENVELOPE

        /** A folder zip is built in cacheDir, so its cost is paid twice: read, then written. */
        private const val FOLDER_ZIP_MAX_ENTRIES = 2_000
        private const val FOLDER_ZIP_MAX_BYTES = 512L * 1024 * 1024

        /** The instance holding our port right now, so a restart can kill a stray listener. */
        @Volatile
        var current: PulseDropServer? = null

        /** Stop any forgotten instance still bound to the port (e.g. after a ViewModel swap). */
        fun silenceStray(except: PulseDropServer?) {
            val stray = current
            if (stray != null && stray !== except) {
                try {
                    stray.stop()
                } catch (ignored: Exception) {
                }
                if (current === stray) current = null
            }
        }

        val WEB_FONTS = mapOf(
            "/fonts/google-sans-regular.ttf" to R.font.google_sans_regular,
            "/fonts/google-sans-medium.ttf" to R.font.google_sans_medium,
            "/fonts/google-sans-semibold.ttf" to R.font.google_sans_semibold,
            "/fonts/google-sans-bold.ttf" to R.font.google_sans_bold
        )
    }
}
