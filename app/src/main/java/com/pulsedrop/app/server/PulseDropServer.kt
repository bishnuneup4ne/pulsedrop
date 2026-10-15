package com.pulsedrop.app.server

import android.content.Context
import android.net.Uri
import com.pulsedrop.app.R
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
    val outgoing: Boolean = false
)

interface ServerTransferListener {
    fun onTransferStarted(transferId: String, fileName: String, fileSize: Long, direction: TransferDirection, remoteDevice: String, clientIp: String)
    fun onTransferProgress(transferId: String, bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long)
    fun onTransferCompleted(transferId: String, fileName: String, fileSize: Long, localFilePath: String?, direction: TransferDirection, remoteDevice: String, clientIp: String)
    fun onTransferFailed(transferId: String, fileName: String, error: String, direction: TransferDirection)
    fun onNewMessage(message: ClipMessage)
    fun onWebCall(call: WebCall)
}

private const val PIN_MAX_ATTEMPTS = 8
private const val PIN_LOCKOUT_MS = 60_000L
private const val PIN_LOCKOUT_MAX_STEPS = 4
private const val SERVER_CHAT_RETENTION = 2000
private const val TYPING_SENDER_LIMIT = 64
private const val WEB_CLIENT_TTL = 90_000L
// Matches CallEngine.RING_TIMEOUT_MS so a browser and a paired app are given up on alike.
private const val WEB_RING_TIMEOUT_MS = 35_000L

