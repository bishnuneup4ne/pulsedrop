package com.pulsedrop.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.pulsedrop.app.ui.components.PulseInlineBanner
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.components.PulseSection
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.ServerCard
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.UpdateInfo
import com.pulsedrop.app.util.NetworkUtils
import android.graphics.Bitmap

/**
 * Home, in the order a person actually asks things: is the server up, what is the address,
 * what needs a decision, who is connected, what is moving, what is hosted. Every section is
 * the same Instagram-style list: a small gray label, rows of icon + title + subtitle, and a
 * hairline between rows that starts where the text starts.
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
                                        "from ${upload.fromDevice}",
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
                                "want${if (incomingPairCount == 1) "s" else ""} to pair",
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
            Column {
                PulseRowLabel("Devices") {
                    Text(
                        "$peersCount nearby • ${pairedDevices.size} paired",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textTertiary
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onNavigate(PulseSection.DEVICES) },
                        modifier = Modifier.testTag("home_manage_devices_btn")
                    ) { Text("Manage all", style = MaterialTheme.typography.labelLarge) }
                }
                pairedDevices.take(3).forEachIndexed { index, device ->
                    if (index > 0) PulseRowDivider(insetStart = 52)
                    DeviceRow(device) { onNavigate(PulseSection.DEVICES) }
                }
                if (pairedDevices.size > 3) {
                    PulseRow(
                        title = "+${pairedDevices.size - 3} more",
                        icon = null,
                        showChevron = false,
                        onClick = { onNavigate(PulseSection.DEVICES) }
                    )
                }
            }
        }

        // 4 ── Transfers: in flight first, then what recently finished. One section.
        item {
            Column {
                PulseRowLabel("Transfers") {
                    if (inflight.isNotEmpty()) {
                        Text(
                            "In transit • ${inflight.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.textTertiary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(
                        onClick = { onNavigate(PulseSection.TRANSFERS) },
                        modifier = Modifier.testTag("home_all_transfers_btn")
                    ) { Text("View all", style = MaterialTheme.typography.labelLarge) }
                }
                if (inflight.isEmpty()) {
                    Text(
                        "Nothing in transit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                } else {
                    ActiveTransfersCard(
                        activeTransfers = inflight,
                        onRowClick = onOpenTransferDetails
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (history.isNotEmpty()) {
                    history.take(3).forEachIndexed { index, item ->
                        if (index > 0) PulseRowDivider(insetStart = 52)
                        ActivityRow(item, onClick = {
                            if (onOpenTransferDetails != null) onOpenTransferDetails(item.id)
                            else onNavigate(PulseSection.TRANSFERS)
                        })
                    }
                }
            }
        }

        // 5 ── Files & storage.
        item {
            Column {
                PulseRowLabel("Files & storage") {
                    Text(
                        if (sharedFiles.isEmpty()) "Nothing hosted"
                        else "${sharedFiles.size} file${if (sharedFiles.size == 1) "" else "s"} • " +
                            NetworkUtils.formatBytes(hostedBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textTertiary
                    )
                    Spacer(Modifier.width(8.dp))
                    PulseButton(
                        label = "Add",
                        icon = Icons.Default.Add,
                        tone = PulseButtonTone.Quiet,
                        onClick = { documentPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("home_add_files_btn")
                    )
                }
                if (sharedFiles.isEmpty()) {
                    Text(
                        "No files hosted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                    )
                } else {
                    sharedFiles.take(3).forEachIndexed { index, file ->
                        if (index > 0) PulseRowDivider(insetStart = 52)
                        HostedFileRow(file = file, onRemove = { onRemoveFile(file.id) })
                    }
                    if (sharedFiles.size > 3) {
                        PulseRowDivider(insetStart = 52)
                        PulseRow(
                            title = "+${sharedFiles.size - 3} more on the server",
                            showChevron = false,
                            onClick = { onNavigate(PulseSection.RECEIVE_FILES) }
                        )
                    }
                    PulseRowDivider(insetStart = 52)
                    PulseRow(
                        title = "Clear all",
                        icon = Icons.Default.Delete,
                        titleColor = c.danger,
                        showChevron = false,
                        onClick = onClearFiles,
                        modifier = Modifier.testTag("home_clear_files_btn")
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(PulseSpacing.xl.dp)) }
    }
}

@Composable
private fun DeviceRow(device: PairedDevice, onClick: () -> Unit) {
    val c = LocalPulseColors.current
    PulseRow(
        title = device.name,
        // Trust is stated in words, never left to the colour of an icon.
        subtitle = if (device.verified) "Verified" else "Not verified",
        icon = if (device.verified) Icons.Default.CheckCircle else Icons.Default.Warning,
        iconTint = if (device.verified) c.success else c.warning,
        detail = device.originIp,
        onClick = onClick
    )
}

@Composable
private fun ActivityRow(item: TransferHistoryEntity, onClick: () -> Unit) {
    val c = LocalPulseColors.current
    val isIncoming = item.direction == TransferDirection.INCOMING.name
    val failed = item.status == "FAILED"
    PulseRow(
        title = item.fileName,
        subtitle = (if (failed) "Failed" else if (isIncoming) "Received" else "Sent") +
            " · ${item.remoteDevice}",
        icon = when {
            failed -> Icons.Default.Error
            isIncoming -> Icons.AutoMirrored.Filled.ArrowBack
            else -> Icons.AutoMirrored.Filled.ArrowForward
        },
        iconTint = if (failed) c.danger else c.textTertiary,
        detail = agoOf(item.timestamp),
        showChevron = false,
        onClick = onClick
    )
}

@Composable
private fun HostedFileRow(file: SharedFile, onRemove: () -> Unit) {
    val c = LocalPulseColors.current
    PulseRow(
        title = file.name,
        subtitle = NetworkUtils.formatBytes(file.size),
        icon = Icons.Default.Description,
        iconTint = c.accent,
        showChevron = false,
        trailing = {
            TextButton(
                onClick = onRemove,
                modifier = Modifier.testTag("home_remove_file_${file.id}")
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${file.name}",
                    tint = c.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    )
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
