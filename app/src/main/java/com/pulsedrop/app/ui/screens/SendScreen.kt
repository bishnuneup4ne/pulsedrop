package com.pulsedrop.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.ui.PulseEvents
import com.pulsedrop.app.ui.UiPeerConnection
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.PeerPinInputDialog
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Selection model ─────────────────────────────────────────────────────────

/** One resolved file in the selection. [size] is -1 when the provider will not say. */
data class SendFileItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val readable: Boolean
)

/**
 * Adding a pick to the current selection. The policy is explicit: the same file twice is one
 * entry (the engine would send two identical jobs), a new file is appended.
 */
internal fun addPicksToSelection(
    current: List<SendFileItem>,
    picks: List<Uri>
): List<SendFileItem> {
    val existing = current.map { it.uri }.toSet()
    val added = picks.filter { it !in existing }.map { uri ->
        SendFileItem(uri = uri, name = uri.lastPathSegment ?: "file", size = -1, mimeType = null, readable = true)
    }
    val dropped = picks.size - added.size
    if (dropped > 0) PulseEvents.post("$dropped file(s) were already in the selection")
    return current + added
}

internal fun totalSizeOf(items: List<SendFileItem>): Long {
    // Saturating Long math: a directory of huge files must not wrap into a negative total.
    var total = 0L
    for (i in items) {
        if (i.size < 0) continue
        if (Long.MAX_VALUE - total < i.size) return Long.MAX_VALUE
        total += i.size
    }
    return total
}

// ── Recipient model ─────────────────────────────────────────────────────────

/** What a send to this recipient will actually do, in words the protocol can honour. */
enum class SendChannel { ENCRYPTED, PLAINTEXT, PIN, WEB_HOSTED }

data class SendRecipient(
    val id: String,
    val name: String,
    val address: String,
    val channel: SendChannel,
    val online: Boolean,
    val lastSeen: Long?,
    val peer: PeerDevice?
)

/**
 * Recipients come from live discovery plus the trust store. A paired device that is not in
 * range right now is listed as offline — visible, unselectable, and honest about when it was
 * last seen, because "we found it once" is not "we can reach it now".
 */
internal fun buildRecipients(
    nearbyPeers: List<PeerDevice>,
    connectedPeer: UiPeerConnection?,
    pairedDevices: List<PairedDevice>
): List<SendRecipient> {
    val pairedByIp = pairedDevices.associateBy { it.originIp }
    val online = nearbyPeers.map { peer ->
        val pairing = pairedByIp[peer.ip]
        SendRecipient(
            id = peer.id,
            name = peer.name,
            address = if (peer.appKind == "Browser") peer.ip else "${peer.ip}:${peer.port}",
            channel = when {
                peer.appKind == "Browser" -> SendChannel.WEB_HOSTED
                pairing != null -> SendChannel.ENCRYPTED
                peer.requiresPin -> SendChannel.PIN
                else -> SendChannel.PLAINTEXT
            },
            online = true,
            lastSeen = peer.lastSeen,
            peer = peer
        )
    }
    val onlineIps = nearbyPeers.map { it.ip }.toSet()
    val offline = pairedDevices
        .filter { it.originIp !in onlineIps }
        .map { p ->
            SendRecipient(
                id = "offline_${p.pid}",
                name = p.name,
                address = "${p.originIp}:${p.originPort}",
                channel = SendChannel.ENCRYPTED,
                online = false,
                lastSeen = null,
                peer = null
            )
        }
    // The live socket is first: it is the one recipient we have already reached.
    val connectedId = connectedPeer?.peer?.id
    return (online.sortedByDescending { it.id == connectedId } + offline)
        .distinctBy { it.name + it.address }
}

// ── Preflight ───────────────────────────────────────────────────────────────

data class SendIssue(
    val blocking: Boolean,
    val text: String
)

