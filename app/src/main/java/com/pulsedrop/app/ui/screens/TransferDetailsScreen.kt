package com.pulsedrop.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseIconButton
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the record for a selected transfer id currently lives. The page resolves the id fresh
 * on every recomposition — nothing is cached per-route, so a stale id can never keep showing
 * the previous transfer's data.
 */
internal sealed interface TransferDetailModel {
    data class FromLive(val item: TransferItem) : TransferDetailModel
    data class FromDurable(val entity: TransferHistoryEntity) : TransferDetailModel
    data class FromPending(val upload: PendingUploadInfo) : TransferDetailModel
    data object Missing : TransferDetailModel
}

internal fun resolveTransferDetail(
    transferId: String?,
    active: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    pending: List<PendingUploadInfo>
): TransferDetailModel {
    if (transferId.isNullOrBlank()) return TransferDetailModel.Missing
    pending.firstOrNull { it.id == transferId }?.let { return TransferDetailModel.FromPending(it) }
    val live = active.firstOrNull { it.id == transferId }
    if (live != null) {
        val running = live.status == TransferStatus.IN_PROGRESS || live.status == TransferStatus.QUEUED
        if (running) return TransferDetailModel.FromLive(live)
        // The durable log owns terminal truth once it exists: it carries the path and reason.
        return history.firstOrNull { it.id == transferId }
            ?.let { TransferDetailModel.FromDurable(it) }
            ?: TransferDetailModel.FromLive(live)
    }
    history.firstOrNull { it.id == transferId }?.let { return TransferDetailModel.FromDurable(it) }
    return TransferDetailModel.Missing
}

internal fun detailStateLabel(model: TransferDetailModel): String = when (model) {
    is TransferDetailModel.FromPending -> "Awaiting approval"
    is TransferDetailModel.FromLive -> when (model.item.status) {
        TransferStatus.QUEUED -> "Queued"
        TransferStatus.IN_PROGRESS -> "Transferring"
        TransferStatus.COMPLETED -> "Completed"
        TransferStatus.FAILED -> "Failed"
        TransferStatus.CANCELLED -> "Canceled"
    }
    is TransferDetailModel.FromDurable -> when (model.entity.status) {
        "COMPLETED" -> "Completed"
        "FAILED" -> "Failed"
        else -> "Unrecognised (“${model.entity.status}”)"
    }
    TransferDetailModel.Missing -> "Not found"
}

internal fun detailStateTone(model: TransferDetailModel): PulseStatusTone = when (model) {
    is TransferDetailModel.FromPending -> PulseStatusTone.Warning
    is TransferDetailModel.FromLive -> when (model.item.status) {
        TransferStatus.IN_PROGRESS, TransferStatus.QUEUED -> PulseStatusTone.Active
        TransferStatus.COMPLETED -> PulseStatusTone.Positive
        else -> PulseStatusTone.Negative
    }
    is TransferDetailModel.FromDurable -> when (model.entity.status) {
        "COMPLETED" -> PulseStatusTone.Positive
        else -> PulseStatusTone.Negative
    }
    TransferDetailModel.Missing -> PulseStatusTone.Neutral
}

/** An ETA needs real numbers; anything else returns null and the UI says so instead. */
internal fun etaSecondsLeft(bytes: Long, total: Long, speedBytesPerSec: Long): Long? {
    if (total <= 0L || speedBytesPerSec <= 0L) return null
    val remaining = total - bytes
    if (remaining <= 0L) return null
    return remaining / speedBytesPerSec
}

internal fun formatEta(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    val s = seconds % 60
    if (m < 60) return "${m}m ${s}s"
    return "${m / 60}h ${m % 60}m"
}

/** Coarse, honest bucketing of the engine's free-text failure; never a fake error code. */
internal fun errorCategory(message: String?): String = when {
    message == null -> "Unrecorded"
    message.contains("Server responded") -> "Peer rejected the upload"
    message.contains("connect", true) || message.contains("resolve", true) ||
        message.contains("timeout", true) || message.contains("stream reset", true) -> "Network or peer vanished"
    message.contains("Cannot open uri") || message.contains("No such file") -> "Source file disappeared"
    else -> "Unclassified transport failure"
}

