package com.pulsedrop.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.PendingPairRequest
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.ui.PulseEvents
import com.pulsedrop.app.ui.UiPeerConnection
import com.pulsedrop.app.ui.components.PulseAction
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseDivider
import com.pulsedrop.app.ui.components.PulseOverflowMenu
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowGroup
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.components.PulseSearchField
import com.pulsedrop.app.ui.components.PulseSkeletonList
import com.pulsedrop.app.ui.components.PulseStatTile
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.PeerPinInputDialog
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing
import java.text.DateFormat
import java.util.Date

private enum class DeviceFilter(val label: String) {
    ALL("All"), NEARBY("Nearby"), TRUSTED("Trusted"), CONNECTED("Connected"), PENDING("Pending")
}

private enum class DeviceSort(val label: String) {
    STATE("Connection state"), NAME("Device name"), LAST_SEEN("Last seen")
}

/**
 * Devices: discovery, trust and connection in one place.
 *
 * Discovery is not trust, and trust is not connection - the page keeps those three states in
 * separate words on every row. Actions only appear where the real services can carry them out:
 * no rename (the protocol has none), no reconnect button (connecting is one tap on the peer),
 * and revoking trust always asks first, because it is the one action that cannot be undone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    nearbyPeers: List<PeerDevice>,
    connectedPeer: UiPeerConnection?,
    peerConnecting: Boolean,
    pairedDevices: List<PairedDevice>,
    pendingOutgoing: PendingPairRequest?,
    incomingPairCount: Int,
    scanning: Boolean,
    activeTransfers: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    onRescan: () -> Unit,
    onSendFilesToPeer: (PeerDevice, List<Uri>, String?) -> Unit,
    onConnectToAddress: (String) -> Unit,
    onDisconnectPeer: () -> Unit,
    onPair: (PeerDevice) -> Unit,
    onCancelPairRequest: () -> Unit,
    onUnpair: (String) -> Unit,
    safetyCodeOf: (String) -> String?,
    onToggleVerified: (String, Boolean) -> Unit,
    onOpenTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = LocalPulseColors.current

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(DeviceSort.STATE) }
    var sortOpen by remember { mutableStateOf(false) }
    var detailPeer by remember { mutableStateOf<PeerDevice?>(null) }
    var detailPaired by remember { mutableStateOf<PairedDevice?>(null) }
    var unpairConfirm by remember { mutableStateOf<PairedDevice?>(null) }
    var verifying by remember { mutableStateOf<PairedDevice?>(null) }
    var selectedPeerForSend by remember { mutableStateOf<PeerDevice?>(null) }
    var pendingUrisForPeer by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var peerRequiringPin by remember { mutableStateOf<PeerDevice?>(null) }
    var manualIp by remember { mutableStateOf("") }

    fun copyText(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("PulseDrop", text))
        PulseEvents.post(label)
    }

    fun matchesText(haystacks: List<String?>): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return haystacks.filterNotNull().any { it.lowercase().contains(q) }
    }

    val pairedByIp = remember(pairedDevices) { pairedDevices.associateBy { it.originIp } }

    fun peerState(peer: PeerDevice): String = when {
        connectedPeer?.peer?.ip == peer.ip -> "Connected"
        peer.id == pendingOutgoing?.peer?.id -> "Invite pending"
        peer.ip in pairedByIp -> "Trusted"
        else -> "Not paired"
    }

    val visiblePeers = remember(nearbyPeers, pairedByIp, connectedPeer, pendingOutgoing, query, filter, sort) {
        nearbyPeers
            .filter { matchesText(listOf(it.name, it.ip, it.appKind)) }
            .filter { peer ->
                when (filter) {
                    DeviceFilter.ALL -> true
                    DeviceFilter.NEARBY -> true
                    DeviceFilter.TRUSTED -> peer.ip in pairedByIp
                    DeviceFilter.CONNECTED -> connectedPeer?.peer?.ip == peer.ip
                    DeviceFilter.PENDING -> peer.id == pendingOutgoing?.peer?.id
                }
            }
            .sortedWith(
                when (sort) {
                    DeviceSort.NAME -> compareBy { it.name.lowercase() }
                    DeviceSort.LAST_SEEN -> compareByDescending { it.lastSeen }
                    DeviceSort.STATE -> compareBy {
                        when {
                            connectedPeer?.peer?.ip == it.ip -> 0
                            it.id == pendingOutgoing?.peer?.id -> 1
                            it.ip in pairedByIp -> 2
                            else -> 3
                        }
                    }
                }
            )
    }

    val visiblePaired = remember(pairedDevices, query, filter, sort) {
        pairedDevices
            .filter { matchesText(listOf(it.name, it.originIp, it.pid)) }
            .filter { filter == DeviceFilter.ALL || filter == DeviceFilter.TRUSTED }
            .sortedWith(
                when (sort) {
                    DeviceSort.NAME -> compareBy { it.name.lowercase() }
                    DeviceSort.LAST_SEEN -> compareByDescending { it.createdAt }
                    DeviceSort.STATE -> compareBy { if (it.verified) 0 else 1 }
                }
            )
    }

    val searchHidEverything = query.isNotBlank() && visiblePeers.isEmpty() && visiblePaired.isEmpty()

    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the address QR code shown on the other device")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            manualIp = contents
            onConnectToAddress(contents)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scannerLauncher.launch(scanOptions)
        } else {
            PulseEvents.post("Camera permission is needed to scan a QR code")
        }
    }

    fun launchScanner() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) scannerLauncher.launch(scanOptions)
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val peer = selectedPeerForSend
        if (uris.isNotEmpty() && peer != null) {
            if (peer.requiresPin) {
                pendingUrisForPeer = uris
                peerRequiringPin = peer
            } else {
                onSendFilesToPeer(peer, uris, null)
                PulseEvents.post("Sending ${uris.size} file(s) to ${peer.name}…")
            }
        }
    }

    fun sendTo(peer: PeerDevice) {
        selectedPeerForSend = peer
        filePicker.launch(arrayOf("*/*"))
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PulseSpacing.lg.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PulseSpacing.md.dp),
                horizontalArrangement = Arrangement.End
            ) {
                PulseStatus(
                    text = when {
                        scanning -> "Searching"
                        nearbyPeers.isNotEmpty() -> "${nearbyPeers.size} found"
                        else -> "Idle"
                    },
                    tone = when {
                        scanning -> PulseStatusTone.Active
                        nearbyPeers.isNotEmpty() -> PulseStatusTone.Positive
                        else -> PulseStatusTone.Neutral
                    }
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PulseSpacing.md.dp),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulseButton(
                    label = if (scanning) "Scanning…" else "Rescan",
                    icon = Icons.Default.Refresh,
                    tone = PulseButtonTone.Neutral,
                    enabled = !scanning,
                    onClick = onRescan,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("devices_rescan_btn")
                )
                PulseButton(
                    label = "Scan QR",
                    icon = Icons.Default.QrCodeScanner,
                    onClick = { launchScanner() },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("devices_scan_qr_btn")
                )
            }
        }

        // ── Search, filters, sorting ───────────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PulseSpacing.lg.dp),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                PulseSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search name, address or kind",
                    busy = scanning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("devices_search")
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DeviceFilter.entries.forEach { f ->
                        PulseChip(
                            label = f.label,
                            selected = filter == f,
                            onClick = { filter = f },
                            badge = when (f) {
                                DeviceFilter.NEARBY -> nearbyPeers.size
                                DeviceFilter.TRUSTED -> pairedDevices.size
                                DeviceFilter.CONNECTED -> if (connectedPeer != null) 1 else 0
                                DeviceFilter.PENDING ->
                                    incomingPairCount + (if (pendingOutgoing != null) 1 else 0)
                                else -> 0
                            },
                            modifier = Modifier.testTag("devices_filter_${f.name}")
                        )
                    }
                    Box {
                        PulseChip(
                            label = "Sort: ${sort.label}",
                            leadingIcon = Icons.Default.Sort,
                            onClick = { sortOpen = true },
                            modifier = Modifier.testTag("devices_sort_btn")
                        )
                        DropdownMenu(
                            expanded = sortOpen,
                            onDismissRequest = { sortOpen = false },
                            containerColor = c.surfaceOverlay
                        ) {
                            DeviceSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            s.label,
                                            color = if (s == sort) c.accent else c.onOverlay,
                                            fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = { sort = s; sortOpen = false }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (searchHidEverything) {
            item {
                PulseStatePanel(
                    icon = Icons.Default.Search,
                    title = "No devices match \"$query\"",
                    message = "Clear the search to see everything on the network again.",
                    actionLabel = "Clear search",
                    onAction = { query = "" }
                )
            }
        }

        // ── Nearby ─────────────────────────────────────────────────────────
        if (!searchHidEverything && filter != DeviceFilter.TRUSTED) {
            item { PulseRowLabel("Nearby devices") }
            if (visiblePeers.isEmpty()) {
                item {
                    if (scanning) {
                        // The sweep is still running. A skeleton says "working on it"; "no devices
                        // found yet" would be a claim about the network we have not finished making.
                        PulseSkeletonList(rows = 2)
                    } else {
                        PulseStatePanel(
                            icon = Icons.Default.Smartphone,
                            title = "No devices found yet",
                            message = "Open PulseDrop on another phone connected to the same Wi-Fi or " +
                                "hotspot, or connect by address below.",
                            actionLabel = "Rescan now",
                            onAction = onRescan
                        )
                    }
                }
            } else {
                itemsIndexed(visiblePeers, key = { _, peer -> peer.id }) { index, peer ->
                    if (index > 0) PulseRowDivider(insetStart = 54)
                    val paired = pairedByIp[peer.ip]
                    PeerDeviceRow(
                        peer = peer,
                        state = peerState(peer),
                        verified = paired?.verified,
                        isConnectingNow = connectedPeer?.peer?.ip == peer.ip ||
                            (peerConnecting && pendingOutgoing?.peer?.id == peer.id),
                        onSend = { sendTo(peer) },
                        onPair = { onPair(peer) },
                        onCancelPair = onCancelPairRequest,
                        onConnect = { onConnectToAddress("${peer.ip}:${peer.port}") },
                        onDetails = { detailPeer = peer },
                        onCopyAddress = { copyText("Address copied", "${peer.ip}:${peer.port}") }
                    )
                }
            }
        }

        // ── Trusted ────────────────────────────────────────────────────────
        if (!searchHidEverything && (filter == DeviceFilter.ALL || filter == DeviceFilter.TRUSTED)) {
            item { PulseRowLabel("Trusted devices") }
            if (visiblePaired.isEmpty()) {
                item {
                    PulseStatePanel(
                        icon = Icons.Default.Link,
                        title = "No trusted devices yet",
                        message = "Pairing gives you chat, calls and encrypted transfers with that " +
                            "device. You confirm the safety code on both screens before anything is trusted.",
                        actionLabel = "See nearby devices",
                        onAction = { filter = DeviceFilter.NEARBY }
                    )
                }
            } else {
                itemsIndexed(visiblePaired, key = { _, paired -> paired.pid }) { index, paired ->
                    if (index > 0) PulseRowDivider(insetStart = 54)
                    PairedDeviceRow(
                        paired = paired,
                        connected = connectedPeer?.peer?.ip == paired.originIp,
                        onVerify = { verifying = paired },
                        onDetails = { detailPaired = paired },
                        onViewTransfers = onOpenTransfers,
                        onRequestUnpair = { unpairConfirm = paired }
                    )
                }
            }
        }

        // ── Live connection ────────────────────────────────────────────────
        item {
            AnimatedVisibility(
                visible = connectedPeer != null || peerConnecting,
                modifier = Modifier.padding(top = PulseSpacing.lg.dp)
            ) {
                ConnectedPeerPanel(
                    connection = connectedPeer,
                    connecting = peerConnecting,
                    onSendFiles = { peer -> sendTo(peer) },
                    onDisconnect = onDisconnectPeer
                )
            }
        }

        // ── Manual / QR connect ────────────────────────────────────────────
        item {
            Column {
                PulseRowLabel("Connect by address")
                PulseRowGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = PulseSpacing.md.dp),
                        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
                    ) {
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            placeholder = { Text("192.168.1.55:8080") },
                            singleLine = true,
                            shape = RoundedCornerShape(PulseRadius.md.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val clean = manualIp.trim()
                                    if (clean.isNotBlank()) onConnectToAddress(clean)
                                    else PulseEvents.post("Enter an address or scan a QR")
                                }
                            ),
                            leadingIcon = { Icon(Icons.Default.Link, null, tint = c.textTertiary) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_ip_input")
                        )
                        PulseButton(
                            label = if (peerConnecting) "Connecting…" else "Connect",
                            icon = Icons.Default.Link,
                            enabled = !peerConnecting && manualIp.isNotBlank(),
                            disabledReason = "Type or scan an address first",
                            onClick = { onConnectToAddress(manualIp.trim()) },
                            fullWidth = true,
                            modifier = Modifier.testTag("connect_peer_btn")
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(PulseSpacing.xxl.dp)) }
    }

    // ── Device details (bottom sheet) ──────────────────────────────────────
    val peerDetail = detailPeer
    val pairedDetail = detailPaired
    if (peerDetail != null || pairedDetail != null) {
        ModalBottomSheet(onDismissRequest = { detailPeer = null; detailPaired = null }) {
            val name = peerDetail?.name ?: pairedDetail?.name ?: ""
            val ip = peerDetail?.ip ?: pairedDetail?.originIp ?: ""
            DeviceDetailsSheet(
                title = name,
                subtitle = peerDetail?.let {
                    "${it.ip}:${it.port} · ${if (it.appKind == "Browser") "Browser session" else "PulseDrop app"}"
                } ?: pairedDetail?.let {
                    "Pair ${it.pid.take(8).uppercase()} · ${it.originIp}:${it.originPort}" +
                        (if (it.appVersion.isNotBlank()) " · PulseDrop ${it.appVersion}" else "")
                } ?: "",
                trustLine = when {
                    pairedDetail != null && pairedDetail.verified -> "Verified — you compared the safety code"
                    pairedDetail != null -> "Not verified — compare the safety code before relying on it"
                    peerDetail != null && peerDetail.ip in pairedByIp -> "Trusted on this network"
                    else -> "Discovered, not trusted"
                },
                trustTone = when {
                    pairedDetail?.verified == true -> PulseStatusTone.Positive
                    pairedDetail != null -> PulseStatusTone.Warning
                    else -> PulseStatusTone.Neutral
                },
                connectionLine = when {
                    connectedPeer?.peer?.ip == ip -> "Connected now"
                    peerConnecting && pendingOutgoing?.peer?.ip == ip -> "Connecting…"
                    else -> "Not connected"
                },
                pairedSince = pairedDetail?.createdAt,
                lastSeen = peerDetail?.lastSeen,
                needsPin = peerDetail?.requiresPin == true,
                transfersNow = activeTransfers.count {
                    it.remoteDevice == name || it.clientIp == ip
                },
                transferCount = history.count { it.remoteDevice == name || it.clientIp == ip },
                recentTransfers = history.filter { it.remoteDevice == name || it.clientIp == ip }.take(3),
                safetyCode = pairedDetail?.let { safetyCodeOf(it.pid) },
                onSend = {
                    // Sending needs a live listener: only peers the discovery actually found.
                    peerDetail?.let { sendTo(it) }
                        ?: nearbyPeers.firstOrNull { it.ip == pairedDetail?.originIp }?.let { sendTo(it) }
                },
                sendEnabled = peerDetail != null ||
                    nearbyPeers.any { it.ip == pairedDetail?.originIp },
                onConnect = {
                    val p = peerDetail
                    if (p != null && p.hasServer) onConnectToAddress("${p.ip}:${p.port}")
                    else if (p == null && pairedDetail != null) {
                        onConnectToAddress("${pairedDetail.originIp}:${pairedDetail.originPort}")
                    }
                },
                connectEnabled = peerDetail?.let { it.hasServer && it.appKind != "Browser" }
                    ?: (pairedDetail != null),
                onDisconnect = onDisconnectPeer,
                showDisconnect = connectedPeer?.peer?.ip == ip,
                onPair = peerDetail?.takeIf { it.appKind != "Browser" && it.ip !in pairedByIp }
                    ?.let { { onPair(it) } },
                onVerify = pairedDetail?.let { { verifying = it } },
                onRequestUnpair = pairedDetail?.let { { unpairConfirm = it } },
                onViewTransfers = onOpenTransfers,
                onCopyDiagnostics = {
                    val text = buildString {
                        appendLine("PulseDrop device report")
                        appendLine("Name: $name")
                        appendLine("Address: $ip")
                        peerDetail?.appKind?.let { appendLine("Kind: $it") }
                        pairedDetail?.let {
                            appendLine("Pair id: ${it.pid.take(8)}")
                            appendLine("Verified: ${it.verified}")
                            appendLine("Paired: ${it.createdAt}")
                        }
                    }
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("PulseDrop", text))
                    PulseEvents.post("Device info copied")
                }
            )
        }
    }

    // ── Unpair confirmation ────────────────────────────────────────────────
    unpairConfirm?.let { paired ->
        AlertDialog(
            onDismissRequest = { unpairConfirm = null },
            icon = { Icon(Icons.Default.LinkOff, null, tint = c.danger) },
            title = { Text("Revoke trust for ${paired.name}?") },
            text = {
                Text(
                    "This deletes the shared encryption keys on this phone. ${paired.name} will no " +
                        "longer reach your chats, calls or encrypted transfers until you pair again " +
                        "and confirm a new safety code. This cannot be undone."
                )
            },
            confirmButton = {
                PulseButton(
                    label = "Revoke trust",
                    tone = PulseButtonTone.Danger,
                    onClick = {
                        onUnpair(paired.pid)
                        unpairConfirm = null
                        detailPaired = null
                    },
                    modifier = Modifier.testTag("confirm_unpair_${paired.pid}")
                )
            },
            dismissButton = {
                TextButton(onClick = { unpairConfirm = null }) { Text("Keep") }
            }
        )
    }

    // ── Safety code comparison ─────────────────────────────────────────────
    val verifyTarget = verifying
    if (verifyTarget != null) {
        val code = safetyCodeOf(verifyTarget.pid)
        AlertDialog(
            onDismissRequest = { verifying = null },
            icon = {
                Icon(
                    imageVector = if (verifyTarget.verified) Icons.Default.VerifiedUser else Icons.Default.Key,
                    contentDescription = null,
                    tint = if (verifyTarget.verified) c.success else c.warning
                )
            },
            title = { Text("Safety code") },
            text = {
                Column {
                    Text(
                        text = "Read these six digits aloud on ${verifyTarget.name} and check they match " +
                                "what that device shows for this pairing. Equal codes mean nobody is " +
                                "in the middle; different codes mean the channel is being intercepted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                    Spacer(modifier = Modifier.height(PulseSpacing.lg.dp))
                    Text(
                        text = code?.chunked(3)?.joinToString(" ") ?: "Unavailable",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (code != null) c.textPrimary else c.danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("safety_code_value")
                    )
                    if (verifyTarget.verified) {
                        Spacer(modifier = Modifier.height(PulseSpacing.sm.dp))
                        Text(
                            text = "You already confirmed this pairing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.success
                        )
                    }
                }
            },
            confirmButton = {
                PulseButton(
                    label = if (verifyTarget.verified) "Mark unverified" else "Codes match",
                    enabled = code != null,
                    onClick = {
                        onToggleVerified(verifyTarget.pid, !verifyTarget.verified)
                        verifying = null
                    },
                    modifier = Modifier.testTag("confirm_safety_code_btn")
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { verifying = null },
                    modifier = Modifier.testTag("close_safety_code_btn")
                ) { Text("Close") }
            }
        )
    }

    if (peerRequiringPin != null) {
        PeerPinInputDialog(
            peerName = peerRequiringPin!!.name,
            onDismiss = {
                peerRequiringPin = null
                pendingUrisForPeer = emptyList()
            },
            onSubmit = { enteredPin ->
                val peer = peerRequiringPin
                val uris = pendingUrisForPeer
                if (peer != null && uris.isNotEmpty()) {
                    onSendFilesToPeer(peer, uris, enteredPin)
                    PulseEvents.post("Sending ${uris.size} file(s) to ${peer.name}…")
                }
                peerRequiringPin = null
                pendingUrisForPeer = emptyList()
            }
        )
    }
}