internal fun sendPreflight(
    items: List<SendFileItem>,
    recipient: SendRecipient?
): List<SendIssue> {
    val issues = mutableListOf<SendIssue>()
    if (items.isEmpty()) {
        issues += SendIssue(true, "Choose at least one file to send.")
    }
    items.filter { !it.readable }.forEach {
        issues += SendIssue(true, "${it.name} cannot be read any more — it moved, or its permission was revoked.")
    }
    items.filter { it.size < 0 }.forEach {
        issues += SendIssue(false, "${it.name} has no known size until it is opened.")
    }
    items.filter { it.size == 0L }.forEach {
        issues += SendIssue(false, "${it.name} is empty (0 bytes).")
    }
    when {
        recipient == null -> issues += SendIssue(true, "Pick who should receive these files.")
        !recipient.online -> issues += SendIssue(true, "${recipient.name} is offline — it was not found on the network this time.")
        recipient.channel == SendChannel.PLAINTEXT ->
            issues += SendIssue(false, "${recipient.name} is not paired, so this send travels unencrypted. Pair it first for an encrypted transfer.")
        recipient.channel == SendChannel.PIN ->
            issues += SendIssue(false, "${recipient.name} protects its inbox with a PIN — you will be asked for it.")
    }
    return issues
}

private fun channelLine(channel: SendChannel): Pair<String, PulseStatusTone> = when (channel) {
    SendChannel.ENCRYPTED -> "Encrypted pairing" to PulseStatusTone.Positive
    SendChannel.PLAINTEXT -> "Not paired — unencrypted" to PulseStatusTone.Warning
    SendChannel.PIN -> "PIN protected" to PulseStatusTone.Neutral
    SendChannel.WEB_HOSTED -> "Hosted for this browser tab only" to PulseStatusTone.Neutral
}

