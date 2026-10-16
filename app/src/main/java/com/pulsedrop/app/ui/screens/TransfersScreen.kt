package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseIconButton
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import java.io.File
import java.util.Locale

/** Lifecycle bucket a raw status string falls into; drives both tabs and row affordances. */
internal enum class TransferCategory { ACTIVE, COMPLETED, FAILED, CANCELLED, UNKNOWN }
internal fun categoryOf(status: String): TransferCategory = when (status) {
    "IN_PROGRESS", "QUEUED" -> TransferCategory.ACTIVE
    "COMPLETED" -> TransferCategory.COMPLETED
    "FAILED" -> TransferCategory.FAILED
    "CANCELLED" -> TransferCategory.CANCELLED
    else -> TransferCategory.UNKNOWN
}

private val TAB_CATEGORIES = mapOf(
    "ACTIVE" to setOf(TransferCategory.ACTIVE),
    "COMPLETED" to setOf(TransferCategory.COMPLETED),
    "FAILED" to setOf(TransferCategory.FAILED, TransferCategory.CANCELLED, TransferCategory.UNKNOWN)
)

/**
 * One transfer as the page sees it. `isHistory` marks rows whose durable record is the Room
 * row; rows only in the live map can be dismissed but were never persisted.
 */
internal data class TransferEntry(
    val id: String,
    val item: TransferItem,
    /** The status string exactly as the store held it, for the honest fallback label. */
    val rawStatus: String,
    val isHistory: Boolean,
    val category: TransferCategory
)

internal fun historyToItem(h: TransferHistoryEntity): TransferItem = TransferItem(
    id = h.id,
    fileName = h.fileName,
    fileSize = h.fileSize,
    bytesTransferred = if (h.status == "COMPLETED") h.fileSize else 0L,
    speedBytesPerSec = 0L,
    direction = if (h.direction == "INCOMING") TransferDirection.INCOMING else TransferDirection.OUTGOING,
    status = TransferStatus.entries.firstOrNull { it.name == h.status } ?: TransferStatus.FAILED,
    progress = if (h.status == "COMPLETED") 1f else 0f,
    remoteDevice = peerLabel(h.remoteDevice, h.clientIp),
    clientIp = h.clientIp ?: "",
    localFilePath = h.localFilePath,
    errorMessage = h.errorMessage,
    timestamp = h.timestamp
)

/**
 * Rows stored before the server kept plain peer names read "Client (10.0.0.5)" or
 * "Paired • study-laptop"; the address only earns a place when it is the whole identity.
 */
internal fun peerLabel(raw: String, ip: String?): String {
    val stripped = raw.substringBefore(" (").trim()
    val name = stripped.removePrefix("Paired •").trim()
    return when {
        name.isNotBlank() && name != "Client" && name != "Web Client" -> name
        !ip.isNullOrBlank() -> "Browser · $ip"
        else -> "Browser"
    }
}

/**
 * The live map and the Room log describe the same transfers at different moments, keyed by the
 * same id. Room is the durable record, so it wins for finished work; the map contributes rows
 * that were never persisted (running, or completed while the log write failed).
 */
internal fun mergeTransfers(
    active: List<TransferItem>,
    history: List<TransferHistoryEntity>
): List<TransferEntry> {
    val historyIds = history.mapTo(HashSet()) { it.id }
    val liveOnly = active.filterNot { it.id in historyIds }.map {
        TransferEntry(it.id, it, rawStatus = it.status.name, isHistory = false, categoryOf(it.status.name))
    }
    val historyRows = history.map {
        TransferEntry(it.id, historyToItem(it), rawStatus = it.status, isHistory = true, categoryOf(it.status))
    }
    return liveOnly + historyRows
}

internal fun transferMatchesQuery(entry: TransferEntry, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase(Locale.ROOT)
    return entry.item.fileName.lowercase(Locale.ROOT).contains(q) ||
        entry.item.remoteDevice.lowercase(Locale.ROOT).contains(q) ||
        entry.item.clientIp.lowercase(Locale.ROOT).contains(q) ||
        entry.id.lowercase(Locale.ROOT).contains(q)
}

/**
 * The transfer log as an Instagram-style settings list: quiet filter chips up top, then plain
 * full-bleed rows split by hairlines inset past the status icon. Every row says what moved,
 * between whom, and how it ended — sizes and times read gray, taps open the details page.
 */
