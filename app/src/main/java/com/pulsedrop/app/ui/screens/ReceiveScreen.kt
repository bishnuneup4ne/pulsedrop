package com.pulsedrop.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseIconButton
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.PulseSwitch
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The receiving half of PulseDrop. Every row here is a real queue the server keeps: pending
 * uploads are bytes parked on disk awaiting a human decision, and history rows point at files
 * that exist in the received folder. Nothing is simulated, and accepting a file never implies
 * trusting whoever sent it.
 */

/** What "recently received" means, in one honest filter: completed and incoming, newest first. */
internal fun receivedRows(history: List<TransferHistoryEntity>): List<TransferHistoryEntity> =
    history.filter { it.direction == "INCOMING" && it.status == "COMPLETED" }
        .sortedByDescending { it.timestamp }

/** Below this much free space where files land, incoming uploads start failing. */
internal const val LOW_SPACE_BYTES = 100L * 1024 * 1024

@Composable
fun ReceiveScreen(
    serverRunning: Boolean,
    autoAccept: Boolean,
    pendingUploads: List<PendingUploadInfo>,
    incomingActive: List<TransferItem>,
    receivedHistory: List<TransferHistoryEntity>,
    receiveDir: String,
    freeSpaceBytes: Long,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onSetAutoAccept: (Boolean) -> Unit,
    onOpenFile: (String?) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onToggleServer: () -> Unit,
    onOpenTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val clipboard = LocalClipboardManager.current
    val dateFormatter = remember { SimpleDateFormat("MMM d • HH:mm", Locale.getDefault()) }
    var removeTarget by remember { mutableStateOf<TransferHistoryEntity?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PulseSpacing.lg.dp),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                PulseButton(
                    label = "History",
                    icon = Icons.Default.History,
                    tone = PulseButtonTone.Quiet,
                    onClick = onOpenTransfers
                )
            }
        }

        // ── 1. Is this phone even listening? ───────────────────────────────
        if (!serverRunning) {
            item {
                PulseStatePanel(
                    icon = Icons.Default.Pause,
                    title = "Receiving is off",
                    message = "Nothing can arrive while the server is stopped.",
                    actionLabel = "Start server",
                    onAction = onToggleServer,
                    tone = PulseStatusTone.Neutral,
                    modifier = Modifier.testTag("receive_start_server_btn")
                )
            }
        } else {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("receive_listening_card"),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                ) {
                    PulseStatus(
                        text = "Listening on your network",
                        tone = PulseStatusTone.Positive
                    )
                }
            }
        }

        // ── 2. The policy, as an explicit choice ───────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("receive_policy_card"),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                PulseRowLabel("When something arrives")
                PulseSwitch(
                    checked = autoAccept,
                    onCheckedChange = onSetAutoAccept,
                    label = "Accept files automatically",
                    detail = if (autoAccept)
                        "On — every file that passes the PIN lands in the received folder without asking."
                    else
                        "Off — files wait below for you to accept or decline, one at a time.",
                    modifier = Modifier.testTag("receive_auto_accept")
                )
            }
        }

        // ── 3. Waiting for a decision ──────────────────────────────────────
        item {
            PulseRowLabel("Needs your decision") {
                if (pendingUploads.isNotEmpty()) {
                    Text(
                        "${pendingUploads.size} waiting",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textTertiary
                    )
                }
            }
        }
        if (pendingUploads.isEmpty() && serverRunning && !autoAccept) {
            item {
                Text(
                    text = "Nothing waiting — files stop here first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .testTag("receive_queue_empty")
                )
            }
        }
        itemsIndexed(pendingUploads, key = { _, upload -> "pending_" + upload.id }) { index, upload ->
            Column {
                if (index > 0) PulseRowDivider(insetStart = 54)
                PendingUploadRow(upload = upload, onAccept = { onAccept(upload.id) }, onDecline = { onDecline(upload.id) })
            }
        }

        // ── 4. In transit right now ────────────────────────────────────────
        if (incomingActive.isNotEmpty()) {
            item { PulseRowLabel("Arriving right now") }
            itemsIndexed(incomingActive, key = { _, item -> "active_" + item.id }) { index, item ->
                Column {
                    if (index > 0) PulseRowDivider(insetStart = 54)
                    PulseRow(
                        title = item.fileName,
                        subtitle = if (item.fileSize >= 0)
                            "${NetworkUtils.formatBytes(item.fileSize)} · from ${item.remoteDevice}"
                        else "from ${item.remoteDevice}",
                        icon = Icons.Default.Inbox,
                        iconTint = c.accent,
                        showChevron = false,
                        modifier = Modifier.testTag("receive_active_${item.id}")
                    )
                }
            }
        }

        // ── 5. Where files land ────────────────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("receive_dest_card"),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                PulseRowLabel("Where files land")
                // Same geometry as PulseRow; the folder name carries its own test tag.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = c.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            receiveDir.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("receive_dir_value")
                        )
                        Text(
                            receiveDir,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    PulseIconButton(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy folder location",
                        onClick = { clipboard.setText(AnnotatedString(receiveDir)) }
                    )
                }
                if (freeSpaceBytes in 0 until LOW_SPACE_BYTES) {
                    PulseStatus(
                        text = "Almost no room left",
                        tone = PulseStatusTone.Warning,
                        detail = "${NetworkUtils.formatBytes(freeSpaceBytes)} free — uploads fail until you clear space.",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .testTag("receive_low_space")
                    )
                } else if (freeSpaceBytes >= 0) {
                    Text(
                        "${NetworkUtils.formatBytes(freeSpaceBytes)} free in that folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .testTag("receive_free_space")
                    )
                }
            }
        }

        // ── 6. Recently received ───────────────────────────────────────────
        item {
            PulseRowLabel("Recently received")
        }
        val received = receivedRows(receivedHistory)
        if (received.isEmpty()) {
            item {
                Text(
                    "Nothing received yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .testTag("receive_history_empty")
                    )
            }
        }
        itemsIndexed(received.take(20), key = { _, row -> "recv_" + row.id }) { index, row ->
            Column {
                if (index > 0) PulseRowDivider(insetStart = 54)
                Column(modifier = Modifier.testTag("receive_history_${row.id}")) {
                    PulseRow(
                        title = row.fileName,
                        subtitle = "${NetworkUtils.formatBytes(row.fileSize)} · ${dateFormatter.format(Date(row.timestamp))}" +
                            " · from ${row.remoteDevice}",
                        icon = Icons.Default.Description,
                        iconTint = c.textSecondary,
                        showChevron = false,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                                PulseIconButton(
                                    icon = Icons.Default.Visibility,
                                    label = "Open ${row.fileName}",
                                    onClick = { onOpenFile(row.localFilePath) },
                                    modifier = Modifier.testTag("receive_open_${row.id}")
                                )
                                PulseIconButton(
                                    icon = Icons.Default.ContentCopy,
                                    label = "Copy file location",
                                    onClick = { row.localFilePath?.let { clipboard.setText(AnnotatedString(it)) } },
                                    modifier = Modifier.testTag("receive_copy_${row.id}")
                                )
                                PulseIconButton(
                                    icon = Icons.Default.Delete,
                                    label = "Remove ${row.fileName} from history",
                                    onClick = { removeTarget = row },
                                    modifier = Modifier.testTag("receive_remove_${row.id}")
                                )
                            }
                        }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(PulseSpacing.xxl.dp)) }
    }

    // Removal says exactly what it does and does not do, and asks before doing it.
    removeTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove from history?") },
            text = {
                Text(
                    "${row.fileName} stays on this phone — only the history entry is removed."
                )
            },
            confirmButton = {
                PulseButton(
                    label = "Remove entry",
                    tone = PulseButtonTone.Danger,
                    onClick = {
                        onRemoveHistory(row.id)
                        removeTarget = null
                    },
                    modifier = Modifier.testTag("receive_remove_confirm")
                )
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Keep it") }
            }
        )
    }
}

@Composable
private fun PendingUploadRow(
    upload: PendingUploadInfo,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val c = LocalPulseColors.current
    Column(modifier = Modifier.testTag("receive_pending_${upload.id}")) {
        PulseRow(
            title = upload.fileName,
            subtitle = "${NetworkUtils.formatBytes(upload.fileSize)} · from ${upload.fromDevice}",
            icon = Icons.Default.Description,
            iconTint = c.warning,
            showChevron = false,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    PulseButton(
                        label = "Decline",
                        tone = PulseButtonTone.Danger,
                        onClick = onDecline,
                        modifier = Modifier.testTag("receive_decline_${upload.id}")
                    )
                    PulseButton(
                        label = "Accept",
                        onClick = onAccept,
                        modifier = Modifier.testTag("receive_accept_${upload.id}")
                    )
                }
            }
        )
    }
}