/**
 * Diagnostics text for copying. Deliberately excludes local file paths, PINs, pairing keys and
 * file contents — only what the engine truly measured, plus what it never measured.
 */
internal fun diagnosticsText(
    transferId: String,
    model: TransferDetailModel,
    transport: String,
    now: Long = System.currentTimeMillis()
): String = buildString {
    appendLine("PulseDrop transfer diagnostics")
    appendLine("transferId=$transferId")
    when (model) {
        is TransferDetailModel.FromLive -> {
            appendLine("recordSource=live engine")
            appendLine("state=${model.item.status}")
            appendLine("direction=${model.item.direction}")
            appendLine("fileName=${model.item.fileName}")
            appendLine("bytesTransferred=${model.item.bytesTransferred}")
            appendLine("totalBytes=${model.item.fileSize}")
            appendLine("speedBytesPerSec=${model.item.speedBytesPerSec}")
            appendLine("startedAt=${model.item.timestamp}")
            model.item.errorMessage?.let { appendLine("error=$it") }
        }
        is TransferDetailModel.FromDurable -> {
            appendLine("recordSource=durable log")
            appendLine("state=${model.entity.status}")
            appendLine("direction=${model.entity.direction}")
            appendLine("fileName=${model.entity.fileName}")
            appendLine("totalBytes=${model.entity.fileSize}")
            appendLine("recordedAt=${model.entity.timestamp}")
            model.entity.errorMessage?.let { appendLine("error=$it") }
        }
        is TransferDetailModel.FromPending -> {
            appendLine("recordSource=staged upload queue")
            appendLine("state=AWAITING_APPROVAL")
            appendLine("direction=INCOMING")
            appendLine("fileName=${model.upload.fileName}")
            appendLine("totalBytes=${model.upload.fileSize}")
            appendLine("arrivalTime=not recorded by the server")
        }
        TransferDetailModel.Missing -> appendLine("recordSource=none; id matches no live, durable or staged record")
    }
    appendLine("transport=$transport")
    appendLine("integrity=transport framing only; no content hash is computed")
    appendLine("pauseResumeCancelRetry=not implemented by the transfer engine")
    appendLine("capturedAt=$now")
}

