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
    val mediaPort: Int
)

data class CallState(
    val active: Boolean = false,
    val outgoing: Boolean = false,
    val connected: Boolean = false,
    val peerName: String = "",
    val peerPid: String = "",
    val video: Boolean = false,
    val muted: Boolean = false,
    val cameraOn: Boolean = false,
    val sharing: Boolean = false,
    val status: String = ""
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
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var speakerphoneBefore = false
    private var ringExpiryJob: kotlinx.coroutines.Job? = null

    private val responses = mutableMapOf<String, JSONObject>()

    companion object {
        private const val TYPE_AUDIO = 1
        private const val TYPE_CAMERA = 2
        private const val TYPE_SCREEN = 3
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
                .toString().toRequestBody("application/json".toMediaType())
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
            mediaPort = json.optInt("mediaPort", 0)
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
        synchronized(responses) { responses[json.optString("cid")] = json }
    }

    /** Server hook: POST /api/call/end */
    fun handleEnd(json: JSONObject) {
        val endedCid = json.optString("cid")
        if (endedCid.isBlank()) return
        // While ringing, `cid` is still empty: the ringing id lives in incomingCall. Without
        // this the caller's cancel/timeout leaves a ringing dialog that can never be dismissed.
        if (endedCid == cid || incomingCall.value?.cid == endedCid) {
            if (incomingCall.value?.cid == endedCid) incomingCall.value = null
            if (_state.value.active) reset("Call ended by other side")
        }
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
        cid = call.cid
        _state.value = CallState(
            active = true, video = call.video,
            peerName = call.name, peerPid = call.pid, status = "Connecting…"
        )
        scope.launch(Dispatchers.IO) {
            try {
                val ss = openMediaListener()
                sendReply(
                    call.peerIp, call.peerPort,
                    JSONObject().put("cid", call.cid).put("accept", true)
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
        reset("Call ended")
        scope.launch(Dispatchers.IO) {
            peer?.let { sendEnd(currentCid, it.originIp, it.originPort) }
        }
    }

    private fun sendEnd(targetCid: String, ip: String, port: Int) {
        try {
            val body = JSONObject().put("cid", targetCid).toString()
                .toRequestBody("application/json".toMediaType())
            client.newCall(
                Request.Builder().url("http://$ip:$port/api/call/end").post(body).build()
            ).execute().close()
        } catch (e: Exception) { }
    }

    private fun sendReply(ip: String, port: Int, payload: JSONObject) {
        try {
            client.newCall(
                Request.Builder().url("http://$ip:$port/api/call/response")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()
        } catch (e: Exception) { }
    }

    // ---- media ------------------------------------------------------------

    private fun beginMedia(s: Socket, peer: PairedDevice, video: Boolean) {
        socket = s
        out = DataOutputStream(BufferedOutputStreamCompat.wrap(s.getOutputStream()))
        running = true
        _state.value = _state.value.copy(
            outgoing = false, connected = true, video = video,
            status = "In call with ${peer.name}"
        )
        openCallAudioRoute()
        startAudioInOut()
        startReader()
        if (video) startCamera()
    }

    private fun writeFrame(type: Int, payload: ByteArray) {
        val o = out ?: return
        try {
            synchronized(o) {
                o.write(type)
                o.writeInt(payload.size)
                o.write(payload)
                o.flush()
            }
        } catch (e: Exception) {
            if (running) reset("Connection lost")
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
                            val frame = if (_state.value.muted) ByteArray(n) else buf.copyOf(n)
                            writeFrame(TYPE_AUDIO, frame)
                        }
                    }
                } else {
                    // No usable capture device: keep the call alive and send silence.
                    // Paced to the chunk length, otherwise this floods the peer's playback
                    // buffer at 5x realtime.
                    while (running) {
                        writeFrame(TYPE_AUDIO, ByteArray(AUDIO_CHUNK))
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
                    when (type) {
                        TYPE_AUDIO -> track?.write(payload, 0, payload.size)
                        TYPE_CAMERA, TYPE_SCREEN -> {
                            val bmp = try {
                                BitmapFactory.decodeByteArray(payload, 0, payload.size)
                            } catch (e: Exception) {
                                null
                            }
                            if (bmp != null) remoteFrame.value = bmp
                        }
                    }
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
                val cam = Camera.open(0)
                opened = cam
                if (generation != cameraGeneration) {
                    // Toggled off, or the call ended, while the sensor was opening.
                    try { cam.release() } catch (e: Exception) { }
                    opened = null
                    return@post
                }
                val params = cam.parameters
                val best = params.supportedPreviewSizes.minByOrNull {
                    kotlin.math.abs(it.width - 480) + kotlin.math.abs(it.height - 360)
                }
                val bw = best?.width ?: 480
                val bh = best?.height ?: 360
                params.setPreviewSize(bw, bh)
                try { params.set("rotation", 90) } catch (e: Exception) { }
                cam.parameters = params
                cameraParams = params
                cam.addCallbackBuffer(ByteArray(bw * bh * 3 / 2))
                cam.setPreviewCallbackWithBuffer(object : Camera.PreviewCallback {
                    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
                        if (data == null || !running || !_state.value.cameraOn) return
                        try {
                            val p = cameraParams
                            val w = p?.previewSize?.width ?: bw
                            val h = p?.previewSize?.height ?: bh
                            val yuv = YuvImage(data, ImageFormat.NV21, w, h, null)
                            val bos = ByteArrayOutputStream()
                            yuv.compressToJpeg(Rect(0, 0, w, h), 45, bos)
                            writeFrame(TYPE_CAMERA, bos.toByteArray())
                        } catch (e: Exception) { }
                        cam.addCallbackBuffer(data)
                    }
                })
                cam.setPreviewTexture(SurfaceTexture(0))
                cam.startPreview()
                camera = cam
                _state.value = _state.value.copy(cameraOn = true)
            } catch (e: Exception) {
                try { opened?.release() } catch (ignored: Exception) { }
                camera = null
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

    @Suppress("DEPRECATION")
    private fun stopCamera() {
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
            return
        }
        Handler(thread.looper).post {
            try { cam?.stopPreview() } catch (e: Exception) { }
            try { cam?.release() } catch (e: Exception) { }
            thread.quitSafely()
        }
    }

    /** Screen share frames are pushed by ProjectionCaptureService. */
    fun pushScreenFrame(jpeg: ByteArray) {
        if (running && _state.value.sharing) writeFrame(TYPE_SCREEN, jpeg)
    }

    fun startScreenShare(resultCode: Int, data: Intent) {
        if (!_state.value.connected) { toast.value = "Connect the call first"; return }
        ProjectionCaptureService.start(context, resultCode, data)
        _state.value = _state.value.copy(sharing = true)
    }

    fun stopScreenShare() {
        ProjectionCaptureService.stop(context)
        _state.value = _state.value.copy(sharing = false)
    }

    /** The system revoked or stopped the capture; drop the "sharing" flag so the UI tells the truth. */
    fun sharingStopped() {
        if (_state.value.sharing) {
            _state.value = _state.value.copy(sharing = false)
            toast.value = "Screen sharing stopped"
        }
    }

    // ---- controls ---------------------------------------------------------

    fun toggleMute() {
        _state.value = _state.value.copy(muted = !_state.value.muted)
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

    private fun reset(reason: String) {
        running = false
        ringExpiryJob?.cancel()
        ringExpiryJob = null
        try { socket?.close() } catch (e: Exception) { }
        try { serverSocket?.close() } catch (e: Exception) { }
        socket = null; out = null; serverSocket = null
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
        _state.value = CallState(status = if (wasActive) reason else "")
        remoteFrame.value = null
        if (reason.isNotBlank() && wasActive) toast.value = reason
    }

    fun consumeToast() { toast.value = null }
    fun consumeIncoming() { incomingCall.value = null }

    private object BufferedOutputStreamCompat {
        fun wrap(s: java.io.OutputStream) = java.io.BufferedOutputStream(s, 64 * 1024)
    }
}
