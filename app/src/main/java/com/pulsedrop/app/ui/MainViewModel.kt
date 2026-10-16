package com.pulsedrop.app.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pulsedrop.app.MainActivity
import com.pulsedrop.app.call.CallEngine
import com.pulsedrop.app.call.formatCallLength
import com.pulsedrop.app.data.AppDatabase
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.discovery.LanScanner
import com.pulsedrop.app.discovery.PeerDiscoveryManager
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.IncomingPairRequest
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PendingPairRequest
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.ServerSettings
import com.pulsedrop.app.model.SharedFile
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.security.PairManager
import com.pulsedrop.app.server.PulseDropServer
import com.pulsedrop.app.server.ServerService
import com.pulsedrop.app.server.ServerTransferListener
import com.pulsedrop.app.server.WebCall
import com.pulsedrop.app.server.WebThread
import com.pulsedrop.app.transfer.TransferManager
import com.pulsedrop.app.util.NetworkUtils
import com.pulsedrop.app.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID

data class UiServerState(
    val isRunning: Boolean = false,
    val ipAddress: String = "127.0.0.1",
    val port: Int = 8080,
    val serverUrl: String = "http://127.0.0.1:8080",
    val isPinRequired: Boolean = false,
    val pin: String = "1234",
    val deviceName: String = "PulseDrop Phone",
    val autoAcceptTransfers: Boolean = true,
    val networkName: String = "Checking...",
    val startedAt: Long = 0L
)