class PulseDropServer(
    val port: Int,
    private val context: Context,
    private val deviceName: String,
    initialPinCode: String?,
    initialIsPinRequired: Boolean,
    initialListener: ServerTransferListener
) : NanoHTTPD(port) {

    /**
     * The server outlives a ViewModel (the foreground service keeps it serving), so the
     * callbacks are re-attached whenever a new ViewModel takes ownership of a live server.
     */
    @Volatile
    private var listener: ServerTransferListener = initialListener

    fun attachListener(newListener: ServerTransferListener) {
        listener = newListener
    }

    @Volatile
    private var pinCode: String? = initialPinCode
    @Volatile
    private var isPinRequired: Boolean = initialIsPinRequired

    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    /** Keeps the idle watchdog from retiring the server while work is live. */
    fun markActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    @Volatile
    var pairManager: com.pulsedrop.app.security.PairManager? = null

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
    private val typingSenders = mutableMapOf<String, Long>()

    /** Per-client PIN guess counter, so a 4-digit PIN cannot be swept brute-force. */
    private class PinAttempts {
        var wrongGuesses = 0
        var blockedUntil = 0L
        var lastWrongCredential: String? = null
    }

    private val pinAttempts = mutableMapOf<String, PinAttempts>()

    /**
     * Chat attachments live here instead of [sharedFiles]: a file sent in a conversation
     * stays in that conversation and never shows up in hosted files, history or stats.
     */
    private val chatAttachments = LinkedHashMap<String, ChatFile>()

    /** A file owned by a chat message. Chat history is restart-scoped, so is its store. */
    private data class ChatFile(val attachment: ChatAttachment, val file: File)

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
                engine.startWebCall(call.video, webClientName(call.ip))
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
    fun ringWebClient(ip: String, name: String, video: Boolean): WebCall {
        val call = WebCall(
            cid = UUID.randomUUID().toString(),
            name = name,
            ip = ip,
            video = video,
            state = "ringing",
            outgoing = true
        )
        webCall = call
        listener.onWebCall(call)
        armWebRingExpiry(call)
        return call
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
        isPinRequired = required
        pinCode = pin
        // A freshly generated PIN invalidates any earlier lockout.
        synchronized(pinAttempts) { pinAttempts.clear() }
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

    // NanoHTTPD's legacy session carries no secure flag, so the TLS delegate marks its
    // own worker threads and the redirect below asks them instead.
    private val secureOrigin = ThreadLocal.withInitial { false }

    override fun start() {
        super.start()
        ensureTls()
    }

    override fun stop() {
        synchronized(this) { tlsServer?.stop(); tlsServer = null }
        super.stop()
    }

    /** Call whenever the LAN address changes; the certificate carries the SAN list. */
    fun refreshTls(hosts: List<String>) {
        if (hosts.filter { it.isNotBlank() } == tlsHosts.filter { it.isNotBlank() }) return
        restartTls()
    }

    private fun restartTls() {
        synchronized(this) {
            tlsServer?.stop()
            tlsServer = null
        }
        ensureTls()
    }

    private fun ensureTls() {
        if (tlsServer != null) return
        // RSA keypair generation is not free, and start() can be reached from a click handler.
        Thread {
            try {
                val hosts = listOf(NetworkUtils.getLocalIpAddress()).filter { it.isNotBlank() } + tlsHosts
                val factory = WebTls.socketFactory(hosts)
                val delegate = object : NanoHTTPD(tlsPort) {
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
                delegate.makeSecure(factory, null)
                delegate.start(SOCKET_READ_TIMEOUT, false)
                tlsHosts = hosts
                tlsServer = delegate
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { isDaemon = true }.start()
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
        attachment: ChatAttachment? = null
    ): ClipMessage {
        val msg = ClipMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = sender,
            clientIp = clientIp,
            threadId = threadId,
            attachment = attachment
        )
        synchronized(messages) {
            messages.add(msg)
            // Chat is restart-scoped but a long-lived server must not grow without bound.
            while (messages.size > SERVER_CHAT_RETENTION) messages.removeAt(0)
        }
        listener.onNewMessage(msg)
        return msg
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
            synchronized(chatAttachments) { chatAttachments[id] = ChatFile(attachment, target) }
            attachment
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Registers an already written file (the browser upload path) as a chat attachment. */
    private fun registerChatAttachment(file: File, name: String): ChatAttachment {
        val attachment = ChatAttachment(
            id = UUID.randomUUID().toString(),
            name = name,
            size = file.length(),
            mime = guessMimeType(name)
        )
        synchronized(chatAttachments) { chatAttachments[attachment.id] = ChatFile(attachment, file) }
        return attachment
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

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (!isPinRequired || pinCode.isNullOrBlank()) return true
        val clientIp = session.remoteIpAddress ?: "127.0.0.1"
        if (isLockedOut(clientIp)) return false
        val headerAuth = session.headers["x-pulse-auth"]
        if (headerAuth == pinCode) return true
        val queryAuth = session.parameters["auth"]?.firstOrNull()
        if (queryAuth == pinCode) return true
        // A paired app authenticates with the end-to-end key it already shares with us, so
        // paired sends keep working against a phone that requires a PIN.
        if (pairedSenderIsAuthorized(session)) return true
        // Only a *wrong* credential counts as a guess: visitors that have not been asked
        // for the PIN yet send nothing, so they must not be penalised.
        val submitted = headerAuth?.takeIf { it.isNotBlank() } ?: queryAuth?.takeIf { it.isNotBlank() }
        if (submitted != null) recordPinFailure(clientIp, submitted)
        return false
    }

    private fun unauthorized(): Response {
        val res = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "application/json",
            "{\"error\":\"Unauthorized. PIN required.\"}"
        )
        addCorsHeaders(res)
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
        val uri = session.uri
        val method = session.method
        val clientIp = session.remoteIpAddress ?: "127.0.0.1"

        // Every HTTP hit is a browser still sitting on the page, so the phone can list the
        // desktops connected to it. A browser has no listener, so this is the only way in.
        if (method != Method.OPTIONS) {
            noteWebClient(
                clientIp,
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
            addCorsHeaders(res)
            return res
        }

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
            addCorsHeaders(res)
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
                addCorsHeaders(res)
                return res
            }
            val html = WebClientHtml.getHtml(deviceName, isPinRequired && !pinCode.isNullOrBlank())
            val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
            addCorsHeaders(res)
            return res
        }

        // API Info
        if (uri == "/api/info" && method == Method.GET) {
            val json = JSONObject().apply {
                put("deviceName", deviceName)
                put("requiresPin", isPinRequired && !pinCode.isNullOrBlank())
                put("port", port)
                put("clientIp", clientIp)
                put("version", "2.0.0")
            }
            val res = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            addCorsHeaders(res)
            return res
        }

        // Typing presence (non-sensitive, open like /api/info)
        if (uri == "/api/typing") {
            if (method == Method.GET) {
                val now = System.currentTimeMillis()
                val active = synchronized(typingSenders) {
                    typingSenders.entries.filter { it.value > now }.map { it.key }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", JSONArray(active).toString())
                addCorsHeaders(res)
                return res
            } else if (method == Method.POST) {
                return try {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val json = JSONObject(map["postData"] ?: "{}")
                    val sender = json.optString("sender", "Client $clientIp")
                    val typing = json.optBoolean("typing", true)
                    synchronized(typingSenders) {
                        val now = System.currentTimeMillis()
                        typingSenders.entries.removeAll { it.value <= now }
                        if (typing) {
                            if (typingSenders.size < TYPING_SENDER_LIMIT) typingSenders[sender] = now + 6000
                        } else {
                            typingSenders.remove(sender)
                        }
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
                    addCorsHeaders(res)
                    res
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                }
            }
        }

        // Group threads the browser can join (names only, no pairing secrets)
        if (uri == "/api/threads" && method == Method.GET) {
            if (!isAuthorized(session)) return unauthorized()
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
            addCorsHeaders(res)
            return res
        }

        // Browser-initiated call: drives the ringing popup on the phone.
        if (uri == "/api/call/web") {
            // Ringing the phone is not free: any LAN host could otherwise pop a fake
            // incoming-call screen over the visitor's own session.
            if (!isAuthorized(session)) return unauthorized()
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
                        val mine = c.ip == clientIp
                        put("incoming", live && c.outgoing && mine)
                        put("self", live && !c.outgoing && mine)
                        put("media", live && c.state == "accepted")
                    }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                addCorsHeaders(res)
                return res
            }
            if (method == Method.POST) {
                return try {
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
                                val call = WebCall(
                                    cid = json.optString("cid", UUID.randomUUID().toString()),
                                    name = json.optString("name", "Browser $clientIp"),
                                    ip = clientIp,
                                    video = json.optBoolean("video", false),
                                    state = "ringing"
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
                            if (c == null || !c.outgoing || c.ip != clientIp || c.state != "ringing") {
                                jsonResponse(false, "error" to "Nothing to answer")
                            } else {
                                updateWebCall(c.cid, if (wanted == "accept") "accepted" else "declined")
                                jsonResponse(true, "cid" to c.cid)
                            }
                        }
                        "end" -> {
                            val c = webCall
                            if (c != null && (json.optString("cid").isBlank() || c.cid == json.optString("cid"))) {
                                val ended = c.copy(state = "ended", updatedAt = System.currentTimeMillis())
                                webCall = ended
                                // The record alone would leave the phone talking to a tab that hung up.
                                syncWebCallMedia(ended)
                                listener.onWebCall(ended)
                            }
                            jsonResponse(true)
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
            if (!isAuthorized(session)) return unauthorized()
            val cid = session.parameters["cid"]?.firstOrNull() ?: ""
            val call = webCall
            if (call == null || call.cid != cid || call.state != "accepted") {
                return jsonResponse(false, "error" to "No open call")
            }
            markActivity()
            val res = newChunkedResponse(
                Response.Status.OK, "application/octet-stream", WebMediaStream(cid)
            )
            res.addHeader("Cache-Control", "no-store")
            addCorsHeaders(res)
            return res
        }
        if (uri == "/api/webcall/up" && method == Method.POST) {
            if (!isAuthorized(session)) return unauthorized()
            return try {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val json = JSONObject(map["postData"] ?: "{}")
                val call = webCall
                val engine = callEngine
                if (call == null || call.cid != json.optString("cid") || call.state != "accepted" ||
                    engine?.hasWebLeg() != true
                ) {
                    jsonResponse(false, "error" to "No open call")
                } else {
                    val frames = json.optString("frames")
                    if (frames.isNotEmpty()) {
                        engine.ingestWebFrames(
                            android.util.Base64.decode(frames, android.util.Base64.DEFAULT)
                        )
                        markActivity()
                    }
                    jsonResponse(true)
                }
            } catch (e: Exception) {
                jsonResponse(false, "error" to "Invalid frame batch")
            }
        }

        // Who is on this server right now, plus who has talked to it recently.
        if (uri == "/api/peers" && method == Method.GET) {
            if (!isAuthorized(session)) return unauthorized()
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
            addCorsHeaders(res)
            return res
        }

        // API Auth
        if (uri == "/api/auth" && method == Method.POST) {
            return try {
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
                    if (success) put("token", pinCode ?: "ok")
                    if (isLockedOut(clientIp)) {
                        put("locked", true)
                        put("retryAfterSeconds", lockoutRemainingSeconds(clientIp))
                    }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
                addCorsHeaders(res)
                res
            } catch (e: Exception) {
                val res = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                addCorsHeaders(res)
                res
            }
        }

        // Pairing + call signaling between PulseDrop apps — PIN-exempt but key-gated:
        // payloads without a known pairing id or valid ciphertext are rejected.
        if ((uri.startsWith("/api/pair/") || uri.startsWith("/api/call/")) && method == Method.POST) {
            return try {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val json = JSONObject(map["postData"] ?: "{}")
                val pm = pairManager
                val engine = callEngine
                val result: JSONObject = when {
                    uri == "/api/pair/request" && pm != null -> {
                        pm.handleIncomingRequest(json, clientIp)
                        JSONObject().put("success", true).put("waiting", true)
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
                addCorsHeaders(res)
                res
            } catch (e: Exception) {
                val res = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                addCorsHeaders(res)
                res
            }
        }

        // Security check for protected endpoints
        if (!isAuthorized(session)) return unauthorized()

        // API Files List with IP Isolation
        if (uri == "/api/files" && method == Method.GET) {
            val jsonArr = JSONArray()
            synchronized(sharedFiles) {
                for (file in sharedFiles) {
                    // IP Isolation: only show files marked public (targetClientIp == null)
                    // OR files explicitly sent to or uploaded by this specific client IP!
                    val isAllowed = file.targetClientIp == null ||
                            file.targetClientIp == clientIp ||
                            file.uploadedByIp == clientIp

                    if (isAllowed) {
                        val obj = JSONObject().apply {
                            put("id", file.id)
                            put("name", file.name)
                            put("size", file.size)
                            put("mimeType", file.mimeType)
                            put("isMine", file.uploadedByIp == clientIp)
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
            addCorsHeaders(res)
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
            if (file.targetClientIp != null && file.targetClientIp != clientIp && file.uploadedByIp != clientIp) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access forbidden for this device IP.")
            }

            return handleFileDownload(session, file)
        }

        // Download a whole received folder as a zip
        if (uri == "/download-folder" && method == Method.GET) {
            val rel = session.parameters["path"]?.firstOrNull() ?: ""
            return handleFolderDownload(rel)
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
            return handleChatFileDownload(session, id)
        }

        // Inline preview of a hosted file: same bytes, no forced download
        if (uri.startsWith("/preview/") && method == Method.GET) {
            val fileId = uri.removePrefix("/preview/").substringBefore("?")
            val file = synchronized(sharedFiles) { sharedFiles.firstOrNull { it.id == fileId } }
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
            if (file.targetClientIp != null && file.targetClientIp != clientIp && file.uploadedByIp != clientIp) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access forbidden for this device IP.")
            }
            return serveFileInline(session, file)
        }

        // Delete a received file (only files saved by the web upload flow)
        if (uri == "/api/delete" && method == Method.POST) {
            return try {
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
                val groupIds = webThreads.map { it.id }.toSet()
                synchronized(messages) {
                    for (m in messages) {
                        // A browser sees the public room, its own address book, the groups
                        // the phone opened to browsers, and anything it sent itself - so a
                        // browser-to-browser thread reads the same on both sides. Never
                        // another host's direct thread, and never a pair conversation,
                        // which stays on the phones.
                        val thread = m.threadId
                        val mine = m.clientIp == clientIp
                        if (thread != null && !mine && thread != "ip:$clientIp" && thread !in groupIds) continue
                        arr.put(JSONObject().apply {
                            put("id", m.id)
                            put("text", m.text)
                            put("sender", m.sender)
                            put("timestamp", m.timestamp)
                            put("clientIp", m.clientIp ?: "")
                            put("threadId", m.threadId ?: "")
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
                addCorsHeaders(res)
                return res
            } else if (method == Method.POST) {
                return try {
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
                    val attachment = attachmentId.takeIf { it.isNotEmpty() }?.let { id ->
                        synchronized(chatAttachments) { chatAttachments[id] }?.attachment
                    }
                    if (text.isNotBlank() || attachment != null) {
                        addMessage(text.trim(), sender, clientIp, threadId, attachment)
                    }
                    val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
                    addCorsHeaders(res)
                    res
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
                }
            }
        }

        val res = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        addCorsHeaders(res)
        return res
    }

    private fun handleFolderDownload(rel: String): Response {
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

        var zipFile: File? = null
        return try {
            zipFile = File.createTempFile("ld-folder-", ".zip", context.cacheDir)
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(zipFile))).use { zos ->
                folder.walkTopDown().filter { it.isFile }.forEach { f ->
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
            addCorsHeaders(response)
            response
        } catch (e: Exception) {
            zipFile?.delete()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Folder zip failed: ${e.message}")
        }
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
            addCorsHeaders(response)
            response
        } catch (e: Exception) {
            listener.onTransferFailed(transferId, file.name, e.message ?: "Download error", TransferDirection.OUTGOING)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Download error: ${e.message}")
        }
    }

    private fun handleFileUpload(session: IHTTPSession): Response {
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

                if (pairCrypt && pairedSender != null) {
                    val pm = pairManager
                    val ok = pm != null && pm.decryptUpload(pairId!!, tempFile, finalFile)
                    tempFile.delete()
                    if (!ok) {
                        finalFile?.delete()
                        return jsonResponse(false, "error" to "Encrypted upload failed - unknown pairing")
                    }
                } else if (pairCrypt) {
                    tempFile.delete()
                    return jsonResponse(false, "error" to "Unknown pairing id")
                } else {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                break
            }

            if (finalFile != null && finalFile.exists()) {
                fileSize = finalFile.length()

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
                addCorsHeaders(res)
                res
            } else {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false,\"error\":\"No file found in request\"}")
            }
        } catch (e: Exception) {
            // A failed copy/decrypt leaves a truncated file behind; it is not registered, so
            // delete it instead of leaking storage on every error.
            try { finalFile?.delete() } catch (ignored: Exception) { }
            listener.onTransferFailed(transferId, "Upload", e.message ?: "Failed", TransferDirection.INCOMING)
            val res = newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message ?: "Upload failed")
                }.toString()
            )
            addCorsHeaders(res)
            res
        }
    }

    /** Browser-side chat upload: identical bytes, but registered only as a chat attachment. */
    private fun handleChatUpload(session: IHTTPSession): Response {
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
            synchronized(chatAttachments) { chatAttachments[id] = ChatFile(attachment, target) }
            markActivity()
            jsonResponse(true, "attachment" to attachmentJson(attachment))
        } catch (e: Exception) {
            jsonResponse(false, "error" to (e.message ?: "Upload failed"))
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
            addCorsHeaders(response)
            return response
        }
        val local = File(file.uriString)
        if (!local.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File is no longer available")
        }
        return serveLocalFile(session, local, mime, asAttachment = false)
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
                if (asAttachment) addDownloadDisposition(response, displayName ?: file.name)
                response.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(response)
                return response
            }
        }
        val response = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), length)
        response.addHeader("Accept-Ranges", "bytes")
        if (asAttachment) addDownloadDisposition(response, displayName ?: file.name)
        addCorsHeaders(response)
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
            val prefix = cleaned.substringBeforeLast(".").take(if (ext.isNotEmpty()) 120 - ext.length - 1 else 120)
            if (ext.isNotEmpty()) "$prefix.$ext" else prefix
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
        addCorsHeaders(res)
        return res
    }

    private fun deleteReceivedFile(fileId: String, clientIp: String): Response {
        if (fileId.isBlank()) return jsonResponse(false, "error" to "Missing id")
        val file = synchronized(sharedFiles) { sharedFiles.firstOrNull { it.id == fileId } }
            ?: return jsonResponse(false, "error" to "Not found")
        if (file.targetClientIp != null && file.targetClientIp != clientIp && file.uploadedByIp != clientIp) {
            return jsonResponse(false, "error" to "Forbidden")
        }
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

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Pulse-Auth")
    }

    private companion object {
        val WEB_FONTS = mapOf(
            "/fonts/google-sans-regular.ttf" to R.font.google_sans_regular,
            "/fonts/google-sans-medium.ttf" to R.font.google_sans_medium,
            "/fonts/google-sans-semibold.ttf" to R.font.google_sans_semibold,
            "/fonts/google-sans-bold.ttf" to R.font.google_sans_bold
        )
    }
}