private fun fileIconFor(mimeType: String?): ImageVector = when {
    mimeType == null -> Icons.Default.InsertDriveFile
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType.startsWith("video/") -> Icons.Default.Movie
    mimeType.startsWith("audio/") -> Icons.Default.MusicNote
    mimeType.startsWith("text/") || mimeType == "application/pdf" -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

// ── Screen ──────────────────────────────────────────────────────────────────

/**
 * Send files: pick, choose a recipient, review, send.
 *
 * The selection lives above this screen so that wandering to Devices or Home never quietly
 * throws away an hour of picking. Nothing moves while you arrange — the transfer engine is
 * touched exactly once, by the review dialog's confirm. Options are not offered where the
 * engine has no knob: there is no resume, priority or compression control because the protocol
 * has no such request, and the recipient — not this page — decides where the file lands.
 */
@Composable
fun SendScreen(
    selectedFiles: List<Uri>,
    onSelectedFilesChange: (List<Uri>) -> Unit,
    nearbyPeers: List<PeerDevice>,
    connectedPeer: UiPeerConnection?,
    pairedDevices: List<PairedDevice>,
    scanning: Boolean,
    onSendToPeer: (PeerDevice, List<Uri>, String?) -> Unit,
    onSendToBrowser: (String, List<Uri>) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = LocalPulseColors.current

    var items by remember { mutableStateOf<List<SendFileItem>>(emptyList()) }
    var recipientId by remember { mutableStateOf<String?>(null) }
    var reviewOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var awaitingPin by remember { mutableStateOf<SendRecipient?>(null) }
    var submitting by remember { mutableStateOf(false) }

    // Resolve name/size/type off the main thread, one pass per selection change. Only metadata
    // is read — file contents never enter memory here.
    LaunchedEffect(selectedFiles) {
        val resolved = withContext(Dispatchers.IO) {
            selectedFiles.map { uri ->
                val existing = items.firstOrNull { it.uri == uri }
                if (existing != null && existing.size >= 0) return@map existing
                resolveFileForSend(context, uri)
            }
        }
        items = resolved
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // Cancelled and empty picks land here too; they are not errors and must not feel like one.
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        onSelectedFilesChange(addPicksToSelection(items.map { it.copy() }, uris).map { it.uri })
    }

    val recipients = remember(nearbyPeers, connectedPeer, pairedDevices) {
        buildRecipients(nearbyPeers, connectedPeer, pairedDevices)
    }
    val matchedRecipient = recipients.firstOrNull { it.id == recipientId }
    // Discovery blinks for a second every few seconds; a peer vanishing from the scan must not
    // silently undo an explicit choice or yank the review dialog out from under the last tap.
    var heldRecipient by remember { mutableStateOf<SendRecipient?>(null) }
    LaunchedEffect(matchedRecipient) { if (matchedRecipient != null) heldRecipient = matchedRecipient }
    val recipient = matchedRecipient ?: heldRecipient.takeIf { it != null && it.id == recipientId }
    val issues = sendPreflight(items, recipient)
    val blockers = issues.filter { it.blocking }
    val warnings = issues.filter { !it.blocking }
    val total = totalSizeOf(items)
    val canSend = blockers.isEmpty() && !submitting

    fun dispatch(r: SendRecipient, pin: String?) {
        if (submitting) return // one hand on the engine per review
        submitting = true
        val uris = items.map { it.uri }
        val peer = r.peer
        when {
            r.channel == SendChannel.WEB_HOSTED && peer != null -> onSendToBrowser(peer.ip, uris)
            peer != null -> onSendToPeer(peer, uris, pin)
        }
        PulseEvents.post(
            if (r.channel == SendChannel.WEB_HOSTED) "Files are in ${r.name}'s download list"
            else "Sending ${uris.size} file(s) to ${r.name} — follow them under Transfers"
        )
        onSelectedFilesChange(emptyList())
        items = emptyList()
        recipientId = null
        heldRecipient = null
        submitting = false
        onOpenTransfers()
    }

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
                    label = "Recent",
                    icon = Icons.Default.History,
                    tone = PulseButtonTone.Quiet,
                    onClick = onOpenTransfers
                )
            }
        }

        // ── 1. Selection ───────────────────────────────────────────────────
        if (items.isEmpty()) {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("send_drop_zone")) {
                    Column(
                        modifier = Modifier.padding(PulseSpacing.xl.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(c.accentTint),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add, null,
                                tint = c.onAccentTint,
                                modifier = Modifier.size(PulseSizes.iconLg.dp)
                            )
                        }
                        Text(
                            "Choose the files to send",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary
                        )
                        Text(
                            "Any file type. Single files only, not folders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        PulseButton(
                            label = "Browse files",
                            icon = Icons.Default.Folder,
                            onClick = { picker.launch(arrayOf("*/*")) },
                            modifier = Modifier.testTag("send_browse_btn")
                        )
                        Text(
                            "Nothing sends until you review.",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary
                        )
                    }
                }
            }
        }

        // ── 2. Selected items ──────────────────────────────────────────────
        if (items.isNotEmpty()) {
            item {
                PulseRowLabel("Selected — ${items.size} file(s)") {
                    Text(
                        if (warnings.any { it.text.contains("no known size") }) "Total excludes unknown sizes"
                        else "Total ${if (total > 0) NetworkUtils.formatBytes(total) else "0 B"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textTertiary
                    )
                    Spacer(Modifier.width(8.dp))
                    PulseButton(
                        label = "Add more files",
                        icon = Icons.Default.Folder,
                        tone = PulseButtonTone.Quiet,
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("send_browse_btn")
                    )
                    Spacer(Modifier.width(PulseSpacing.xs.dp))
                    PulseButton(
                        label = "Clear all",
                        icon = Icons.Default.DeleteSweep,
                        tone = PulseButtonTone.Quiet,
                        onClick = { clearConfirm = true },
                        modifier = Modifier.testTag("send_clear_btn")
                    )
                }
            }
            itemsIndexed(items, key = { _, it -> it.uri.toString() }) { index, file ->
                Column {
                    if (index > 0) PulseRowDivider(insetStart = 52)
                    SelectedFileRow(
                        file = file,
                        onRemove = {
                            onSelectedFilesChange(items.filterNot { it.uri == file.uri }.map { it.uri })
                        }
                    )
                }
            }
        }

        // ── 3. Recipient ───────────────────────────────────────────────────
        item {
            PulseRowLabel("Who receives them")
        }
        if (recipients.isEmpty()) {
            item {
                PulseStatePanel(
                    icon = Icons.Default.Radar,
                    title = "No one to send to yet",
                    message = if (scanning) "Still looking for devices on this network…"
                    else "No devices found on this network yet.",
                    actionLabel = "Open devices",
                    onAction = onOpenDevices
                )
            }
        } else {
            itemsIndexed(recipients, key = { _, r -> r.id }) { index, r ->
                Column {
                    if (index > 0) PulseRowDivider(insetStart = 52)
                    RecipientRow(
                        recipient = r,
                        selected = r.id == recipientId,
                        onSelect = { recipientId = r.id }
                    )
                }
            }
        }

        // ── 4. Warnings and blockers ───────────────────────────────────────
        if (issues.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    blockers.forEach { issue ->
                        IssueBanner(issue, tag = "send_blocker")
                    }
                    warnings.forEach { issue ->
                        IssueBanner(issue, tag = "send_warning")
                    }
                }
            }
        }

        // ── 6. Review and send ─────────────────────────────────────────────
        item {
            PulseButton(
                label = "Review and send",
                icon = Icons.AutoMirrored.Filled.Send,
                enabled = canSend,
                disabledReason = blockers.firstOrNull()?.text,
                fullWidth = true,
                onClick = { reviewOpen = true },
                modifier = Modifier.testTag("send_review_btn")
            )
        }

        item { Spacer(modifier = Modifier.height(PulseSpacing.xxl.dp)) }
    }

    // ── Review dialog: the last honest look before bytes move ──────────────
    if (reviewOpen && recipient != null) {
        AlertDialog(
            onDismissRequest = { reviewOpen = false },
            title = { Text("Send ${items.size} file(s) to ${recipient.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    val (channelText, channelTone) = channelLine(recipient.channel)
                    PulseStatus(
                        text = channelText,
                        tone = channelTone,
                        modifier = Modifier.testTag("send_review_channel")
                    )
                    Text(
                        "Address ${recipient.address}" +
                            if (total > 0) " · ${NetworkUtils.formatBytes(total)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    items.take(3).forEach { f ->
                        Text(
                            f.name + if (f.size >= 0) " · ${NetworkUtils.formatBytes(f.size)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (items.size > 3) {
                        Text(
                            "and ${items.size - 3} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                    warnings.forEach {
                        Text(it.text, style = MaterialTheme.typography.bodySmall, color = c.warning)
                    }
                }
            },
            confirmButton = {
                PulseButton(
                    label = if (recipient.channel == SendChannel.PIN) "Enter PIN and send" else "Send now",
                    onClick = {
                        reviewOpen = false
                        if (recipient.channel == SendChannel.PIN) {
                            awaitingPin = recipient
                        } else {
                            dispatch(recipient, null)
                        }
                    },
                    modifier = Modifier.testTag("send_confirm_btn")
                )
            },
            dismissButton = {
                TextButton(onClick = { reviewOpen = false }) { Text("Keep editing") }
            }
        )
    }

    awaitingPin?.let { r ->
        PeerPinInputDialog(
            peerName = r.name,
            onDismiss = { awaitingPin = null },
            onSubmit = { pin ->
                awaitingPin = null
                dispatch(r, pin)
            }
        )
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear the whole selection?") },
            text = { Text("${items.size} file(s) will need to be picked again. Nothing has been sent yet.") },
            confirmButton = {
                PulseButton(
                    label = "Clear",
                    tone = PulseButtonTone.Danger,
                    onClick = {
                        clearConfirm = false
                        onSelectedFilesChange(emptyList())
                        items = emptyList()
                    },
                    modifier = Modifier.testTag("send_clear_confirm_btn")
                )
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("Keep them") }
            }
        )
    }
}