@Composable
fun TransferDetailsScreen(
    transferId: String?,
    activeTransfers: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    pendingUploads: List<PendingUploadInfo>,
    pairedDevices: List<PairedDevice>,
    nearbyPeers: List<PeerDevice>,
    onBack: () -> Unit,
    onOpenFile: (String?) -> Unit,
    onSaveToDownloads: (String?) -> Unit,
    onAcceptPending: (String) -> Unit,
    onDeclinePending: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onDismissLive: (String) -> Unit,
    onOpenSend: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPulseColors.current
    val dateFormatter = remember { SimpleDateFormat("MMM d • HH:mm", Locale.getDefault()) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val model = remember(transferId, activeTransfers, history, pendingUploads) {
        resolveTransferDetail(transferId, activeTransfers, history, pendingUploads)
    }
    val id = transferId.orEmpty()

    // Shared facts across all sources so the sections below stay declarative.
    val fileName: String?
    val totalBytes: Long
    val bytesDone: Long
    val speed: Long
    val direction: String // "INCOMING" / "OUTGOING" / null-ish
    val remoteName: String
    val peerIp: String
    val errorMessage: String?
    val startedAt: Long?
    val recordedAt: Long?
    val localPath: String?
    when (model) {
        is TransferDetailModel.FromLive -> {
            fileName = model.item.fileName; totalBytes = model.item.fileSize
            bytesDone = model.item.bytesTransferred; speed = model.item.speedBytesPerSec
            direction = model.item.direction.name; remoteName = model.item.remoteDevice
            peerIp = model.item.clientIp; errorMessage = model.item.errorMessage
            startedAt = model.item.timestamp; recordedAt = null; localPath = model.item.localFilePath
        }
        is TransferDetailModel.FromDurable -> {
            fileName = model.entity.fileName; totalBytes = model.entity.fileSize
            bytesDone = if (model.entity.status == "COMPLETED") model.entity.fileSize else 0L
            speed = 0L; direction = model.entity.direction; remoteName = model.entity.remoteDevice
            peerIp = model.entity.clientIp ?: ""; errorMessage = model.entity.errorMessage
            startedAt = null; recordedAt = model.entity.timestamp; localPath = model.entity.localFilePath
        }
        is TransferDetailModel.FromPending -> {
            fileName = model.upload.fileName; totalBytes = model.upload.fileSize
            bytesDone = model.upload.fileSize; speed = 0L
            direction = TransferDirection.INCOMING.name
            remoteName = model.upload.fromDevice; peerIp = ""
            errorMessage = null; startedAt = null; recordedAt = null; localPath = null
        }
        TransferDetailModel.Missing -> {
            fileName = null; totalBytes = -1; bytesDone = -1; speed = -1
            direction = ""; remoteName = ""; peerIp = ""; errorMessage = null
            startedAt = null; recordedAt = null; localPath = null
        }
    }
    val running = model is TransferDetailModel.FromLive &&
        (model.item.status == TransferStatus.IN_PROGRESS || model.item.status == TransferStatus.QUEUED)
    val fileThere = remember(localPath) { !localPath.isNullOrBlank() && File(localPath).exists() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PulseSpacing.lg.dp),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PulseSpacing.md.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
        ) {
            PulseIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Back to transfers",
                onClick = onBack,
                modifier = Modifier.testTag("details_back")
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName ?: "Transfer not found",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("details_title")
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                ) {
                    PulseStatus(
                        text = detailStateLabel(model),
                        tone = detailStateTone(model),
                        modifier = Modifier.testTag("details_status")
                    )
                    if (model !is TransferDetailModel.Missing && totalBytes >= 0) {
                        Text(
                            text = NetworkUtils.formatBytes(totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        if (model is TransferDetailModel.Missing) {
            PulseStatePanel(
                icon = Icons.Default.History,
                title = "Nothing lives behind this id",
                message = "The transfer was removed from the log, dismissed from the live list, or the id never existed. " +
                    "No other transfer's data is shown — the engine simply has no record to read.",
                actionLabel = "Back to transfers",
                onAction = onBack,
                modifier = Modifier.testTag("details_missing")
            )
            Spacer(modifier = Modifier.height(PulseSpacing.xxl.dp))
            return@Column
        }

        // ── Action toolbar ────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                if (model is TransferDetailModel.FromPending) {
                    PulseButton(
                        label = "Accept",
                        onClick = { onAcceptPending(model.upload.id) },
                        tone = PulseButtonTone.Primary,
                        modifier = Modifier.testTag("details_accept")
                    )
                    PulseButton(
                        label = "Decline",
                        onClick = { onDeclinePending(model.upload.id) },
                        tone = PulseButtonTone.Danger,
                        modifier = Modifier.testTag("details_decline")
                    )
                }
                if (fileThere) {
                    PulseButton(
                        label = "Open file",
                        onClick = { onOpenFile(localPath) },
                        tone = PulseButtonTone.Neutral,
                        icon = Icons.Default.Download,
                        modifier = Modifier.testTag("details_open")
                    )
                    PulseButton(
                        label = "Save to Downloads",
                        onClick = { onSaveToDownloads(localPath) },
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("details_save")
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                if (running) {
                    PulseButton(
                        label = "Cancel",
                        onClick = {},
                        enabled = false,
                        disabledReason = "The transfer engine has no cancel operation — closing the app or the peer is the only stop",
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("details_cancel_disabled")
                    )
                    PulseButton(
                        label = "Pause",
                        onClick = {},
                        enabled = false,
                        disabledReason = "The transfer engine has no pause operation",
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("details_pause_disabled")
                    )
                }
                if (detailStateLabel(model) == "Failed") {
                    PulseButton(
                        label = "Start again in Send",
                        onClick = onOpenSend,
                        tone = PulseButtonTone.Neutral,
                        icon = Icons.Default.Send,
                        modifier = Modifier.testTag("details_resend")
                    )
                    PulseButton(
                        label = "Retry",
                        onClick = {},
                        enabled = false,
                        disabledReason = "No checkpoint exists — a retry would be a brand-new transfer, so use Send",
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("details_retry_disabled")
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseChip(
                    label = "Copy id",
                    leadingIcon = Icons.Default.ContentCopy,
                    onClick = { onCopyText(id) },
                    modifier = Modifier.testTag("details_copy_id")
                )
                if (model !is TransferDetailModel.FromPending) {
                    PulseChip(
                        label = if (model.isHistoryRecord()) "Remove from history" else "Hide from list",
                        leadingIcon = Icons.Default.Delete,
                        onClick = {
                            if (model.isHistoryRecord()) onDeleteHistory(id) else onDismissLive(id)
                            onBack()
                        },
                        modifier = Modifier.testTag("details_remove")
                    )
                }
            }
        }

        // ── Progress ──────────────────────────────────────────────────────
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PulseSpacing.lg.dp),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                PulseSectionHeader("Progress", detail = if (running) "Measured from the wire, throttled by the engine" else null)
                when {
                    model is TransferDetailModel.FromPending -> {
                        Text(
                            text = "All ${NetworkUtils.formatBytes(totalBytes)} already sit on this phone in the staging folder, " +
                                "paused until you accept or decline. Nothing has been released into the received folder.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.testTag("details_progress_pending")
                        )
                    }
                    totalBytes == 0L -> {
                        Text(
                            text = "A 0-byte file has no byte count to measure. Completion here means the request itself finished.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.testTag("details_progress_zerobyte")
                        )
                    }
                    totalBytes > 0L -> {
                        val fraction = (bytesDone.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .testTag("details_progress_bar")
                        )
                        Text(
                            text = "${NetworkUtils.formatBytes(bytesDone)} of ${NetworkUtils.formatBytes(totalBytes)} • ${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.testTag("details_progress_counts")
                        )
                    }
                    else -> Text(
                        text = "The record stores no byte count, so no percentage is shown.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
                if (running) {
                    val eta = etaSecondsLeft(bytesDone, totalBytes, speed)
                    Text(
                        text = buildString {
                            append("Speed: ${NetworkUtils.formatSpeed(speed)}")
                            append(" — ")
                            append(
                                if (eta != null) "about ${formatEta(eta)} left at this measured speed"
                                else "no stable estimate yet (ETA needs a non-zero measured speed and a known total)"
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.testTag("details_eta")
                    )
                }
                startedAt?.let {
                    Text(
                        text = "Started ${dateFormatter.format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                recordedAt?.let {
                    Text(
                        text = "Recorded ${dateFormatter.format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                if (model is TransferDetailModel.FromPending) {
                    Text(
                        text = "Arrival time is not recorded by the staging queue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Text(
                    text = "One file per transfer: a multi-file send appears as one transfer each.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.testTag("details_single_file")
                )
            }
        }

        // ── Between which devices ─────────────────────────────────────────
        PulseCard(modifier = Modifier.fillMaxWidth().testTag("details_peers")) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PulseSpacing.lg.dp),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
            ) {
                PulseSectionHeader("Between which devices")
                DetailFact(
                    if (direction == "INCOMING") "From" else "To",
                    remoteName.ifBlank { "Unknown device" }
                )
                DetailFact("This phone", "the PulseDrop app you are reading")
                if (peerIp.isNotBlank()) DetailFact("Peer address", peerIp)
                val paired = pairedDevices.firstOrNull { it.originIp.isNotBlank() && it.originIp == peerIp }
                val visibleNow = nearbyPeers.any { peerIp.isNotBlank() && it.ip == peerIp }
                Text(
                    text = when {
                        paired != null && paired.verified ->
                            "Paired and trusted: both sides confirmed the safety code."
                        paired != null ->
                            "Paired, but the safety code was never confirmed on this phone — the key is trusted, the identity is not proven."
                        model is TransferDetailModel.FromPending || direction == "INCOMING" ->
                            "Identity unverified: this name arrived with the request and proves nothing. Network proximity is not authorisation."
                        else ->
                            "Unpaired device: the transfer was authorised by the PIN entered at the time, not by a verified identity."
                    } + if (peerIp.isNotBlank() && !visibleNow && model is TransferDetailModel.FromLive)
                        " The peer is not currently visible on the network."
                    else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (paired != null && paired.verified) colors.success else colors.warning,
                    modifier = Modifier.testTag("details_trust")
                )
            }
        }

        // ── Integrity verification ────────────────────────────────────────
        PulseCard(modifier = Modifier.fillMaxWidth().testTag("details_integrity")) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PulseSpacing.lg.dp),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
            ) {
                PulseSectionHeader("Integrity verification", detail = "Not supported — shown as the protocol is, not as one would like it")
                Text(
                    text = "This transfer engine computes no content hash and the protocol authenticates no checksum. " +
                        "“Completed” means every declared byte reached the peer and the request closed successfully — " +
                        "transport framing only. Nothing here has been independently verified, and no file is scanned or safe by virtue of completing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        // ── Errors and recovery ───────────────────────────────────────────
        if (detailStateLabel(model) == "Failed") {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("details_error")) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = colors.danger)
                        Spacer(modifier = Modifier.width(PulseSpacing.sm.dp))
                        Text("What happened", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    }
                    Text(
                        text = errorMessage ?: "The failure carried no message, and rows written before reasons were stored never kept one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.danger,
                        modifier = Modifier.testTag("details_error_message")
                    )
                    DetailFact("Category", errorCategory(errorMessage))
                    recordedAt?.let { DetailFact("Occurred", dateFormatter.format(Date(it))) }
                    Text(
                        text = if (direction == "INCOMING")
                            "Recovery: nothing was released into the received folder. The sender simply uploads again; there is no resume checkpoint."
                        else
                            "Recovery: re-send the file from the Send page. A retry restarts the whole transfer — the engine keeps no checkpoint to resume from.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.testTag("details_error_guidance")
                    )
                }
            }
        }

        // ── Timeline ──────────────────────────────────────────────────────
        PulseCard(modifier = Modifier.fillMaxWidth().testTag("details_timeline")) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PulseSpacing.lg.dp),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
            ) {
                PulseSectionHeader("Timeline")
                if (startedAt != null) {
                    TimelineRow(dateFormatter.format(Date(startedAt)), if (running) "Transfer started — bytes have been moving since" else "Transfer started")
                }
                if (model is TransferDetailModel.FromPending) {
                    TimelineRow("—", "Staged on this phone, waiting for your decision (arrival time not recorded)")
                }
                if (recordedAt != null) {
                    TimelineRow(
                        dateFormatter.format(Date(recordedAt)),
                        when (detailStateLabel(model)) {
                            "Completed" -> "Recorded complete — the durable log wrote this row"
                            "Failed" -> "Recorded as failed"
                            else -> "Recorded (${detailStateLabel(model)})"
                        }
                    )
                }
                if (startedAt == null && recordedAt == null && model !is TransferDetailModel.FromPending) {
                    Text(
                        text = "The engine stored no timestamps for this record beyond what you see above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Text(
                    text = "Only timestamps the engine actually persisted are listed; there is no fuller event log to show.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        // ── Diagnostics ───────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                PulseChip(
                    label = if (showDiagnostics) "Hide diagnostics" else "Technical diagnostics",
                    onClick = { showDiagnostics = !showDiagnostics },
                    modifier = Modifier.testTag("details_diag_toggle")
                )
                if (showDiagnostics) {
                    PulseChip(
                        label = "Copy diagnostics",
                        leadingIcon = Icons.Default.ContentCopy,
                        onClick = {
                            onCopyText(
                                diagnosticsText(
                                    transferId = id,
                                    model = model,
                                    transport = if (remoteName.startsWith("Paired •"))
                                        "encrypted paired channel over the local network"
                                    else
                                        "HTTP upload over the local network"
                                )
                            )
                        },
                        modifier = Modifier.testTag("details_copy_diag")
                    )
                }
            }
            if (showDiagnostics) {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("details_diag_block")) {
                    Text(
                        text = diagnosticsText(
                            transferId = id,
                            model = model,
                            transport = if (remoteName.startsWith("Paired •"))
                                "encrypted paired channel over the local network"
                            else
                                "HTTP upload over the local network"
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PulseSpacing.lg.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(PulseSpacing.xxl.dp))
    }
}

/** History rows are the only ones with a durable record to delete. */
private fun TransferDetailModel.isHistoryRecord(): Boolean = this is TransferDetailModel.FromDurable

@Composable
private fun DetailFact(label: String, value: String) {
    val colors = LocalPulseColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TimelineRow(time: String, description: String) {
    val colors = LocalPulseColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )
        Column {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary
            )
        }
    }
}
