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
import android.util.Log
import com.pulsedrop.app.discovery.PeerCapabilityRegistry
import com.pulsedrop.app.graph.AppGraph
import com.pulsedrop.app.media.h264.H264Decoder
import com.pulsedrop.app.media.h264.H264Encoder
import com.pulsedrop.app.media.h264.H264SurfaceEncoder
import com.pulsedrop.app.media.h264.HardwareCodec
import com.pulsedrop.app.media.h264.Nv21Pool
import com.pulsedrop.app.media.h264.ScreenLane
import com.pulsedrop.app.media.ladder.QualityLadder
import com.pulsedrop.app.media.ladder.QualityTier
import com.pulsedrop.app.media.protocol.FrameCodec
import com.pulsedrop.app.media.protocol.TYPE_AUDIO
import com.pulsedrop.app.media.protocol.TYPE_CAMERA
import com.pulsedrop.app.media.protocol.TYPE_CONF_AUDIO
import com.pulsedrop.app.media.protocol.TYPE_CONF_H264
import com.pulsedrop.app.media.protocol.TYPE_CONF_STATE
import com.pulsedrop.app.media.protocol.TYPE_CONF_VIDEO
import com.pulsedrop.app.media.protocol.TYPE_CONTROL
import com.pulsedrop.app.media.protocol.TYPE_H264
import com.pulsedrop.app.media.protocol.TYPE_SCREEN
import com.pulsedrop.app.media.protocol.TYPE_SCREEN_H264
import com.pulsedrop.app.media.protocol.TYPE_SCREEN_STATE
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.security.PairManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    val participants: List<CallParticipant> = emptyList(),
    /**
     * Which of [participants] is this device.
     *
     * The grid needs it to put the local camera in the right seat: a phone never receives its own
     * frames back, so without this its own tile is a letter while everyone else has a picture.
     * The bridge names itself `host`; a member is listed under the pair id it shares with the
     * bridge, which both ends of that pair compute identically.
     */
    val selfPid: String = ""
)

/**
 * What the media lane is really doing, counted off the wire rather than read off the encoder's
 * settings. Every field is measured over the last stats window, so a call that negotiated 1080p30 and
 * is delivering 12 fps says 12.
 */