// Metadata only: name, size, type, readability. The file's bytes are never buffered here.
private fun resolveFileForSend(context: Context, uri: Uri): SendFileItem {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = -1L
    var mime: String? = null
    var readable = false
    try {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
            mime = context.contentResolver.getType(uri)
            readable = context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } else {
            val path = uri.path
            val file = path?.let { java.io.File(it) }
            if (file != null && file.canRead()) {
                name = file.name
                size = file.length()
                readable = true
            }
        }
    } catch (_: Exception) {
        readable = false
    }
    return SendFileItem(uri, name, size, mime, readable)
}

@Composable
private fun SelectedFileRow(file: SendFileItem, onRemove: () -> Unit) {
    val c = LocalPulseColors.current
    val meta = buildString {
        append(if (file.size >= 0) NetworkUtils.formatBytes(file.size) else "Size unknown")
        file.mimeType?.let { append(" · $it") }
    }
    Column(modifier = Modifier.testTag("send_file_row_${file.name}")) {
        PulseRow(
            title = file.name,
            subtitle = meta,
            icon = fileIconFor(file.mimeType),
            iconTint = if (file.readable) c.textSecondary else c.danger,
            titleColor = if (file.readable) null else c.danger,
            showChevron = false,
            trailing = {
                IconButton(onClick = onRemove, modifier = Modifier.testTag("send_remove_${file.name}")) {
                    Icon(Icons.Default.Close, "Remove ${file.name}", tint = c.textSecondary)
                }
            }
        )
        if (!file.readable) {
            Text(
                "Cannot be read any more — it moved, or access was revoked",
                style = MaterialTheme.typography.labelSmall,
                color = c.danger,
                modifier = Modifier.padding(start = 52.dp)
            )
        }
    }
}