data class UiPeerConnection(
    val peer: PeerDevice,
    val label: String,
    val requiresPin: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val historyDao = database.transferHistoryDao()

    val transferHistory = historyDao.getAllTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _serverState = MutableStateFlow(UiServerState())
    val serverState: StateFlow<UiServerState> = _serverState.asStateFlow()

    /**
     * Files arriving at the phone: straight into the received folder when auto-accept is on,
     * or parked in the pending folder for a decision when it is not. Declared beside the other
     * flows because the constructor's startServer() clears it, and Kotlin initialises in order.
     */
    private val _pendingUploads = MutableStateFlow<List<com.pulsedrop.app.model.PendingUploadInfo>>(emptyList())
    val pendingUploads: StateFlow<List<com.pulsedrop.app.model.PendingUploadInfo>> = _pendingUploads.asStateFlow()

    /** Where accepted uploads land. One folder, owned by the app; the Receive page shows it as text. */
    val receiveDir: String
        get() = java.io.File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            PulseDropServer.RECEIVED_DIR_NAME
        ).absolutePath

    private val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()

    private val _clipMessages = MutableStateFlow<List<ClipMessage>>(emptyList())
    val clipMessages: StateFlow<List<ClipMessage>> = _clipMessages.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val discoveryManager = PeerDiscoveryManager(context)
    val nearbyPeers: StateFlow<List<PeerDevice>> = discoveryManager.nearbyPeers

    private val transferManager = TransferManager(context, viewModelScope)
    val activeTransfers: StateFlow<Map<String, TransferItem>> = transferManager.activeTransfers

    private var serverInstance: PulseDropServer? = null

    private val _connectedPeer = MutableStateFlow<UiPeerConnection?>(null)
    val connectedPeer: StateFlow<UiPeerConnection?> = _connectedPeer.asStateFlow()

    private val _peerConnecting = MutableStateFlow(false)
    val peerConnecting: StateFlow<Boolean> = _peerConnecting.asStateFlow()

    /** One-shot messages for the snackbar all go through the app's single feedback channel. */
    private fun postEvent(message: String) {
        PulseEvents.post(message)
    }

    private val _typingUsers = MutableStateFlow<List<String>>(emptyList())
    val typingUsers: StateFlow<List<String>> = _typingUsers.asStateFlow()

    private var typingPollJob: kotlinx.coroutines.Job? = null

    /** Desktop browsers currently pointed at this phone's server. */
    private val _webClients = MutableStateFlow<List<com.pulsedrop.app.server.WebClientInfo>>(emptyList())
    val webClients: StateFlow<List<com.pulsedrop.app.server.WebClientInfo>> = _webClients.asStateFlow()
    private var webClientJob: kotlinx.coroutines.Job? = null

    private val settingsPrefs = context.getSharedPreferences("localdrop_settings", Context.MODE_PRIVATE)

    val pairManager = PairManager(context, viewModelScope)
    val pairedDevices: StateFlow<List<PairedDevice>> = pairManager.paired
    val incomingPairRequests: StateFlow<List<IncomingPairRequest>> = pairManager.incomingRequests

    /**
     * Lets this phone chat through somebody else's server. One host is enough for a whole room:
     * the phone that is not hosting pairs, sends and receives through the host's mailbox instead of
     * needing a socket of its own, which is what used to force "start the server on both devices".
     */
    val relayClient = com.pulsedrop.app.relay.RelayClient(pairManager, viewModelScope)
    val relayHost: StateFlow<com.pulsedrop.app.relay.RelayClient.Host?> = relayClient.attached

    /** Whichever phone is doing the server work for us, as the UI has no business knowing more. */
    val relayHostName: StateFlow<String?> = relayClient.attached
        .map { it?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pendingOutgoingPair: StateFlow<PendingPairRequest?> = pairManager.pendingOutgoing
    val pairEvent: StateFlow<String?> = pairManager.pairEvent

    val callEngine = CallEngine(
        context = context,
        pairManager = { pairManager },
        nameProvider = { _serverState.value.deviceName },
        endpointProvider = { _serverState.value.ipAddress to _serverState.value.port },
        scope = viewModelScope
    )
    val callState = callEngine.state
    val incomingCall = callEngine.incomingCall
    val remoteFrame = callEngine.remoteFrame
    val remoteFrames = callEngine.remoteFrames

    val updateManager = com.pulsedrop.app.update.UpdateManager(context, viewModelScope)
    private val installReporter = com.pulsedrop.app.stats.InstallReporter(context, viewModelScope)
    private val updateWatch = com.pulsedrop.app.update.UpdateWatch(
        context, updateManager, onConnected = { installReporter.report() }
    )

    // ---- top-bar call notifications ---------------------------------------

    private val _webCall = MutableStateFlow<WebCall?>(null)
    val webCall: StateFlow<WebCall?> = _webCall.asStateFlow()

    private val callNotifications = context.getSystemService(NotificationManager::class.java)

    /** The button a call notification offers; the name rides back into the app as an intent extra. */
    enum class CallNotifAction { ANSWER, DECLINE, HANG_UP }

    private data class CallNotification(
        val title: String,
        val text: String,
        val ring: Boolean,
        val actions: List<CallNotifAction> = emptyList()
    )

    /** The note currently in the shade; a rebuild that says the same thing is not re-posted. */
    private var lastCallNote: CallNotification? = null

    /**
     * A call used to exist only while the app was open; dialled during another
     * session it fell on deaf ears. Mirror every call leg into the system's top
     * bar as a high-importance notification - tapping opens MainActivity, which
     * already paints the full call UI. Answering and hanging up happen from the
     * shade itself, so the ringing screen never has to be found.
     */
    private fun startCallNotifications() {
        viewModelScope.launch {
            // A connected call has to keep its running length in the shade, so the note is
            // rebuilt on a slow clock too, not only when the call state changes.
            val clock = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(10_000L)
                }
            }
            combine(callEngine.state, callEngine.incomingCall, _webCall, clock) { state, incoming, web, now ->
                when {
                    incoming != null -> CallNotification(
                        "Incoming ${if (incoming.video) "video" else "voice"} call",
                        incoming.name.ifBlank { "PulseDrop peer" },
                        ring = true,
                        actions = listOf(CallNotifAction.ANSWER, CallNotifAction.DECLINE)
                    )
                    state.active -> CallNotification(
                        if (state.connected) "In call with ${state.peerName}" else "Calling ${state.peerName}",
                        buildString {
                            if (state.connected && state.connectedAt > 0) {
                                append(formatCallLength((now - state.connectedAt) / 1000L))
                                append(" · ")
                            }
                            if (state.muted) append("You are muted · ")
                            append(state.status.ifBlank { if (state.video) "Video call" else "Voice call" })
                        },
                        ring = false,
                        actions = listOf(CallNotifAction.HANG_UP)
                    )
                    web != null && !web.outgoing && web.state == "ringing" -> CallNotification(
                        "Incoming ${if (web.video) "video" else "voice"} call",
                        web.name.ifBlank { "Browser" },
                        ring = true,
                        actions = listOf(CallNotifAction.ANSWER, CallNotifAction.DECLINE)
                    )
                    web != null -> CallNotification(
                        if (web.state == "accepted") "In call with ${web.name.ifBlank { "the browser" }}"
                        else "Ringing ${web.name.ifBlank { "the browser" }}",
                        "Browser call on this phone's server",
                        ring = false,
                        actions = listOf(CallNotifAction.HANG_UP)
                    )
                    else -> null
                }
            }.collect { note ->
                if (note == lastCallNote) return@collect
                lastCallNote = note
                if (note == null) callNotifications.cancel(CALL_NOTIF_ID)
                else postCallNotification(note)
            }
        }
        // The one record a user actually goes back to read after a call: how long it lasted.
        viewModelScope.launch {
            callEngine.lastCall.collect { rec ->
                if (rec != null) postEndedCallNotification(rec)
            }
        }
    }

    /**
     * Answer or hang up straight from the shade. The incoming leg is decided by
     * [CallEngine.respondIncoming]; a ringing browser leg is decided by the server.
     */
    fun runCallNotificationAction(name: String) {
        val action = CallNotifAction.values().firstOrNull { it.name == name } ?: return
        when (action) {
            CallNotifAction.ANSWER -> when {
                callEngine.incomingCall.value != null -> respondToCall(true)
                _webCall.value != null -> answerWebCall(true)
            }
            CallNotifAction.DECLINE -> when {
                callEngine.incomingCall.value != null -> respondToCall(false)
                _webCall.value != null -> answerWebCall(false)
            }
            CallNotifAction.HANG_UP -> hangUpCall()
        }
    }

    private fun ensureCallChannel(id: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        callNotifications.createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
                .apply { this.description = description }
        )
    }

    private fun callOpenIntent(flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(flags),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun callActionIntent(action: CallNotifAction): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CALL_ACTION, action.name)
        return PendingIntent.getActivity(
            context, action.ordinal + 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun postCallNotification(note: CallNotification) {
        ensureCallChannel(CALL_CHANNEL_ID, "Calls", "Incoming and ongoing PulseDrop calls")
        callNotifications.cancel(CALL_ENDED_NOTIF_ID)
        val builder = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(note.title)
            .setContentText(note.text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(callOpenIntent())
            .setOngoing(true)
            // The clock re-posts this note while a call runs; only the first one may sound.
            .setOnlyAlertOnce(true)
            .setSilent(!note.ring)
        if (note.ring) {
            builder.priority = NotificationCompat.PRIORITY_MAX
            builder.setFullScreenIntent(callOpenIntent(), true)
        }
        note.actions.forEach { action ->
            builder.addAction(
                0,
                when (action) {
                    CallNotifAction.ANSWER -> "Answer"
                    CallNotifAction.DECLINE -> "Decline"
                    CallNotifAction.HANG_UP -> "Hang up"
                },
                callActionIntent(action)
            )
        }
        callNotifications.notify(CALL_NOTIF_ID, builder.build())
    }

    private fun postEndedCallNotification(rec: CallEngine.CallRecord) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callNotifications.createNotificationChannel(
                NotificationChannel(CALL_ENDED_CHANNEL_ID, "Ended calls", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = "How long each PulseDrop call lasted"
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        }
        val detail = buildString {
            append(rec.peerName)
            if (rec.video) append(" · video")
            if (rec.conference) append(" · group")
            if (rec.reason.isNotBlank()) append(" · ").append(rec.reason)
        }
        callNotifications.notify(
            CALL_ENDED_NOTIF_ID,
            NotificationCompat.Builder(context, CALL_ENDED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_offline)
                .setContentTitle("Call ended · ${formatCallLength(rec.seconds)}")
                .setContentText(detail)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(callOpenIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    private val _scannedPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val scannedPeers: StateFlow<List<PeerDevice>> = _scannedPeers.asStateFlow()

    private val _radarScanning = MutableStateFlow(false)
    val radarScanning: StateFlow<Boolean> = _radarScanning.asStateFlow()

    private var radarJob: kotlinx.coroutines.Job? = null

    init {
        val defaultDeviceName = NetworkUtils.getDefaultDeviceName()
        val initialIp = NetworkUtils.getLocalIpAddress()
        _serverState.update {
            it.copy(
                deviceName = settingsPrefs.getString("deviceName", null) ?: defaultDeviceName,
                ipAddress = initialIp,
                serverUrl = "http://$initialIp:${it.port}",
                isPinRequired = settingsPrefs.getBoolean("pinRequired", false),
                autoAcceptTransfers = settingsPrefs.getBoolean("autoAccept", true),
                pin = settingsPrefs.getString("pin", null) ?: generateRandomPin()
            )
        }
        updateQrCode("http://$initialIp:${_serverState.value.port}")
        startIdleWatchdog()

        // Set before anything can reach PairManager: the server and the relay both call back
        // through these, and a pairing answer arriving first would be named and addressed wrong.
        pairManager.nameProvider = { _serverState.value.deviceName }
        pairManager.versionProvider = { com.pulsedrop.app.BuildConfig.VERSION_NAME }
        pairManager.endpointProvider = { _serverState.value.ipAddress to _serverState.value.port }
        pairManager.onPairedMessage = { msg ->
            // A room message from a paired phone goes into the shared room as well, or the
            // browsers pointed at this phone would never see what another phone said.
            if (msg.threadId == null) serverInstance?.ingestRoomMessage(msg)
            _clipMessages.update { list ->
                if (list.any { it.id == msg.id }) list else (list + msg).takeLast(CHAT_RETENTION)
            }
        }
        pairManager.acceptsInbound = { serverInstance != null }
        // A retraction names a message id, and we do not know which store it lives in, so mark
        // both: a missing id is a no-op in either.
        pairManager.onPairedDelete = { id ->
            markLocalDeleted(id)
            serverInstance?.markMessageDeleted(id)
        }
        pairManager.relaySender = { pairing, iv, ct -> relayClient.send(pairing, iv, ct) }
        // A phone reading mail from our own mailbox is a local drop, not a remote post:
        // that is how a browser's room message reaches a paired phone that hosts nothing.
        pairManager.hostMailDropper = { pairing, iv, ct ->
            serverInstance?.dropToRelayMailbox(
                pairManager.deviceIdOf(pairing), pairManager.myDeviceId(), pairing.pid, iv, ct
            ) == true
        }
        pairManager.relayPairPoller = { ip, port, reqId ->
            relayClient.pollPairAnswer(ip, port, reqId)
        }
        // Hosting: leave a relay-mode answer where its requester can pick it up.
        pairManager.pairAnswerSink = { reqId, answer ->
            serverInstance?.setPairAnswer(reqId, answer)
        }

        // Hosting on launch means pairing never needs "start the server on BOTH phones":
        // whichever app is open already answers the dial. If a previous ViewModel left the
        // server listening, startServer() adopts it instead of fighting it for the port.
        startServer()
        announcePresence()
        startRelayWatch()
        com.pulsedrop.app.call.ProjectionCaptureService.engine = callEngine

        viewModelScope.launch {
            callEngine.toast.collect { t ->
                t?.let(::postEvent)
            }
        }
        startCallNotifications()

        // Every app launch quietly looks for a newer release on GitHub, and keeps looking: a phone
        // that opened on a plane finds out when the Wi-Fi lands rather than at the next launch.
        viewModelScope.launch {
            kotlinx.coroutines.delay(4_000L)
            updateWatch.start()
        }
        viewModelScope.launch {
            updateManager.statusMessage.collect { it?.let(::postEvent) }
        }
    }

    private fun persistSettings() {
        val s = _serverState.value
        settingsPrefs.edit()
            .putString("deviceName", s.deviceName)
            .putBoolean("pinRequired", s.isPinRequired)
            .putBoolean("autoAccept", s.autoAcceptTransfers)
            .putString("pin", s.pin)
            .apply()
    }

    /**
     * Files arriving at the phone: straight into the received folder when auto-accept is on,
     * or parked in the pending folder for a decision when it is not.
     */
    fun setAutoAcceptTransfers(on: Boolean) {
        _serverState.update { it.copy(autoAcceptTransfers = on) }
        persistSettings()
        serverInstance?.autoAcceptTransfers = on
        postEvent(if (on) "Files will arrive without asking"
        else "Incoming files now wait for you to accept them on the phone")
    }

    fun acceptPendingUpload(id: String) {
        val server = serverInstance ?: return
        callNotifications.cancel(id.hashCode())
        viewModelScope.launch(Dispatchers.IO) {
            val name = server.acceptPendingUpload(id)
            _pendingUploads.value = server.pendingUploadsSnapshot()
            postEvent(if (name != null) "Accepted $name" else "That file is no longer waiting")
        }
    }

    fun declinePendingUpload(id: String) {
        val server = serverInstance ?: return
        callNotifications.cancel(id.hashCode())
        viewModelScope.launch(Dispatchers.IO) {
            server.declinePendingUpload(id)
            _pendingUploads.value = server.pendingUploadsSnapshot()
            postEvent("Declined - the file was deleted")
        }
    }

    /** Accept / decline pressed on the "waiting for a decision" notification itself. */
    fun runUploadNotificationAction(spec: String) {
        val sep = spec.indexOf(':')
        if (sep <= 0) return
        when (spec.substring(0, sep)) {
            "accept" -> acceptPendingUpload(spec.substring(sep + 1))
            "decline" -> declinePendingUpload(spec.substring(sep + 1))
        }
    }

    private fun uploadActionIntent(action: String, id: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_UPLOAD_ACTION, "$action:$id")
        return PendingIntent.getActivity(
            context, "$action$id".hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * A parked upload is a decision, and a decision behind a Settings page nobody opens is
     * not a decision. It goes to the shade with both buttons on it.
     */
    private fun postPendingUploadNotification(id: String, fileName: String, size: Long, from: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callNotifications.createNotificationChannel(
                NotificationChannel(
                    PENDING_CHANNEL_ID, "Waiting for a decision",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Uploads held until you accept or decline them on the phone" }
            )
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("com.pulsedrop.app.SECTION", "receive")
        callNotifications.notify(
            id.hashCode(),
            NotificationCompat.Builder(context, PENDING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("A file is waiting")
                .setContentText("$fileName (${NetworkUtils.formatBytes(size)}) from $from")
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, "open$id".hashCode(), open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .addAction(android.R.drawable.ic_input_add, "Accept", uploadActionIntent("accept", id))
                .addAction(android.R.drawable.ic_delete, "Decline", uploadActionIntent("decline", id))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun refreshPendingUploads() {
        _pendingUploads.value = serverInstance?.pendingUploadsSnapshot() ?: emptyList()
    }

    // ---- radar -----------------------------------------------------------

    fun startRadarScan() {
        if (radarJob?.isActive == true) return
        radarJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                _radarScanning.value = true
                val found = try {
                    LanScanner.scanSubnet(_serverState.value.ipAddress)
                } catch (e: Exception) {
                    emptyList()
                }
                _scannedPeers.value = found
                _radarScanning.value = false
                delay(20_000L)
            }
        }
    }

    fun stopRadarScan() {
        radarJob?.cancel()
        radarJob = null
        _radarScanning.value = false
    }

    // ---- pairing -----------------------------------------------------------

    fun requestPairing(peer: PeerDevice) {
        pairManager.sendPairRequest(peer)
    }

    fun cancelPairingRequest() {
        pairManager.cancelPendingRequest()
    }

    fun acceptPairing(request: IncomingPairRequest) {
        pairManager.acceptIncoming(request)
    }

    fun declinePairing(request: IncomingPairRequest) {
        pairManager.declineIncoming(request)
    }

    fun unpair(pid: String) {
        pairManager.unpair(pid)
    }

    fun pairSafetyCode(pid: String): String? = pairManager.safetyCodeFor(pid)

    fun setPairVerified(pid: String, verified: Boolean) {
        pairManager.setVerified(pid, verified)
        postEvent(if (verified) "Pairing marked verified" else "Pairing marked unverified")
    }

    fun consumePairEvent() {
        pairManager.consumeEvent()
    }

    // ---- calls -----------------------------------------------------------

    fun startCall(pid: String, video: Boolean) {
        // Call signaling answers come back to our HTTP server; with it off the peer's reply
        // has nowhere to land and the caller just rings until it times out.
        if (!_serverState.value.isRunning) {
            postEvent("Turn your server on first - calls signal over it")
            return
        }
        callEngine.invite(pid, video)
    }

    fun startGroupCall(pids: List<String>, video: Boolean) {
        if (!_serverState.value.isRunning) {
            postEvent("Turn your server on first - calls signal over it")
            return
        }
        if (pids.isEmpty()) {
            postEvent("Pair a device first - browsers can only be rung from their chat")
            return
        }
        // Calling "everyone" with a single device behind is just a one-to-one call.
        if (pids.size == 1) callEngine.invite(pids.first(), video)
        else callEngine.inviteConference(pids, video)
    }

    fun respondToCall(accept: Boolean) {
        callEngine.respondIncoming(accept)
    }

    fun hangUpCall() {
        // A browser leg has no socket to notify: the shared call record is how the tab learns.
        if (callEngine.state.value.peerPid == CallEngine.WEB_PID) {
            if (_webCall.value != null) endWebCall() else dismissWebCall()
        }
        callEngine.hangup()
    }

    fun toggleCallMute() = callEngine.toggleMute()

    fun toggleCallCamera() = callEngine.toggleCamera()

    fun switchCallCamera() = callEngine.switchCamera()

    fun attachCallPreview(holder: android.view.SurfaceHolder?) = callEngine.attachPreview(holder)

    fun stopScreenShare() = callEngine.stopScreenShare()

    fun toastCallMessage(message: String) {
        postEvent(message)
    }

    fun armScreenShare() {
        callEngine.armScreenShare()
    }

    /** The user backed out of the system picker; drop the reserved foreground slot. */
    fun cancelScreenShare() {
        callEngine.abandonScreenShare()
    }

    fun startScreenShare(resultCode: Int, data: Intent) {
        callEngine.startScreenShare(resultCode, data)
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun startIdleWatchdog() {
        var awakeRequested = false
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                // The network only answers while the CPU does: hold the lock for the work in
                // flight, and nothing at all once the phone is just sitting there serving.
                val busy = _serverState.value.isRunning &&
                    (activeTransfers.value.values.any { it.status == TransferStatus.IN_PROGRESS } ||
                        callEngine.state.value.active)
                if (busy != awakeRequested) {
                    awakeRequested = busy
                    ServerService.keepAwake(context, busy)
                }
                val server = serverInstance ?: continue
                if (!_serverState.value.isRunning) continue
                // The service (or the system) may have stopped the server underneath us;
                // bring it back instead of leaving a dead card — unless the user pressed
                // Stop on the notification, which is a real stop and must stick.
                if (!server.isAlive) {
                    serverInstance = null
                    ServerService.hostedServer = null
                    _serverState.update { it.copy(isRunning = false, startedAt = 0L) }
                    if (ServerService.userStopped) {
                        postEvent("Server stopped from the notification")
                    } else {
                        postEvent("Server dropped — bringing it back up")
                        startServer()
                    }
                    continue
                }
                // Anything live keeps the server busy: an in-flight transfer or a call.
                if (activeTransfers.value.values.any { it.status == TransferStatus.IN_PROGRESS }) continue
                if (callEngine.state.value.active) continue
            }
        }
    }

    fun connectToPeer(rawAddress: String) {
        val address = rawAddress.trim()
            .removePrefix("http://").removePrefix("https://")
            .removeSuffix("/")
        if (address.isBlank()) return

        // An IPv6 literal contains its own colons, so only the last colon (or a bracketed
        // [addr]:port form) separates the port.
        val hostAndPort = if (address.startsWith("[")) {
            val close = address.indexOf(']')
            if (close < 0) Pair(address, "") else Pair(
                address.substring(1, close),
                address.substring(close + 1).removePrefix(":")
            )
        } else if (address.count { it == ':' } > 1) {
            // Bare IPv6 is ambiguous with a port, so it is taken as the host and the
            // default port applies; "[fe80::1]:8080" is the form that carries a port.
            Pair(address, "")
        } else {
            Pair(address.substringBefore(":"), address.substringAfter(":", ""))
        }
        val host = hostAndPort.first
        val port = hostAndPort.second.toIntOrNull() ?: 8080
        if (host.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            _peerConnecting.value = true
            try {
                val response = probeClient.newCall(
                    Request.Builder().url("http://$host:$port/api/info").get().build()
                ).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val peer = PeerDevice(
                            id = "conn_${host}_${port}",
                            name = json.optString("deviceName", host),
                            ip = host,
                            port = json.optInt("port", port),
                            requiresPin = json.optBoolean("requiresPin", false),
                            lastSeen = System.currentTimeMillis()
                        )
                        _connectedPeer.value = UiPeerConnection(
                            peer = peer,
                            label = peer.name,
                            requiresPin = peer.requiresPin
                        )
                        postEvent("Connected to ${peer.name}")
                    } else {
                        postEvent("No PulseDrop server found at $host:$port")
                        _connectedPeer.value = null
                    }
                }
            } catch (e: Exception) {
                postEvent("Could not reach $host:$port")
                _connectedPeer.value = null
            } finally {
                _peerConnecting.value = false
            }
        }
    }

    fun disconnectPeer() {
        _connectedPeer.value = null
    }

    /** Typing presence only matters while the chat screen is on screen. */
    @Volatile private var chatVisible = false

    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (visible) {
            if (_serverState.value.isRunning) startTypingPolling()
        } else {
            typingPollJob?.cancel()
            typingPollJob = null
        }
    }

    private fun startWebClientWatch() {
        if (webClientJob?.isActive == true) return
        webClientJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                // The server records every browser hit itself; this only lifts it into state.
                _webClients.value = serverInstance?.webClientsSnapshot() ?: emptyList()
                delay(2500)
            }
        }
    }

    private fun startTypingPolling() {
        typingPollJob?.cancel()
        typingPollJob = viewModelScope.launch(Dispatchers.IO) {
            val port = _serverState.value.port
            while (isActive) {
                try {
                    val response = probeClient.newCall(
                        Request.Builder().url("http://127.0.0.1:$port/api/typing").get().build()
                    ).execute()
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val arr = org.json.JSONArray(resp.body?.string() ?: "[]")
                            val list = mutableListOf<String>()
                            for (i in 0 until arr.length()) list.add(arr.getString(i))
                            _typingUsers.value = list.filter { it != _serverState.value.deviceName }
                        }
                    }
                } catch (e: Exception) {
                    // Server may be restarting; keep polling
                }
                delay(3500L)
            }
        }
    }

    fun sendTyping(active: Boolean) {
        val server = serverInstance ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("sender", _serverState.value.deviceName)
                    .put("typing", active)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                probeClient.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:${_serverState.value.port}/api/typing")
                        .post(body)
                        .build()
                ).execute().close()
            } catch (e: Exception) {
                // presence is best-effort
            }
        }
    }

    fun saveToDownloads(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        val file = File(filePath)
        if (!file.exists()) {
            postEvent("File is no longer on this device")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(MediaStore.Downloads.SIZE, file.length())
                        put(
                            MediaStore.Downloads.MIME_TYPE,
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                MimeTypeMap.getFileExtensionFromUrl(file.name)
                            ) ?: "application/octet-stream"
                        )
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (target != null) {
                        resolver.openOutputStream(target)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                        postEvent("Saved ${file.name} to Downloads")
                    } else {
                        postEvent("Could not save to Downloads")
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val dest = File(downloadsDir, file.name)
                    file.copyTo(dest, overwrite = true)
                    postEvent("Saved ${dest.name} to Downloads")
                }
            } catch (e: Exception) {
                postEvent("Save failed: ${e.message}")
            }
        }
    }

    private fun generateRandomPin(): String {
        return (1000..9999).random().toString()
    }

    /**
     * Broadcast who we are. This runs whether or not we host: a phone with its server off still
     * has to be seen, or the radar is empty and pairing looks broken. The beacon says so explicitly
     * (`server=false`) so peers chat with it through the relay instead of dialling a dead port.
     */
    private fun announcePresence() {
        val state = _serverState.value
        discoveryManager.startDiscovery(
            deviceName = state.deviceName,
            currentIp = state.ipAddress,
            serverPort = state.port,
            requiresPin = state.isPinRequired,
            scope = viewModelScope,
            hasServer = state.isRunning,
            deviceId = runCatching { pairManager.myDeviceId() }.getOrDefault("")
        )
    }

    /**
     * If we are not the host, become a client of a paired phone that is. Only ever one host at a
     * time: two mailboxes would mean two halves of the same conversation and no way to join them.
     */
    private fun ensureRelayAttached() {
        if (_serverState.value.isRunning) {
            relayClient.detach()
            return
        }
        if (relayClient.attached.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            // The address stored on a pairing is the best guess; discovery tells us who is awake.
            val candidates = pairManager.paired.value.sortedByDescending { it.createdAt } +
                discoveryManager.nearbyPeers.value
                    .filter { it.hasServer && it.deviceId.isNotBlank() }
                    .mapNotNull { pairManager.findPairingByDeviceId(it.deviceId) }
            for (pairing in candidates) {
                if (_serverState.value.isRunning) break
                if (pairing.originIp.isBlank()) continue
                if (relayClient.attach(pairing.originIp, pairing.originPort)) break
            }
        }
    }

    /**
     * A host can appear, reboot or walk out of range at any moment, so attaching is not a one-shot.
     * The tick is cheap: it does nothing at all while we are hosting or already attached.
     */
    private var relayWatchJob: kotlinx.coroutines.Job? = null

    private fun startRelayWatch() {
        if (relayWatchJob?.isActive == true) return
        relayWatchJob = viewModelScope.launch {
            while (isActive) {
                ensureRelayAttached()
                delay(30_000L)
            }
        }
    }

    fun regeneratePin() {
        val newPin = generateRandomPin()
        _serverState.update { it.copy(pin = newPin) }
        persistSettings()
        val state = _serverState.value
        serverInstance?.updatePinSecurity(state.isPinRequired, if (state.isPinRequired) newPin else null)
        announcePresence()
    }

    fun setPinRequired(required: Boolean) {
        _serverState.update { it.copy(isPinRequired = required) }
        persistSettings()
        val state = _serverState.value
        serverInstance?.updatePinSecurity(required, if (required) state.pin else null)
        announcePresence()
    }

    fun setCustomPin(newPin: String) {
        val safePin = newPin.filter { it.isDigit() }.let {
            if (it.length in 4..6) it else if (it.length > 6) it.take(6) else it.padEnd(4, '0').ifBlank { "1234" }
        }
        _serverState.update { it.copy(pin = safePin) }
        persistSettings()
        val state = _serverState.value
        serverInstance?.updatePinSecurity(state.isPinRequired, if (state.isPinRequired) safePin else null)
        announcePresence()
    }

    fun setDeviceName(name: String) {
        if (name.isNotBlank()) {
            _serverState.update { it.copy(deviceName = name) }
            persistSettings()
            if (_serverState.value.isRunning) {
                // Renaming used to bounce the server off its port, cutting whatever was
                // mid-flight. The name is served from a live field now, so only the
                // announcement has to go out again.
                serverInstance?.rename(name)
            }
            announcePresence()
        }
    }

    /**
     * Pull-to-refresh. The lists here are live queries and LAN announcements, so the gesture
     * re-reads the network and re-sweeps the radar instead of re-downloading anything.
     */
    fun pullToRefresh(scanRadar: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            refreshNetwork()
            serverInstance?.let { _webClients.value = it.webClientsSnapshot() }
            if (scanRadar) {
                stopRadarScan()
                startRadarScan()
            }
            // A local read finishes in a blink; hold the spinner long enough to be seen.
            delay(500)
            onDone()
        }
    }

    fun refreshNetwork() {
        val ip = NetworkUtils.getLocalIpAddress()
        val currentPort = _serverState.value.port
        val previous = _serverState.value.ipAddress
        // The plain address is what people expect; since v1.2 the phone bounces browser
        // visits from it onto the secure port on their behalf, so never show "+1" here.
        val url = "http://$ip:$currentPort"
        _serverState.update {
            it.copy(
                ipAddress = ip,
                serverUrl = url
            )
        }
        updateQrCode(url)
        // Discovery announced the address captured at start-up, so after a Wi-Fi switch
        // peers kept dialling the phone's old IP. Re-broadcast, and re-point the notification.
        if (ip != previous) {
            serverInstance?.refreshTls(listOf(ip))
            announcePresence()
            if (_serverState.value.isRunning) ServerService.startService(context, url)
        }
    }

    private fun updateQrCode(url: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = QrCodeGenerator.generateQrBitmap(url, 512, 512)
            _qrBitmap.value = bmp
        }
    }

    fun toggleServer() {
        if (_serverState.value.isRunning) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun startServer() {
        // Grab the reference before stopServer() clears it: a leftover ViewModel swap can
        // leave the port bound by a server nobody remembers, and re-binding it ourselves
        // is exactly the "port 8080 already in use" the user sees on a restart.
        val stray = ServerService.hostedServer
        stopServer()
        refreshNetwork()

        val state = _serverState.value
        val listener = object : ServerTransferListener {
            override fun onTransferStarted(transferId: String, fileName: String, fileSize: Long, direction: TransferDirection, remoteDevice: String, clientIp: String) {
                transferManager.onTransferStarted(transferId, fileName, fileSize, direction, remoteDevice, clientIp)
            }

            override fun onTransferProgress(transferId: String, bytesTransferred: Long, totalBytes: Long, speedBytesPerSec: Long) {
                transferManager.onTransferProgress(transferId, bytesTransferred, totalBytes, speedBytesPerSec)
            }

            override fun onTransferCompleted(transferId: String, fileName: String, fileSize: Long, localFilePath: String?, direction: TransferDirection, remoteDevice: String, clientIp: String) {
                transferManager.onTransferCompleted(transferId, fileName, fileSize, localFilePath, direction, remoteDevice, clientIp)
            }

            override fun onTransferFailed(transferId: String, fileName: String, error: String, direction: TransferDirection) {
                transferManager.onTransferFailed(transferId, fileName, error, direction)
            }

            override fun onNewMessage(message: ClipMessage) {
                _clipMessages.update { list ->
                    if (list.any { it.id == message.id }) list else (list + message).takeLast(CHAT_RETENTION)
                }
            }

            override fun onMessageDeleted(id: String) = markLocalDeleted(id)

            override fun onPendingUpload(id: String, fileName: String, fileSize: Long, fromDevice: String) {
                refreshPendingUploads()
                postPendingUploadNotification(id, fileName, fileSize, fromDevice)
                postEvent("$fileName from $fromDevice is waiting on the phone - accept or decline it from the Receive page")
            }

            override fun onRoomMessageFromWeb(message: ClipMessage) {
                // A browser spoke to the whole room, not only to the tabs on this phone. The
                // message keeps the browser's author, so the other phones see who really said it.
                val reply = message.replyTo?.let {
                    ClipMessage(
                        id = it,
                        text = message.replyText ?: "",
                        sender = message.replySender ?: ""
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    pairManager.broadcastToRoom(
                        message.text, message.id, reply, senderName = message.sender
                    )
                }
            }

            override fun onRoomDeleteFromWeb(id: String) {
                // The tombstone travels the same paths the message did, or one phone would keep
                // a bubble every other device has already struck out.
                viewModelScope.launch(Dispatchers.IO) { pairManager.deleteEverywhere(id, null) }
            }

            override fun onWebCall(call: WebCall) {
                // The browser can hang up or be dismissed remotely; tear the sheet down then.
                _webCall.value = if (call.state == "ended" || call.state == "declined") null else call
            }
        }

        try {
            PulseDropServer.silenceStray(null)
            val server = if (stray != null && stray.isAlive) {
                // A previous ViewModel left this server serving; take it over rather than
                // trying to bind the same port and silently failing.
                stray.attachListener(listener)
                stray
            } else {
                PulseDropServer(
                    port = state.port,
                    context = context,
                    initialDeviceName = state.deviceName,
                    initialPinCode = if (state.isPinRequired) state.pin else null,
                    initialIsPinRequired = state.isPinRequired,
                    initialListener = listener
                )
            }
            server.updatePinSecurity(state.isPinRequired, if (state.isPinRequired) state.pin else null)
            server.autoAcceptTransfers = state.autoAcceptTransfers
            server.setSharedFiles(_sharedFiles.value)
            server.pairManager = pairManager
            server.callEngine = callEngine
            // A browser call that outlived the old ViewModel must re-appear on this one,
            // otherwise the phone forgets a ringing tab while the browser keeps waiting.
            server.webCall?.takeIf { it.state == "ringing" || it.state == "accepted" }?.let {
                _webCall.value = it
            }
            if (!server.isAlive) startWithSettle(server)
            serverInstance = server
            ServerService.publishServer(server)
            syncWebThreads()

            _serverState.update { it.copy(isRunning = true, startedAt = System.currentTimeMillis()) }
            ServerService.startService(context, state.serverUrl)
            if (chatVisible) startTypingPolling()
            startWebClientWatch()

            announcePresence()
            // Hosting means mail for us arrives directly, not from a mailbox to keep polling.
            relayClient.detach()
        } catch (e: Exception) {
            e.printStackTrace()
            serverInstance = null
            ServerService.hostedServer = null
            _serverState.update { it.copy(isRunning = false, startedAt = 0L) }
            postEvent("Could not start the server on port ${state.port}: ${e.message}")
            // The port is taken or the radio is odd — being somebody else's client still works.
            ensureRelayAttached()
        }
    }

    fun stopServer() {
        typingPollJob?.cancel()
        typingPollJob = null
        webClientJob?.cancel()
        webClientJob = null
        _webClients.value = emptyList()
        _typingUsers.value = emptyList()
        // The session's finished rows live in history now; the live sheet starts empty again.
        transferManager.clearFinishedTransfers()
        // Signaling rides on this socket, so a live call cannot outlive it: end it here
        // instead of leaving the call sheet up over a dead connection.
        if (callEngine.state.value.active || callEngine.incomingCall.value != null) {
            callEngine.hangup()
        }
        _webCall.value = null
        try {
            serverInstance?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverInstance = null
        ServerService.hostedServer = null
        ServerService.stopService(context)
        // The queue lived inside that server; the files are still on disk but nobody is
        // holding decisions for them now, so the sheet must stop pretending otherwise.
        _pendingUploads.value = emptyList()
        _serverState.update { it.copy(isRunning = false, startedAt = 0L) }
        // Turning our own server off must not make the phone invisible: it keeps announcing,
        // now flagged server=false, and starts carrying its mail through a paired host instead.
        announcePresence()
        ensureRelayAttached()
    }

    /**
     * Bind with a short settle: a server stopped moments ago can keep the port a beat
     * while its sockets close, and one retry beats telling the user their own port is taken.
     */
    private fun startWithSettle(server: PulseDropServer) {
        var attempt = 0
        while (true) {
            try {
                server.start()
                return
            } catch (e: Exception) {
                if (e !is java.net.BindException || ++attempt >= 4) throw e
                PulseDropServer.silenceStray(server)
                try {
                    Thread.sleep(350L * attempt)
                } catch (ie: InterruptedException) {
                    throw e
                }
            }
        }
    }

    /**
     * A browser cannot be dialed back, so "sending" to one means hosting the file for that
     * address alone: it lands in their list and nowhere else on the network.
     */
    fun sendFileToBrowser(ip: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = resolveFileMetadata(uri)
            val item = SharedFile(
                id = UUID.randomUUID().toString(),
                name = meta.first,
                size = meta.second,
                mimeType = meta.third,
                uriString = uri.toString(),
                targetClientIp = ip
            )
            _sharedFiles.update { current ->
                val updated = current + item
                serverInstance?.setSharedFiles(updated)
                updated
            }
            postEvent("Sent ${meta.first} to $ip — it is in their download list")
        }
    }

    fun addFiles(uris: List<Uri>) {        viewModelScope.launch(Dispatchers.IO) {
            val newItems = mutableListOf<SharedFile>()
            for (uri in uris) {
                val metadata = resolveFileMetadata(uri)
                val item = SharedFile(
                    id = UUID.randomUUID().toString(),
                    name = metadata.first,
                    size = metadata.second,
                    mimeType = metadata.third,
                    uriString = uri.toString()
                )
                newItems.add(item)
            }
            _sharedFiles.update { current ->
                val updated = current + newItems
                serverInstance?.setSharedFiles(updated)
                updated
            }
        }
    }

    fun removeSharedFile(id: String) {
        _sharedFiles.update { current ->
            val updated = current.filterNot { it.id == id }
            serverInstance?.setSharedFiles(updated)
            updated
        }
    }

    fun clearSharedFiles() {
        _sharedFiles.update {
            serverInstance?.setSharedFiles(emptyList())
            emptyList()
        }
    }

    fun sendFilesToPeer(peer: PeerDevice, uris: List<Uri>, peerPin: String? = null, onDone: (Boolean, String?) -> Unit) {
        val pairing = pairManager.pairForPeer(peer)
        if (pairing != null) {
            for (uri in uris) {
                viewModelScope.launch(Dispatchers.IO) {
                    val meta = resolveFileMetadata(uri)
                    val cipher = pairManager.prepareEncryptedUpload(pairing, uri)
                    if (cipher == null) {
                        postEvent("Could not encrypt ${meta.first}")
                    } else {
                        transferManager.sendPairEncryptedFile(
                            peer, pairing.pid, cipher, meta.first, meta.second,
                            proofFor = { length -> pairManager.uploadProof(pairing.pid, length) },
                            onComplete = onDone
                        )
                    }
                }
            }
            return
        }
        // Only use our own PIN fallback when the peer does not demand its own PIN.
        val pin = peerPin
        for (uri in uris) {
            transferManager.sendFileToPeer(peer, uri, pin, onDone)
        }
    }

    val groups: StateFlow<List<ChatGroup>> get() = pairManager.groups

    /** Dial the browser tab of a LAN visitor; the phone owns the ringing UI on both ends. */
    fun callWebClient(ip: String, name: String, video: Boolean) {
        val server = serverInstance ?: run {
            postEvent("Turn your server on first - calls signal over it")
            return
        }
        if (_webCall.value != null || callEngine.state.value.active || callEngine.incomingCall.value != null) {
            postEvent("Another call is already up")
            return
        }
        server.ringWebClient(ip, name, video)
    }

    /**
     * One dial for the room, which has two kinds of guest: paired devices and browser tabs.
     * With a tab in the list it rings first and the devices join through it as one conference,
     * so the caller never has to know which audience a button was for.
     */
    fun callEveryone(memberPids: List<String>, browserIp: String?, browserName: String?, video: Boolean) {
        if (!_serverState.value.isRunning) {
            postEvent("Turn your server on first - calls signal over it")
            return
        }
        if (_webCall.value != null || callEngine.state.value.active || callEngine.incomingCall.value != null) {
            postEvent("Another call is already up")
            return
        }
        if (browserIp == null) {
            startGroupCall(memberPids, video)
            return
        }
        serverInstance?.ringWebClient(browserIp, browserName?.takeIf { it.isNotBlank() } ?: "Browser", video, memberPids)
    }

    fun answerWebCall(accept: Boolean) {
        val call = _webCall.value ?: return
        serverInstance?.updateWebCall(call.cid, if (accept) "accepted" else "declined")
        _webCall.value = if (accept) call.copy(state = "accepted") else null
    }

    fun endWebCall() {
        val call = _webCall.value ?: return
        serverInstance?.updateWebCall(call.cid, "ended")
        _webCall.value = null
    }

    fun dismissWebCall() {
        _webCall.value = null
    }

    /** Browsers may join group threads; pair threads stay end-to-end encrypted and hidden. */
    private fun syncWebThreads() {
        val names = pairManager.paired.value.associate { it.pid to it.name }
        serverInstance?.webThreads = pairManager.groups.value.map { g ->
            WebThread(
                id = "group:${g.id}",
                title = g.name,
                kind = "group",
                members = g.memberPids.map { names[it] ?: "Device" }
            )
        }
    }

    fun createGroup(name: String, memberPids: List<String>) {
        if (pairManager.createGroup(name, memberPids) == null) {
            postEvent("A group needs a name and at least two paired devices")
        }
        syncWebThreads()
    }

    fun deleteGroup(id: String) {
        pairManager.deleteGroup(id)
        syncWebThreads()
    }

    private val _lastReadAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastReadAt: StateFlow<Map<String, Long>> = _lastReadAt.asStateFlow()

    /** Marks the newest message of a thread as read so the inbox can show an unread badge. */
    fun markThreadRead(threadId: String?) {
        val key = threadId ?: "room"
        val newest = _clipMessages.value
            .filter { it.threadId == threadId }
            .maxOfOrNull { it.timestamp } ?: return
        if ((_lastReadAt.value[key] ?: 0L) >= newest) return
        _lastReadAt.update { it + (key to newest) }
    }

    /**
     * Composer attachment. Paired and group threads ship the bytes sealed with the
     * recipient's own key; room and direct-IP threads keep the file in the chat store,
     * so it stays inside the conversation instead of the hosted file list.
     */
    fun sendFileToThread(threadId: String?, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val (name, size, _) = resolveFileMetadata(uri)
            when {
                threadId == null || threadId.startsWith("ip:") -> {
                    val server = serverInstance
                    if (server == null) {
                        // Attached to somebody else's room: chat rides the relay, but a file is
                        // bytes, not a message. Say which of the two the user is out of.
                        val host = relayClient.attached.value
                        postEvent(if (host != null)
                            "Text goes through ${host.name}'s server, but files need a server of your own — start yours to send $name"
                        else
                            "Start the server to attach files to chat")
                        return@launch
                    }
                    val attachment = server.storeChatAttachment(uri, name)
                    if (attachment == null) {
                        postEvent("Could not attach $name")
                        return@launch
                    }
                    val sender = _serverState.value.deviceName
                    val clientIp = if (threadId == null) null else _serverState.value.ipAddress
                    val msg = server.addMessage(
                        text = "", sender = sender, clientIp = clientIp,
                        threadId = threadId, attachment = attachment
                    )
                    _clipMessages.update { list ->
                        if (list.any { it.id == msg.id }) list else (list + msg).takeLast(CHAT_RETENTION)
                    }
                }

                threadId.startsWith("pair:") -> {
                    val pid = threadId.removePrefix("pair:")
                    val pairing = pairManager.findPairing(pid) ?: return@launch
                    sendEncryptedFileTo(pairing, uri, name, size)
                }

                threadId.startsWith("group:") -> {
                    val group = pairManager.findGroup(threadId.removePrefix("group:")) ?: return@launch
                    val members = group.memberPids.mapNotNull { pairManager.findPairing(it) }
                    if (members.isEmpty()) return@launch
                    postEvent("Sending $name to ${members.size} devices")
                    members.forEach { sendEncryptedFileTo(it, uri, name, size) }
                }
            }
        }
    }

    /** One tap, many files: each one takes the same path a lone attachment would. */
    fun sendFilesToThread(threadId: String?, uris: List<Uri>) {
        uris.forEach { sendFileToThread(threadId, it) }
    }

    private suspend fun sendEncryptedFileTo(pairing: PairedDevice, uri: Uri, name: String, size: Long) {
        val cipher = pairManager.prepareEncryptedUpload(pairing, uri)
        if (cipher == null) {
            postEvent("Could not encrypt $name")
            return
        }
        if (pairing.originIp.isBlank()) {
            cipher.delete()
            postEvent("${pairing.name} has no known address yet — re-pair from the radar")
            return
        }
        val peer = PeerDevice(
            id = pairing.pid,
            name = pairing.name,
            ip = pairing.originIp,
            port = pairing.originPort,
            requiresPin = false
        )
        transferManager.sendPairEncryptedFile(
            peer, pairing.pid, cipher, name, size,
            proofFor = { length -> pairManager.uploadProof(pairing.pid, length) }
        ) { ok, err ->
            cipher.delete()
            postEvent(when {
                ok -> "Sent $name to ${pairing.name}, end-to-end sealed"
                else -> "$name to ${pairing.name} failed: ${err ?: "unknown error"}"
            })
        }
    }

    /** Resolves a chat attachment to the file the server stored for it. */
    fun chatAttachmentFile(attachment: com.pulsedrop.app.model.ChatAttachment): File? =
        serverInstance?.chatAttachmentFile(attachment.id)

    fun sendClipMessage(text: String, threadId: String? = null, reply: ClipMessage? = null) {
        if (text.isBlank()) return
        val sender = _serverState.value.deviceName

        fun echo(msg: ClipMessage) {
            _clipMessages.update { list ->
                if (list.any { it.id == msg.id }) list else (list + msg).takeLast(CHAT_RETENTION)
            }
        }

        when {
            threadId == null -> {
                val msg = serverInstance?.addMessage(text, sender, replyTo = reply)
                    ?: sentBubble(text = text, sender = sender, reply = reply)
                echo(msg)
                // The room is not just this phone's browsers: every paired phone has to get it too,
                // otherwise two phones in one room read as two unrelated chats.
                viewModelScope.launch(Dispatchers.IO) {
                    pairManager.broadcastToRoom(text, msg.id, reply)
                    if (reply != null) olderPeerNotice(null, "Replies")?.let(::postEvent)
                }
            }

            threadId.startsWith("pair:") -> {
                val pid = threadId.removePrefix("pair:")
                val pairing = pairManager.findPairing(pid) ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    val ok = pairManager.sendPairedMessage(pairing, text, threadId, reply)
                    if (ok) {
                        echo(
                            sentBubble(
                                text = text, sender = sender,
                                threadId = threadId, encrypted = true, reply = reply
                            )
                        )
                        if (reply != null) olderPeerNotice(threadId, "Replies")?.let(::postEvent)
                    } else {
                        val host = relayClient.attached.value
                        postEvent(if (host != null)
                            "${pairing.name} is offline, and ${host.name}'s relay did not take the message"
                        else
                            "${pairing.name} is offline — pair with a phone that is hosting, or start your own server")
                    }
                }
            }

            threadId.startsWith("group:") -> {
                val gid = threadId.removePrefix("group:")
                val group = pairManager.findGroup(gid) ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    val delivered = pairManager.sendGroupMessage(group, text, reply)
                    if (delivered > 0) {
                        echo(
                            sentBubble(
                                text = text, sender = sender,
                                threadId = threadId, encrypted = true, reply = reply
                            )
                        )
                        if (delivered < group.memberPids.size) {
                            postEvent("Reached $delivered of ${group.memberPids.size} members of ${group.name}")
                        }
                    } else {
                        postEvent("No member of ${group.name} was reachable")
                    }
                }
            }

            threadId.startsWith("ip:") -> {
                val selfIp = _serverState.value.ipAddress
                val msg = serverInstance?.addMessage(
                    text = text, sender = sender, clientIp = selfIp,
                    threadId = threadId, replyTo = reply
                ) ?: sentBubble(
                    text = text, sender = sender, threadId = threadId,
                    clientIp = selfIp, reply = reply
                )
                echo(msg)
            }
        }
    }

    /** The bubble this device shows for something it just wrote, carrying the same quote as the wire. */
    private fun sentBubble(
        text: String,
        sender: String,
        threadId: String? = null,
        encrypted: Boolean = false,
        clientIp: String? = null,
        reply: ClipMessage? = null
    ) = ClipMessage(
        id = UUID.randomUUID().toString(),
        text = text,
        sender = sender,
        clientIp = clientIp,
        encrypted = encrypted,
        threadId = threadId,
        replyTo = reply?.id,
        replySender = reply?.sender,
        replyText = reply?.text?.take(200)
    )

    /**
     * Removes a chat message. Locally it always becomes a tombstone, so the thread does not shift
     * under anything already drawn; "for everyone" additionally seals a retraction along the paths
     * the original travelled. A peer on a build too old to understand that simply keeps showing the
     * message, which is why the bubble is marked rather than silently dropped.
     */
    /**
     * Whether to warn that an audience may not understand something this build does. Replies,
     * retractions and relay mail arrived in 1.6; a peer that paired before versions were
     * exchanged reported nothing, and silence is treated as older rather than guessed at.
     */
    private val RECENT_FEATURES = 1 to 6

    private fun olderPeerNotice(threadId: String?, feature: String): String? {
        if (threadId == null) {
            val stale = pairedDevices.value.filterNot { pairManager.peerAtLeast(it, RECENT_FEATURES.first, RECENT_FEATURES.second) }
            return if (stale.isEmpty()) null else
                "$feature shows only on phones running PulseDrop ${RECENT_FEATURES.first}.${RECENT_FEATURES.second} or newer — " +
                    stale.joinToString { it.name } + " ${if (stale.size == 1) "is" else "are"} still behind"
        }
        if (!threadId.startsWith("pair:")) return null
        val pairing = pairManager.findPairing(threadId.removePrefix("pair:")) ?: return null
        if (pairManager.peerAtLeast(pairing, RECENT_FEATURES.first, RECENT_FEATURES.second)) return null
        return "$feature needs PulseDrop ${RECENT_FEATURES.first}.${RECENT_FEATURES.second} or newer on ${pairing.name} — " +
            (if (pairing.appVersion.isBlank()) "it has not reported a version" else "it is running ${pairing.appVersion}")
    }

    fun deleteMessage(id: String, forEveryone: Boolean) {
        val target = _clipMessages.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            markLocalDeleted(id)
            if (target.threadId == null || target.threadId.startsWith("ip:")) {
                serverInstance?.markMessageDeleted(id)
            }
            if (!forEveryone) return@launch
            if (target.encrypted && !target.sender.equals(_serverState.value.deviceName, ignoreCase = true)) {
                postEvent("Only ${target.sender} can retract their own message")
                return@launch
            }
            val reached = pairManager.deleteEverywhere(id, target.threadId)
            val stale = olderPeerNotice(target.threadId, "Retractions")
            postEvent(when {
                stale != null -> stale
                reached > 0 -> "Retracted on $reached other device${if (reached > 1) "s" else ""}"
                target.threadId == null || target.threadId.startsWith("ip:") -> "Deleted here and on the room"
                else -> "Deleted here — the other side is offline, so it still shows their copy"
            })
        }
    }

    private fun markLocalDeleted(id: String) {
        _clipMessages.update { list ->
            list.map { if (it.id == id) it.copy(deleted = true, text = "") else it }
        }
    }

    fun deleteHistoryItem(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.deleteById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.clearHistory()
        }
    }

    /** Removes a finished row from the live list; cancels nothing and deletes no files. */
    fun dismissFinishedTransfer(id: String): Boolean = transferManager.dismissFinished(id)

    fun openFile(filePath: String?, onResult: ((Boolean, String?) -> Unit)? = null) {
        if (filePath.isNullOrBlank()) {
            onResult?.invoke(false, "No location was recorded for this file.")
            return
        }
        val file = File(filePath)
        if (!file.exists()) {
            onResult?.invoke(false, "The file is no longer at its recorded location.")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val ext = MimeTypeMap.getFileExtensionFromUrl(file.name)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            onResult?.invoke(true, null)
        } catch (e: android.content.ActivityNotFoundException) {
            onResult?.invoke(false, "No app on this phone opens this type of file.")
        } catch (e: Exception) {
            e.printStackTrace()
            onResult?.invoke(false, e.message ?: "Android refused to open the file.")
        }
    }

    /**
     * Renames a received file inside the one folder the app owns. The DB record moves only after
     * Android reports the rename succeeded, so the UI never shows a file at a path that is not real.
     */
    fun renameReceivedFile(id: String, newName: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = historyDao.getById(id)
            val cleaned = newName.trim()
            val failure = when {
                row == null || row.localFilePath.isNullOrBlank() ->
                    "No received-file record lives behind this id."
                cleaned.isEmpty() || cleaned == "." || cleaned == ".." ||
                    cleaned.contains('/') || cleaned.contains('\\') || cleaned.contains('\u0000') ->
                    "That name is not usable: no path separators, no empty or dot-only names."
                else -> null
            }
            if (failure != null) {
                withContext(Dispatchers.Main) { onResult(false, failure) }
                return@launch
            }
            val src = File(row!!.localFilePath!!)
            if (!src.exists()) {
                withContext(Dispatchers.Main) { onResult(false, "The file is no longer at its recorded location.") }
                return@launch
            }
            val dest = File(src.parentFile, cleaned)
            if (dest.exists()) {
                withContext(Dispatchers.Main) { onResult(false, "A file named “$cleaned” already sits in the received folder.") }
                return@launch
            }
            if (!src.renameTo(dest)) {
                withContext(Dispatchers.Main) { onResult(false, "Android refused the rename — the file may be open in another app.") }
                return@launch
            }
            historyDao.updateFileLocation(id, cleaned, dest.absolutePath)
            withContext(Dispatchers.Main) { onResult(true, "Renamed to “$cleaned”.") }
        }
    }

    /**
     * Deletes the physical file only. The history record deliberately survives as a missing-file
     * marker — file deletion and record removal are different operations and never conflated here.
     */
    fun deleteReceivedFile(id: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = historyDao.getById(id)
            if (row == null || row.localFilePath.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onResult(false, "No received-file record lives behind this id.") }
                return@launch
            }
            val file = File(row.localFilePath!!)
            if (!file.exists()) {
                withContext(Dispatchers.Main) { onResult(false, "The file was already gone — nothing was deleted.") }
                return@launch
            }
            if (!file.delete()) {
                withContext(Dispatchers.Main) { onResult(false, "Android refused the delete — the file may be open elsewhere.") }
                return@launch
            }
            withContext(Dispatchers.Main) { onResult(true, "Deleted ${row.fileName}.") }
        }
    }

    fun shareFile(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        val file = File(filePath)
        if (!file.exists()) return

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val ext = MimeTypeMap.getFileExtensionFromUrl(file.name)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveFileMetadata(uri: Uri): Triple<String, Long, String> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        var mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

        if (uri.scheme == "content") {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) name = it.getString(nameIndex) ?: name
                        if (sizeIndex != -1) size = it.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            name = uri.lastPathSegment ?: name
        }

        if (size <= 0) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    size = it.statSize
                }
            } catch (e: Exception) {
                size = 0L
            }
        }

        return Triple(name, size.coerceAtLeast(0L), mime)
    }

    override fun onCleared() {
        super.onCleared()
        // The server is owned by its foreground service, not this ViewModel — clearing
        // the ViewModel (config change, process trim) must not tear the server down.
        discoveryManager.stopDiscovery()
        updateWatch.stop()
        callNotifications.cancel(CALL_NOTIF_ID)
    }

    companion object {
        /** Chat lives for the session — kept until the app is restarted, not written to disk. */
        private const val CHAT_RETENTION = 2000

        private const val CALL_CHANNEL_ID = "pulsedrop_call"
        private const val CALL_NOTIF_ID = 4141
        private const val CALL_ENDED_CHANNEL_ID = "pulsedrop_call_ended"
        private const val CALL_ENDED_NOTIF_ID = 4142

        /** Which shade button was pressed; MainActivity hands the name back to the ViewModel. */
        const val EXTRA_CALL_ACTION = "com.pulsedrop.app.extra.CALL_ACTION"
        private const val PENDING_CHANNEL_ID = "pulsedrop_pending_upload"
        const val EXTRA_UPLOAD_ACTION = "com.pulsedrop.app.extra.UPLOAD_ACTION"
    }
}