data class CallStats(
    val fpsOut: Int = 0,
    val kbpsOut: Int = 0,
    val fpsIn: Int = 0,
    val tier: String = "",
    val lane: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** Access units thrown away because the peer or this device could not keep up. */
    val dropped: Int = 0,
    /**
     * Whether the peer's camera is arriving encoded. The call view renders off this alone: it is a
     * different question from our own lane, which the two ends negotiate separately.
     */
    val remoteH264: Boolean = false,
    /**
     * Whether the peer's *shared screen* is arriving encoded. A third question, answered separately
     * again: the peer's camera lane, the peer's share and this device's own share each negotiate their
     * own codec, and the call view cannot tell which tile to draw from any of the other two.
     */
    val remoteScreenH264: Boolean = false,
    /**
     * The share's own uplink, named apart because it is a different stream with a different tier and a
     * different frame pacer. Empty until something is actually being shared, so a call that never
     * touched the share button does not read as a share running at zero.
     */
    val screenLane: String = "",
    val screenTier: String = "",
    val screenFpsOut: Int = 0,
    val screenKbpsOut: Int = 0,
    val available: Boolean = false
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
    /**
     * The JPEG lane holds frames back ten to the second, as it always has: the CPU encode is the
     * expensive half of that path and asking for more only makes each one later. With the H.264 lane up
     * the throttle is the tier's own frame interval, because the encoder — not the air — decides what
     * it can afford, and it drops rather than queues.
     */
    private val cameraFrameMs: Long get() = if (h264Out != null) tier.frameMs.toLong() else 100L

    // ---- H.264 lane ---------------------------------------------------------

    /** The rung this call is currently transmitting at, and the one the stats overlay names. */
    @Volatile private var tier: QualityTier = QualityLadder.BEST

    /** Non-null only while both ends advertised `h264` and this device has a hardware encoder. */
    @Volatile private var h264Out: H264Encoder? = null

    /** One decoder per remote source: a shared one would tangle two reference chains into noise. */
    private val h264In = java.util.concurrent.ConcurrentHashMap<String, H264Decoder>()

    /**
     * Surface the peer's video is decoded onto.
     *
     * A hardware decoder writes straight to a surface and never yields a Bitmap, so the H.264 lane
     * cannot travel through `remoteFrame` the way a JPEG frame does. Without a surface attached the
     * decoder is created but has nowhere to present, which is why the call overlay must hand one over
     * — a black tile with a live bitrate on it is the symptom of forgetting this.
     */
    @Volatile private var remoteSurface: android.view.Surface? = null
    @Volatile private var remoteSurfaceWidth = 0
    @Volatile private var remoteSurfaceHeight = 0

    /**
     * Geometry actually going into the encoder: the sensor's preview size as the phone is held, after
     * [Nv21]'s turn. Held separately from [tier] because stepping the ladder re-casts the rung onto
     * whatever the sensor is already delivering, and re-opening the lens mid-call to chase a rung's
     * nominal pixels would flash the picture for no gain — the load a congested link needs shedded is
     * bitrate and frame rate, both of which [QualityLadder.fitTo] carries over untouched.
     */
    @Volatile private var encodeWidth = 0
    @Volatile private var encodeHeight = 0

    /** The camera's NV21 buffers, owned by whoever holds the frame. See [Nv21Pool]. */
    private val framePool = Nv21Pool()

    /**
     * Whether the video *arriving* on this call is encoded rather than JPEG.
     *
     * This, and not our own encoder's state, is what decides how the call view draws: the two lanes are
     * negotiated independently, so a peer with no hardware encoder leaves us sending H.264 at the same
     * time as it sends us JPEG. Rendering off the wrong one of these two flags is a black tile with a
     * live bitrate underneath it.
     */
    @Volatile private var remoteH264 = false

    // ---- screen-share lane ---------------------------------------------------

    /**
     * The share's own rung, kept apart from the camera's [tier].
     *
     * Two streams on one socket are two streams, and a ladder that both of them stepped would have the
     * peer's desktop dragging the local camera's resolution down with it — the camera sheds load on its
     * own measured frame rate, which a screen never has, since a still desktop legitimately produces one
     * frame a second.
     */
    @Volatile private var screenTier: QualityTier = QualityLadder.BEST

    /** Non-null only while the share is encoded: see [ScreenLane] for everything that has to agree. */
    @Volatile private var screenOut: H264SurfaceEncoder? = null
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0

    /**
     * Latched by an encoder that died, and cleared when the share is next started.
     *
     * Without it the capture service's tick would ask for a surface eight times a second and rebuild a
     * hardware block that has already refused once, on the same socket the call is trying to stay up on.
     */
    @Volatile private var screenLaneFaulted = false

    /** One decoder per remote source, as the camera lane's are — reference chains do not cross. */
    private val screenIn = java.util.concurrent.ConcurrentHashMap<String, H264Decoder>()

    /**
     * The tile the peer's shared screen is drawn into, held apart from [remoteSurface] because a peer can
     * be sharing its desktop and pointing its camera at the same time, and those are two different
     * pictures that cannot be presented onto one surface.
     */
    @Volatile private var peerScreenSurface: android.view.Surface? = null
    @Volatile private var peerScreenWidth = 0
    @Volatile private var peerScreenHeight = 0

    @Volatile private var remoteScreenH264 = false
    @Volatile private var screenSentUnits = 0
    @Volatile private var screenSentBytes = 0L

    @Volatile private var sentUnits = 0
    @Volatile private var sentBytes = 0L
    @Volatile private var sentWindowStartedAt = 0L
    @Volatile private var receivedUnits = 0
    @Volatile private var receivedWindowStartedAt = 0L
    private var statsJob: kotlinx.coroutines.Job? = null

    private val _stats = MutableStateFlow(CallStats())

    /**
     * Measured, not configured: what this call is actually putting on the wire and taking off it.
     * A tier name with no number behind it would let the UI claim 1080p30 on a link delivering ten.
     */
    val stats: StateFlow<CallStats> = _stats.asStateFlow()
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
    private val webIn = FrameCodec.BatchParser()

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
        /** Conference roster id for the bridge itself: pairwise ids are per-member, so they
         *  cannot name the host to everybody at once. */
        const val HOST_SRC = "host"
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
        // An outgoing video call opens on your own picture, not on a black screen:
        // the lens is live before the other phone picks up.
        if (video) startCamera()

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
        webIn.reset()
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
        webIn.reset()
        _state.value = CallState(
            active = true, video = video,
            peerName = webTabName, peerPid = WEB_PID,
            conference = true, isHost = true, confId = newCid,
            status = "Group call from $webTabName…",
            participants = members.map { CallParticipant(it.pid, it.name, false) } +
                CallParticipant(HOST_SRC, nameProvider(), true) +
                CallParticipant(WEB_PID, webTabName, true),
            selfPid = HOST_SRC
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
        for (f in webIn.feed(bytes)) {
            val type = f.type
            val payload = f.payload
            handleFrame(WEB_PID, type, payload)
            // In a hosted conference the tab is a seat like any other member.
            if (webAttached && !webOnly) {
                when (type) {
                    TYPE_AUDIO, TYPE_CAMERA, TYPE_SCREEN, TYPE_H264 ->
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
            // A member's seat in the roster is the pair id it shares with the bridge, which is the
            // same string the bridge keys its leg under: `pairId` is symmetric by construction.
            selfPid = if (_state.value.conference) peer.pid else _state.value.selfPid,
            status = if (_state.value.conference) "Group call · ${peer.name}’s call"
            else "In call with ${peer.name}"
        )
        confHostPid = if (_state.value.conference) HOST_SRC else peer.pid
        openCallAudioRoute()
        startAudioInOut()
        startReader()
        // Say what this device can do, over the socket both ends already hold. The scanner records a
        // peer's caps by address and a pairing is cached by id, and neither probe is guaranteed to have
        // run by the time the first frame is due — an announcement that can be missed is a lane that
        // silently never comes up, and a share that stays JPEG for a whole call nobody explains.
        writeControl(
            JSONObject()
                .put("t", "caps")
                .put("caps", JSONArray(PeerCapabilityRegistry.localCaps()))
        )
        if (video) {
            // The tier is picked before the sensor opens so the ladder's numbers are on the stats line
            // from the first second; the encoder itself waits for [startCamera] to learn what the
            // sensor can actually deliver, and is configured to that rather than to the tier's
            // nominal pixels.
            tier = QualityLadder.startFor(_state.value.conference)
        }
        startStatsTicker()
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
        while (!webOut.offer(FrameCodec.encode(type, payload))) webOut.poll()
    }

    /** Returns false when the write failed, so the caller can drop just that connection. */
    private fun writeTo(o: DataOutputStream, type: Int, payload: ByteArray): Boolean =
        FrameCodec.writeTo(o, type, payload)

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
            TYPE_H264 -> {
                relayType = TYPE_CONF_H264
                body = ConfFrame.h264(fromPid, payload)
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
                // Only a camera frame says anything about the camera lane; a shared screen is JPEG
                // whatever the camera is doing, and treating it as a lane change would blank the peer's
                // video. Each of the two answers about its own stream, and only its own.
                if (type == TYPE_CAMERA) noteRemoteLane(encoded = false)
                else noteRemoteScreenLane(encoded = false)
                val bmp = decodeJpeg(payload) ?: return
                showFrame(mySrc, bmp)
            }
            TYPE_H264 -> {
                noteRemoteLane(encoded = true)
                receivedUnits++
                decoderFor(mySrc).submit(payload)
            }
            TYPE_CONF_H264 -> {
                // Relayed by the bridge, which never transcodes: the tag says who a unit came from,
                // and each source needs its own decoder because reference frames do not cross chains.
                val unit = ConfFrame.readH264(payload) ?: return
                noteRemoteLane(encoded = true)
                receivedUnits++
                decoderFor(unit.first).submit(unit.second)
            }
            TYPE_SCREEN_H264 -> {
                // Its own decoder per source, and its own tile: a peer's desktop and a peer's face are
                // two chains, and feeding both to one decoder paints a spreadsheet out of a chin.
                noteRemoteScreenLane(encoded = true)
                screenDecoderFor(mySrc).submit(payload)
            }
            TYPE_CONTROL -> handleControl(payload)
            // Unknown types are skipped, never fatal: a newer peer's media must not end this call.
        }
    }

    /**
     * Note which encoding the peer's camera is using, and tell the view as soon as it changes.
     *
     * A lane switch mid-call is the fallback the spec asks for — an encoder dying on either side drops
     * to JPEG without a reconnect — and the call view can only follow it if it hears about it at the
     * moment the first frame of the new kind lands.
     */
    private fun noteRemoteLane(encoded: Boolean) {
        if (remoteH264 == encoded) return
        remoteH264 = encoded
        if (!encoded) {
            // JPEG came back, so nothing more will be fed to these. A hardware decoder is a limited
            // system resource, and holding one idle for the rest of the call is how the next one fails
            // to be created.
            h264In.values.forEach { it.stop() }
            h264In.clear()
        }
        publishStats()
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
            participants = members.map { CallParticipant(it.pid, it.name, false) },
            selfPid = HOST_SRC
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
                    val frame = FrameCodec.readFrame(input) ?: break
                    val type = frame.type
                    val payload = frame.payload
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
                    val frame = FrameCodec.readFrame(input) ?: break
                    // confHostPid is the peer for a 1:1 call and the bridge for a group call.
                    handleFrame(confHostPid, frame.type, frame.payload)
                }
            } catch (e: Exception) {
                // socket closed
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * Whether the H.264 lane may carry this call. Three halves are required: the peer having to accept
     * type 9, this device having a hardware encoder able to fill it at the tier's frame rate, and the
     * call being one-to-one.
     *
     * Absence of the first two is normal and silent — a v1.7 phone, or a phone whose only AVC encoder
     * is the software one, keeps the JPEG lane. Advertising `h264` locally while encoding in software
     * would be worse than not advertising it at all, because the peer would drop its own encoder and
     * both ends would end up behind.
     *
     * A conference is excluded because fan-out is a different cap (`conf2`) and a different renderer:
     * the grid draws one Bitmap per tile, and a hardware decoder writes to a surface, not to a Bitmap.
     * Relaying type 10 is still implemented for the host's benefit, but this build neither asks for it
     * nor claims it, so a group call stays on the JPEG 720p lane the spec prescribes for a mixed one.
     *
     * The remote capability is checked under both keys the registry knows: the scanner records what it
     * probed by address, and a pairing could be cached by id, so either route is enough to earn the lane.
     */
    private fun h264LaneAgreed(s: Socket, peer: PairedDevice): Boolean {
        if (_state.value.conference) return false
        val caps = AppGraph.peerCaps
        val peerIp = try { s.inetAddress.hostAddress.orEmpty() } catch (e: Exception) { "" }
        if (peerIp.isBlank()) return false
        val remote = caps.has(caps.keyForIp(peerIp), PeerCapabilityRegistry.CAP_H264) ||
            caps.has(caps.keyForPid(peer.pid), PeerCapabilityRegistry.CAP_H264)
        return remote && HardwareCodec.hasHardwareAvcEncoder()
    }

    /**
     * Bring the H.264 lane up at the geometry the sensor is delivering, if the peer asked for it.
     *
     * Called from the camera thread once a preview size exists, and again from a peer's `caps` control
     * frame for a peer that only declared the capability mid-call. Both need the sensor's real numbers:
     * an encoder configured for 1920x1080 and fed 1280x720 does not fail, it stretches.
     */
    private fun startH264Lane() {
        if (h264Out != null) return
        val s = socket ?: return
        val peer = pairManager()?.findPairing(_state.value.peerPid) ?: return
        if (!h264LaneAgreed(s, peer)) {
            publishStats()
            return
        }
        val w = encodeWidth
        val h = encodeHeight
        // Both planes of NV21 are half-size, so a luma dimension of one odd pixel has nowhere to go:
        // the rotator refuses the frame and the encoder would refuse the format. JPEG takes odd sizes
        // happily, so an odd preview keeps the old lane rather than a black new one.
        if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0) return
        val encTier = QualityLadder.fitTo(tier, w, h)
        val started = H264Encoder(
            tier = encTier,
            onAccessUnit = { au, _ -> transmitH264(au) },
            onFault = { reason -> onEncoderFault(reason) },
            onFrameDone = { buf -> framePool.release(buf) }
        )
        tier = encTier
        if (started.start()) {
            h264Out = started
            // The full list, not just `h264`: two caps frames on one socket that claim different sets of
            // things read as two different promises to whoever merges them.
            writeControl(JSONObject().put("t", "caps").put("caps", JSONArray(PeerCapabilityRegistry.localCaps())))
            Log.i("PulseDropCall", "H.264 lane up with ${peer.name} at ${encTier.describe()}")
        } else {
            h264Out = null
        }
        publishStats()
    }

    /**
     * An encoder that dies mid-call takes the lane down, not the call. The JPEG path is still warm,
     * the peer never negotiated a reconnect, and a request for a keyframe is what lets the peer's
     * decoder come back clean for the next attempt rather than sitting on a half-decoded frame.
     */
    private fun onEncoderFault(reason: String) {
        Log.w("PulseDropCall", "H.264 encoder fault, falling back to JPEG: $reason")
        h264Out = null
        // Whatever the dying lane still held is never encoded, and its buffer will not come back on
        // its own. Forget the slots rather than strand four of them for the rest of the call.
        framePool.clear()
        writeControl(JSONObject().put("t", "keyframe-request"))
        publishStats()
    }

    private fun transmitH264(au: ByteArray) {
        if (!running) return
        sentUnits++
        sentBytes += au.size
        writeFrame(TYPE_H264, au)
    }

    private fun decoderFor(src: String): H264Decoder = h264In.getOrPut(src) {
        H264Decoder { reason ->
            Log.w("PulseDropCall", "H.264 decoder for $src stopped: $reason")
            h264In.remove(src)
        }.also { it.start(remoteSurface, remoteSurfaceWidth, remoteSurfaceHeight) }
    }

    /**
     * Hand the call overlay's tile over as the decode target.
     *
     * The overlay is composed after the media socket is already reading, so a decoder created before
     * this arrives has no surface. Every live decoder re-attaches rather than waiting to be recreated:
     * a decoder that never got a surface at all renders nothing, and the peer's video stays black for
     * the whole call with a healthy bitrate on the stats line to contradict it.
     */
    fun attachRemoteSurface(surface: android.view.Surface?, width: Int, height: Int) {
        remoteSurface = surface
        remoteSurfaceWidth = width
        remoteSurfaceHeight = height
        if (surface != null) {
            h264In.values.forEach { it.setSurface(surface, width, height) }
        }
    }

    /** One JSON control frame on type 8. Never carries media, and peers ignore what they don't know. */
    private fun writeControl(body: JSONObject) {
        writeFrame(TYPE_CONTROL, body.toString().toByteArray(Charsets.UTF_8))
    }

    // ---- the shared screen's own lane ---------------------------------------

    /**
     * The surface a shared screen should be rendered into, or null to keep sending JPEGs.
     *
     * Pulled by [ProjectionCaptureService] once a tick rather than pushed at it, because the answer
     * changes underneath the capture on its own: a peer that only declares the capability mid-share turns
     * the lane on, an encoder that dies turns it back off, and a rotation changes the geometry the encoder
     * is built for. Polling means the service converges on whichever answer is current without either
     * half owning the other's lifecycle, and without a callback that outlives the call it was set up for.
     *
     * Safe to call on any thread and as often as the caller likes: a lane already running at this
     * geometry is handed back untouched.
     */
    @Synchronized
    fun screenCaptureSurface(width: Int, height: Int): android.view.Surface? {
        if (!running || !mediaStarted) return null
        val live = screenOut
        if (live != null && live.matches(width, height)) return live.inputSurface
        // Wrong geometry — a rotation, or a block that died since the last ask. Either way it will never
        // render into this surface again, so it goes before anything else is decided.
        if (live != null) stopScreenLane()
        if (screenLaneFaulted) return null
        if (ScreenLane.choose(
                peerPromisesDecode = peerPromisesScreenDecode(),
                hardwareEncoder = HardwareCodec.hasHardwareAvcEncoder(),
                conference = _state.value.conference,
                width = width,
                height = height
            ) != ScreenLane.Lane.ENCODED
        ) return null
        // Re-cast onto the display's true geometry, so the name the stats overlay prints is the picture
        // the peer is getting. A 1080x2400 panel scaled to 864x1920 is labeled 864x1920, never 1080p.
        val encTier = QualityLadder.fitTo(QualityLadder.BEST, width, height)
        val started = H264SurfaceEncoder(
            tier = encTier,
            onAccessUnit = { au, _ -> transmitScreenH264(au) },
            onFault = { reason -> onScreenEncoderFault(reason) }
        )
        if (!started.start()) return null
        screenOut = started
        screenTier = encTier
        screenWidth = width
        screenHeight = height
        Log.i("PulseDropCall", "screen share lane up at ${encTier.describe()}")
        publishStats()
        return started.inputSurface
    }

    /**
     * What the peer promised about watching an encoded share.
     *
     * Checked under both keys the registry knows, exactly as the camera lane checks them: the scanner
     * records what it probed by address and a pairing is cached by id, and only one of the two is
     * guaranteed to have run before the first frame is due.
     */
    private fun peerPromisesScreenDecode(): Boolean {
        val ip = try { socket?.inetAddress?.hostAddress.orEmpty() } catch (e: Exception) { "" }
        if (ip.isBlank()) return false
        val caps = AppGraph.peerCaps
        return caps.has(caps.keyForIp(ip), PeerCapabilityRegistry.CAP_SCREEN_H264) ||
            caps.has(caps.keyForPid(_state.value.peerPid), PeerCapabilityRegistry.CAP_SCREEN_H264)
    }

    private fun transmitScreenH264(au: ByteArray) {
        if (!running) return
        screenSentUnits++
        screenSentBytes += au.size
        writeFrame(TYPE_SCREEN_H264, au)
    }

    /**
     * An encoder that dies mid-share takes the lane down, not the call and not the share.
     *
     * The JPEG pump is still warm and its `ImageReader` was never closed, so the capture service's next
     * tick finds no surface here and points the display straight back at the reader. A share that falls
     * back is a share that got less smooth, which is the same deal the camera lane makes with its own
     * encoder; it is not a share that stopped.
     */
    private fun onScreenEncoderFault(reason: String) {
        Log.w("PulseDropCall", "screen encoder fault, falling back to JPEG: $reason")
        screenLaneFaulted = true
        stopScreenLane()
        publishStats()
    }

    /** Take the share's encoder down. The peer's decoders are left alone: they answer to the wire. */
    private fun stopScreenLane() {
        screenOut?.stop()
        screenOut = null
        screenWidth = 0
        screenHeight = 0
    }

    private fun screenDecoderFor(src: String): H264Decoder = screenIn.getOrPut(src) {
        H264Decoder { reason ->
            Log.w("PulseDropCall", "screen decoder for $src stopped: $reason")
            screenIn.remove(src)
        }.also { it.start(peerScreenSurface, peerScreenWidth, peerScreenHeight) }
    }

    /**
     * Hand the call overlay's screen tile over as the decode target.
     *
     * A separate method from [attachRemoteSurface], and a separate surface, because the peer's camera and
     * the peer's desktop are two reference chains that a hardware decoder presents onto whatever it was
     * configured with: one tile shared between them shows a face composited into a spreadsheet, which
     * looks like a codec bug and is only a surface-count bug.
     */
    fun attachPeerScreenSurface(surface: android.view.Surface?, width: Int, height: Int) {
        peerScreenSurface = surface
        peerScreenWidth = width
        peerScreenHeight = height
        if (surface == null) return
        screenIn.values.forEach { it.setSurface(surface, width, height) }
        // A tile that has just appeared is empty, and the chain already running behind it has no reason
        // to make a keyframe for another two seconds. Asking is what makes the share show up on the tap
        // rather than on the timer.
        writeControl(JSONObject().put("t", "keyframe-request").put("lane", "screen"))
    }

    /**
     * Note whether the peer's share is arriving encoded, and tell the view as soon as it changes.
     *
     * The same argument as the camera's lane, and a genuinely different answer: a peer whose screen
     * encoder dies mid-share starts sending JPEG on type 3 while its camera keeps sending H.264 on type
     * 9, so neither of those two flags can be used to decide which feed the stage should be showing.
     */
    private fun noteRemoteScreenLane(encoded: Boolean) {
        if (remoteScreenH264 == encoded) return
        remoteScreenH264 = encoded
        if (!encoded) {
            screenIn.values.forEach { it.stop() }
            screenIn.clear()
        }
        publishStats()
    }

    private fun handleControl(payload: ByteArray) {
        val obj = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (e: Exception) { return }
        when (obj.optString("t")) {
            "keyframe-request" -> when (obj.optString("lane")) {
                // A lane is named, not assumed. The field postdates the build that first asked, so an
                // old peer sends no lane at all — and re-keying both streams costs two frames of bitrate
                // where guessing the wrong one leaves a tile frozen for two seconds.
                "screen" -> screenOut?.requestKeyframe()
                "camera" -> h264Out?.requestKeyframe()
                else -> {
                    h264Out?.requestKeyframe()
                    screenOut?.requestKeyframe()
                }
            }
            "stats-ping" -> {
                // Answer with what was measured, not with what the tier was asked for: a peer's stats
                // view is only worth anything if both ends agree the numbers are observations.
                val now = publishStats()
                writeControl(
                    JSONObject()
                        .put("t", "stats")
                        .put("fps", now.fpsOut)
                        .put("bps", now.kbpsOut * 1000)
                        .put("tier", tier.label)
                )
            }
            "caps" -> {
                val announced = PeerCapabilityRegistry.capsFromJson(obj.optJSONArray("caps"))
                val peerIp = try { socket?.inetAddress?.hostAddress.orEmpty() } catch (e: Exception) { "" }
                if (peerIp.isNotBlank()) {
                    AppGraph.peerCaps.record(AppGraph.peerCaps.keyForIp(peerIp), null, announced)
                }
                // A peer that only just declared the cap can still be given the lane: this is the
                // post-hoc exchange the spec allows at media start, not a reconnect.
                if (h264Out == null && announced != null) startH264Lane()
                // The same grace for the share. `screenLaneFaulted` exists to stop a block that died
                // mid-stream from being rebuilt every tick; it must not also mean "this call is JPEG
                // because the caps frame arrived after the first share tick asked".
                if (announced?.contains(PeerCapabilityRegistry.CAP_SCREEN_H264) == true &&
                    screenOut == null && screenLaneFaulted
                ) {
                    screenLaneFaulted = false
                    Log.i("PulseDropCall", "peer now promises a screen decoder; the share lane will ask again")
                }
            }
            // Unknown control text is ignored, per the spec. Nothing here is fatal to a call.
        }
    }

    /** Take the sending lane down. The peer's decoders are left alone: they answer to the wire, not to our encoder. */
    private fun stopH264Out() {
        h264Out?.stop()
        h264Out = null
        framePool.clear()
    }

    /** Put the whole lane down for the end of a call: both directions, and the counters behind them. */
    private fun stopH264Lane() {
        stopH264Out()
        h264In.values.forEach { it.stop() }
        h264In.clear()
        // The next call opens a different lens at a different size, and a tier still holding this
        // call's geometry would build the next encoder for pixels no sensor is producing.
        encodeWidth = 0
        encodeHeight = 0
        remoteH264 = false
        sentUnits = 0
        sentBytes = 0
        sentWindowStartedAt = 0L
        receivedUnits = 0
        receivedWindowStartedAt = 0L
        _stats.value = CallStats()
    }

    /**
     * Once a second: republish the readout, and give the call a lower rung if the one it is on is not
     * being delivered.
     *
     * The signal is frames actually written out against the tier's own frame rate, which is a measure of
     * the whole path — a short link, a blocked socket write or an encoder behind on its input all look
     * the same from here, and all mean "this rung is too ambitious for right now". Three ticks in a row
     * rather than one, because the first second of a call is the encoder's own warm-up.
     */
    private fun startStatsTicker() {
        stopStatsTicker()
        val now = System.currentTimeMillis()
        sentWindowStartedAt = now
        receivedWindowStartedAt = now
        statsJob = scope.launch {
            var behind = 0
            while (isActive) {
                delay(1000)
                val fps = publishStats().fpsOut
                val budget = tier.fps
                if (h264Out != null && running && fps in 1 until (budget * 3 / 4)) {
                    if (++behind >= 3) {
                        behind = 0
                        stepLadderDown()
                    }
                } else {
                    behind = 0
                }
            }
        }
    }

    private fun stopStatsTicker() {
        statsJob?.cancel()
        statsJob = null
    }

    /**
     * Shed load by one rung without touching the call.
     *
     * The encoder is rebuilt at the new bitrate and frame rate on the geometry the sensor already
     * delivers — reopening the lens to chase a rung's nominal pixels would blank the picture for a
     * second to buy nothing, since bitrate is what a saturated hotspot is short of. Every rung's access
     * unit carries its own SPS/PPS, so the peer needs no notice: it decodes the next frame it sees.
     */
    private fun stepLadderDown() {
        val next = QualityLadder.stepDown(tier) ?: return
        val target = QualityLadder.fitTo(next, encodeWidth, encodeHeight)
        Log.i("PulseDropCall", "link behind at ${tier.describe()}, stepping to ${target.describe()}")
        stopH264Out()
        tier = target
        startH264Lane()
    }

    /**
     * Recomputes the readout from the counters accumulated since the last tick, then closes the
     * window. Driven once a second while a call runs and again at every lane change, so both
     * directions are always measured over the same length of time.
     */
    private fun publishStats(): CallStats {
        val now = System.currentTimeMillis()
        val encoder = h264Out
        val share = screenOut
        val next = CallStats(
            fpsOut = rate(sentUnits, sentWindowStartedAt, now),
            kbpsOut = kilobits(sentBytes, sentWindowStartedAt, now),
            fpsIn = rate(receivedUnits, receivedWindowStartedAt, now),
            tier = if (encoder != null) tier.label else "jpeg",
            lane = if (encoder != null) "h264" else "jpeg",
            width = tier.width,
            height = tier.height,
            dropped = h264In.values.sumOf { it.droppedUnits },
            remoteH264 = remoteH264,
            remoteScreenH264 = remoteScreenH264,
            // Only named once a share is actually running: "jpeg" from a call that never shared looks
            // like a lane that failed rather than a lane nobody asked for.
            screenLane = when {
                share != null -> "h264"
                _state.value.sharing -> "jpeg"
                else -> ""
            },
            screenTier = if (share != null) screenTier.label else "",
            screenFpsOut = rate(screenSentUnits, sentWindowStartedAt, now),
            screenKbpsOut = kilobits(screenSentBytes, sentWindowStartedAt, now),
            available = running
        )
        _stats.value = next
        sentUnits = 0
        sentBytes = 0L
        receivedUnits = 0
        screenSentUnits = 0
        screenSentBytes = 0L
        sentWindowStartedAt = now
        receivedWindowStartedAt = now
        return next
    }

    /**
     * Units over the window that just closed, per second. A window shorter than half a second reports
     * nothing rather than a number invented by dividing by a near-zero age: a first tick claiming
     * 3000 fps is how a stats overlay stops being believed.
     */
    private fun rate(units: Int, startedAt: Long, now: Long): Int {
        if (startedAt == 0L || units == 0) return 0
        val elapsed = now - startedAt
        if (elapsed < 500) return 0
        return (units * 1000L / elapsed).toInt()
    }

    private fun kilobits(bytes: Long, startedAt: Long, now: Long): Int {
        if (startedAt == 0L || bytes == 0L) return 0
        val elapsed = now - startedAt
        if (elapsed < 500) return 0
        return (bytes * 8L / elapsed).toInt()
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
                // The sensor is asked for the tier's pixel budget, and takes the largest preview it
                // offers under it. Phones list far more sizes than they can do at rate, and a 1080p
                // preview on a phone that manages 8 fps of it is a worse call than a 720p one at 30.
                val sizes = params.supportedPreviewSizes
                val candidates = sizes.map { it.width to it.height }
                val chosen = QualityLadder.bestPreviewUnder(tier, candidates)
                    ?: candidates.minByOrNull { kotlin.math.abs(it.first - 640) + kotlin.math.abs(it.second - 480) }
                val bw = chosen?.first ?: 640
                val bh = chosen?.second ?: 480
                params.setPreviewSize(bw, bh)
                cam.parameters = params
                cameraParams = params
                // The encoder sees the frame as the peer will: turned to how the phone is held. A
                // portrait call is 1080x1920, and configuring the encoder for the sensor's landscape
                // size instead writes the whole picture on its side.
                val turned = Nv21.rotatedSize(bw, bh, frameRotation)
                encodeWidth = turned.first
                encodeHeight = turned.second
                tier = QualityLadder.fitTo(tier, turned.first, turned.second)
                // Only now, with the geometry settled, is there anything for an encoder to be built for.
                startH264Lane()
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
                // Three buffers in the rotation, not one: with a single buffer the sensor cannot start
                // the next frame until this callback hands the last one back, so the feed would be
                // paced by the callback's own cost rather than by the tier.
                repeat(3) { cam.addCallbackBuffer(ByteArray(bw * bh * 3 / 2)) }
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
                            val encoder = h264Out
                            if (encoder != null) {
                                // The camera buffer goes straight back to the sensor and a copy takes
                                // its place, because the encoder reads these bytes on its own thread
                                // after this callback has returned. Rotating into the copy costs the
                                // same pass and covers the upright case too, so one path does it all.
                                val slot = framePool.acquire(w * h * 3 / 2)
                                val placed = slot?.let { Nv21.rotate(data, w, h, turn, it) }
                                if (placed == null || !encoder.submit(placed.data, placed.width, placed.height)) {
                                    slot?.let { framePool.release(it) }
                                }
                            } else {
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
                            }
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
        // The encoder is fed by this sensor and nothing else, so it goes with the sensor — otherwise a
        // lens flip leaves an encoder configured for the old camera's size running against the new one.
        stopH264Out()
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
        // A fresh share gets a fresh chance at the hardware: the latch below is for a block that refused
        // this share, not for the next one.
        screenLaneFaulted = false
        ProjectionCaptureService.start(context, resultCode, data)
        _state.value = _state.value.copy(sharing = true)
        notifyMediaState()
    }

    fun stopScreenShare() {
        if (!_state.value.sharing) return
        ProjectionCaptureService.stop(context)
        stopScreenLane()
        _state.value = _state.value.copy(sharing = false)
        notifyMediaState()
    }

    /** The system revoked or stopped the capture; drop the "sharing" flag so the UI tells the truth. */
    fun sharingStopped() {
        stopScreenLane()
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

    /** Open the lens without flipping it off when it is already live; safe before media is up. */
    fun ensureCamera() {
        if (!_state.value.cameraOn) startCamera()
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
        webIn.reset()
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
        stopStatsTicker()
        // Encoder and decoders both go with the socket: a decoder left running holds a MediaCodec
        // instance and a surface nobody can see, and the next call would feed it stale reference frames.
        stopH264Lane()
        // The share's lane too, and its own two answers: an encoder left holding the display server's
        // buffers is a share that starts black next time, and a stale `remoteScreenH264` points the call
        // view at a surface that nothing is decoding onto.
        stopScreenLane()
        screenLaneFaulted = false
        remoteScreenH264 = false
        screenIn.values.forEach { it.stop() }
        screenIn.clear()
        peerScreenSurface = null
        peerScreenWidth = 0
        peerScreenHeight = 0
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