// ── Rows ───────────────────────────────────────────────────────────────────

@Composable
private fun PeerDeviceRow(
    peer: PeerDevice,
    state: String,
    verified: Boolean?,
    isConnectingNow: Boolean,
    onSend: () -> Unit,
    onPair: () -> Unit,
    onCancelPair: () -> Unit,
    onConnect: () -> Unit,
    onDetails: () -> Unit,
    onCopyAddress: () -> Unit
) {
    val c = LocalPulseColors.current
    val pending = state == "Invite pending"
    PulseRow(
        title = peer.name,
        icon = when {
            peer.appKind == "Browser" -> Icons.Default.Language
            peer.name.contains("PC", ignoreCase = true) ||
                peer.name.contains("Mac", ignoreCase = true) -> Icons.Default.Computer
            else -> Icons.Default.Smartphone
        },
        iconTint = if (state == "Connected") c.accent else c.textSecondary,
        subtitle = (if (peer.appKind == "Browser") peer.ip else "${peer.ip}:${peer.port}") +
            if (peer.requiresPin) " · PIN protected" else "",
        detail = state + (if (verified == false && state == "Trusted") " · not verified" else ""),
        showChevron = false,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                ) {
                    PulseButton(
                        label = "Send",
                        icon = Icons.AutoMirrored.Filled.Send,
                        tone = PulseButtonTone.Quiet,
                        onClick = onSend,
                        modifier = Modifier.testTag("send_to_peer_${peer.id}")
                    )
                    when {
                        pending -> PulseButton(
                            label = "Cancel invite",
                            tone = PulseButtonTone.Quiet,
                            onClick = onCancelPair,
                            modifier = Modifier.testTag("cancel_pair_btn")
                        )
                        state == "Trusted" || state == "Connected" -> Unit
                        peer.appKind == "Browser" -> Text(
                            "Web session",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary
                        )
                        else -> PulseButton(
                            label = "Pair",
                            icon = Icons.Default.Link,
                            onClick = onPair,
                            modifier = Modifier.testTag("pair_btn_${peer.id}")
                        )
                    }
                }
                PulseOverflowMenu(
                    label = "Actions for ${peer.name}",
                    actions = buildList {
                        add(PulseAction("Details", Icons.Default.Info, onClick = { onDetails() }))
                        add(PulseAction("Copy address", Icons.Default.ContentCopy, onClick = { onCopyAddress() }))
                        if (peer.appKind != "Browser" && peer.hasServer) {
                            add(PulseAction("Connect", Icons.Default.Link, onClick = { onConnect() }))
                        }
                        if (state == "Not paired" && peer.appKind != "Browser") {
                            add(PulseAction("Pair", Icons.Default.Link, onClick = { onPair() }))
                        }
                    }
                )
            }
        },
        onClick = onDetails,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("peer_row_${peer.id}")
    )
}

