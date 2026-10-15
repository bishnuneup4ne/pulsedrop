package com.pulsedrop.app.ui.screens

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
import androidx.compose.foundation.lazy.items
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
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseDivider
import com.pulsedrop.app.ui.components.PulseIconButton
import com.pulsedrop.app.ui.components.PulsePageHeader
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.PulseSwitch
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSizes
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
            PulsePageHeader(
                title = "Receive files",
                subtitle = "The queue, the folder and the policy — all of it is the real server state",
                status = {
                    PulseButton(
                        label = "History",
                        icon = Icons.Default.History,
                        tone = PulseButtonTone.Quiet,
                        onClick = onOpenTransfers
                    )
                }
            )
        }

        // ── 1. Is this phone even listening? ───────────────────────────────
        if (!serverRunning) {
            item {
                PulseStatePanel(
                    icon = Icons.Default.Pause,
                    title = "Receiving is off",
                    message = "The server is stopped, so nothing can arrive. Start it and devices on " +
                        "your network — and browser tabs on the web page — can send to this phone.",
                    actionLabel = "Start server",
                    onAction = onToggleServer,
                    tone = PulseStatusTone.Neutral,
                    modifier = Modifier.testTag("receive_start_server_btn")
                )
            }
        } else {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("receive_listening_card")) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                        PulseStatus(
                            text = "Listening on your network",
                            tone = PulseStatusTone.Positive,
                            detail = "Files arrive through the same server that serves your downloads."
                        )
                    }
                }
            }
        }

        // ── 2. The policy, as an explicit choice ───────────────────────────
        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("receive_policy_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseSectionHeader(
                        title = "When something arrives",
                        detail = "This is a security decision, not a convenience switch"
                    )
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
                    Text(
                        text = if (autoAccept)
                            "Anyone who can reach this server and knows the PIN can write files to this phone. " +
                                "Turn the switch off if you would rather see every file first."
                        else
                            "Declining deletes the file from the phone. Accepting stores it — " +
                                "it never pairs or trusts the sender.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary
                    )
                }
            }
        }

        // ── 3. Waiting for a decision ──────────────────────────────────────
        item {
            PulseSectionHeader(
                title = "Needs your decision",
                detail = if (pendingUploads.isEmpty()) null
                else "${pendingUploads.size} file(s) parked on disk, nothing stored yet"
            )
        }
        if (pendingUploads.isEmpty() && serverRunning && !autoAccept) {
            item {
                Text(
                    text = "Nothing waiting. With auto-accept off, every incoming file stops here " +
                        "until you say so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    modifier = Modifier.testTag("receive_queue_empty")
                )
            }
        }
        items(pendingUploads, key = { "pending_" + it.id }) { upload ->
            PendingUploadRow(upload = upload, onAccept = { onAccept(upload.id) }, onDecline = { onDecline(upload.id) })
        }

        // ── 4. In transit right now ────────────────────────────────────────
        if (incomingActive.isNotEmpty()) {
            item { PulseSectionHeader("Arriving right now") }
            items(incomingActive, key = { "active_" + it.id }) { item ->
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("receive_active_${item.id}")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
                    ) {
                        Icon(
                            Icons.Default.Inbox,
                            contentDescription = null,
                            tint = c.accent,
                            modifier = Modifier.size(PulseSizes.icon.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                color = c.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (item.fileSize >= 0)
                                    "${NetworkUtils.formatBytes(item.fileSize)} · from ${item.remoteDevice}"
                                else "from ${item.remoteDevice}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary
                            )
                        }
                    }
                }
            }
        }

        // ── 5. Where files land ────────────────────────────────────────────
        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("receive_dest_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseSectionHeader(
                        title = "Where files land",
                        detail = "One folder the app owns; the engine has no other destination"
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = c.textTertiary,
                            modifier = Modifier.size(PulseSizes.icon.dp)
                        )
                        Spacer(Modifier.width(PulseSpacing.sm.dp))
                        Text(
                            receiveDir,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = c.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).testTag("receive_dir_value")
                        )
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
                            detail = "${NetworkUtils.formatBytes(freeSpaceBytes)} free where files land — " +
                                "incoming uploads will fail until you clear space.",
                            modifier = Modifier.testTag("receive_low_space")
                        )
                    } else if (freeSpaceBytes >= 0) {
                        Text(
                            "${NetworkUtils.formatBytes(freeSpaceBytes)} free in that folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary,
                            modifier = Modifier.testTag("receive_free_space")
                        )
                    }
                    PulseDivider()
                    HonestLine("A taken name gets a timestamp appended. Accepting never overwrites a file already on disk.")
                    HonestLine("Sender-supplied paths are sanitized; directory escapes are rejected before anything is written.")
                    HonestLine("Choosing a different folder is not supported by the current engine, so no control pretends otherwise.")
                }
            }
        }

        // ── 6. Recently received ───────────────────────────────────────────
        item {
            PulseSectionHeader(
                title = "Recently received",
                detail = "From this phone's history — removing a row never deletes the file"
            )
        }
        val received = receivedRows(receivedHistory)
        if (received.isEmpty()) {
            item {
                Text(
                    "Nothing has been received on this phone yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    modifier = Modifier.testTag("receive_history_empty")
                )
            }
        }
        items(received.take(20), key = { "recv_" + it.id }) { row ->
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("receive_history_${row.id}")) {
                Column {
                    Text(
                        row.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${NetworkUtils.formatBytes(row.fileSize)} · ${dateFormatter.format(Date(row.timestamp))}" +
                            " · from ${row.remoteDevice}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    row.localFilePath?.let { path ->
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                        Box(Modifier.weight(1f))
                        PulseIconButton(
                            icon = Icons.Default.Delete,
                            label = "Remove ${row.fileName} from history",
                            onClick = { removeTarget = row },
                            modifier = Modifier.testTag("receive_remove_${row.id}")
                        )
                    }
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
                    "${row.fileName} stays on this phone. Only the entry in the history list is removed — " +
                        "the file itself is never deleted from here."
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
    PulseCard(modifier = Modifier.fillMaxWidth().testTag("receive_pending_${upload.id}")) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = c.warning,
                    modifier = Modifier.size(PulseSizes.icon.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        upload.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${NetworkUtils.formatBytes(upload.fileSize)} · from ${upload.fromDevice}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
            Text(
                "The sender named itself — treat that name as unverified. Accept stores the file; " +
                    "decline deletes it from the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseButton(
                    label = "Accept",
                    onClick = onAccept,
                    modifier = Modifier.testTag("receive_accept_${upload.id}")
                )
                PulseButton(
                    label = "Decline",
                    tone = PulseButtonTone.Danger,
                    onClick = onDecline,
                    modifier = Modifier.testTag("receive_decline_${upload.id}")
                )
            }
        }
    }
}

@Composable
private fun HonestLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalPulseColors.current.textTertiary
    )
}
