package com.pulsedrop.app.ui

import android.app.Application
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
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pulsedrop.app.call.CallEngine
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val serverUrl: String = "https://127.0.0.1:8081",
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

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

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
                serverUrl = "https://$initialIp:${it.port + 1}",
                isPinRequired = settingsPrefs.getBoolean("pinRequired", false),
                pin = settingsPrefs.getString("pin", null) ?: generateRandomPin()
            )
        }
        updateQrCode("https://$initialIp:${_serverState.value.port + 1}")
        startIdleWatchdog()

        pairManager.nameProvider = { _serverState.value.deviceName }
        pairManager.endpointProvider = { _serverState.value.ipAddress to _serverState.value.port }
        pairManager.onPairedMessage = { msg ->
            _clipMessages.update { list ->
                if (list.any { it.id == msg.id }) list else (list + msg).takeLast(CHAT_RETENTION)
            }
        }
        com.pulsedrop.app.call.ProjectionCaptureService.engine = callEngine

        viewModelScope.launch {
            callEngine.toast.collect { t ->
                if (t != null) _uiMessage.value = t
            }
        }
    }

    private fun persistSettings() {
        val s = _serverState.value
        settingsPrefs.edit()
            .putString("deviceName", s.deviceName)
            .putBoolean("pinRequired", s.isPinRequired)
            .putString("pin", s.pin)
            .apply()
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
        _uiMessage.value = if (verified) "Pairing marked verified" else "Pairing marked unverified"
    }

    fun consumePairEvent() {
        pairManager.consumeEvent()
    }

    // ---- calls -----------------------------------------------------------

    fun startCall(pid: String, video: Boolean) {
        // Call signaling answers come back to our HTTP server; with it off the peer's reply
        // has nowhere to land and the caller just rings until it times out.
        if (!_serverState.value.isRunning) {
            _uiMessage.value = "Turn your server on first - calls signal over it"
            return
        }
        callEngine.invite(pid, video)
    }

    fun startGroupCall(pids: List<String>, video: Boolean) {
        if (!_serverState.value.isRunning) {
            _uiMessage.value = "Turn your server on first - calls signal over it"
            return
        }
        if (pids.isEmpty()) {
            _uiMessage.value = "Pair a device first - browsers can only be rung from their chat"
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

    fun attachCallPreview(holder: android.view.SurfaceHolder?) = callEngine.attachPreview(holder)

    fun stopScreenShare() = callEngine.stopScreenShare()

    fun toastCallMessage(message: String) {
        _uiMessage.value = message
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
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                val server = serverInstance ?: continue
                if (!_serverState.value.isRunning) continue
                // The service (or the system) may have stopped the server underneath us;
                // reconcile the UI instead of showing a server that is no longer listening.
                if (!server.isAlive) {
                    serverInstance = null
                    ServerService.hostedServer = null
                    _serverState.update { it.copy(isRunning = false, startedAt = 0L) }
                    _uiMessage.value = "Server stopped"
                    continue
                }
                // Anything live keeps the server awake: an in-flight transfer or a call.
                if (activeTransfers.value.isNotEmpty()) {
                    server.markActivity()
                    continue
                }
                if (callEngine.state.value.active) continue
                val idleMs = System.currentTimeMillis() - server.lastActivityAt
                if (idleMs >= AUTO_OFF_AFTER_MS) {
                    stopServer()
                    _uiMessage.value = "Server turned off after ${AUTO_OFF_AFTER_MS / 60000} minutes of no traffic"
                }
            }
        }
    }

    fun consumeUiMessage() {
        _uiMessage.value = null
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
                        _uiMessage.value = "Connected to ${peer.name}"
                    } else {
                        _uiMessage.value = "No PulseDrop server found at $host:$port"
                        _connectedPeer.value = null
                    }
                }
            } catch (e: Exception) {
                _uiMessage.value = "Could not reach $host:$port"
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
            _uiMessage.value = "File is no longer on this device"
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
                        _uiMessage.value = "Saved ${file.name} to Downloads"
                    } else {
                        _uiMessage.value = "Could not save to Downloads"
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val dest = File(downloadsDir, file.name)
                    file.copyTo(dest, overwrite = true)
                    _uiMessage.value = "Saved ${dest.name} to Downloads"
                }
            } catch (e: Exception) {
                _uiMessage.value = "Save failed: ${e.message}"
            }
        }
    }

    private fun generateRandomPin(): String {
        return (1000..9999).random().toString()
    }

    fun regeneratePin() {
        val newPin = generateRandomPin()
        _serverState.update { it.copy(pin = newPin) }
        persistSettings()
        val state = _serverState.value
        serverInstance?.updatePinSecurity(state.isPinRequired, if (state.isPinRequired) newPin else null)
        if (state.isRunning) {
            discoveryManager.startDiscovery(
                deviceName = state.deviceName,
                currentIp = state.ipAddress,
                serverPort = state.port,
                requiresPin = state.isPinRequired,
                scope = viewModelScope
            )
        }
    }

    fun setPinRequired(required: Boolean) {
        _serverState.update { it.copy(isPinRequired = required) }
        persistSettings()
        val state = _serverState.value
        serverInstance?.updatePinSecurity(required, if (required) state.pin else null)
        if (state.isRunning) {
            discoveryManager.startDiscovery(
                deviceName = state.deviceName,
                currentIp = state.ipAddress,
                serverPort = state.port,
                requiresPin = required,
                scope = viewModelScope
            )
        }
    }

    fun setCustomPin(newPin: String) {
        val safePin = newPin.filter { it.isDigit() }.let {
            if (it.length in 4..6) it else if (it.length > 6) it.take(6) else it.padEnd(4, '0').ifBlank { "1234" }
        }
        _serverState.update { it.copy(pin = safePin) }
        persistSettings()
        val state = _serverState.value
        serverInstance?.updatePinSecurity(state.isPinRequired, if (state.isPinRequired) safePin else null)
        if (state.isRunning) {
            discoveryManager.startDiscovery(
                deviceName = state.deviceName,
                currentIp = state.ipAddress,
                serverPort = state.port,
                requiresPin = state.isPinRequired,
                scope = viewModelScope
            )
        }
    }

    fun setDeviceName(name: String) {
        if (name.isNotBlank()) {
            _serverState.update { it.copy(deviceName = name) }
            persistSettings()
            if (_serverState.value.isRunning) {
                restartServer()
            }
        }
    }

    fun refreshNetwork() {
        val ip = NetworkUtils.getLocalIpAddress()
        val currentPort = _serverState.value.port
        val previous = _serverState.value.ipAddress
        val url = "https://$ip:${currentPort + 1}"
        _serverState.update {
            it.copy(
                ipAddress = ip,
                serverUrl = url
            )
        }
        updateQrCode(url)
        // Discovery announced the address captured at start-up, so after a Wi-Fi switch
        // peers kept dialling the phone's old IP. Re-broadcast, and re-point the notification.
        if (ip != previous && _serverState.value.isRunning) {
            serverInstance?.refreshTls(listOf(ip))
            val state = _serverState.value
            discoveryManager.startDiscovery(
                deviceName = state.deviceName,
                currentIp = ip,
                serverPort = currentPort,
                requiresPin = state.isPinRequired,
                scope = viewModelScope
            )
            ServerService.startService(context, url)
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

            override fun onWebCall(call: WebCall) {
                // The browser can hang up or be dismissed remotely; tear the sheet down then.
                _webCall.value = if (call.state == "ended" || call.state == "declined") null else call
            }
        }

        try {
            val live = ServerService.hostedServer
            val server = if (live != null && live.isAlive) {
                // A previous ViewModel left this server serving; take it over rather than
                // trying to bind the same port and silently failing.
                live.attachListener(listener)
                live
            } else {
                PulseDropServer(
                    port = state.port,
                    context = context,
                    deviceName = state.deviceName,
                    initialPinCode = if (state.isPinRequired) state.pin else null,
                    initialIsPinRequired = state.isPinRequired,
                    initialListener = listener
                )
            }
            server.updatePinSecurity(state.isPinRequired, if (state.isPinRequired) state.pin else null)
            server.setSharedFiles(_sharedFiles.value)
            server.pairManager = pairManager
            server.callEngine = callEngine
            // A browser call that outlived the old ViewModel must re-appear on this one,
            // otherwise the phone forgets a ringing tab while the browser keeps waiting.
            server.webCall?.takeIf { it.state == "ringing" || it.state == "accepted" }?.let {
                _webCall.value = it
            }
            if (!server.isAlive) server.start()
            serverInstance = server
            ServerService.publishServer(server)
            syncWebThreads()

            _serverState.update { it.copy(isRunning = true, startedAt = System.currentTimeMillis()) }
            ServerService.startService(context, state.serverUrl)
            if (chatVisible) startTypingPolling()
            startWebClientWatch()

            discoveryManager.startDiscovery(
                deviceName = state.deviceName,
                currentIp = state.ipAddress,
                serverPort = state.port,
                requiresPin = state.isPinRequired,
                scope = viewModelScope
            )
        } catch (e: Exception) {
            e.printStackTrace()
            serverInstance = null
            ServerService.hostedServer = null
            _serverState.update { it.copy(isRunning = false, startedAt = 0L) }
            _uiMessage.value = "Could not start the server on port ${state.port}: ${e.message}"
        }
    }

    fun stopServer() {
        typingPollJob?.cancel()
        typingPollJob = null
        webClientJob?.cancel()
        webClientJob = null
        _webClients.value = emptyList()
        _typingUsers.value = emptyList()
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
        discoveryManager.stopDiscovery()
        ServerService.stopService(context)
        _serverState.update { it.copy(isRunning = false, startedAt = 0L) }
    }

    private fun restartServer() {
        if (_serverState.value.isRunning) {
            startServer()
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
            _uiMessage.value = "Sent ${meta.first} to $ip — it is in their download list"
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
                        _uiMessage.value = "Could not encrypt ${meta.first}"
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

    private val _webCall = MutableStateFlow<WebCall?>(null)
    val webCall: StateFlow<WebCall?> = _webCall.asStateFlow()

    /** Dial the browser tab of a LAN visitor; the phone owns the ringing UI on both ends. */
    fun callWebClient(ip: String, name: String, video: Boolean) {
        val server = serverInstance ?: run {
            _uiMessage.value = "Turn your server on first - calls signal over it"
            return
        }
        if (_webCall.value != null || callEngine.state.value.active || callEngine.incomingCall.value != null) {
            _uiMessage.value = "Another call is already up"
            return
        }
        server.ringWebClient(ip, name, video)
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
            _uiMessage.value = "A group needs a name and at least two paired devices"
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
                        _uiMessage.value = "Start the server to attach files to chat"
                        return@launch
                    }
                    val attachment = server.storeChatAttachment(uri, name)
                    if (attachment == null) {
                        _uiMessage.value = "Could not attach $name"
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
                    _uiMessage.value = "Sending $name to ${members.size} devices"
                    members.forEach { sendEncryptedFileTo(it, uri, name, size) }
                }
            }
        }
    }

    private suspend fun sendEncryptedFileTo(pairing: PairedDevice, uri: Uri, name: String, size: Long) {
        val cipher = pairManager.prepareEncryptedUpload(pairing, uri)
        if (cipher == null) {
            _uiMessage.value = "Could not encrypt $name"
            return
        }
        if (pairing.originIp.isBlank()) {
            cipher.delete()
            _uiMessage.value = "${pairing.name} has no known address yet — re-pair from the radar"
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
            _uiMessage.value = when {
                ok -> "Sent $name to ${pairing.name}, end-to-end sealed"
                else -> "$name to ${pairing.name} failed: ${err ?: "unknown error"}"
            }
        }
    }

    /** Resolves a chat attachment to the file the server stored for it. */
    fun chatAttachmentFile(attachment: com.pulsedrop.app.model.ChatAttachment): File? =
        serverInstance?.chatAttachmentFile(attachment.id)

    fun sendClipMessage(text: String, threadId: String? = null) {
        if (text.isBlank()) return
        val sender = _serverState.value.deviceName

        fun echo(msg: ClipMessage) {
            _clipMessages.update { list ->
                if (list.any { it.id == msg.id }) list else (list + msg).takeLast(CHAT_RETENTION)
            }
        }

        when {
            threadId == null -> {
                val msg = serverInstance?.addMessage(text, sender) ?: ClipMessage(
                    id = UUID.randomUUID().toString(), text = text, sender = sender
                )
                echo(msg)
            }

            threadId.startsWith("pair:") -> {
                val pid = threadId.removePrefix("pair:")
                val pairing = pairManager.findPairing(pid) ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    val ok = pairManager.sendPairedMessage(pairing, text, threadId)
                    if (ok) {
                        echo(
                            ClipMessage(
                                id = UUID.randomUUID().toString(),
                                text = text, sender = sender,
                                encrypted = true, threadId = threadId
                            )
                        )
                    } else {
                        _uiMessage.value = "${pairing.name} is offline — paired chat needs both phones online"
                    }
                }
            }

            threadId.startsWith("group:") -> {
                val gid = threadId.removePrefix("group:")
                val group = pairManager.findGroup(gid) ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    val delivered = pairManager.sendGroupMessage(group, text)
                    if (delivered > 0) {
                        echo(
                            ClipMessage(
                                id = UUID.randomUUID().toString(),
                                text = text, sender = sender,
                                encrypted = true, threadId = threadId
                            )
                        )
                        if (delivered < group.memberPids.size) {
                            _uiMessage.value = "Reached $delivered of ${group.memberPids.size} members of ${group.name}"
                        }
                    } else {
                        _uiMessage.value = "No member of ${group.name} was reachable"
                    }
                }
            }

            threadId.startsWith("ip:") -> {
                val to = threadId.removePrefix("ip:")
                val msg = serverInstance?.addMessage(
                    text = text, sender = sender, clientIp = _serverState.value.ipAddress,
                    threadId = threadId
                ) ?: ClipMessage(
                    id = UUID.randomUUID().toString(), text = text, sender = sender,
                    clientIp = _serverState.value.ipAddress, threadId = threadId
                )
                echo(msg)
            }
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

    fun openFile(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        val file = File(filePath)
        if (!file.exists()) return

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val ext = MimeTypeMap.getFileExtensionFromUrl(file.name)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
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
    }

    companion object {
        private const val AUTO_OFF_AFTER_MS = 30 * 60 * 1000L

        /** Chat lives for the session — kept until the app is restarted, not written to disk. */
        private const val CHAT_RETENTION = 2000
    }
}