@Composable
private fun PairedDeviceRow(
    paired: PairedDevice,
    connected: Boolean,
    onVerify: () -> Unit,
    onDetails: () -> Unit,
    onViewTransfers: () -> Unit,
    onRequestUnpair: () -> Unit
) {
    val c = LocalPulseColors.current
    PulseRow(
        title = paired.name,
        icon = if (paired.verified) Icons.Default.VerifiedUser else Icons.Default.Key,
        iconTint = if (paired.verified) c.success else c.warning,
        subtitle = "Pair ${paired.pid.take(6).uppercase()} · ${paired.originIp}:${paired.originPort}" +
            (if (paired.appVersion.isNotBlank()) " · PulseDrop ${paired.appVersion}" else ""),
        // Trust is stated in words; colour only reinforces it, never replaces it.
        detail = (if (paired.verified) "Verified" else "Not verified") +
            (if (connected) " · connected now" else ""),
        showChevron = false,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
            ) {
                PulseButton(
                    label = if (paired.verified) "Code" else "Verify",
                    tone = PulseButtonTone.Quiet,
                    onClick = onVerify,
                    modifier = Modifier.testTag("verify_btn_${paired.pid}")
                )
                PulseOverflowMenu(
                    label = "Actions for ${paired.name}",
                    actions = listOf(
                        PulseAction("Details", Icons.Default.Info, onClick = { onDetails() }),
                        PulseAction("View transfers", Icons.Default.History, onClick = { onViewTransfers() }),
                        PulseAction("Revoke trust", Icons.Default.LinkOff,
                            onClick = { onRequestUnpair() }, destructive = true)
                    )
                )
            }
        },
        onClick = onDetails,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("paired_device_${paired.pid}")
    )
}

