package com.pulsedrop.app.call

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.security.PairManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

data class IncomingCallInfo(
    val cid: String,
    val name: String,
    val pid: String,
    val video: Boolean,
    val peerIp: String,
    val peerPort: Int,
    val mediaIp: String,
    val mediaPort: Int,
    val confId: String = ""
)

/** One seat in a group call. `pid` is the host's own pid for a two-way call. */
data class CallParticipant(
    val pid: String,
    val name: String,
    val connected: Boolean,
    val muted: Boolean = false,
    val sharing: Boolean = false
)

/** "3:07:04" only past the hour; a two-part clock is what a call screen always shows. */
fun formatCallLength(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    val ss = if (s < 10) "0$s" else "$s"
    val mm = if (m < 10) "0$m" else "$m"
    return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
}

data class CallState(
    val active: Boolean = false,
    val outgoing: Boolean = false,
    val connected: Boolean = false,
    val peerName: String = "",
    val peerPid: String = "",
    val video: Boolean = false,
    val muted: Boolean = false,
    val cameraOn: Boolean = false,
    val cameraFront: Boolean = false,
    val sharing: Boolean = false,
    val peerSharing: Boolean = false,
    val peerMuted: Boolean = false,
    /** Wall clock of the moment media came up; 0 until then, so a live call can show its length. */
    val connectedAt: Long = 0L,
    val status: String = "",
    val conference: Boolean = false,
    val isHost: Boolean = false,
    val confId: String = "",
    val participants: List<CallParticipant> = emptyList()
)

/**
 * Peer-to-peer LAN calls between paired PulseDrop devices.
 * Signaling rides on both devices' HTTP servers; media is PCM audio plus
 * JPEG camera/screen frames over a direct TCP socket.
 */