@Composable
fun TransfersScreen(
    activeTransfers: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    pendingUploads: List<PendingUploadInfo>,
    onOpenFile: (String?) -> Unit,
    onShareFile: (String?) -> Unit,
    onSaveToDownloads: (String?) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onDismissLive: (String) -> Unit,
    onClearAll: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalPulseColors.current

    var tab by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("ALL") }
    var sort by remember { mutableStateOf("RECENT") }
    var showSortMenu by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf<TransferEntry?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Counts on the chips come from the full store, never from the filtered view —
    // otherwise searching would rewrite the summary the search itself is hiding rows from.
    val entries = remember(activeTransfers, history) { mergeTransfers(activeTransfers, history) }
    val activeCount = entries.count { it.category == TransferCategory.ACTIVE }
    val completedCount = entries.count { it.category == TransferCategory.COMPLETED }
    val failedCount = entries.count {
        it.category == TransferCategory.FAILED || it.category == TransferCategory.CANCELLED ||
            it.category == TransferCategory.UNKNOWN
    }

    val visible = remember(entries, tab, query, direction, sort) {
        entries.filter { e ->
            val tabOk = when (tab) {
                "ALL" -> true
                "PENDING" -> false
                else -> TAB_CATEGORIES.getValue(tab).contains(e.category)
            }
            val dirOk = when (direction) {
                "SENT" -> e.item.direction == TransferDirection.OUTGOING
                "RECEIVED" -> e.item.direction == TransferDirection.INCOMING
                else -> true
            }
            tabOk && dirOk && transferMatchesQuery(e, query)
        }.sortedWith(
            when (sort) {
                "OLDEST" -> compareBy { it.item.timestamp }
                "NAME" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.item.fileName }
                "SIZE" -> compareByDescending { it.item.fileSize }
                else -> compareByDescending { it.item.timestamp }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("transfers_list")
            .padding(horizontal = PulseSpacing.lg.dp),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                CountFilterChip("Active", activeCount, tab == "ACTIVE", "transfers_tile_active") {
                    tab = if (tab == "ACTIVE") "ALL" else "ACTIVE"
                }
                CountFilterChip("Done", completedCount, tab == "COMPLETED", "transfers_tile_completed") {
                    tab = if (tab == "COMPLETED") "ALL" else "COMPLETED"
                }
                CountFilterChip("Failed", failedCount, tab == "FAILED", "transfers_tile_failed") {
                    tab = if (tab == "FAILED") "ALL" else "FAILED"
                }
                CountFilterChip("Awaiting", pendingUploads.size, tab == "PENDING", "transfers_tile_awaiting") {
                    tab = if (tab == "PENDING") "ALL" else "PENDING"
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("transfers_search"),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Name, device, IP or id") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        PulseIconButton(
                            icon = Icons.Default.Close,
                            label = "Clear search",
                            onClick = { query = "" },
                            modifier = Modifier.testTag("transfers_search_clear")
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderSubtle
                )
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                PulseChip(
                    label = when (direction) {
                        "SENT" -> "Sent"
                        "RECEIVED" -> "Received"
                        else -> "Both ways"
                    },
                    selected = direction != "ALL",
                    leadingIcon = Icons.Default.SwapVert,
                    onClick = {
                        direction = when (direction) {
                            "ALL" -> "SENT"
                            "SENT" -> "RECEIVED"
                            else -> "ALL"
                        }
                    },
                    modifier = Modifier.testTag("transfers_direction_chip")
                )
                Box {
                    PulseIconButton(
                        icon = Icons.Default.MoreVert,
                        label = "Sort",
                        onClick = { showSortMenu = true },
                        modifier = Modifier.testTag("transfers_sort_btn")
                    )
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        listOf(
                            "RECENT" to "Most recent first",
                            "OLDEST" to "Oldest first",
                            "NAME" to "Name (A–Z)",
                            "SIZE" to "Largest first"
                        ).forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { sort = key; showSortMenu = false },
                                trailingIcon = {
                                    if (sort == key) Icon(Icons.Default.Check, contentDescription = null)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.testTag("transfers_clear_all_btn")
                    ) {
                        Text("Clear all", color = colors.danger, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (tab == "PENDING") {
            if (pendingUploads.isEmpty()) {
                item {
                    PulseStatePanel(
                        icon = Icons.Default.History,
                        title = "Nothing waiting on you",
                        message = "Uploads pause on the Receive page until you decide.",
                        modifier = Modifier.testTag("transfers_pending_empty")
                    )
                }
            } else {
                item {
                    PulseStatePanel(
                        icon = Icons.Default.ErrorOutline,
                        title = "${pendingUploads.size} upload${if (pendingUploads.size == 1) "" else "s"} need a decision",
                        message = "Accept or decline on the Receive page.",
                        actionLabel = "Open Receive",
                        onAction = onOpenReceive,
                        tone = PulseStatusTone.Warning,
                        modifier = Modifier.testTag("transfers_pending_panel")
                    )
                }
                item { PulseRowLabel("Staged uploads") }
                itemsIndexed(pendingUploads, key = { _, u -> "pending_${u.id}" }) { index, pending ->
                    PendingPeekRow(pending, showDivider = index > 0)
                }
            }
        } else if (visible.isEmpty()) {
            item {
                when {
                    entries.isEmpty() -> PulseStatePanel(
                        icon = Icons.Default.History,
                        title = "No transfers yet",
                        message = "Your sends and receives appear here.",
                        actionLabel = "Send a file",
                        onAction = onOpenSend,
                        modifier = Modifier.testTag("transfers_empty")
                    )
                    query.isNotBlank() || direction != "ALL" -> PulseStatePanel(
                        icon = Icons.Outlined.SearchOff,
                        title = "Nothing matches",
                        message = "None match the current search and filters.",
                        actionLabel = "Clear filters",
                        onAction = { query = ""; direction = "ALL" },
                        modifier = Modifier.testTag("transfers_no_results")
                    )
                    tab == "ACTIVE" -> PulseStatePanel(
                        icon = Icons.Default.SwapHoriz,
                        title = "Nothing in flight",
                        message = "Interrupted transfers leave no record.",
                        modifier = Modifier.testTag("transfers_tab_empty")
                    )
                    else -> PulseStatePanel(
                        icon = Icons.Default.History,
                        title = "No ${tab.lowercase(Locale.ROOT)} transfers",
                        message = "This tab has no rows, though ${entries.size} exist overall.",
                        actionLabel = "Show all",
                        onAction = { tab = "ALL" },
                        modifier = Modifier.testTag("transfers_tab_empty")
                    )
                }
            }
        } else {
            item(key = "rows_label") {
                PulseRowLabel(
                    when (tab) {
                        "ALL" -> "All transfers"
                        "ACTIVE" -> "Active transfers"
                        "COMPLETED" -> "Completed transfers"
                        else -> "Failed transfers"
                    }
                ) {
                    if (tab == "ALL") {
                        Text(
                            "${entries.size} record${if (entries.size == 1) "" else "s"} • $activeCount in flight",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textTertiary
                        )
                    }
                }
            }
            itemsIndexed(visible, key = { _, e -> e.id }) { index, entry ->
                TransferListRow(
                    entry = entry,
                    showDivider = index > 0,
                    onDetails = { onOpenDetails(entry.id) },
                    onOpen = { onOpenFile(entry.item.localFilePath) },
                    onShare = { onShareFile(entry.item.localFilePath) },
                    onSave = { onSaveToDownloads(entry.item.localFilePath) },
                    onRemove = { removeConfirm = entry }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(PulseSpacing.xxl.dp)) }
    }

    val pendingRemove = removeConfirm
    if (pendingRemove != null) {
        AlertDialog(
            onDismissRequest = { removeConfirm = null },
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    if (pendingRemove.isHistory) "Remove this record?" else "Hide this row?",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = if (pendingRemove.isHistory)
                        "The log entry for “${pendingRemove.item.fileName}” will be removed. The file itself stays on this phone — removing a record never deletes files."
                    else
                        "“${pendingRemove.item.fileName}” has no durable log entry. This only clears it from the live list; it is not a cancellation.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pendingRemove.isHistory) onDeleteHistory(pendingRemove.id) else onDismissLive(pendingRemove.id)
                        removeConfirm = null
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                    modifier = Modifier.testTag("transfers_remove_confirm")
                ) {
                    Text(
                        if (pendingRemove.isHistory) "Remove record" else "Hide row",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { removeConfirm = null },
                    modifier = Modifier.testTag("transfers_remove_cancel")
                ) {
                    Text("Keep it", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Clear the log?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "All ${history.size} stored record${if (history.size == 1) "" else "s"} will be removed. Transfers in flight keep running, and files already on this phone stay untouched.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { onClearAll(); showClearConfirm = false },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                    modifier = Modifier.testTag("transfers_clear_confirm")
                ) {
                    Text("Clear all", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

/** A count you can tap to filter, in the quiet chip style the rest of the lists use. */
@Composable
private fun RowScope.CountFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    PulseChip(
        label = label,
        selected = selected,
        badge = count,
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .testTag(tag)
    )
}

@Composable
private fun PendingPeekRow(pending: PendingUploadInfo, showDivider: Boolean) {
    val colors = LocalPulseColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag("transfers_pending_${pending.id}")) {
        if (showDivider) PulseRowDivider(insetStart = 52)
        PulseRow(
            title = pending.fileName,
            subtitle = "From ${pending.fromDevice}",
            detail = NetworkUtils.formatBytes(pending.fileSize),
            icon = Icons.Default.HourglassEmpty,
            iconTint = colors.warning,
            showChevron = false
        )
    }
}

/**
 * The single row widget for the page: a status icon, the file name, who it moved between, and
 * the one real number that matters (size, or live percent while the engine is moving bytes).
 * Running rows carry the live engine's percentage; finished rows show what the log recorded.
 * Actions are limited to what the backend truly supports — there is deliberately no pause,
 * resume, cancel or retry button here, because the transfer engine exposes no such operation.
 */
@Composable
private fun TransferListRow(
    entry: TransferEntry,
    showDivider: Boolean,
    onDetails: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = LocalPulseColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag("transfers_row_${entry.id}")) {
        if (showDivider) PulseRowDivider(insetStart = 52)

        if (entry.category == TransferCategory.UNKNOWN || entry.category == TransferCategory.CANCELLED) {
            UnknownStateRow(entry, onDetails, onRemove)
            return@Column
        }

        val path = entry.item.localFilePath
        val fileThere = remember(path) { !path.isNullOrBlank() && File(path).exists() }
        val isIncoming = entry.item.direction == TransferDirection.INCOMING
        val device = entry.item.remoteDevice

        val icon: ImageVector = when (entry.category) {
            TransferCategory.ACTIVE ->
                if (entry.item.status == TransferStatus.QUEUED) Icons.Default.HourglassEmpty else Icons.Default.Sync
            TransferCategory.COMPLETED -> Icons.Default.CheckCircle
            else -> Icons.Default.Error
        }
        val iconTint = when (entry.category) {
            TransferCategory.ACTIVE -> colors.accent
            TransferCategory.COMPLETED -> colors.success
            else -> colors.danger
        }
        val subtitle = when (entry.category) {
            TransferCategory.ACTIVE -> (if (isIncoming) "Receiving from " else "Sending to ") + device
            TransferCategory.COMPLETED -> (if (isIncoming) "Received" else "Sent") + " · $device"
            else -> "Failed · $device"
        }
        val detail = when (entry.category) {
            TransferCategory.COMPLETED -> NetworkUtils.formatBytes(entry.item.fileSize)
            TransferCategory.FAILED -> entry.item.errorMessage ?: "Reason not recorded"
            else -> null
        }

        PulseRow(
            title = entry.item.fileName,
            subtitle = subtitle,
            detail = detail,
            icon = icon,
            iconTint = iconTint,
            modifier = Modifier.testTag("transfer_progress_${entry.id}"),
            onClick = onDetails,
            trailing = {
                if (entry.category == TransferCategory.ACTIVE) {
                    Text(
                        text = "${(entry.item.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        modifier = Modifier.testTag("transfer_percentage_${entry.id}")
                    )
                }
                if (fileThere) {
                    Spacer(modifier = Modifier.size(4.dp))
                    PulseIconButton(
                        icon = Icons.Default.Download,
                        label = "Open file",
                        onClick = onOpen,
                        modifier = Modifier.testTag("transfers_open_${entry.id}")
                    )
                    var menuOpen by remember(entry.id) { mutableStateOf(false) }
                    PulseIconButton(
                        icon = Icons.Default.MoreVert,
                        label = "More actions",
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share file") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = { menuOpen = false; onShare() },
                            modifier = Modifier.testTag("transfers_share_${entry.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Save to Downloads") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = { menuOpen = false; onSave() },
                            modifier = Modifier.testTag("transfers_save_${entry.id}")
                        )
                    }
                }
                Spacer(modifier = Modifier.size(4.dp))
                PulseIconButton(
                    icon = Icons.Default.Delete,
                    label = if (entry.isHistory) "Remove from history" else "Hide from live list",
                    onClick = onRemove,
                    modifier = Modifier.testTag("transfers_remove_${entry.id}")
                )
            }
        )
    }
}

@Composable
private fun UnknownStateRow(entry: TransferEntry, onDetails: () -> Unit, onRemove: () -> Unit) {
    val colors = LocalPulseColors.current
    PulseRow(
        title = entry.item.fileName,
        subtitle = "State “${entry.rawStatus}” is not produced by the current engine — shown for honesty, " +
            "with no actions beyond viewing details.",
        icon = Icons.Default.ErrorOutline,
        iconTint = colors.warning,
        onClick = onDetails,
        trailing = {
            PulseIconButton(
                icon = Icons.Default.Delete,
                label = "Remove",
                onClick = onRemove,
                modifier = Modifier.testTag("transfers_remove_${entry.id}")
            )
        }
    )
}