@Composable
private fun ConnectedPeerPanel(
    connection: UiPeerConnection?,
    connecting: Boolean,
    onSendFiles: (PeerDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    val c = LocalPulseColors.current
    // A short callout block, not a list row: it groups the live-connection facts and its two actions.
    PulseRowGroup(modifier = Modifier.fillMaxWidth().testTag("connected_peer_panel")) {
        PulseRow(
            title = if (connection != null) connection.label else "Connecting…",
            subtitle = connection?.peer?.let { "${it.ip}:${it.port}" },
            showChevron = false,
            leading = {
                if (connecting || connection == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(c.success)
                    )
                }
            },
            trailing = {
                if (connection?.requiresPin == true) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Needs PIN for sending",
                        tint = c.accent,
                        modifier = Modifier.size(PulseSizes.icon.dp)
                    )
                }
            }
        )
        if (connection != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = PulseSpacing.sm.dp, bottom = PulseSpacing.sm.dp),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                PulseButton(
                    label = "Send files",
                    icon = Icons.AutoMirrored.Filled.Send,
                    onClick = { onSendFiles(connection.peer) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("send_to_connected_peer")
                )
                PulseButton(
                    label = "Disconnect",
                    tone = PulseButtonTone.Danger,
                    onClick = onDisconnect,
                    modifier = Modifier.testTag("disconnect_peer_btn")
                )
            }
        }
    }
}

