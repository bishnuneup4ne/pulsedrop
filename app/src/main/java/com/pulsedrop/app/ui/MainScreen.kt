package com.pulsedrop.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.ui.components.PinSecurityDialog
import com.pulsedrop.app.ui.components.PulseHeader
import com.pulsedrop.app.ui.screens.CallOverlay
import com.pulsedrop.app.ui.screens.ChatScreen
import com.pulsedrop.app.ui.screens.CompactCallBar
import com.pulsedrop.app.ui.screens.HistoryScreen
import com.pulsedrop.app.ui.screens.HotspotGuideScreen
import com.pulsedrop.app.ui.screens.RadarScreen
import com.pulsedrop.app.ui.screens.ShareScreen
import com.pulsedrop.app.ui.screens.WebCallScreen

enum class PulseNavTab(val title: String, val icon: ImageVector, val tag: String) {
    SERVE("Server", Icons.Default.Wifi, "tab_serve"),
    RADAR("Radar", Icons.Default.Radar, "tab_radar"),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat, "tab_chat"),
    HISTORY("History", Icons.Default.History, "tab_history"),
    HOTSPOT("Hotspot", Icons.Default.WifiTethering, "tab_hotspot")
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val serverState by viewModel.serverState.collectAsState()
    val sharedFiles by viewModel.sharedFiles.collectAsState()
    val activeTransfersMap by viewModel.activeTransfers.collectAsState()
    val clipMessages by viewModel.clipMessages.collectAsState()
    val qrBitmap by viewModel.qrBitmap.collectAsState()
    val nearbyPeers by viewModel.nearbyPeers.collectAsState()
    val connectedPeer by viewModel.connectedPeer.collectAsState()
    val peerConnecting by viewModel.peerConnecting.collectAsState()
    val typingUsers by viewModel.typingUsers.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()
    val historyList by viewModel.transferHistory.collectAsState()
    val scannedPeers by viewModel.scannedPeers.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val incomingPairRequests by viewModel.incomingPairRequests.collectAsState()
    val pendingOutgoingPair by viewModel.pendingOutgoingPair.collectAsState()
    val radarScanning by viewModel.radarScanning.collectAsState()
    val pairEvent by viewModel.pairEvent.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val incomingCall by viewModel.incomingCall.collectAsState()
    val remoteFrame by viewModel.remoteFrame.collectAsState()
    val remoteFrames by viewModel.remoteFrames.collectAsState()
    val chatGroups by viewModel.groups.collectAsState()
    val webCall by viewModel.webCall.collectAsState()
    val webClients by viewModel.webClients.collectAsState()
    val lastReadAt by viewModel.lastReadAt.collectAsState()
    val updateInfo by viewModel.updateManager.update.collectAsState()
    val updateProgress by viewModel.updateManager.progress.collectAsState()
    val updateApk by viewModel.updateManager.downloadedApk.collectAsState()
    val colorScheme = MaterialTheme.colorScheme

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableStateOf(PulseNavTab.SERVE) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var selectedThreadId by remember { mutableStateOf<String?>(null) }
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showShareConsent by remember { mutableStateOf(false) }
    var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }

    var pendingChatAttachThread by remember { mutableStateOf<String?>(null) }
    val chatAttachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val thread = pendingChatAttachThread
        if (uri != null) viewModel.sendFileToThread(if (thread == "room") null else thread, uri)
        pendingChatAttachThread = null
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingCallAction
        pendingCallAction = null
        if (action != null && result[Manifest.permission.RECORD_AUDIO] == true) action()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the server's foreground notification becomes visible once granted */ }

    // Without this, Android 13+ silently hides the server's persistent notification, so
    // the user sees no sign the LAN server is live and has no Stop control.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            viewModel.startScreenShare(result.resultCode, data)
        } else {
            viewModel.cancelScreenShare()
        }
    }

    fun missingCallPermissions(video: Boolean): List<String> {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) needed.add(Manifest.permission.CAMERA)
        return needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun withCallPermissions(video: Boolean, action: () -> Unit) {
        val missing = missingCallPermissions(video)
        if (missing.isEmpty()) action()
        else {
            pendingCallAction = action
            callPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun requestScreenShare() {
        if (callState.sharing) {
            viewModel.stopScreenShare()
        } else if (!callState.connected) {
            viewModel.toastCallMessage("Connect the call before sharing your screen")
        } else {
            showShareConsent = true
        }
    }

    LaunchedEffect(uiMessage) {
        val msg = uiMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.consumeUiMessage()
        }
    }

    LaunchedEffect(pairEvent) {
        val ev = pairEvent
        if (ev != null) {
            snackbarHostState.showSnackbar(ev, duration = SnackbarDuration.Short)
            viewModel.consumePairEvent()
        }
    }

    // Drive the LAN sweep only while the radar tab is open; multicast discovery keeps running globally.
    LaunchedEffect(selectedTab) {
        if (selectedTab == PulseNavTab.RADAR) viewModel.startRadarScan()
        else viewModel.stopRadarScan()
        viewModel.setChatVisible(selectedTab == PulseNavTab.CHAT)
    }

    // Merge broadcast + scanned peers, dedupe by ip:port, prefer the PulseDrop app entry.
    val displayPeers = remember(nearbyPeers, scannedPeers, webClients) {
        val combined = mutableListOf<PeerDevice>()
        val seen = HashMap<String, Int>()
        fun key(p: PeerDevice) = "${p.ip}:${p.port}"
        nearbyPeers.forEach { p ->
            val k = key(p)
            val idx = seen[k]
            if (idx == null) { seen[k] = combined.size; combined.add(p) }
        }
        scannedPeers.forEach { p ->
            val k = key(p)
            val idx = seen[k]
            if (idx == null) { seen[k] = combined.size; combined.add(p) }
            else if (p.appKind == "LocalDrop" && combined[idx].appKind != "LocalDrop") {
                combined[idx] = p
            }
        }
        // A desktop with the web page open is a device too — it just cannot be dialled back.
        webClients.forEach { c ->
            if (combined.any { it.ip == c.ip && it.appKind == "LocalDrop" }) return@forEach
            val k = "${c.ip}:0"
            if (!seen.containsKey(k)) {
                seen[k] = combined.size
                combined.add(
                    PeerDevice(
                        id = "web:${c.ip}",
                        name = c.name,
                        ip = c.ip,
                        port = 0,
                        requiresPin = false,
                        lastSeen = c.lastSeen,
                        appKind = "Browser"
                    )
                )
            }
        }
        combined
    }

    BackHandler(enabled = callState.active) {
        viewModel.hangUpCall()
    }

    BackHandler(enabled = selectedTab != PulseNavTab.SERVE && !callState.active) {
        selectedTab = PulseNavTab.SERVE
    }

    // Declared last so it wins over the tab handler: back leaves a conversation for the inbox.
    BackHandler(enabled = selectedThreadId != null && !callState.active) {
        selectedThreadId = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                PulseHeader(
                    isRunning = serverState.isRunning,
                    deviceName = serverState.deviceName,
                    onToggleServer = { viewModel.toggleServer() },
                    onRefresh = { viewModel.refreshNetwork() },
                    modifier = Modifier.statusBarsPadding()
                )
            },
            bottomBar = {
                PulseBottomBar(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
            Crossfade(targetState = selectedTab, label = "tab_crossfade") { tab ->
                when (tab) {
                    PulseNavTab.SERVE -> ShareScreen(
                        serverState = serverState,
                        qrBitmap = qrBitmap,
                        sharedFiles = sharedFiles,
                        activeTransfers = activeTransfersMap.values.toList(),
                        clipMessages = clipMessages,
                        pairedCount = pairedDevices.size,
                        peersCount = displayPeers.size,
                        onToggleServer = { viewModel.toggleServer() },
                        onOpenSecuritySettings = { showSecurityDialog = true },
                        onAddFiles = { uris -> viewModel.addFiles(uris) },
                        onRemoveFile = { id -> viewModel.removeSharedFile(id) },
                        onClearFiles = { viewModel.clearSharedFiles() },
                        onSendClip = { text -> viewModel.sendClipMessage(text) },
                        appVersion = BuildConfig.VERSION_NAME,
                        onUpdateCheck = {
                            dismissedUpdateVersion = null
                            viewModel.updateManager.check(auto = false)
                        }
                    )

                    PulseNavTab.RADAR -> RadarScreen(
                        nearbyPeers = displayPeers,
                        connectedPeer = connectedPeer,
                        peerConnecting = peerConnecting,
                        pairedDevices = pairedDevices,
                        pendingOutgoing = pendingOutgoingPair,
                        scanning = radarScanning,
                        onSendFilesToPeer = { peer, uris, peerPin ->
                            if (peer.appKind == "Browser") {
                                // No listener on a browser tab: host the file for that address alone.
                                uris.forEach { uri -> viewModel.sendFileToBrowser(peer.ip, uri) }
                            } else {
                                viewModel.sendFilesToPeer(peer, uris, peerPin) { _, _ -> }
                            }
                        },
                        onConnectToAddress = { address -> viewModel.connectToPeer(address) },
                        onDisconnectPeer = { viewModel.disconnectPeer() },
                        onPair = { peer -> viewModel.requestPairing(peer) },
                        onCancelPairRequest = { viewModel.cancelPairingRequest() },
                        onUnpair = { pid -> viewModel.unpair(pid) },
                        safetyCodeOf = { pid -> viewModel.pairSafetyCode(pid) },
                        onToggleVerified = { pid, verified -> viewModel.setPairVerified(pid, verified) }
                    )

                    PulseNavTab.CHAT -> ChatScreen(
                        messages = clipMessages,
                        myName = serverState.deviceName,
                        typingUsers = typingUsers,
                        pairedDevices = pairedDevices,
                        groups = chatGroups,
                        lanPeers = displayPeers,
                        lastReadAt = lastReadAt,
                        selectedThreadId = selectedThreadId,
                        onSelectThread = { selectedThreadId = it },
                        onStartCall = { pid, video ->
                            withCallPermissions(video) { viewModel.startCall(pid, video) }
                        },
                        onStartGroupCall = { pids, video ->
                            withCallPermissions(video) { viewModel.startGroupCall(pids, video) }
                        },
                        onStartBrowserCall = { ip, name, video ->
                            viewModel.callWebClient(ip, name, video)
                        },
                        onSend = { text, threadId ->
                            viewModel.sendClipMessage(text, threadId?.takeIf { it != "room" })
                        },
                        onTyping = { active -> viewModel.sendTyping(active) },
                        onAttach = { threadId ->
                            pendingChatAttachThread = threadId
                            chatAttachLauncher.launch(arrayOf("*/*"))
                        },
                        resolveAttachment = { attachment -> viewModel.chatAttachmentFile(attachment) },
                        onCreateGroup = { name, pids -> viewModel.createGroup(name, pids) },
                        onDeleteGroup = { id -> viewModel.deleteGroup(id) },
                        onMarkRead = { threadId ->
                            viewModel.markThreadRead(threadId?.takeIf { it != "room" })
                        }
                    )

                    PulseNavTab.HISTORY -> HistoryScreen(
                        historyList = historyList,
                        onOpenFile = { path -> viewModel.openFile(path) },
                        onShareFile = { path -> viewModel.shareFile(path) },
                        onSaveToDownloads = { path -> viewModel.saveToDownloads(path) },
                        onDeleteItem = { id -> viewModel.deleteHistoryItem(id) },
                        onClearAll = { viewModel.clearHistory() }
                    )

                    PulseNavTab.HOTSPOT -> HotspotGuideScreen(
                        serverState = serverState
                    )
                }
            }
            }
        }

        var callMinimized by remember { mutableStateOf(false) }
        LaunchedEffect(callState.active) {
            if (!callState.active) callMinimized = false
        }
        if (callState.active) {
            if (callMinimized) {
                CompactCallBar(
                    state = callState,
                    onExpand = { callMinimized = false },
                    onHangUp = { viewModel.hangUpCall() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 340.dp)
                        .padding(horizontal = 16.dp, vertical = 96.dp)
                )
            } else {
                CallOverlay(
                    state = callState,
                    remoteFrame = remoteFrame,
                    remoteFrames = remoteFrames,
                    onToggleMute = { viewModel.toggleCallMute() },
                    onToggleCamera = {
                        // Turning the camera on mid-call needs the same permission as a video call.
                        val missing = if (callState.cameraOn) emptyList() else missingCallPermissions(true)
                        if (missing.isEmpty()) viewModel.toggleCallCamera()
                        else {
                            pendingCallAction = { viewModel.toggleCallCamera() }
                            callPermissionLauncher.launch(missing.toTypedArray())
                        }
                    },
                    onToggleScreenShare = { requestScreenShare() },
                    onHangUp = { viewModel.hangUpCall() },
                    onAttachPreview = { viewModel.attachCallPreview(it) },
                    onSwitchCamera = { viewModel.switchCallCamera() },
                    onMinimize = { callMinimized = true }
                )
            }
        }

        val browserCall = webCall
        if (browserCall != null && !callState.active) {
            val leaveBrowserCall: () -> Unit = {
                // Cancelling our own dial must clear the shared record, not "decline" it.
                if (browserCall.outgoing || browserCall.state == "accepted") viewModel.endWebCall()
                else viewModel.answerWebCall(false)
            }
            BackHandler(onBack = leaveBrowserCall)
            WebCallScreen(
                call = browserCall,
                onAccept = {
                    withCallPermissions(browserCall.video) { viewModel.answerWebCall(true) }
                },
                onDecline = leaveBrowserCall,
                onHangUp = { viewModel.endWebCall() }
            )
        }
    }

    val incoming = incomingCall
    if (incoming != null) {
        AlertDialog(
            onDismissRequest = { /* must answer explicitly */ },
            icon = {
                Icon(
                    imageVector = if (incoming.video) Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = null,
                    tint = colorScheme.primary
                )
            },
            title = {
                Text(
                    if (incoming.confId.isNotBlank()) "Incoming group call"
                    else "Incoming ${if (incoming.video) "video" else "voice"} call"
                )
            },
            text = {
                Text(
                    if (incoming.confId.isNotBlank()) {
                        "${incoming.name} has started a group call. Everyone you have both paired with " +
                                "is being invited; the call bridges through their device."
                    } else {
                        "${incoming.name} is calling you over your encrypted pairing. " +
                                "Accept to connect audio${if (incoming.video) ", video and screen sharing" else ""}."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        withCallPermissions(incoming.video) { viewModel.respondToCall(true) }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("accept_call_btn")
                ) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.respondToCall(false) },
                    modifier = Modifier.testTag("decline_call_btn")
                ) {
                    Text("Decline", color = colorScheme.error)
                }
            }
        )
    }

    val pendingUpdate = updateInfo
    if (pendingUpdate != null && dismissedUpdateVersion != pendingUpdate.versionName) {
        val um = viewModel.updateManager
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Update to ${pendingUpdate.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = pendingUpdate.releaseName.ifBlank { "PulseDrop ${pendingUpdate.versionName}" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (pendingUpdate.notes.isNotBlank()) {
                        Text(
                            text = pendingUpdate.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (pendingUpdate.sizeBytes > 0) {
                        Text(
                            text = "Download size: %.1f MB".format(pendingUpdate.sizeBytes / 1048576.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val pct = updateProgress
                    if (pct != null) {
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Downloading… ${(pct * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (updateApk != null) {
                        Text(
                            text = "Downloaded. Tap Install to finish.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                if (updateApk != null) {
                    Button(
                        onClick = {
                            if (!um.canInstall()) {
                                context.startActivity(um.installPermissionIntent())
                            } else {
                                um.install()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("install_update_btn")
                    ) { Text("Install") }
                } else {
                    Button(
                        onClick = { um.download() },
                        enabled = updateProgress == null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("download_update_btn")
                    ) { Text(if (updateProgress == null) "Download" else "Downloading…") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismissedUpdateVersion = pendingUpdate.versionName
                        um.dismiss()
                    },
                    modifier = Modifier.testTag("dismiss_update_btn")
                ) { Text("Not now") }
            }
        )
    }

    // Pairing prompts must reach the user on ANY tab - keeping them inside the radar screen
    // meant a device sitting on Server/Chat never saw the invite and the pair half-completed.
    val pairInvite = incomingPairRequests.firstOrNull()
    if (pairInvite != null) {
        AlertDialog(
            onDismissRequest = { viewModel.declinePairing(pairInvite) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = colorScheme.primary
                )
            },
            title = { Text("Pairing request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${pairInvite.name} wants to pair with you.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "From ${pairInvite.myIp}:${pairInvite.myPort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.primaryContainer.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Safety code",
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = pairInvite.safetyCode,
                                style = MaterialTheme.typography.headlineMedium,
                                color = colorScheme.primary
                            )
                            Text(
                                text = "Say this code out loud to the other person. If it matches on both screens, you are paired with each other — not someone else.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptPairing(pairInvite) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("accept_pair_btn")
                ) {
                    Text("Accept & pair", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.declinePairing(pairInvite) },
                    modifier = Modifier.testTag("decline_pair_btn")
                ) {
                    Text("Decline", color = colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }

    if (showShareConsent) {
        AlertDialog(
            onDismissRequest = { showShareConsent = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.ScreenShare,
                    contentDescription = null,
                    tint = colorScheme.primary
                )
            },
            title = { Text("Share your screen with ${callState.peerName.ifBlank { "this device" }}?") },
            text = {
                Text(
                    "Everything on your screen goes to the other side while the call is up - " +
                            "including messages, photos and codes that pop up. Android will ask you to " +
                            "confirm once more. You can stop at any time with the same button."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShareConsent = false
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as? MediaProjectionManager
                        if (mgr != null) {
                            viewModel.armScreenShare()
                            projectionLauncher.launch(mgr.createScreenCaptureIntent())
                        } else viewModel.toastCallMessage("Screen capture is not available on this device")
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("confirm_screen_share_btn")
                ) {
                    Text("Share screen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showShareConsent = false },
                    modifier = Modifier.testTag("cancel_screen_share_btn")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(pairedDevices, chatGroups) {
        val tid = selectedThreadId ?: return@LaunchedEffect
        val alive = when {
            tid.startsWith("pair:") -> pairedDevices.any { "pair:${it.pid}" == tid }
            tid.startsWith("group:") -> chatGroups.any { "group:${it.id}" == tid }
            else -> true
        }
        if (!alive) selectedThreadId = null
    }

    if (showSecurityDialog) {
        PinSecurityDialog(
            isPinRequired = serverState.isPinRequired,
            currentPin = serverState.pin,
            onDismiss = { showSecurityDialog = false },
            onSave = { required, pin ->
                viewModel.setPinRequired(required)
                viewModel.setCustomPin(pin)
            },
            onRegeneratePin = { viewModel.regeneratePin() }
        )
    }
}

@Composable
private fun PulseBottomBar(
    selectedTab: PulseNavTab,
    onSelect: (PulseNavTab) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    NavigationBar(
        modifier = Modifier.testTag("pulse_bottom_tabs"),
        containerColor = colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        PulseNavTab.values().forEach { tab ->
            val selected = tab == selectedTab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.testTag(tab.tag),
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title
                    )
                },
                label = {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                alwaysShowLabel = true,
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                    indicatorColor = colorScheme.primaryContainer,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
