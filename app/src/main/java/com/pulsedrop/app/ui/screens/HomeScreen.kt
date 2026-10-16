package com.pulsedrop.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.SharedFile
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.UiServerState
import com.pulsedrop.app.ui.components.ActiveTransfersCard
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseDivider
import com.pulsedrop.app.ui.components.PulseInlineBanner
import com.pulsedrop.app.ui.components.PulsePageHeader
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseSkeleton
import com.pulsedrop.app.ui.components.PulseStatTile
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.ServerCard
import com.pulsedrop.app.ui.components.PulseSection
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.UpdateInfo
import com.pulsedrop.app.util.NetworkUtils
import java.util.Calendar

/**
 * Home as the room's command center.
 *
 * Everything on this page reads a live source: counts come from real flows, alerts only appear
 * when something actually needs an answer, and no card promises a capability the services do
 * not have. A quiet dashboard is a correct dashboard.
 */
@Composable
fun HomeScreen(
    serverState: UiServerState,
    qrBitmap: Bitmap?,
    sharedFiles: List<SharedFile>,
    activeTransfers: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    messagesCount: Int,
    pairedDevices: List<PairedDevice>,
    peersCount: Int,
    webClientsCount: Int,
    pendingUploads: List<PendingUploadInfo>,
    incomingPairCount: Int,
    relayHostName: String?,
    callInProgress: Boolean,
    isSharingScreen: Boolean,
    updateInfo: UpdateInfo?,
    updateProgress: Float?,
    updateStatus: String?,
    appVersion: String,
    installs: Int?,
    onToggleServer: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onAddFiles: (List<Uri>) -> Unit,
    onRemoveFile: (String) -> Unit,
    onClearFiles: () -> Unit,
    onAcceptUpload: (String) -> Unit,
    onDeclineUpload: (String) -> Unit,
    onToggleScreenShare: () -> Unit,
    onUpdateCheck: () -> Unit,
    onNavigate: (PulseSection) -> Unit,
    onOpenTransferDetails: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    var showQr by remember { mutableStateOf(false) }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onAddFiles(uris)
    }

    val inflight = activeTransfers.filter {
        it.status == TransferStatus.QUEUED || it.status == TransferStatus.IN_PROGRESS
    }
    val failed = activeTransfers.filter { it.status == TransferStatus.FAILED }
    val movedBytes = history.filter { it.status == "COMPLETED" }.sumOf { it.fileSize }
    val hostedBytes = sharedFiles.sumOf { it.size }
    val unverifiedPairs = pairedDevices.count { !it.verified }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PulseSpacing.lg.dp),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg.dp)
    ) {
        item {
            PulsePageHeader(
                title = "${greeting()}, ${serverState.deviceName}",
                subtitle = if (serverState.isRunning)
                    "Serving on port ${serverState.port}"
                else if (relayHostName != null)
                    "Chatting through $relayHostName's server"
                else
                    "Not serving — start the server to receive files",
                status = {
                    PulseStatus(
                        text = if (serverState.isRunning) "Live" else "Idle",
                        tone = if (serverState.isRunning) PulseStatusTone.Active else PulseStatusTone.Neutral,
                        detail = serverState.networkName
                    )
                }
            )
        }

        // ── Quick actions ──────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseButton(
                        label = "Send files",
                        icon = Icons.AutoMirrored.Filled.Send,
                        onClick = { onNavigate(PulseSection.SEND_FILES) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_send_files")
                    )
                    PulseButton(
                        label = "Connect device",
                        icon = Icons.Default.Link,
                        tone = PulseButtonTone.Neutral,
                        onClick = { onNavigate(PulseSection.DEVICES) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_connect_device")
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                ) {
                    PulseChip(
                        label = if (serverState.isRunning) "Stop serving" else "Start serving",
                        leadingIcon = Icons.Default.WifiTethering,
                        selected = serverState.isRunning,
                        onClick = onToggleServer,
                        modifier = Modifier.testTag("home_serve_chip")
                    )
                    PulseChip(
                        label = "QR code",
                        leadingIcon = Icons.Default.QrCode2,
                        onClick = { showQr = true },
                        modifier = Modifier.testTag("home_qr_chip")
                    )
                    PulseChip(
                        label = "Server PIN",
                        leadingIcon = if (serverState.isPinRequired) Icons.Default.Lock else Icons.Default.LockOpen,
                        onClick = onOpenSecuritySettings,
                        modifier = Modifier.testTag("home_pin_chip")
                    )
                    PulseChip(
                        label = "Transfers",
                        leadingIcon = Icons.Default.SwapHoriz,
                        badge = inflight.size,
                        onClick = { onNavigate(PulseSection.TRANSFERS) },
                        modifier = Modifier.testTag("home_transfers_chip")
                    )
                    if (callInProgress) {
                        PulseChip(
                            label = if (isSharingScreen) "Stop sharing" else "Share screen",
                            leadingIcon = Icons.Default.ScreenShare,
                            selected = isSharingScreen,
                            onClick = onToggleScreenShare,
                            modifier = Modifier.testTag("home_share_chip")
                        )
                    }
                }
            }
        }

        // ── Needs you (only rendered when something really needs an answer) ─
        if (pendingUploads.isNotEmpty() || incomingPairCount > 0 ||
            failed.isNotEmpty() || updateInfo != null
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    pendingUploads.take(3).forEach { upload ->
                        PulseCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${upload.fileName} · ${NetworkUtils.formatBytes(upload.fileSize)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = c.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "from ${upload.fromDevice} — accept to store it on this phone",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(PulseSpacing.sm.dp))
                                PulseButton(
                                    label = "Decline",
                                    tone = PulseButtonTone.Quiet,
                                    onClick = { onDeclineUpload(upload.id) },
                                    modifier = Modifier.testTag("decline_upload_${upload.id}")
                                )
                                Spacer(Modifier.width(PulseSpacing.xs.dp))
                                PulseButton(
                                    label = "Accept",
                                    onClick = { onAcceptUpload(upload.id) },
                                    modifier = Modifier.testTag("accept_upload_${upload.id}")
                                )
                            }
                        }
                    }
                    if (incomingPairCount > 0) {
                        PulseInlineBanner(
                            text = "$incomingPairCount device${if (incomingPairCount == 1) "" else "s"} " +
                                "want${if (incomingPairCount == 1) "s" else ""} to pair — confirm the safety code aloud",
                            tone = PulseStatusTone.Warning,
                            action = {
                                TextButton(
                                    onClick = { onNavigate(PulseSection.DEVICES) },
                                    modifier = Modifier.wrapContentWidth()
                                ) { Text("View", style = MaterialTheme.typography.labelLarge) }
                            }
                        )
                    }
                    if (failed.isNotEmpty()) {
                        PulseInlineBanner(
                            text = "${failed.size} transfer${if (failed.size == 1) "" else "s"} failed" +
                                (failed.lastOrNull()?.errorMessage?.let { " — $it" } ?: ""),
                            tone = PulseStatusTone.Negative,
                            action = {
                                TextButton(
                                    onClick = { onNavigate(PulseSection.TRANSFERS) },
                                    modifier = Modifier.wrapContentWidth()
                                ) { Text("History", style = MaterialTheme.typography.labelLarge) }
                            }
                        )
                    }
                    if (updateInfo != null) {
                        PulseInlineBanner(
                            text = "PulseDrop ${updateInfo.versionName} is available",
                            tone = PulseStatusTone.Active,
                            action = {
                                TextButton(
                                    onClick = onUpdateCheck,
                                    modifier = Modifier.wrapContentWidth()
                                ) { Text("Review", style = MaterialTheme.typography.labelLarge) }
                            }
                        )
                    }
                }
            }
        }

        // ── Overview ───────────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader("This session", detail = if (serverState.isRunning)
                    "up ${uptimeOf(serverState.startedAt)}" else "server stopped")
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseStatTile(
                        label = "Devices nearby",
                        value = peersCount.toString(),
                        onClick = { onNavigate(PulseSection.DEVICES) },
                        modifier = Modifier.weight(1f)
                    )
                    PulseStatTile(
                        label = "Paired",
                        value = pairedDevices.size.toString(),
                        onClick = { onNavigate(PulseSection.DEVICES) },
                        modifier = Modifier.weight(1f)
                    )
                    PulseStatTile(
                        label = "Messages",
                        value = messagesCount.toString(),
                        onClick = { onNavigate(PulseSection.MESSAGES) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseStatTile(
                        label = "In transit",
                        value = inflight.size.toString(),
                        onClick = { onNavigate(PulseSection.TRANSFERS) },
                        modifier = Modifier.weight(1f)
                    )
                    PulseStatTile(
                        label = "Files hosted",
                        value = sharedFiles.size.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    PulseStatTile(
                        label = "Moved",
                        value = NetworkUtils.formatBytes(movedBytes),
                        onClick = { onNavigate(PulseSection.TRANSFERS) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Active transfers ───────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader(
                    "Transfers in flight",
                    action = {
                        TextButton(
                            onClick = { onNavigate(PulseSection.TRANSFERS) },
                            modifier = Modifier.testTag("home_all_transfers_btn")
                        ) { Text("All history", style = MaterialTheme.typography.labelLarge) }
                    }
                )
                if (inflight.isEmpty()) {
                    PulseCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = c.success,
                                modifier = Modifier.size(PulseStatIconSize)
                            )
                            Spacer(Modifier.width(PulseSpacing.sm.dp))
                            Text(
                                "Nothing in transit. Files you send or receive show up here live.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSecondary
                            )
                        }
                    }
                } else {
                    ActiveTransfersCard(
                        activeTransfers = inflight,
                        onRowClick = onOpenTransferDetails
                    )
                }
            }
        }

        // ── Devices ────────────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader(
                    "Devices",
                    detail = "$peersCount nearby • ${pairedDevices.size} paired",
                    action = {
                        TextButton(
                            onClick = { onNavigate(PulseSection.DEVICES) },
                            modifier = Modifier.testTag("home_manage_devices_btn")
                        ) { Text("Manage", style = MaterialTheme.typography.labelLarge) }
                    }
                )
                if (pairedDevices.isEmpty()) {
                    PulseCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No paired devices yet. Pairing gives you chat, calls and encrypted transfers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                    ) {
                        pairedDevices.take(6).forEach { device ->
                            PairDeviceChip(device) { onNavigate(PulseSection.DEVICES) }
                        }
                    }
                }
            }
        }

        // ── Connection ─────────────────────────────────────────────────────
        item {
            ServerCard(
                serverState = serverState,
                qrBitmap = qrBitmap,
                onToggleServer = onToggleServer,
                onOpenSecuritySettings = onOpenSecuritySettings,
                relayHostName = relayHostName
            )
        }
        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("home_connection_health")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
                    Text(
                        "Connection health",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    HealthRow("Network", serverState.networkName)
                    HealthRow(
                        "Address",
                        if (serverState.isRunning) serverState.serverUrl else "— not serving"
                    )
                    HealthRow(
                        "Browser clients",
                        if (serverState.isRunning) "$webClientsCount connected" else "— server off"
                    )
                    HealthRow(
                        "Relay",
                        when {
                            serverState.isRunning -> "This phone is the host"
                            relayHostName != null -> "Attached to $relayHostName"
                            else -> "Not attached"
                        }
                    )
                }
            }
        }

        // ── Recent activity ────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader(
                    "Recent activity",
                    action = {
                        TextButton(
                            onClick = { onNavigate(PulseSection.ACTIVITY) },
                            modifier = Modifier.testTag("home_activity_more_btn")
                        ) { Text("See all", style = MaterialTheme.typography.labelLarge) }
                    }
                )
                if (history.isEmpty()) {
                    PulseCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Nothing has finished moving yet. Completed and failed transfers land here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                } else {
                    PulseCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_recent_activity"),
                        onClick = { onNavigate(PulseSection.ACTIVITY) }
                    ) {
                        Column {
                            history.take(5).forEachIndexed { index, item ->
                                if (index > 0) PulseDivider()
                                ActivityRow(item)
                            }
                        }
                    }
                }
            }
        }

        // ── Files & storage ────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader(
                    "Files & storage",
                    detail = if (sharedFiles.isEmpty()) "Nothing hosted"
                    else "${sharedFiles.size} file${if (sharedFiles.size == 1) "" else "s"} • " +
                        NetworkUtils.formatBytes(hostedBytes) + " hosted",
                    action = {
                        PulseButton(
                            label = "Add",
                            icon = Icons.Default.Add,
                            tone = PulseButtonTone.Quiet,
                            onClick = { documentPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.testTag("home_add_files_btn")
                        )
                    }
                )
                if (sharedFiles.isEmpty()) {
                    PulseCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Files you add here go live on the browser server for every device on the network.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                } else {
                    PulseCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                            sharedFiles.take(4).forEach { file ->
                                HostedFileRow(file = file, onRemove = { onRemoveFile(file.id) })
                            }
                            if (sharedFiles.size > 4) {
                                Text(
                                    "+${sharedFiles.size - 4} more on the server",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.textTertiary
                                )
                            }
                            if (sharedFiles.isNotEmpty()) {
                                TextButton(
                                    onClick = onClearFiles,
                                    modifier = Modifier.testTag("home_clear_files_btn")
                                ) {
                                    Text("Clear all", color = c.danger, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Security summary ───────────────────────────────────────────────
        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("home_security_summary")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
                    PulseSectionHeader(
                        "Security",
                        action = {
                            TextButton(
                                onClick = { onNavigate(PulseSection.SETTINGS) },
                                modifier = Modifier.testTag("home_security_settings_btn")
                            ) { Text("Settings", style = MaterialTheme.typography.labelLarge) }
                        }
                    )
                    SecurityRow(
                        icon = if (serverState.isPinRequired) Icons.Default.Lock else Icons.Default.LockOpen,
                        text = when {
                            serverState.isPinRequired -> "Browser access needs the PIN"
                            serverState.isRunning -> "Open access while serving"
                            else -> "No server, nothing to guard"
                        },
                        tone = when {
                            serverState.isPinRequired -> PulseStatusTone.Positive
                            serverState.isRunning -> PulseStatusTone.Warning
                            else -> PulseStatusTone.Neutral
                        }
                    )
                    SecurityRow(
                        icon = Icons.Default.CheckCircle,
                        text = if (serverState.autoAcceptTransfers)
                            "Incoming files land without asking"
                        else
                            "You approve every incoming upload",
                        tone = if (serverState.autoAcceptTransfers)
                            PulseStatusTone.Neutral else PulseStatusTone.Positive
                    )
                    SecurityRow(
                        icon = Icons.Default.Shield,
                        text = when {
                            pairedDevices.isEmpty() -> "No pairings yet"
                            unverifiedPairs > 0 -> "$unverifiedPairs of ${pairedDevices.size} pairings not verified"
                            else -> "All ${pairedDevices.size} pairings verified"
                        },
                        tone = when {
                            pairedDevices.isEmpty() -> PulseStatusTone.Neutral
                            unverifiedPairs > 0 -> PulseStatusTone.Warning
                            else -> PulseStatusTone.Positive
                        }
                    )
                }
            }
        }

        // ── App health ─────────────────────────────────────────────────────
        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("home_app_health")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PulseDrop $appVersion", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (installs == null) {
                                    PulseSkeleton(height = 12.dp, fixedWidth = 120.dp)
                                } else {
                                    Text(
                                        "%,d installs of this release".format(installs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textTertiary
                                    )
                                }
                            }
                        }
                        PulseButton(
                            label = "Check for updates",
                            tone = PulseButtonTone.Neutral,
                            onClick = onUpdateCheck,
                            loading = updateProgress != null,
                            modifier = Modifier.testTag("home_update_btn")
                        )
                    }
                    val pct = updateProgress
                    if (pct != null) {
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                        Text("Downloading… ${(pct * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    } else if (updateStatus != null) {
                        Text(updateStatus, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(PulseSpacing.xxl.dp)) }
    }

    if (showQr) {
        HomeQrDialog(
            qrBitmap = qrBitmap,
            url = serverState.serverUrl,
            onDismiss = { showQr = false }
        )
    }
}

