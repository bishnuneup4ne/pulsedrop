package com.pulsedrop.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import com.pulsedrop.app.ui.components.PulseDrawerContent
import com.pulsedrop.app.ui.components.PulseNavFrame
import com.pulsedrop.app.ui.components.PulseSection
import com.pulsedrop.app.ui.components.PulseStatePanel
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.pulsedrop.app.call.formatCallLength
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.ui.components.PinSecurityDialog
import com.pulsedrop.app.ui.components.PulseBanner
import com.pulsedrop.app.ui.components.PulseBannerStack
import com.pulsedrop.app.ui.components.PulseHeader
import com.pulsedrop.app.ui.screens.CallOverlay
import com.pulsedrop.app.ui.screens.ChatScreen
import com.pulsedrop.app.ui.screens.CompactCallBar
import com.pulsedrop.app.ui.screens.HotspotGuideScreen
import com.pulsedrop.app.ui.screens.IncomingCallCard
import com.pulsedrop.app.ui.screens.IncomingCallStage
import android.net.Uri
import com.pulsedrop.app.ui.screens.DevicesScreen
import com.pulsedrop.app.ui.screens.ReceiveScreen
import com.pulsedrop.app.ui.screens.SendScreen
import com.pulsedrop.app.ui.screens.SettingsSheet
import com.pulsedrop.app.ui.screens.TransfersScreen
import com.pulsedrop.app.ui.screens.TransferDetailsScreen
import com.pulsedrop.app.ui.screens.AboutScreen
import com.pulsedrop.app.ui.screens.HelpScreen
import com.pulsedrop.app.ui.screens.NotificationScreen
import com.pulsedrop.app.ui.screens.HomeScreen
import com.pulsedrop.app.ui.screens.WebCallScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
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
    val downloadCount by viewModel.updateManager.downloads.collectAsState()
    val relayHostName by viewModel.relayHostName.collectAsState()
    val relayHost by viewModel.relayHost.collectAsState()
    val pendingUploads by viewModel.pendingUploads.collectAsState()
    val updateStatus by viewModel.updateManager.statusMessage.collectAsState()
    val colorScheme = MaterialTheme.colorScheme

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedSection by rememberSaveable { mutableStateOf(PulseSection.HOME) }
    // The send selection lives here, not on the Send screen, so visiting Devices or Home
    // mid-arrangement does not quietly throw away an hour of file picking.
    var sendSelectionUris by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var selectedThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    // Which transfer's details page is open inside the Transfers section; survives process death
    // so back-ing out of a killed app still lands on the same record, or its honest "missing" twin.
    var transferDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showShareConsent by remember { mutableStateOf(false) }
    var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }
    // Stopping the server kicks every connected device, so the press asks first.
    var confirmStopServer by remember { mutableStateOf(false) }
    val requestToggleServer: () -> Unit = {
        if (serverState.isRunning) confirmStopServer = true else viewModel.toggleServer()
    }

    var pendingChatAttachThread by remember { mutableStateOf<String?>(null) }
    val chatAttachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val thread = pendingChatAttachThread
        if (!uris.isNullOrEmpty()) viewModel.sendFilesToThread(if (thread == "room") null else thread, uris)
        pendingChatAttachThread = null
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingCallAction
        pendingCallAction = null
        if (action != null && result[Manifest.permission.RECORD_AUDIO] == true) action()
    }

    var notifPermTick by remember { mutableStateOf(0) }
    val notifPermissionGranted = remember(notifPermTick) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the answer is re-read from the permission itself, not from this callback */ notifPermTick++ }

    // Without this, Android 13+ silently hides the server's persistent notification, so
    // the user sees no sign the server is live and has no Stop control.
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

    // One collector per event; showSnackbar suspends until a message is dismissed, so events
    // arriving in a burst queue up in order instead of overwriting each other.
    LaunchedEffect(Unit) {
        PulseEvents.flow.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(pairEvent) {
        val ev = pairEvent
        if (ev != null) {
            snackbarHostState.showSnackbar(ev, duration = SnackbarDuration.Short)
            viewModel.consumePairEvent()
        }
    }

    // Drive the LAN sweep only while the Devices section is open; multicast discovery keeps running globally.
    LaunchedEffect(selectedSection) {
        if (selectedSection == PulseSection.DEVICES || selectedSection == PulseSection.SEND_FILES)
            viewModel.startRadarScan()
        else viewModel.stopRadarScan()
        viewModel.setChatVisible(selectedSection == PulseSection.MESSAGES)
    }

    // Notification taps carry a section name; both file receipts and pending decisions land
    // on the Receive page, where the files and the waiting queue actually live.
    LaunchedEffect(Unit) {
        val target = (context as? android.app.Activity)?.intent
            ?.getStringExtra("com.pulsedrop.app.SECTION")
        if (target == "my_files" || target == "receive") selectedSection = PulseSection.RECEIVE_FILES
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

    BackHandler(enabled = selectedSection != PulseSection.HOME && !callState.active) {
        selectedSection = PulseSection.HOME
    }

    // Declared last so it wins over the tab handler: back leaves a conversation for the inbox.
    BackHandler(enabled = selectedThreadId != null && !callState.active) {
        selectedThreadId = null
    }

    // Back closes the details page before it leaves the Transfers section.
    BackHandler(enabled = transferDetailsId != null && !callState.active) {
        transferDetailsId = null
    }

    // Badges carry real counts only: pending requests, unread threads, live transfers.
    val unreadThreads = remember(clipMessages, lastReadAt, serverState.deviceName) {
        clipMessages
            .filter { !it.deleted && !it.sender.equals(serverState.deviceName, ignoreCase = true) }
            .groupBy { it.threadId ?: "room" }
            .count { (thread, msgs) ->
                (msgs.maxOfOrNull { it.timestamp } ?: 0L) > (lastReadAt[thread] ?: 0L)
            }
    }
    val transfersInFlight = activeTransfersMap.values.count { it.status == TransferStatus.IN_PROGRESS }
    val navBadges = mapOf(
        PulseSection.DEVICES to incomingPairRequests.size,
        PulseSection.MESSAGES to unreadThreads,
        // Files parked for a decision wait on the phone — the badge is where they hide.
        PulseSection.RECEIVE_FILES to pendingUploads.size,
        // Finished rows linger in the map for the session; the badge counts only real work.
        PulseSection.TRANSFERS to transfersInFlight
    )
    val transfersLive = transfersInFlight > 0

    Box(modifier = Modifier.fillMaxSize()) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        var sidebarOpen by rememberSaveable { mutableStateOf(true) }
        val navScope = rememberCoroutineScope()
        fun selectSection(section: PulseSection) {
            selectedSection = section
            // Section taps go to the section's own front door, not to a record the user
            // was reading last time. Callers that mean to open details set the id after this.
            transferDetailsId = null
            if (!wide) navScope.launch { drawerState.close() }
        }
        fun toggleNav() {
            if (wide) sidebarOpen = !sidebarOpen
            else navScope.launch { drawerState.open() }
        }
        PulseNavFrame(
            wide = wide,
            drawerState = drawerState,
            sidebarOpen = sidebarOpen,
            drawerContent = {
                PulseDrawerContent(
                    selected = selectedSection,
                    badges = navBadges,
                    liveTransfers = transfersLive,
                    isRunning = serverState.isRunning,
                    deviceName = serverState.deviceName,
                    onSelect = { selectSection(it) }
                )
            }
        ) {
          Scaffold(
            containerColor = colorScheme.background,
            snackbarHost = {
                // Announce every transient message: transfers and connections change while you work.
                SnackbarHost(
                    snackbarHostState,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                )
            },
            topBar = {
                PulseHeader(
                    title = selectedSection.title,
                    isRunning = serverState.isRunning,
                    onOpenDrawer = { toggleNav() },
                    onToggleServer = requestToggleServer,
                    modifier = Modifier.statusBarsPadding()
                )
            }
        ) { paddingValues ->
                // Every section pulls: the lists are live queries, and a swipe is how people ask
                // "is that really up to date?" without hunting for a button.
                val ptrState = rememberPullToRefreshState()
                var pulling by remember { mutableStateOf(false) }
                PullToRefreshBox(
                    isRefreshing = pulling,
                    onRefresh = {
                        if (!pulling) {
                            pulling = true
                            viewModel.pullToRefresh(selectedSection == PulseSection.DEVICES) { pulling = false }
                        }
                    },
                    state = ptrState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 880.dp)
                ) {
            Crossfade(targetState = selectedSection, label = "tab_crossfade") { section ->
                when (section) {
                    PulseSection.HOME -> HomeScreen(
                        serverState = serverState,
                        qrBitmap = qrBitmap,
                        sharedFiles = sharedFiles,
                        activeTransfers = activeTransfersMap.values.toList(),
                        history = historyList,
                        pairedDevices = pairedDevices,
                        peersCount = displayPeers.size,
                        pendingUploads = pendingUploads,
                        incomingPairCount = incomingPairRequests.size,
                        relayHostName = relayHostName,
                        callInProgress = callState.active && callState.connected,
                        isSharingScreen = callState.sharing,
                        updateInfo = updateInfo,
                        onToggleServer = requestToggleServer,
                        onAddFiles = { uris -> viewModel.addFiles(uris) },
                        onRemoveFile = { id -> viewModel.removeSharedFile(id) },
                        onClearFiles = { viewModel.clearSharedFiles() },
                        onAcceptUpload = { viewModel.acceptPendingUpload(it) },
                        onDeclineUpload = { viewModel.declinePendingUpload(it) },
                        onToggleScreenShare = { requestScreenShare() },
                        onUpdateCheck = {
                            dismissedUpdateVersion = null
                            viewModel.updateManager.check(auto = false)
                        },
                        onNavigate = { selectSection(it) },
                        onOpenTransferDetails = { id ->
                            selectSection(PulseSection.TRANSFERS)
                            transferDetailsId = id
                        }
                    )

                    PulseSection.DEVICES -> DevicesScreen(
                        nearbyPeers = displayPeers,
                        connectedPeer = connectedPeer,
                        peerConnecting = peerConnecting,
                        pairedDevices = pairedDevices,
                        pendingOutgoing = pendingOutgoingPair,
                        incomingPairCount = incomingPairRequests.size,
                        scanning = radarScanning,
                        activeTransfers = activeTransfersMap.values.toList(),
                        history = historyList,
                        onRescan = { viewModel.startRadarScan() },
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
                        onToggleVerified = { pid, verified -> viewModel.setPairVerified(pid, verified) },
                        onOpenTransfers = { selectSection(PulseSection.TRANSFERS) }
                    )

                    PulseSection.SEND_FILES -> SendScreen(
                        selectedFiles = sendSelectionUris.map(Uri::parse),
                        onSelectedFilesChange = { uris -> sendSelectionUris = uris.map { it.toString() } },
                        nearbyPeers = displayPeers,
                        connectedPeer = connectedPeer,
                        pairedDevices = pairedDevices,
                        scanning = radarScanning,
                        onSendToPeer = { peer, uris, pin ->
                            viewModel.sendFilesToPeer(peer, uris, pin) { _, _ -> }
                        },
                        onSendToBrowser = { ip, uris -> uris.forEach { uri -> viewModel.sendFileToBrowser(ip, uri) } },
                        onOpenDevices = { selectSection(PulseSection.DEVICES) },
                        onOpenTransfers = { selectSection(PulseSection.TRANSFERS) }
                    )

                    PulseSection.RECEIVE_FILES -> {
                        val receiveDir = viewModel.receiveDir
                        val freeSpace = remember(receiveDir) {
                            val root = java.io.File(receiveDir).parentFile ?: java.io.File("/")
                            try {
                                android.os.StatFs(root.absolutePath).availableBytes
                            } catch (_: Exception) { -1L }
                        }
                        ReceiveScreen(
                            serverRunning = serverState.isRunning,
                            autoAccept = serverState.autoAcceptTransfers,
                            pendingUploads = pendingUploads,
                            incomingActive = activeTransfersMap.values
                                .filter { it.direction == com.pulsedrop.app.model.TransferDirection.INCOMING },
                            receivedHistory = historyList,
                            receiveDir = receiveDir,
                            freeSpaceBytes = freeSpace,
                            onAccept = { viewModel.acceptPendingUpload(it) },
                            onDecline = { viewModel.declinePendingUpload(it) },
                            onSetAutoAccept = { viewModel.setAutoAcceptTransfers(it) },
                            onOpenFile = { viewModel.openFile(it) },
                            onRemoveHistory = { viewModel.deleteHistoryItem(it) },
                            onToggleServer = requestToggleServer,
                            onOpenTransfers = { selectSection(PulseSection.TRANSFERS) }
                        )
                    }

                    PulseSection.MESSAGES -> ChatScreen(
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
                        onCallEveryone = { pids, ip, name, video ->
                            withCallPermissions(video) {
                                viewModel.callEveryone(pids, ip, name, video)
                            }
                        },
                        onSend = { text, threadId, reply ->
                            viewModel.sendClipMessage(
                                text,
                                threadId?.takeIf { it != "room" },
                                reply
                            )
                        },
                        onDelete = { id, forEveryone -> viewModel.deleteMessage(id, forEveryone) },
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

                    PulseSection.TRANSFERS -> {
                        val detailsId = transferDetailsId
                        if (detailsId != null) {
                            TransferDetailsScreen(
                                transferId = detailsId,
                                activeTransfers = activeTransfersMap.values.toList(),
                                history = historyList,
                                pendingUploads = pendingUploads,
                                pairedDevices = pairedDevices,
                                nearbyPeers = displayPeers,
                                onBack = { transferDetailsId = null },
                                onOpenFile = { path -> viewModel.openFile(path) },
                                onSaveToDownloads = { path -> viewModel.saveToDownloads(path) },
                                onAcceptPending = { viewModel.acceptPendingUpload(it) },
                                onDeclinePending = { viewModel.declinePendingUpload(it) },
                                onDeleteHistory = { viewModel.deleteHistoryItem(it) },
                                onDismissLive = { viewModel.dismissFinishedTransfer(it) },
                                onOpenSend = {
                                    selectSection(PulseSection.SEND_FILES)
                                },
                                onCopyText = { text ->
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("Transfer details", text))
                                }
                            )
                        } else {
                            TransfersScreen(
                                activeTransfers = activeTransfersMap.values.toList(),
                                history = historyList,
                                pendingUploads = pendingUploads,
                                onOpenFile = { path -> viewModel.openFile(path) },
                                onShareFile = { path -> viewModel.shareFile(path) },
                                onSaveToDownloads = { path -> viewModel.saveToDownloads(path) },
                                onDeleteHistory = { id -> viewModel.deleteHistoryItem(id) },
                                onDismissLive = { id -> viewModel.dismissFinishedTransfer(id) },
                                onClearAll = { viewModel.clearHistory() },
                                onOpenSend = { selectSection(PulseSection.SEND_FILES) },
                                onOpenReceive = { selectSection(PulseSection.RECEIVE_FILES) },
                                onOpenDetails = { id -> transferDetailsId = id }
                            )
                        }
                    }

                    PulseSection.HELP -> {
                        var guideOpen by remember { mutableStateOf(false) }
                        if (guideOpen) {
                            HotspotGuideScreen(serverState = serverState)
                        } else {
                            HelpScreen(
                                receiveDir = viewModel.receiveDir,
                                onOpenHotspotGuide = { guideOpen = true },
                                onOpenNotifications = { selectSection(PulseSection.NOTIFICATIONS) },
                                onOpenGithubIssues = {
                                    try {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://github.com/bishnuneup4ne/pulsedrop/issues/new")
                                            )
                                        )
                                    } catch (_: Exception) {
                                        com.pulsedrop.app.ui.PulseEvents.post("No browser available to open GitHub")
                                    }
                                },
                                onCheckUpdate = { viewModel.updateManager.check(auto = false) }
                            )
                        }
                    }

                    PulseSection.SETTINGS -> SettingsSheet(
                        serverState = serverState,
                        pendingUploads = pendingUploads,
                        pairedCount = pairedDevices.size,
                        updateInfo = updateInfo,
                        updateStatus = updateStatus,
                        onAutoAcceptChange = { viewModel.setAutoAcceptTransfers(it) },
                        onAcceptUpload = { viewModel.acceptPendingUpload(it) },
                        onDeclineUpload = { viewModel.declinePendingUpload(it) },
                        onCheckUpdate = { viewModel.updateManager.check(auto = false) },
                        onSetPinRequired = { viewModel.setPinRequired(it) },
                        onRegeneratePin = { viewModel.regeneratePin() },
                        onOpenDevices = { selectSection(PulseSection.DEVICES) },
                        onOpenServer = { selectSection(PulseSection.HOME) }
                    )

                    PulseSection.ABOUT -> AboutScreen(
                        updateInfo = updateInfo,
                        updateStatus = updateStatus,
                        onCheckUpdate = { viewModel.updateManager.check(auto = false) },
                        onOpenLink = { url ->
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    )
                                )
                            } catch (_: Exception) {
                                com.pulsedrop.app.ui.PulseEvents.post("No browser available to open this link")
                            }
                        }
                    )


                    PulseSection.NOTIFICATIONS -> NotificationScreen(
                        permissionGranted = notifPermissionGranted,
                        onRequestPermission = {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onOpenSystemSettings = {
                            try {
                                context.startActivity(
                                    android.content.Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                                        .putExtra(
                                            "android.provider.extra.APP_PACKAGE",
                                            context.packageName
                                        )
                                )
                            } catch (_: Exception) {
                                com.pulsedrop.app.ui.PulseEvents.post("This phone does not expose notification settings")
                            }
                        }
                    )
                }
                }
                }
                }
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

        // ---- in-app heads-up feed ------------------------------------------
        // The system shade covers calls and the server while the app is closed; this
        // covers them while it is open, on any tab. Tapping a banner jumps to its
        // subject — back into a minimized call, the server card, or history.
        val bannerScope = rememberCoroutineScope()
        val banners = remember { mutableStateListOf<PulseBanner>() }

        fun dismissBanner(entry: PulseBanner) {
            banners.replaceAll { if (it.id == entry.id) it.copy(visible = false) else it }
            bannerScope.launch {
                delay(300)
                banners.removeAll { it.id == entry.id }
            }
        }

        fun pushBanner(
            group: String,
            title: String,
            text: String,
            icon: ImageVector,
            action: String? = null,
            onTap: (() -> Unit)? = null
        ) {
            banners.removeAll { it.group == group }
            val entry = PulseBanner(
                id = "$group-${System.nanoTime()}",
                group = group,
                title = title,
                text = text,
                icon = icon,
                action = action,
                onTap = onTap
            )
            banners.add(entry)
            bannerScope.launch {
                delay(7_000)
                dismissBanner(entry)
            }
        }

        var lastRunning by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(serverState.isRunning) {
            val prev = lastRunning
            val now = serverState.isRunning
            lastRunning = now
            if (prev == now) return@LaunchedEffect
            if (now) {
                pushBanner(
                    group = "server",
                    title = "Server started",
                    text = "${serverState.serverUrl} — devices on this network can connect now.",
                    icon = Icons.Default.WifiTethering,
                    action = "Open server screen",
                    onTap = { selectedSection = PulseSection.HOME }
                )
            } else {
                pushBanner(
                    group = "server",
                    title = "Server stopped",
                    text = "This phone is no longer reachable from the network.",
                    icon = Icons.Default.Wifi
                )
            }
        }

        var lastCallKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(callState.active, callState.connected, callState.peerName) {
            val key = when {
                callState.active && callState.connected -> "connected"
                callState.active -> "dialing"
                else -> null
            }
            val prev = lastCallKey
            lastCallKey = key
            if (key == prev) return@LaunchedEffect
            // Only announce when the full call UI is NOT already on screen; minimized,
            // the banner is the one glanceable signal, and tapping it maximizes again.
            if (key != null && callMinimized) {
                pushBanner(
                    group = "call",
                    title = if (key == "connected") "In call with ${callState.peerName}"
                    else "Calling ${callState.peerName}",
                    text = if (key == "connected")
                        if (callState.video) "Video call connected" else "Voice call connected"
                    else callState.status.ifBlank { "Ringing…" },
                    icon = Icons.Default.Call,
                    action = "Back to call",
                    onTap = { callMinimized = false }
                )
            } else if (key == null && prev != null) {
                // The length is the one thing a finished call is asked for afterwards.
                val rec = viewModel.callEngine.lastCall.value
                pushBanner(
                    group = "call",
                    title = if (rec != null) "Call ended · ${formatCallLength(rec.seconds)}"
                    else "Call ended",
                    text = rec?.let {
                        buildString {
                            append(it.peerName)
                            if (it.video) append(" · video")
                            if (it.conference) append(" · group")
                            if (it.reason.isNotBlank()) append(" · ${it.reason}")
                        }
                    } ?: "The call is finished.",
                    icon = Icons.Default.Call
                )
                viewModel.callEngine.acknowledgeLastCall()
            }
        }

        val seenTransfers = remember { mutableStateOf(setOf<String>()) }
        var transfersSeeded by remember { mutableStateOf(false) }
        LaunchedEffect(activeTransfersMap) {
            val done = activeTransfersMap.values.filter {
                it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED
            }
            val previous = seenTransfers.value
            seenTransfers.value = done.map { it.id }.toSet()
            if (!transfersSeeded) {
                transfersSeeded = true
                return@LaunchedEffect
            }
            val fresh = done.lastOrNull { it.id !in previous } ?: return@LaunchedEffect
            val label = if (fresh.direction == TransferDirection.INCOMING) "Received" else "Sent"
            pushBanner(
                group = "transfer",
                title = if (fresh.status == TransferStatus.FAILED)
                    "Transfer failed · ${fresh.fileName}"
                else "$label ${fresh.fileName}",
                text = if (fresh.status == TransferStatus.FAILED)
                    fresh.errorMessage ?: "Open history for details."
                else "%.1f MB · %s".format(fresh.fileSize / 1048576.0, fresh.remoteDevice),
                icon = Icons.Default.History,
                action = "See history",
                onTap = { selectedSection = PulseSection.TRANSFERS }
            )
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

        PulseBannerStack(
            banners = banners,
            onDismiss = { dismissBanner(it) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 10.dp)
                .padding(top = 84.dp)
        )
    }

    val incoming = incomingCall
    if (incoming != null) {
        // The same screen a browser call rings with - one ringing UI, whatever dialled.
        Dialog(
            onDismissRequest = { /* must answer explicitly */ },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            IncomingCallStage(
                card = IncomingCallCard(
                    name = incoming.name.ifBlank { "PulseDrop" },
                    detail = "${if (incoming.video) "Video" else "Voice"} call · ${incoming.peerIp}",
                    groupNote = if (incoming.confId.isNotBlank())
                        "Group call — everyone you have both paired with is being invited, " +
                                "bridged through this device"
                    else null,
                    hint = "Accept to connect audio${if (incoming.video) ", video and screen sharing" else ""} " +
                            "over your encrypted pairing, or decline to send them back to the chat."
                ),
                onAccept = { withCallPermissions(incoming.video) { viewModel.respondToCall(true) } },
                onDecline = { viewModel.respondToCall(false) }
            )
        }
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

    if (confirmStopServer) {
        AlertDialog(
            onDismissRequest = { confirmStopServer = false },
            title = { Text("Stop the server?") },
            text = {
                Text(
                    "Devices using ${serverState.serverUrl} lose access right away. " +
                        "Files already waiting on this phone stay put."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStopServer = false
                        viewModel.toggleServer()
                    },
                    modifier = Modifier.testTag("confirm_stop_server")
                ) { Text("Stop server", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopServer = false }) { Text("Keep running") }
            }
        )
    }
}