@Composable
private fun RecipientRow(recipient: SendRecipient, selected: Boolean, onSelect: () -> Unit) {
    val c = LocalPulseColors.current
    val (channelText, channelTone) = channelLine(recipient.channel)
    PulseRow(
        title = recipient.name,
        subtitle = recipient.address,
        icon = if (recipient.channel == SendChannel.WEB_HOSTED) Icons.Default.Language else Icons.Default.Radar,
        iconTint = if (recipient.online) c.textSecondary else c.disabled,
        titleColor = if (recipient.online) null else c.disabled,
        showChevron = false,
        // The click and its tag stay on one chain: an offline row is honestly disabled, not just quiet.
        modifier = Modifier
            .testTag("send_recipient_${recipient.id}")
            .clickable(enabled = recipient.online) { onSelect() },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                PulseStatus(
                    text = if (recipient.online) channelText else "Offline — not found this scan",
                    tone = if (recipient.online) channelTone else PulseStatusTone.Neutral
                )
                if (selected && recipient.online) {
                    PulseStatus(text = "Selected", tone = PulseStatusTone.Active)
                }
            }
        }
    )
}

@Composable
private fun IssueBanner(issue: SendIssue, tag: String) {
    val c = LocalPulseColors.current
    val bg = if (issue.blocking) c.dangerTint else c.warningTint
    val fg = if (issue.blocking) c.onDangerTint else c.onWarningTint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PulseRadius.md.dp))
            .background(bg)
            .padding(PulseSpacing.lg.dp)
            .testTag(tag),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (issue.blocking) Icons.Default.ErrorOutline else Icons.Default.Warning,
            null,
            tint = fg,
            modifier = Modifier.size(PulseSizes.icon.dp)
        )
        Spacer(Modifier.width(PulseSpacing.md.dp))
        Text(
            issue.text,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.weight(1f)
        )
    }
}