private val PulseStatIconSize = 20.dp

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}

private fun uptimeOf(startedAt: Long): String {
    if (startedAt <= 0L) return "—"
    val minutes = (System.currentTimeMillis() - startedAt) / 60_000L
    return when {
        minutes < 1 -> "just started"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

private fun agoOf(timestamp: Long): String {
    val minutes = (System.currentTimeMillis() - timestamp) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

@Composable
private fun PairDeviceChip(device: PairedDevice, onClick: () -> Unit) {
    val c = LocalPulseColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(PulseRadius.md.dp),
        color = c.surfaceElevated,
        contentColor = c.textPrimary,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.borderSubtle)
    ) {
        Row(
            Modifier.padding(horizontal = PulseSpacing.md.dp, vertical = PulseSpacing.sm.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
        ) {
            Icon(
                imageVector = if (device.verified) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (device.verified) c.success else c.warning,
                modifier = Modifier.size(16.dp)
            )
            Column {
                Text(
                    device.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthInLimit()
                )
                // Trust is stated in words, never left to the colour of an icon.
                Text(
                    if (device.verified) "Verified" else "Not verified",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary
                )
            }
        }
    }
}

private fun Modifier.widthInLimit(): Modifier = this.width(150.dp)

@Composable
private fun HealthRow(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = c.textTertiary,
            modifier = Modifier.width(120.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.Medium
            ),
            color = c.textPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SecurityRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tone: PulseStatusTone) {
    val c = LocalPulseColors.current
    val tint = when (tone) {
        PulseStatusTone.Positive -> c.success
        PulseStatusTone.Warning -> c.warning
        PulseStatusTone.Negative -> c.danger
        PulseStatusTone.Active -> c.accent
        PulseStatusTone.Neutral -> c.textTertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(PulseSpacing.sm.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    }
}

@Composable
private fun ActivityRow(item: TransferHistoryEntity) {
    val c = LocalPulseColors.current
    val isIncoming = item.direction == TransferDirection.INCOMING.name
    val failed = item.status == "FAILED"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = PulseSpacing.md.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                failed -> Icons.Default.Error
                isIncoming -> Icons.AutoMirrored.Filled.ArrowBack
                else -> Icons.AutoMirrored.Filled.ArrowForward
            },
            contentDescription = when {
                failed -> "Failed"
                isIncoming -> "Received"
                else -> "Sent"
            },
            tint = if (failed) c.danger else c.textTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(PulseSpacing.sm.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                (if (failed) "Failed" else if (isIncoming) "Received" else "Sent") +
                    " · ${item.remoteDevice}",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            agoOf(item.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = c.textTertiary
        )
    }
}

@Composable
private fun HostedFileRow(file: SharedFile, onRemove: () -> Unit) {
    val c = LocalPulseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PulseRadius.sm.dp))
            .background(c.surfaceSunken)
            .padding(start = PulseSpacing.md.dp, end = PulseSpacing.xs.dp, top = PulseSpacing.sm.dp, bottom = PulseSpacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(PulseRadius.sm.dp))
                .background(c.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(PulseSpacing.sm.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                NetworkUtils.formatBytes(file.size),
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary
            )
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier.testTag("home_remove_file_${file.id}")
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Remove ${file.name}", tint = c.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun HomeQrDialog(qrBitmap: Bitmap?, url: String, onDismiss: () -> Unit) {
    val c = LocalPulseColors.current
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(PulseRadius.xl.dp)),
            color = c.surfaceElevated,
            contentColor = c.textPrimary
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(PulseSpacing.xxl.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
            ) {
                Text("Scan to connect", style = MaterialTheme.typography.titleMedium)
                if (qrBitmap == null) {
                    PulseSkeleton(height = 200.dp)
                    Text("Generating the code…", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                } else {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(PulseRadius.md.dp))
                            // A QR quiet zone has to be the code's own light mat, not a theme surface.
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code for $url",
                            modifier = Modifier.size(180.dp)
                        )
                    }
                }
                Text(url, style = MaterialTheme.typography.bodyMedium, color = c.accent)
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseButton(
                        label = "Copy link",
                        tone = PulseButtonTone.Quiet,
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("PulseDrop URL", url))
                        },
                        modifier = Modifier.testTag("home_qr_copy_btn")
                    )
                    PulseButton(label = "Close", onClick = onDismiss)
                }
            }
        }
    }
}
