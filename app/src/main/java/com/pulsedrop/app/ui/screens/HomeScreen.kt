package com.pulsedrop.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.ServerCard
import com.pulsedrop.app.ui.components.PulseSection
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.UpdateInfo
import com.pulsedrop.app.util.NetworkUtils
import android.graphics.Bitmap

/**
 * Home, in the order a person actually asks things: is the server up, what is the address,
 * who is connected, what is moving, what needs a decision. Sections carry their own headings
 * and plain rows — no wall of stat tiles, no duplicated Live badges, no card inside a card.
 */
@Composable
fun HomeScreen(
    serverState: UiServerState,
    qrBitmap: Bitmap?,
    sharedFiles: List<SharedFile>,
    activeTransfers: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    pairedDevices: List<PairedDevice>,
    peersCount: Int,
    webClientsCount: Int,
    pendingUploads: List<PendingUploadInfo>,
    incomingPairCount: Int,
    relayHostName: String?,
    callInProgress: Boolean,
    isSharingScreen: Boolean,
    updateInfo: UpdateInfo?,
    onToggleServer: () -> Unit,
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

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onAddFiles(uris)
    }

    val inflight = activeTransfers.filter {
        it.status == TransferStatus.QUEUED || it.status == TransferStatus.IN_PROGRESS
    }
    val failed = activeTransfers.filter { it.status == TransferStatus.FAILED }
    val hostedBytes = sharedFiles.sumOf { it.size }
    val unverifiedPairs = pairedDevices.count { !it.verified }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PulseSpacing.lg.dp),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg.dp)
    ) {
        // 1 ── The connection card: the main visual focus of the page.
        item {
            ServerCard(
                serverState = serverState,
                qrBitmap = qrBitmap,
                onToggleServer = onToggleServer,
                relayHostName = relayHostName
            )
        }

        if (callInProgress) {
            item {
                PulseChip(
                    label = if (isSharingScreen) "Stop sharing" else "Share screen",
                    leadingIcon = Icons.Default.ScreenShare,
                    selected = isSharingScreen,
                    onClick = onToggleScreenShare,
                    modifier = Modifier.testTag("home_share_chip")
                )
            }
        }

        // 2 ── Decisions waiting on the phone. Only rendered when something really is.
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

        // 3 ── Devices: who is reachable, who is trusted.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader(
                    "Devices",
                    detail = "$peersCount nearby • ${pairedDevices.size} paired",
                    action = {
                        TextButton(
                            onClick = { onNavigate(PulseSection.DEVICES) },
                            modifier = Modifier.testTag("home_manage_devices_btn")
                        ) { Text("Manage all", style = MaterialTheme.typography.labelLarge) }
                    }
                )
                if (pairedDevices.isEmpty()) {
                    Text(
                        "No paired devices yet. Pairing gives you chat, calls and encrypted transfers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                } else {
                    Column {
                        pairedDevices.take(3).forEachIndexed { index, device ->
                            if (index > 0) PulseDivider()
                            DeviceSummaryRow(device) { onNavigate(PulseSection.DEVICES) }
                        }
                        if (pairedDevices.size > 3) {
                            Text(
                                "+${pairedDevices.size - 3} more",
                                style = MaterialTheme.typography.labelMedium,
                                color = c.textTertiary,
                                modifier = Modifier.padding(top = PulseSpacing.xs.dp)
                            )
                        }
                    }
                }
            }
        }

        // 4 ── Transfers: in flight first, then what recently finished. One section.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader(
                    "Transfers",
                    detail = if (inflight.isNotEmpty()) "In transit • ${inflight.size}" else null,
                    action = {
                        TextButton(
                            onClick = { onNavigate(PulseSection.TRANSFERS) },
                            modifier = Modifier.testTag("home_all_transfers_btn")
                        ) { Text("View all", style = MaterialTheme.typography.labelLarge) }
                    }
                )
                if (inflight.isEmpty()) {
                    Text(
                        "Nothing in transit. Files you send or receive show up here live.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                } else {
                    ActiveTransfersCard(
                        activeTransfers = inflight,
                        onRowClick = onOpenTransferDetails
                    )
                }
                if (history.isNotEmpty()) {
                    Column {
                        history.take(3).forEachIndexed { index, item ->
                            if (index > 0) PulseDivider()
                            ActivityRow(item)
                        }
                    }
                }
            }
        }

        // 5 ── Files & storage.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseSectionHeader(
                    "Files & storage",
                    detail = if (sharedFiles.isEmpty()) "Nothing hosted"
                    else "${sharedFiles.size} file${if (sharedFiles.size == 1) "" else "s"} • " +
                        NetworkUtils.formatBytes(hostedBytes),
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
                    Text(
                        "Files you add go live on the browser server for every device on the network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                        sharedFiles.take(3).forEach { file ->
                            HostedFileRow(file = file, onRemove = { onRemoveFile(file.id) })
                        }
                        if (sharedFiles.size > 3) {
                            Text(
                                "+${sharedFiles.size - 3} more on the server",
                                style = MaterialTheme.typography.labelMedium,
                                color = c.textTertiary
                            )
                        }
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

        // 6 ── Health & security, one plain list with the door to the details.
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_security_summary")
            ) {
                PulseSectionHeader(
                    "Connection & security",
                    action = {
                        TextButton(
                            onClick = { onNavigate(PulseSection.SETTINGS) },
                            modifier = Modifier.testTag("home_security_settings_btn")
                        ) { Text("Settings", style = MaterialTheme.typography.labelLarge) }
                    }
                )
                SummaryRow(
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
                SummaryRow(
                    icon = Icons.Default.CheckCircle,
                    text = if (serverState.autoAcceptTransfers)
                        "Incoming files land without asking"
                    else
                        "You approve every incoming upload",
                    tone = if (serverState.autoAcceptTransfers)
                        PulseStatusTone.Neutral else PulseStatusTone.Positive
                )
                SummaryRow(
                    icon = Icons.Default.Shield,
                    text = when {
                        pairedDevices.isEmpty() -> "No pairings yet"
                        unverifiedPairs > 0 -> "$unverifiedPairs of ${pairedDevices.size} ${if (pairedDevices.size == 1) "pairing" else "pairings"} not verified"
                        else -> "All ${pairedDevices.size} ${if (pairedDevices.size == 1) "pairing" else "pairings"} verified"
                    },
                    tone = when {
                        pairedDevices.isEmpty() -> PulseStatusTone.Neutral
                        unverifiedPairs > 0 -> PulseStatusTone.Warning
                        else -> PulseStatusTone.Positive
                    }
                )
                SummaryRow(
                    icon = Icons.Default.Warning,
                    text = when {
                        serverState.isRunning -> "${webClientsCount} browser${if (webClientsCount == 1) "" else "s"} " +
                            "connected · ${serverState.networkName}"
                        relayHostName != null -> "Chatting through $relayHostName's server"
                        else -> "Not on a network yet"
                    },
                    tone = PulseStatusTone.Neutral
                )
            }
        }

        item { Spacer(modifier = Modifier.height(PulseSpacing.xl.dp)) }
    }
}

@Composable
private fun DeviceSummaryRow(device: PairedDevice, onClick: () -> Unit) {
    val c = LocalPulseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PulseRadius.sm.dp))
            .clickable(onClick = onClick)
            .padding(vertical = PulseSpacing.md.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (device.verified) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (device.verified) c.success else c.warning,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(PulseSpacing.sm.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Trust is stated in words, never left to the colour of an icon.
            Text(
                if (device.verified) "Verified" else "Not verified",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary
            )
        }
        Text(
            device.originIp,
            style = MaterialTheme.typography.labelMedium,
            color = c.textTertiary
        )
    }
}

@Composable
private fun SummaryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tone: PulseStatusTone) {
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
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
            .clip(RoundedCornerShape(PulseRadius.md.dp))
            .background(c.surfaceElevated)
            .padding(start = PulseSpacing.md.dp, end = PulseSpacing.xs.dp, top = PulseSpacing.sm.dp, bottom = PulseSpacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(PulseRadius.sm.dp))
                .background(c.surfaceSunken),
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

private fun agoOf(timestamp: Long): String {
    val minutes = (System.currentTimeMillis() - timestamp) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