class CallEngine(
    private val context: Context,
    private val pairManager: () -> PairManager?,
    private val nameProvider: () -> String,
    private val endpointProvider: () -> Pair<String, Int>,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    val remoteFrame = MutableStateFlow<Bitmap?>(null)
    val incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val toast = MutableStateFlow<String?>(null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var cid: String = ""
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    /** The most recent finished call, for the "Call ended - 3:12" card. */
    private val _lastCall = MutableStateFlow<CallRecord?>(null)
    val lastCall: StateFlow<CallRecord?> = _lastCall.asStateFlow()

    /**
     * Recorded the moment a leg stops: the UI needs both the length and why it stopped, and a
     * "Call ended" notice with neither reads as though the call had never connected.
     */
    data class CallRecord(
        val peerName: String,
        val seconds: Long,
        val video: Boolean,
        val conference: Boolean,
        val reason: String,
        val endedAt: Long = System.currentTimeMillis()
    )

    /** Address the peer can dial to reach our media listener. */
    @Volatile private var mediaHost: String = ""
    @Volatile private var mediaPort: Int = 0

    private var audioThread: Thread? = null
    private var readerThread: Thread? = null
    private var cameraThread: android.os.HandlerThread? = null
    @Volatile private var cameraOpening = false
    @Volatile private var cameraGeneration = 0
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var camera: Camera? = null
    @Volatile private var cameraParams: Camera.Parameters? = null
    @Volatile private var requestedFront = false
    /** A lens swap the next successful open should confirm or deny out loud. */
    @Volatile private var flipPending = false
    @Volatile private var frameRotation = 0
    @Volatile private var lastCamFrameAt = 0L
    /** Ten a second: the frame got cheap enough to pay for, see [startCamera]. */
    private val cameraFrameMs = 100L
    /** Reused rotation scratch, so a turn allocates nothing per frame. */
    private var rotBuf = ByteArray(0)
    /** Playback audio from a screen share, waiting for the frame pacer to take it. */
    private val shareRing = SampleRing(CALL_FRAME_SAMPLES * 4)
    private val shareFrame = ShortArray(CALL_FRAME_SAMPLES)
    private val shareScratch = ShortArray(CALL_FRAME_SAMPLES * 2)
    /** SurfaceView holder the call overlay attaches to for the local self-preview. */
    @Volatile private var previewHolder: android.view.SurfaceHolder? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var speakerphoneBefore = false
    private var ringExpiryJob: kotlinx.coroutines.Job? = null
    @Volatile private var mediaStarted = false
    @Volatile private var confId: String = ""

    private val responses = mutableMapOf<String, JSONObject>()

    val remoteFrames = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /**
     * Browser leg. A tab cannot open a TCP socket, so its frames ride the site's own HTTP
     * server: the downlink is one long chunked response reading [webOut], the uplink is a
     * POST appending to [webIn]. Both carry the same [type][len][payload] framing as a socket.
     */
    @Volatile private var webOnly = false
    private val webOut = java.util.concurrent.LinkedBlockingQueue<ByteArray>(96)
    /** A batch may end mid-frame, so the remainder waits here for the next one. */
    private val pendingIn = java.io.ByteArrayOutputStream(64 * 1024)

    /**
     * A browser tab riding along in a hosted conference. The host is the bridge, so this is
     * the only shape where web media and socket legs exist at the same time: member audio
     * arrives per-leg and leaves to the tab as one mixed stream.
     */
    @Volatile private var webAttached = false
    @Volatile private var webTabName = ""
    @Volatile private var webMuted = false
    @Volatile private var webSharing = false
    private val webAudioLatest = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, Long>>()
    @Volatile private var webMixThread: Thread? = null

    /** Host-side fan-out: one leg per joined member, each with its own reader thread. */
    private class ConfLeg(
        val pid: String,
        val name: String,
        val socket: Socket,
        val out: DataOutputStream
    ) {
        @Volatile var muted = false
        @Volatile var sharing = false
        @Volatile var connected = false
    }

    private val legs = java.util.concurrent.ConcurrentHashMap<String, ConfLeg>()
    /** Invited but not yet answered, as pid to name, so the roster can show a ringing seat. */
    private val confInvitees = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val confTracks = HashMap<String, AudioTrack>()
    @Volatile private var confHostPid = ""

    companion object {
        private const val TYPE_AUDIO = 1
        private const val TYPE_CAMERA = 2
        private const val TYPE_SCREEN = 3
        /** In-band "I started/stopped sharing" notice; peers ignore unknown types. */
        private const val TYPE_SCREEN_STATE = 4
        /** Conference relay: [1b srcLen][src][pcm] - a member's single leg never needs this. */
        private const val TYPE_CONF_AUDIO = 5
        /** Conference relay: [1b srcLen][src][1b kind][jpeg], kind 2 = screen, else camera. */
        private const val TYPE_CONF_VIDEO = 6
        /** Conference roster as JSON, pushed by the host whenever it changes. */
        private const val TYPE_CONF_STATE = 7
        /** Conference roster id for the bridge itself: pairwise ids are per-member, so they
         *  cannot name the host to everybody at once. */
        private const val HOST_SRC = "host"
        /** The synthetic peer id a browser tab gets: it has no pairing and no TCP media socket. */
        const val WEB_PID = "web"
        private const val MAX_CONF_MEMBERS = 5
        private const val SR = 16000
        private const val AUDIO_CHUNK = 3200 // 100 ms of 16-bit mono
        private const val RING_TIMEOUT_MS = 35_000L
    }

    // ---- signaling --------------------------------------------------------

    private fun openMediaListener(): ServerSocket {
        val ss = ServerSocket(0)
        serverSocket = ss
        val (localIp, _) = endpointProvider()
        mediaHost = localIp
        mediaPort = ss.localPort
        return ss
    }

    fun invite(pid: String, video: Boolean) {
        val pm = pairManager() ?: return
        val peer = pm.findPairing(pid) ?: return
        if (_state.value.active) { toast.value = "Already in a call"; return }

        val newCid = UUID.randomUUID().toString()
        cid = newCid
        val (myIp, myPort) = endpointProvider()
        val media = try {
            openMediaListener()
        } catch (e: Exception) {
            toast.value = "Could not open media channel"; return
        }
        _state.value = CallState(
            active = true, outgoing = true, video = video,
            peerName = peer.name, peerPid = pid, status = "Ringing ${peer.name}…"
        )

        scope.launch(Dispatchers.IO) {
            val body = JSONObject()
                .put("cid", newCid)
                .put("name", nameProvider())
                .put("pid", pid)
                .put("video", video)
                .put("myIp", myIp)
                .put("myPort", myPort)
                .put("mediaIp", mediaHost)
                .put("mediaPort", mediaPort)
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            try {
                client.newCall(
                    Request.Builder().url("http://${peer.originIp}:${peer.originPort}/api/call/invite")
                        .post(body).build()
                ).execute().close()
            } catch (e: Exception) {
                if (_state.value.outgoing) reset("Could not reach ${peer.name}")
                return@launch
            }

            var waited = 0
            while (waited < 45_000 && _state.value.outgoing) {
                delay(500); waited += 500
                val answer = synchronized(responses) { responses.remove(newCid) }
                if (answer != null) {
                    if (!answer.optBoolean("accept", false)) {
                        reset(if (answer.optBoolean("busy", false)) "${peer.name} is busy" else "Call declined")
                        return@launch
                    }
                    // The answering side listens; we dial its media channel.
                    val ip = answer.optString("mediaIp").ifBlank { peer.originIp }
                    val port = answer.optInt("mediaPort", 0)
                    if (ip.isBlank() || port <= 0) {
                        reset("No media channel from ${peer.name}"); return@launch
                    }
                    try {
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress(ip, port), 5000)
                        beginMedia(s, peer, video)
                    } catch (e: Exception) {
                        reset("Media channel failed: ${e.message}")
                    }
                    return@launch
                }
            }
            if (_state.value.outgoing) {
                sendEnd(newCid, peer.originIp, peer.originPort)
                reset("No answer from ${peer.name}")
            }
        }
    }

    /** Both sides advertise a media listener; the peer that answers does the dialing. */
    private fun awaitInboundMedia(ss: ServerSocket, peer: PairedDevice, video: Boolean) {
        try {
            ss.soTimeout = 20000
            val s = ss.accept()
            s.tcpNoDelay = true
            beginMedia(s, peer, video)
        } catch (e: Exception) {
            reset("Media channel failed")
        }
    }

    /** Server hook: POST /api/call/invite */
    fun handleInvite(json: JSONObject, clientIp: String): JSONObject {
        val newCid = json.optString("cid")
        val pid = json.optString("pid")
        val pm = pairManager()
        val paired = pid.isNotBlank() && pm?.findPairing(pid) != null
        if (!paired || _state.value.active) {
            val busy = _state.value.active
            scope.launch(Dispatchers.IO) {
                sendReply(
                    json.optString("myIp", clientIp), json.optInt("myPort", 8080),
                    JSONObject().put("cid", newCid).put("accept", false).put("busy", busy)
                )
            }
            return JSONObject().put("success", true).put("busy", busy)
        }
        incomingCall.value = IncomingCallInfo(
            cid = newCid,
            name = json.optString("name", "Paired device"),
            pid = pid,
            video = json.optBoolean("video", false),
            peerIp = json.optString("myIp", clientIp),
            peerPort = json.optInt("myPort", 8080),
            mediaIp = json.optString("mediaIp"),
            mediaPort = json.optInt("mediaPort", 0),
            confId = json.optString("conf")
        )
        // A caller that vanishes without posting /api/call/end (crash, walked off the Wi-Fi)
        // would otherwise leave the ringing dialog up forever.
        ringExpiryJob?.cancel()
        ringExpiryJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (incomingCall.value?.cid == newCid) {
                incomingCall.value = null
                toast.value = "Missed call from ${json.optString("name", "Paired device")}"
            }
        }
        return JSONObject().put("success", true)
    }

    /** Server hook: POST /api/call/response */
    fun handleResponse(json: JSONObject) {
        val cidKey = json.optString("cid")
        // A conference host gets one answer per invitee, so keep them apart.
        val who = json.optString("pid")
        val key = if (json.optString("conf").isNotBlank() && who.isNotBlank()) "$cidKey|$who" else cidKey
        synchronized(responses) { responses[key] = json }
    }

    /** Server hook: POST /api/call/end */
    fun handleEnd(json: JSONObject) {
        val endedCid = json.optString("cid")
        if (endedCid.isBlank()) return
        val who = json.optString("pid")
        // While ringing, `cid` is still empty: the ringing id lives in incomingCall. Without
        // this the caller's cancel/timeout leaves a ringing dialog that can never be dismissed.
        if (endedCid != cid && incomingCall.value?.cid != endedCid) return
        if (incomingCall.value?.cid == endedCid) incomingCall.value = null
        if (!_state.value.active) return
        // One member hanging up on the bridge must not tear down everybody else's call.
        if (_state.value.conference && _state.value.isHost && who != HOST_SRC) {
            legs.remove(who)?.let { try { it.socket.close() } catch (e: Exception) { } }
            confInvitees.remove(who)
            broadcastConfState()
            if (legs.values.none { it.connected }) reset("Group call ended")
            return
        }
        reset(if (_state.value.conference) "Group call ended" else "Call ended by other side")
    }

    fun respondIncoming(accept: Boolean) {
        val call = incomingCall.value ?: return
        ringExpiryJob?.cancel()
        ringExpiryJob = null
        incomingCall.value = null
        if (!accept) {
            scope.launch(Dispatchers.IO) {
                sendReply(call.peerIp, call.peerPort,
                    JSONObject().put("cid", call.cid).put("accept", false))
            }
            return
        }
        val pm = pairManager() ?: return
        val peer = pm.findPairing(call.pid) ?: return
        val joining = call.confId.isNotBlank()
        cid = if (joining) call.confId else call.cid
        confId = call.confId
        _state.value = CallState(
            active = true, video = call.video,
            peerName = call.name, peerPid = call.pid,
            conference = joining, isHost = false, confId = call.confId,
            status = if (joining) "Joining ${call.name}’s group call…" else "Connecting…"
        )
        scope.launch(Dispatchers.IO) {
            try {
                val ss = openMediaListener()
                sendReply(
                    call.peerIp, call.peerPort,
                    JSONObject().put("cid", call.cid).put("accept", true)
                        .put("pid", call.pid).put("conf", call.confId)
                        .put("mediaIp", mediaHost).put("mediaPort", mediaPort)
                )
                awaitInboundMedia(ss, peer, call.video)
            } catch (e: Exception) {
                reset("Call setup failed")
            }
        }
    }

    fun hangup() {
        val peer = pairManager()?.findPairing(_state.value.peerPid)
        val currentCid = cid
        val wasHost = _state.value.isHost && _state.value.conference
        val mySeat = _state.value.peerPid
        val legTargets = legs.keys.toList()
        reset("Call ended")
        scope.launch(Dispatchers.IO) {
            if (wasHost) {
                // Members only know the bridge, so the bridge has to break the news: close the
                // leg and post /api/call/end to each one's last known server.
                legTargets.forEach { pid ->
                    pairManager()?.findPairing(pid)?.let {
                        sendEnd(currentCid, it.originIp, it.originPort, HOST_SRC)
                    }
                }
            } else {
                peer?.let { sendEnd(currentCid, it.originIp, it.originPort, mySeat) }
            }
        }
    }

    private fun sendEnd(targetCid: String, ip: String, port: Int, fromPid: String = "") {
        try {
            val body = JSONObject().put("cid", targetCid).put("pid", fromPid).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            client.newCall(
                Request.Builder().url("http://$ip:$port/api/call/end").post(body).build()
            ).execute().close()
        } catch (e: Exception) { }
    }

    private fun sendReply(ip: String, port: Int, payload: JSONObject) {
        try {
            client.newCall(
                Request.Builder().url("http://$ip:$port/api/call/response")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute().close()
        } catch (e: Exception) { }
    }

    // ---- media ------------------------------------------------------------

    /** True while the far end of the live call is a browser tab rather than a paired device. */
    fun hasWebLeg(): Boolean = (webOnly || webAttached) && running

    /**
     * Bring up a call whose other end is a browser tab. There is no socket to dial: the frames
     * appear on [webOut] and arrive on [webIn], which the embedded server pumps.
     */
    fun startWebCall(video: Boolean, peerName: String) {
        if (_state.value.active) { toast.value = "Already in a call"; return }
        running = true
        mediaStarted = true
        webOnly = true
        confHostPid = WEB_PID
        webOut.clear()
        synchronized(pendingIn) { pendingIn.reset() }
        _state.value = CallState(
            active = true, video = video,
            peerName = peerName, peerPid = WEB_PID,
            status = "Connecting to $peerName…"
        )
        scope.launch(Dispatchers.IO) {
            openCallAudioRoute()
            startAudioInOut()
            if (video) startCamera()
            _state.value = _state.value.copy(
                outgoing = false, connected = true,
                connectedAt = System.currentTimeMillis(),
                status = "In call with $peerName"
            )
        }
    }

    /**
     * A browser dialled this phone and asked for a whole group. The phone answers as host of
     * a star conference, the tab rides in as an extra seat, and every member device gets its
     * normal incoming group-call invite - so calling from a browser rings everybody, not
     * just the phone the tab happens to be served by.
     */
    fun startWebCallGroup(video: Boolean, tabName: String, pids: List<String>) {
        val pm = pairManager() ?: return
        if (_state.value.active) { toast.value = "Already in a call"; return }
        val members = pids.mapNotNull { pm.findPairing(it) }.distinctBy { it.pid }.take(MAX_CONF_MEMBERS)
        if (members.isEmpty()) {
            startWebCall(video, tabName)
            return
        }

        val newCid = UUID.randomUUID().toString()
        cid = newCid
        confId = newCid
        webAttached = true
        webTabName = tabName.ifBlank { "Browser" }
        running = true
        mediaStarted = true
        webOut.clear()
        synchronized(pendingIn) { pendingIn.reset() }
        _state.value = CallState(
            active = true, video = video,
            peerName = webTabName, peerPid = WEB_PID,
            conference = true, isHost = true, confId = newCid,
            status = "Group call from $webTabName…",
            participants = members.map { CallParticipant(it.pid, it.name, false) } +
                CallParticipant(HOST_SRC, nameProvider(), true) +
                CallParticipant(WEB_PID, webTabName, true)
        )

        val (myIp, myPort) = endpointProvider()
        confInvitees.clear()
        members.forEach { confInvitees[it.pid] = it.name }
        openCallAudioRoute()
        startAudioInOut()
        if (video) startCamera()
        startWebMixer()
        scope.launch(Dispatchers.IO) {
            var reached = 0
            members.forEach { peer ->
                val body = JSONObject()
                    .put("cid", newCid)
                    .put("conf", newCid)
                    .put("name", nameProvider())
                    .put("pid", peer.pid)
                    .put("video", video)
                    .put("myIp", myIp)
                    .put("myPort", myPort)
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                try {
                    client.newCall(
                        Request.Builder().url("http://${peer.originIp}:${peer.originPort}/api/call/invite")
                            .post(body).build()
                    ).execute().close()
                    reached++
                } catch (e: Exception) {
                    confInvitees.remove(peer.pid)
                }
            }
            if (reached == 0) {
                confInvitees.clear()
                // Nobody could be dialled; the tab and this phone still have each other.
                _state.value = _state.value.copy(
                    connected = true,
                    connectedAt = System.currentTimeMillis(),
                    status = "In call with $webTabName"
                )
                broadcastConfState()
                return@launch
            }
            var waited = 0
            while (waited < RING_TIMEOUT_MS.toInt() && _state.value.active && confInvitees.isNotEmpty()) {
                delay(500); waited += 500
                val keys = synchronized(responses) { responses.keys.filter { it.startsWith("$newCid|") } }
                keys.forEach { key ->
                    val answer = synchronized(responses) { responses.remove(key) } ?: return@forEach
                    val who = key.substringAfter("|")
                    val ip = answer.optString("mediaIp")
                    val port = answer.optInt("mediaPort", 0)
                    if (answer.optBoolean("accept", false) && ip.isNotBlank() && port > 0) {
                        dialLeg(who, ip, port)
                    }
                    confInvitees.remove(who)
                    broadcastConfState()
                }
            }
            if (!_state.value.active) return@launch
            val live = legs.values.count { it.connected }
            _state.value = _state.value.copy(
                connected = true,
                connectedAt = System.currentTimeMillis(),
                status = if (live > 0) "Group call · ${live + 2} devices" else "In call with $webTabName"
            )
            broadcastConfState()
        }
    }

    /** Tear a browser leg down from the server side, with no peer socket to notify. */
    fun stopWebCall(reason: String = "Call ended") {
        if (!webOnly && !webAttached) return
        reset(reason)
    }
    /** Block for the next frame headed to the tab. Null means the leg is gone. */
    fun nextWebOutFrame(timeoutMs: Long): ByteArray? {
        if (!webOnly && !webAttached) return null
        val f = webOut.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return if (f == null && !webOnly && !webAttached) null else f
    }

    /**
     * Accept one uplink batch: zero or more `[1b type][4b BE len][payload]` frames. A trailing
     * partial frame is held until the next batch completes it.
     */
    fun ingestWebFrames(bytes: ByteArray) {
        if ((!webOnly && !webAttached) || !running) return
        synchronized(pendingIn) {
            if (bytes.isNotEmpty()) pendingIn.write(bytes)
            val buf = pendingIn.toByteArray()
            var i = 0
            while (i + 5 <= buf.size) {
                val type = buf[i].toInt() and 0xFF
                val len = ((buf[i + 1].toInt() and 0xFF) shl 24) or
                    ((buf[i + 2].toInt() and 0xFF) shl 16) or
                    ((buf[i + 3].toInt() and 0xFF) shl 8) or
                    (buf[i + 4].toInt() and 0xFF)
                if (len <= 0 || len > 4 * 1024 * 1024) { i = buf.size; break }
                if (i + 5 + len > buf.size) break
                val payload = buf.copyOfRange(i + 5, i + 5 + len)
                handleFrame(WEB_PID, type, payload)
                // In a hosted conference the tab is a seat like any other member.
                if (webAttached && !webOnly) {
                    when (type) {
                        TYPE_AUDIO, TYPE_CAMERA, TYPE_SCREEN ->
                            relayToLegs(WEB_PID, null, type, payload)
                        TYPE_SCREEN_STATE -> {
                            val obj = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (e: Exception) { null }
                            if (obj != null) {
                                webSharing = obj.optBoolean("sharing", false)
                                webMuted = obj.optBoolean("muted", false)
                                broadcastConfState()
                            }
                        }
                    }
                }
                i += 5 + len
            }
            pendingIn.reset()
            if (i < buf.size) pendingIn.write(buf, i, buf.size - i)
        }
    }

    private fun beginMedia(s: Socket, peer: PairedDevice, video: Boolean) {
        socket = s
        out = DataOutputStream(BufferedOutputStreamCompat.wrap(s.getOutputStream()))
        running = true
        mediaStarted = true
        _state.value = _state.value.copy(
            outgoing = false, connected = true, video = video,
            connectedAt = System.currentTimeMillis(),
            status = if (_state.value.conference) "Group call · ${peer.name}’s call"
            else "In call with ${peer.name}"
        )
        confHostPid = if (_state.value.conference) HOST_SRC else peer.pid
        openCallAudioRoute()
        startAudioInOut()
        startReader()
        if (video) startCamera()
    }

    /**
     * Compose click handlers run on the main thread, where StrictMode rejects socket I/O, so a
     * control frame sent straight from a button would look like a dead link and tear the call
     * down. Anything raised by the UI leaves from a worker instead.
     */
    private fun writeFrame(type: Int, payload: ByteArray) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scope.launch(Dispatchers.IO) { writeFrameNow(type, payload) }
            return
        }
        writeFrameNow(type, payload)
    }

    private fun writeFrameNow(type: Int, payload: ByteArray) {
        if (webOnly) {
            writeWebFrame(type, payload)
            return
        }
        if (_state.value.conference && _state.value.isHost) {
            // The host is the only sender with more than one listener. A leg that has died must
            // not take the conference down with it, so failures are reaped per leg here.
            legs.values.forEach { leg ->
                if (!writeTo(leg.out, type, payload)) reapLeg(leg.pid)
            }
            // A browser riding the conference receives the host's own frames directly.
            if (webAttached) writeWebFrame(type, payload)
            return
        }
        val o = out ?: return
        if (!writeTo(o, type, payload) && running) reset("Connection lost")
    }

    /**
     * The tab drains [webOut] as one chunked stream, so a stalled reader must never build a
     * backlog: live media only ever wants the newest frame.
     */
    private fun writeWebFrame(type: Int, payload: ByteArray) {
        val n = payload.size
        val frame = ByteArray(5 + n)
        frame[0] = type.toByte()
        frame[1] = (n ushr 24).toByte()
        frame[2] = (n ushr 16).toByte()
        frame[3] = (n ushr 8).toByte()
        frame[4] = n.toByte()
        System.arraycopy(payload, 0, frame, 5, n)
        while (!webOut.offer(frame)) webOut.poll()
    }

    /** Returns false when the write failed, so the caller can drop just that connection. */
    private fun writeTo(o: DataOutputStream, type: Int, payload: ByteArray): Boolean {
        return try {
            synchronized(o) {
                o.write(type)
                o.writeInt(payload.size)
                o.write(payload)
                o.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun reapLeg(pid: String) {
        legs.remove(pid)?.let { try { it.socket.close() } catch (e: Exception) { } }
        confInvitees.remove(pid)
        broadcastConfState()
        if (running && legs.values.none { it.connected }) reset("Group call ended")
    }

    /** Forward one member's frames to everyone else, tagged with who sent them. */
    private fun relayToLegs(fromPid: String, legOut: DataOutputStream?, type: Int, payload: ByteArray) {
        val relayType: Int
        val body: ByteArray?
        when (type) {
            TYPE_AUDIO -> {
                relayType = TYPE_CONF_AUDIO
                body = ConfFrame.audio(fromPid, payload)
            }
            TYPE_CAMERA, TYPE_SCREEN -> {
                relayType = TYPE_CONF_VIDEO
                val kind = if (type == TYPE_SCREEN) ConfFrame.KIND_SCREEN else ConfFrame.KIND_CAMERA
                body = ConfFrame.video(fromPid, payload, kind)
            }
            else -> return
        }
        if (body == null) return
        legs.values.forEach { leg ->
            if (legOut != null && leg.out === legOut) return@forEach
            if (!writeTo(leg.out, relayType, body)) reapLeg(leg.pid)
        }
    }

    private fun startAudioInOut() {
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val hasMicPermission = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            _state.value = _state.value.copy(status = "Call connected - mic unavailable, no RECORD_AUDIO permission")
        }
        audioThread = Thread {
            var rec: AudioRecord? = null
            try {
                rec = if (hasMicPermission) {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, AUDIO_CHUNK * 4)
                    )
                } else null
                record = rec
                val buf = ByteArray(AUDIO_CHUNK)
                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording()
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            val voice = if (_state.value.muted) ByteArray(n) else buf.copyOf(n)
                            writeFrame(TYPE_AUDIO, withShareAudio(voice, n))
                        }
                    }
                } else {
                    // No usable capture device: keep the call alive and send silence.
                    // Paced to the chunk length, otherwise this floods the peer's playback
                    // buffer at 5x realtime.
                    while (running) {
                        // Muted or micless, a shared video is still audible to the peer.
                        writeFrame(TYPE_AUDIO, withShareAudio(ByteArray(AUDIO_CHUNK), AUDIO_CHUNK))
                        Thread.sleep(100)
                    }
                }
            } catch (e: Exception) {
                // mic unavailable; sending silence
            } finally {
                try { rec?.stop() } catch (e: Exception) { }
                try { rec?.release() } catch (e: Exception) { }
            }
        }.apply { isDaemon = true; start() }

        // A group call plays each remote through its own track; a single one would sit silent.
        if (_state.value.conference) return
        val minOut = AudioTrack.getMinBufferSize(
            SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            AudioTrack(attrs, fmt, maxOf(minOut, AUDIO_CHUNK * 4), AudioTrack.MODE_STREAM, 0)
                .also { it.play() }
        } catch (e: Exception) { null }
    }

    /** Decode one inbound frame and route it to the right speaker/tile. */
    private fun handleFrame(mySrc: String, type: Int, payload: ByteArray) {
        when (type) {
            TYPE_AUDIO -> playbackFor(mySrc)?.write(payload, 0, payload.size)
            TYPE_SCREEN_STATE -> {
                val obj = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (e: Exception) { null }
                val on = obj?.optBoolean("sharing", false) ?: false
                val muted = obj?.optBoolean("muted", false) ?: false
                if (_state.value.conference && _state.value.isHost) {
                    legs[mySrc]?.let { leg ->
                        if (leg.sharing != on || leg.muted != muted) {
                            leg.sharing = on
                            leg.muted = muted
                            broadcastConfState()
                        }
                    }
                } else if (_state.value.conference) {
                    // A member's own mute/share is echoed back into its own tile by the bridge.
                    _state.value = _state.value.copy(
                        participants = _state.value.participants.map {
                            if (it.pid == confHostPid) it.copy(sharing = on, muted = muted) else it
                        }
                    )
                } else {
                    val wasSharing = _state.value.peerSharing
                    if (on != wasSharing || muted != _state.value.peerMuted) {
                        _state.value = _state.value.copy(peerSharing = on, peerMuted = muted)
                        if (on != wasSharing) {
                            toast.value = if (on) "${_state.value.peerName} is sharing their screen"
                            else "${_state.value.peerName} stopped sharing"
                        }
                    }
                }
            }
            TYPE_CONF_STATE -> {
                // Only the bridge computes the roster; everybody else just renders it.
                if (!_state.value.isHost) applyRoster(payload)
            }
            TYPE_CONF_AUDIO -> {
                val part = ConfFrame.readAudio(payload) ?: return
                playbackFor(part.first)?.write(part.second, 0, part.second.size)
            }
            TYPE_CONF_VIDEO -> {
                val part = ConfFrame.readVideo(payload) ?: return
                val bmp = decodeJpeg(part.second) ?: return
                showFrame(part.first, bmp)
            }
            TYPE_CAMERA, TYPE_SCREEN -> {
                val bmp = decodeJpeg(payload) ?: return
                showFrame(mySrc, bmp)
            }
        }
    }

    private fun decodeJpeg(payload: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(payload, 0, payload.size)
    } catch (e: Exception) {
        null
    }

    private fun showFrame(src: String, bmp: Bitmap) {
        remoteFrame.value = bmp
        if (!_state.value.conference) return
        val next = HashMap(remoteFrames.value)
        next[src] = bmp
        remoteFrames.value = next
    }

    /**
     * Conference playback uses one AudioTrack per remote speaker. Android mixes them in
     * hardware, which is both simpler and better than summing PCM in software.
     */
    private fun playbackFor(src: String): AudioTrack? {
        if (!_state.value.conference) return track
        synchronized(confTracks) {
            confTracks[src]?.let { return it }
        }
        val created = try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack(attrs, fmt, maxOf(min, AUDIO_CHUNK * 4), AudioTrack.MODE_STREAM, 0)
                .also { it.play() }
        } catch (e: Exception) {
            null
        } ?: return null
        synchronized(confTracks) { confTracks[src] = created }
        return created
    }

    // ---- conference (group) calls -------------------------------------------

    /**
     * Start a star conference with the host as the bridge: the host dials every member,
     * each member holds exactly one connection, and the host relays between them. A member
     * that drops out takes only its own leg down with it.
     */
    fun inviteConference(pids: List<String>, video: Boolean) {
        val pm = pairManager() ?: return
        val members = pids.mapNotNull { pm.findPairing(it) }.distinctBy { it.pid }
            .take(MAX_CONF_MEMBERS)
        if (_state.value.active) { toast.value = "Already in a call"; return }
        if (members.size < 2) { toast.value = "A group call needs at least two paired devices"; return }
        if (pids.size > MAX_CONF_MEMBERS) {
            toast.value = "Group calls top out at $MAX_CONF_MEMBERS devices"
        }

        val newCid = UUID.randomUUID().toString()
        cid = newCid
        confId = newCid
        _state.value = CallState(
            active = true, outgoing = true, video = video,
            peerName = nameProvider(), peerPid = "",
            conference = true, isHost = true, confId = newCid,
            status = "Calling ${members.size} devices…",
            participants = members.map { CallParticipant(it.pid, it.name, false) }
        )
        _state.value = _state.value.copy(
            participants = _state.value.participants +
                    CallParticipant(HOST_SRC, nameProvider(), true)
        )

        val (myIp, myPort) = endpointProvider()
        confInvitees.clear()
        members.forEach { confInvitees[it.pid] = it.name }
        // Leg readers and the media pump both stop on `running`, so it has to be true before
        // the first leg is dialed - not after the ringing window.
        running = true
        scope.launch(Dispatchers.IO) {
            var reached = 0
            members.forEach { peer ->
                val body = JSONObject()
                    .put("cid", newCid)
                    .put("conf", newCid)
                    .put("name", nameProvider())
                    .put("pid", peer.pid)
                    .put("video", video)
                    .put("myIp", myIp)
                    .put("myPort", myPort)
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                try {
                    client.newCall(
                        Request.Builder().url("http://${peer.originIp}:${peer.originPort}/api/call/invite")
                            .post(body).build()
                    ).execute().close()
                    reached++
                } catch (e: Exception) {
                    confInvitees.remove(peer.pid)
                }
            }
            if (reached == 0) {
                confInvitees.clear()
                reset("No one could be reached"); return@launch
            }
            var waited = 0
            while (waited < RING_TIMEOUT_MS.toInt() && _state.value.active && confInvitees.isNotEmpty()) {
                delay(500); waited += 500
                val keys = synchronized(responses) { responses.keys.filter { it.startsWith("$newCid|") } }
                keys.forEach { key ->
                    val answer = synchronized(responses) { responses.remove(key) } ?: return@forEach
                    val who = key.substringAfter("|")
                    val ip = answer.optString("mediaIp")
                    val port = answer.optInt("mediaPort", 0)
                    if (answer.optBoolean("accept", false) && ip.isNotBlank() && port > 0) {
                        dialLeg(who, ip, port)
                    }
                    confInvitees.remove(who)
                    broadcastConfState()
                }
            }
            val live = legs.values.count { it.connected }
            if (live == 0) { confInvitees.clear(); reset("Nobody joined the group call"); return@launch }
            if (!mediaStarted) {
                mediaStarted = true
                openCallAudioRoute()
                startAudioInOut()
                if (video) startCamera()
            }
            _state.value = _state.value.copy(
                outgoing = false, connected = true,
                connectedAt = System.currentTimeMillis(),
                status = "Group call · ${live + 1} devices"
            )
            broadcastConfState()
        }
    }

    private fun dialLeg(pid: String, ip: String, port: Int) {
        val pm = pairManager()
        val name = pm?.findPairing(pid)?.name ?: "Device"
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, port), 5000)
            val leg = ConfLeg(pid, name, s, DataOutputStream(BufferedOutputStreamCompat.wrap(s.getOutputStream())))
            legs[pid] = leg
            leg.connected = true
            startLegReader(leg)
        } catch (e: Exception) {
            reapLeg(pid)
        }
    }

    private fun startLegReader(leg: ConfLeg) {
        Thread {
            try {
                val input = DataInputStream(java.io.BufferedInputStream(leg.socket.getInputStream()))
                while (running) {
                    val type = input.read()
                    if (type == -1) break
                    val len = input.readInt()
                    if (len <= 0 || len > 4 * 1024 * 1024) break
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    handleFrame(leg.pid, type, payload)
                    if (type == TYPE_AUDIO || type == TYPE_CAMERA || type == TYPE_SCREEN) {
                        relayToLegs(leg.pid, leg.out, type, payload)
                        if (webAttached) feedWebLeg(type, leg.pid, payload)
                    }
                }
            } catch (e: Exception) {
                // leg closed
            } finally {
                if (running && legs[leg.pid]?.connected == true) reapLeg(leg.pid)
            }
        }.apply { isDaemon = true; name = "PulseDropConfLeg"; start() }
    }


    private fun rosterJson(): JSONArray {
        val arr = JSONArray()
        legs.values.forEach { leg ->
            arr.put(
                JSONObject()
                    .put("pid", leg.pid)
                    .put("name", leg.name)
                    .put("connected", leg.connected)
                    .put("muted", leg.muted)
                    .put("sharing", leg.sharing)
            )
        }
        confInvitees.forEach { (pid, name) ->
            if (legs.containsKey(pid)) return@forEach
            arr.put(
                JSONObject()
                    .put("pid", pid)
                    .put("name", name)
                    .put("connected", false)
                    .put("muted", false)
                    .put("sharing", false)
            )
        }
        val me = JSONObject()
            .put("pid", HOST_SRC)
            .put("name", nameProvider())
            .put("connected", true)
            .put("muted", _state.value.muted)
            .put("sharing", _state.value.sharing)
        if (_state.value.isHost) arr.put(me)
        if (_state.value.isHost && webAttached) arr.put(
            JSONObject()
                .put("pid", WEB_PID)
                .put("name", webTabName.ifBlank { "Browser" })
                .put("connected", true)
                .put("muted", webMuted)
                .put("sharing", webSharing)
        )
        return arr
    }

    /** Host pushes the roster so every tile can show names, mute and sharing state. */
    private fun broadcastConfState() {
        if (!_state.value.conference) return
        if (_state.value.isHost) {
            val json = JSONObject().put("participants", rosterJson()).toString()
                .toByteArray(Charsets.UTF_8)
            legs.values.forEach { writeTo(it.out, TYPE_CONF_STATE, json) }
            applyRoster(json)
        }
        // Members only ever receive the roster from the host.
    }

    /**
     * A tab has one remote feed, not a grid: audio from every member is summed into one chunk
     * stream by [startWebMixer], and video forwards whoever spoke last. Group calling from a
     * browser is a bridge with a guest seat, so hearing everyone is the part that must work.
     */
    private fun feedWebLeg(type: Int, fromPid: String, payload: ByteArray) {
        when (type) {
            TYPE_AUDIO -> webAudioLatest[fromPid] = payload to System.currentTimeMillis()
            TYPE_CAMERA, TYPE_SCREEN -> writeWebFrame(type, payload)
        }
    }

    private fun startWebMixer() {
        if (webMixThread != null) return
        webMixThread = Thread {
            val samples = AUDIO_CHUNK / 2
            while (running && (webOnly || webAttached)) {
                val now = System.currentTimeMillis()
                val live = webAudioLatest.entries.filter { it.value.second > now - 400 }
                if (live.isNotEmpty()) {
                    val acc = IntArray(samples)
                    live.forEach { (_, v) ->
                        val pcm = v.first
                        var i = 0
                        var s = 0
                        while (i + 1 < pcm.size && s < samples) {
                            acc[s] += (((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()).toInt()
                            i += 2; s++
                        }
                    }
                    val mixed = ByteArray(samples * 2)
                    for (k in 0 until samples) {
                        val clamped = acc[k].coerceIn(-32768, 32767).toShort().toInt()
                        mixed[k * 2] = (clamped and 0xFF).toByte()
                        mixed[k * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
                    }
                    writeWebFrame(TYPE_AUDIO, mixed)
                }
                try { Thread.sleep(100) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "PulseDropWebMix"; start() }
    }

    private fun applyRoster(bytes: ByteArray) {
        try {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = obj.getJSONArray("participants")
            val list = ArrayList<CallParticipant>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list += CallParticipant(
                    pid = p.optString("pid"),
                    name = p.optString("name"),
                    connected = p.optBoolean("connected"),
                    muted = p.optBoolean("muted"),
                    sharing = p.optBoolean("sharing")
                )
            }
            _state.value = _state.value.copy(participants = list)
            val seen = list.map { it.pid }.toSet()
            remoteFrames.value = remoteFrames.value.filterKeys { it in seen }
        } catch (e: Exception) { }
    }

    /**
     * Without MODE_IN_COMMUNICATION the voice APIs keep the device in normal media mode and
     * VOICE_CALL playback goes to the earpiece, which users report as "the call has no sound".
     */
    private fun openCallAudioRoute() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            savedAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        } catch (e: Exception) { }
    }

    private fun restoreCallAudioRoute() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = savedAudioMode
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speakerphoneBefore
        } catch (e: Exception) { }
    }

    private fun startReader() {
        readerThread = Thread {
            try {
                val input = DataInputStream(java.io.BufferedInputStream(socket?.getInputStream()))
                while (running) {
                    val type = input.read()
                    if (type == -1) break
                    val len = input.readInt()
                    if (len <= 0 || len > 4 * 1024 * 1024) break
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    // confHostPid is the peer for a 1:1 call and the bridge for a group call.
                    handleFrame(confHostPid, type, payload)
                }
            } catch (e: Exception) {
                // socket closed
            }
        }.apply { isDaemon = true; start() }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        // Opening is asynchronous, so without this a double tap starts two HandlerThreads and
        // a second Camera.open() that fails because the sensor is already held.
        if (camera != null || cameraOpening) return
        cameraOpening = true
        // The legacy Camera API builds a Handler internally, so it must be opened on a
        // thread that has a Looper; preview callbacks are dispatched there too.
        val thread = HandlerThread("PulseDropCamera").apply { start() }
        cameraThread = thread
        val generation = ++cameraGeneration
        Handler(thread.looper).post {
            var opened: Camera? = null
            try {
                val camId = findCameraId(requestedFront)
                var cam: Camera? = null
                // Flipping lenses releases the old sensor on its own thread; the new open
                // can land a beat early and hit "device busy", so give the release a moment.
                var tries = 0
                while (cam == null) {
                    try {
                        cam = Camera.open(camId)
                    } catch (e: Exception) {
                        if (++tries >= 3) throw e
                        Thread.sleep(220)
                    }
                }
                opened = cam
                if (generation != cameraGeneration) {
                    // Toggled off, or the call ended, while the sensor was opening.
                    try { cam.release() } catch (e: Exception) { }
                    opened = null
                    return@post
                }
                val info = Camera.CameraInfo()
                try { Camera.getCameraInfo(camId, info) } catch (e: Exception) { }
                val isFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                frameRotation = info.orientation
                val params = cam.parameters
                // Keep the feed light enough to ride the Wi-Fi at a steady frame rate,
                // but take the biggest size under that cap — phones offer far more than 480x360.
                val sizes = params.supportedPreviewSizes
                fun areaOf(s: Camera.Size) = s.width.toLong() * s.height
                val cap = 1280L * 720L
                val best = sizes.filter { areaOf(it) <= cap }.maxByOrNull { areaOf(it) }
                    ?: sizes.minByOrNull {
                        kotlin.math.abs(it.width - 640) + kotlin.math.abs(it.height - 480)
                    }
                val bw = best?.width ?: 640
                val bh = best?.height ?: 480
                params.setPreviewSize(bw, bh)
                cam.parameters = params
                cameraParams = params
                // On-screen preview must follow the sensor or the self-view shows up sideways.
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                    @Suppress("DEPRECATION")
                    val degrees = (wm?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0) * 90
                    val displayRotation = if (isFront) {
                        (360 - (info.orientation + degrees) % 360) % 360
                    } else {
                        (info.orientation - degrees + 360) % 360
                    }
                    cam.setDisplayOrientation(displayRotation)
                } catch (e: Exception) { }
                cam.addCallbackBuffer(ByteArray(bw * bh * 3 / 2))
                cam.setPreviewCallbackWithBuffer(object : Camera.PreviewCallback {
                    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
                        if (data == null || !running || !_state.value.cameraOn) return
                        val now = System.currentTimeMillis()
                        // Sensors fire at 15-30 fps and the peer cannot take all of it over the
                        // air, so frames are dropped rather than queued: a late frame of a face
                        // that has already moved is not worth holding the next one for.
                        if (now - lastCamFrameAt < cameraFrameMs) {
                            cam.addCallbackBuffer(data)
                            return
                        }
                        lastCamFrameAt = now
                        try {
                            val p = cameraParams
                            val w = p?.previewSize?.width ?: bw
                            val h = p?.previewSize?.height ?: bh
                            // Turn the bytes, then encode once. This path used to encode, decode
                            // that JPEG back into a Bitmap, rotate it and encode again: three JPEG
                            // passes per frame, on the one thread the sensor delivers to, which is
                            // what made the feed stutter while it looked sharp on a still.
                            val turn = frameRotation
                            var fw = w
                            var fh = h
                            var src: ByteArray = data
                            if (turn % 90 == 0 && turn != 0) {
                                val need = w * h * 3 / 2
                                if (rotBuf.size < need) rotBuf = ByteArray(need)
                                Nv21.rotate(data, w, h, turn, rotBuf)?.let { f ->
                                    src = f.data; fw = f.width; fh = f.height
                                }
                            }
                            val yuv = YuvImage(src, ImageFormat.NV21, fw, fh, null)
                            val bos = ByteArrayOutputStream()
                            yuv.compressToJpeg(Rect(0, 0, fw, fh), 70, bos)
                            writeFrame(TYPE_CAMERA, bos.toByteArray())
                        } catch (e: Exception) { }
                        cam.addCallbackBuffer(data)
                    }
                })
                // A SurfaceView is the only sink that shows the sensor without a GL context;
                // fall back to a detached texture when the call overlay has no preview attached.
                val holder = previewHolder
                if (holder == null || !trySetPreview(cam, holder)) {
                    cam.setPreviewTexture(SurfaceTexture(0))
                }
                cam.startPreview()
                camera = cam
                _state.value = _state.value.copy(cameraOn = true, video = true, cameraFront = isFront)
                if (flipPending && isFront != requestedFront) {
                    // The other lens is on this phone's list but not free, so the flip looked like a
                    // no-op. Say so rather than let the button sit there having done nothing.
                    toast.value = "The other camera is busy or not available"
                }
                flipPending = false
            } catch (e: Exception) {
                try { opened?.release() } catch (ignored: Exception) { }
                camera = null
                flipPending = false
                _state.value = _state.value.copy(cameraOn = false)
                toast.value = "Camera unavailable on this device"
            } finally {
                cameraOpening = false
                if (camera == null) {
                    cameraThread = null
                    thread.quitSafely()
                }
            }
        }
    }

    /** Nearest requested lens, falling back to whatever the device actually has. */
    private fun findCameraId(front: Boolean): Int {
        val want = if (front) {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        } else {
            Camera.CameraInfo.CAMERA_FACING_BACK
        }
        var fallback = -1
        try {
            for (i in 0 until Camera.getNumberOfCameras()) {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(i, info)
                if (info.facing == want) return i
                if (fallback < 0) fallback = i
            }
        } catch (e: Exception) { }
        return if (fallback >= 0) fallback else 0
    }

    @Suppress("DEPRECATION")
    private fun stopCamera(andThen: (() -> Unit)? = null) {
        cameraGeneration++
        cameraOpening = false
        val thread = cameraThread
        val cam = camera
        camera = null
        cameraThread = null
        if (thread == null || thread === Thread.currentThread()) {
            try { cam?.stopPreview() } catch (e: Exception) { }
            try { cam?.release() } catch (e: Exception) { }
            thread?.quitSafely()
            andThen?.invoke()
            return
        }
        Handler(thread.looper).post {
            try { cam?.stopPreview() } catch (e: Exception) { }
            try { cam?.release() } catch (e: Exception) { }
            thread.quitSafely()
            // Runs on the thread that just let go of the sensor, so the next open is a follower
            // and not a competitor.
            andThen?.invoke()
        }
    }

    /** Screen share frames are pushed by ProjectionCaptureService. */
    fun pushScreenFrame(jpeg: ByteArray) {
        if (running && _state.value.sharing) writeFrame(TYPE_SCREEN, jpeg)
    }

    /**
     * Playback audio from the share, as 48 kHz stereo bytes. The peer hears the game or the video,
     * not just the voice over it - which is the whole point of sharing a screen with sound on.
     */
    fun pushShareAudio(bytes: ByteArray, len: Int) {
        if (!running || !_state.value.sharing) return
        val n = downmixToCallRate(bytes, len, shareScratch)
        if (n > 0) shareRing.write(shareScratch, n)
    }

    /** The frame the peer gets: voice, plus whatever the share played during the same window. */
    private fun withShareAudio(voice: ByteArray, len: Int): ByteArray {
        if (!_state.value.sharing) {
            // Stale samples from a share that just ended would arrive as a burst of old sound.
            shareRing.clear()
            return voice
        }
        val samples = len / 2
        if (samples == 0) return voice
        shareRing.read(shareFrame, samples)
        return mixDown(voice, len, shareFrame)
    }

    /**
     * Take the foreground-service slot for the capture before the system picker runs, so the
     * share still works when PulseDrop is backgrounded by the app it ends up capturing.
     */
    fun armScreenShare() {
        if (!_state.value.connected) return
        ProjectionCaptureService.arm(context)
    }

    /** Release a share slot that was armed but never granted. */
    fun abandonScreenShare() {
        ProjectionCaptureService.stop(context)
    }

    fun startScreenShare(resultCode: Int, data: Intent) {
        if (!_state.value.connected) { toast.value = "Connect the call first"; return }
        ProjectionCaptureService.start(context, resultCode, data)
        _state.value = _state.value.copy(sharing = true)
        notifyMediaState()
    }

    fun stopScreenShare() {
        if (!_state.value.sharing) return
        ProjectionCaptureService.stop(context)
        _state.value = _state.value.copy(sharing = false)
        notifyMediaState()
    }

    /** The system revoked or stopped the capture; drop the "sharing" flag so the UI tells the truth. */
    fun sharingStopped() {
        if (_state.value.sharing) {
            _state.value = _state.value.copy(sharing = false)
            notifyMediaState()
            toast.value = "Screen sharing stopped"
        }
    }

    @Suppress("DEPRECATION")
    private fun trySetPreview(cam: Camera, holder: android.view.SurfaceHolder): Boolean = try {
        cam.setPreviewDisplay(holder)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Attach (or detach with null) the call overlay's SurfaceView. Legacy Camera wants the
     * display set between stopPreview and startPreview, and only on its own thread.
     */
    @Suppress("DEPRECATION")
    fun attachPreview(holder: android.view.SurfaceHolder?) {
        previewHolder = holder
        val cam = camera ?: return
        val handler = cameraThread?.let { android.os.Handler(it.looper) }
        val work = Runnable {
            if (camera !== cam) return@Runnable
            try {
                cam.stopPreview()
                if (holder != null) trySetPreview(cam, holder)
                cam.startPreview()
            } catch (e: Exception) { }
        }
        if (handler != null) handler.post(work) else work.run()
    }

    private fun notifyMediaState() {
        writeFrame(
            TYPE_SCREEN_STATE,
            JSONObject()
                .put("sharing", _state.value.sharing)
                .put("muted", _state.value.muted)
                .toString().toByteArray(Charsets.UTF_8)
        )
    }

    // ---- controls ---------------------------------------------------------

    fun toggleMute() {
        _state.value = _state.value.copy(muted = !_state.value.muted)
        if (_state.value.conference) broadcastConfState()
        notifyMediaState()
    }

    fun toggleCamera() {
        if (_state.value.cameraOn) {
            _state.value = _state.value.copy(cameraOn = false)
            // Release the hardware, otherwise the sensor and its indicator stay live
            // while the user believes the camera is off.
            stopCamera()
        } else {
            // cameraOn is set once the preview actually starts.
            startCamera()
        }
    }

    /** Swap between the front and back lens; re-opens the sensor on the next id. */
    fun switchCamera() {
        requestedFront = !requestedFront
        _state.value = _state.value.copy(cameraFront = requestedFront)
        if (!_state.value.cameraOn) return
        flipPending = true
        // The new open has to be asked for after the old release has actually run. Toggling on two
        // threads at once is what made the flip look dead: the second Camera.open() hit "device
        // busy", the retry gave up, and the feed ended up off with a toast about the camera.
        stopCamera { if (running && _state.value.cameraOn) startCamera() }
    }

    private fun reset(reason: String) {
        running = false
        mediaStarted = false
        webOnly = false
        webAttached = false
        webTabName = ""
        webMuted = false
        webSharing = false
        webMixThread = null
        webAudioLatest.clear()
        webOut.clear()
        synchronized(pendingIn) { pendingIn.reset() }
        ringExpiryJob?.cancel()
        ringExpiryJob = null
        legs.values.forEach { leg ->
            try { leg.socket.close() } catch (e: Exception) { }
        }
        legs.clear()
        confInvitees.clear()
        confId = ""
        confHostPid = ""
        synchronized(confTracks) {
            confTracks.values.forEach { t ->
                try { t.stop() } catch (e: Exception) { }
                try { t.release() } catch (e: Exception) { }
            }
            confTracks.clear()
        }
        try { socket?.close() } catch (e: Exception) { }
        try { serverSocket?.close() } catch (e: Exception) { }
        socket = null; out = null; serverSocket = null
        flipPending = false
        cid = ""
        mediaHost = ""
        mediaPort = 0
        stopCamera()
        ProjectionCaptureService.stop(context)
        try { track?.stop() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        track = null
        restoreCallAudioRoute()
        // The capture thread releases its own AudioRecord, but the reference must go:
        // holding it pins the mic open across the gap between calls.
        record = null
        audioThread = null
        readerThread = null
        synchronized(responses) { responses.clear() }
        val wasActive = _state.value.active
        val was = _state.value
        val seconds = if (was.connectedAt > 0) (System.currentTimeMillis() - was.connectedAt) / 1000 else 0L
        if (seconds > 0) {
            _lastCall.value = CallRecord(
                peerName = was.peerName.ifBlank { if (was.conference) "Group call" else "Peer" },
                seconds = seconds,
                video = was.video,
                conference = was.conference,
                reason = reason
            )
        }
        _state.value = CallState(status = if (wasActive) reason else "")
        remoteFrame.value = null
        remoteFrames.value = emptyMap()
        if (reason.isNotBlank() && wasActive) {
            toast.value = if (seconds > 0) "$reason · ${formatCallLength(seconds)}" else reason
        }
    }

    /** Consume the finished-call card once the UI has shown it. */
    fun acknowledgeLastCall() { _lastCall.value = null }

    fun consumeToast() { toast.value = null }
    fun consumeIncoming() { incomingCall.value = null }

    private object BufferedOutputStreamCompat {
        fun wrap(s: java.io.OutputStream) = java.io.BufferedOutputStream(s, 64 * 1024)
    }
}