// ── Details sheet ──────────────────────────────────────────────────────────

@Suppress("LongParameterList")
@Composable
private fun DeviceDetailsSheet(
    title: String,
    subtitle: String,
    trustLine: String,
    trustTone: PulseStatusTone,
    connectionLine: String,
    pairedSince: Long?,
    lastSeen: Long?,
    needsPin: Boolean,
    transfersNow: Int,
    transferCount: Int,
    recentTransfers: List<TransferHistoryEntity>,
    safetyCode: String?,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    onConnect: () -> Unit,
    connectEnabled: Boolean,
    onDisconnect: () -> Unit,
    showDisconnect: Boolean,
    onPair: (() -> Unit)?,
    onVerify: (() -> Unit)?,
    onRequestUnpair: (() -> Unit)?,
    onViewTransfers: () -> Unit,
    onCopyDiagnostics: () -> Unit
) {
    val c = LocalPulseColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PulseSpacing.xl.dp)
            .padding(bottom = PulseSpacing.xxl.dp),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        PulseStatus(
            text = trustLine,
            tone = trustTone,
            detail = connectionLine
        )
        if (needsPin) {
            Text(
                "This device asks for a PIN before it accepts files.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
            PulseStatTile(
                label = "In transit",
                value = transfersNow.toString(),
                modifier = Modifier.weight(1f)
            )
            PulseStatTile(
                label = "Transfers total",
                value = transferCount.toString(),
                onClick = onViewTransfers,
                modifier = Modifier.weight(1f)
            )
        }
        DetailLine("Paired", pairedSince?.let { dateFormat().format(Date(it)) } ?: "—")
        DetailLine("Last seen", lastSeen?.let { dateFormat().format(Date(it)) } ?: "—")
        DetailLine(
            "Safety code",
            safetyCode?.chunked(3)?.joinToString(" ")
                ?: if (safetyCode == null && pairedSince != null) "Unavailable" else "—"
        )

        PulseDivider(Modifier.padding(vertical = PulseSpacing.xs.dp))

        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseButton(
                    label = "Send files",
                    icon = Icons.AutoMirrored.Filled.Send,
                    onClick = onSend,
                    enabled = sendEnabled,
                    disabledReason = "This device is not reachable right now",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("detail_send_btn")
                )
                PulseButton(
                    label = "Connect",
                    icon = Icons.Default.Link,
                    tone = PulseButtonTone.Neutral,
                    onClick = onConnect,
                    enabled = connectEnabled,
                    disabledReason = "No listener to dial on this device",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("detail_connect_btn")
                )
            }
            if (showDisconnect) {
                PulseButton(
                    label = "Disconnect",
                    tone = PulseButtonTone.Danger,
                    onClick = onDisconnect,
                    fullWidth = true,
                    modifier = Modifier.testTag("detail_disconnect_btn")
                )
            }
            onPair?.let {
                PulseButton(
                    label = "Pair with this device",
                    onClick = it,
                    fullWidth = true,
                    modifier = Modifier.testTag("detail_pair_btn")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                onVerify?.let {
                    PulseButton(
                        label = "Compare safety code",
                        tone = PulseButtonTone.Quiet,
                        onClick = it,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("detail_verify_btn")
                    )
                }
                PulseButton(
                    label = "Copy info",
                    icon = Icons.Default.ContentCopy,
                    tone = PulseButtonTone.Quiet,
                    onClick = onCopyDiagnostics,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("detail_copy_btn")
                )
            }
            onRequestUnpair?.let {
                PulseButton(
                    label = "Revoke trust",
                    icon = Icons.Default.LinkOff,
                    tone = PulseButtonTone.Danger,
                    onClick = it,
                    fullWidth = true,
                    modifier = Modifier.testTag("detail_unpair_btn")
                )
            }
        }

        if (recentTransfers.isNotEmpty()) {
            PulseDivider(Modifier.padding(vertical = PulseSpacing.xs.dp))
            Text(
                "Recent transfers with this device",
                style = MaterialTheme.typography.labelLarge,
                color = c.textSecondary
            )
            recentTransfers.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = PulseSpacing.xs.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.direction == TransferDirection.INCOMING.name)
                            Icons.AutoMirrored.Filled.Send else Icons.Default.Language,
                        contentDescription = null,
                        tint = c.textTertiary,
                        modifier = Modifier.size(PulseSizes.icon.dp)
                    )
                    Spacer(Modifier.width(PulseSpacing.sm.dp))
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (item.status == "FAILED") "failed" else "done",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.status == "FAILED") c.danger else c.success
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = c.textTertiary,
            modifier = Modifier.width(110.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = c.textPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun dateFormat(): DateFormat =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
