package com.pulsedrop.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseIconButton
import com.pulsedrop.app.ui.components.PulsePageHeader
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * My Files is a view over two things that both exist: the durable transfer log and the one
 * folder this app owns on disk. It is deliberately *not* a device browser — Android's scoped
 * storage gives PulseDrop no right to list anything else, and the page never implies it does.
 */

internal enum class FileCategory { DOCUMENT, IMAGE, VIDEO, AUDIO, ARCHIVE, OTHER }

internal data class MyFileRecord(
    val id: String,
    val name: String,
    val recordedSize: Long,
    val onDiskSize: Long?,
    val extension: String,
    val category: FileCategory,
    val receivedAt: Long,
    val sender: String,
    val path: String,
    val exists: Boolean,
    val onDiskSizeKnown: Boolean
)

internal fun fileCategoryOf(mimeType: String?, fileName: String): FileCategory {
    val mime = mimeType?.lowercase(Locale.ROOT) ?: ""
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp") -> FileCategory.IMAGE
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "avi") -> FileCategory.VIDEO
        mime.startsWith("audio/") || ext in setOf("mp3", "wav", "ogg", "flac", "m4a", "aac") -> FileCategory.AUDIO
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz") -> FileCategory.ARCHIVE
        mime.startsWith("text/") ||
            ext in setOf(
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv",
                "json", "xml", "html", "htm", "log", "ini", "yaml", "yml"
            ) -> FileCategory.DOCUMENT
        else -> FileCategory.OTHER
    }
}

/**
 * Only completed *incoming* rows with a recorded path are received files. Failed receipts never
 * produced a file, and outgoing rows are covered by the Transfers page — this page owns neither.
 */
internal fun myFileRecords(
    history: List<TransferHistoryEntity>,
    scan: Boolean
): List<MyFileRecord> = history
    .filter { it.direction == "INCOMING" && it.status == "COMPLETED" && !it.localFilePath.isNullOrBlank() }
    .map { row ->
        val path = row.localFilePath!!
        val file = File(path)
        val present = file.exists()
        val diskSize = if (scan && present) file.length() else null
        MyFileRecord(
            id = row.id,
            name = row.fileName,
            recordedSize = row.fileSize,
            onDiskSize = diskSize,
            extension = row.fileName.substringAfterLast('.', ""),
            category = fileCategoryOf(row.mimeType, row.fileName),
            receivedAt = row.timestamp,
            sender = row.remoteDevice.ifBlank { "Unknown device" },
            path = path,
            exists = present,
            onDiskSizeKnown = scan
        )
    }
    .sortedByDescending { it.receivedAt }

internal enum class MyFileSort { RECEIVED, NAME, SIZE, TYPE, SENDER }

/**
 * Stable sort on raw values — never on formatted strings, so "2.0 KB" and "100 B" compare as
 * numbers. Missing measurements sort last in both directions instead of shuffling.
 */
internal fun sortMyFiles(records: List<MyFileRecord>, sort: MyFileSort, ascending: Boolean): List<MyFileRecord> {
    val primary: (MyFileRecord) -> Comparable<*> = when (sort) {
        MyFileSort.RECEIVED -> { r -> r.receivedAt }
        MyFileSort.NAME -> { r -> r.name.lowercase(Locale.ROOT) }
        MyFileSort.SIZE -> { r -> r.onDiskSize ?: r.recordedSize }
        MyFileSort.TYPE -> { r -> r.category.name + r.name.lowercase(Locale.ROOT) }
        MyFileSort.SENDER -> { r -> r.sender.lowercase(Locale.ROOT) }
    }
    @Suppress("UNCHECKED_CAST")
    val comparator = Comparator<MyFileRecord> { a, b ->
        val ka = primary(a) as Comparable<Any?>
        val kb = primary(b) as Comparable<Any?>
        ka.compareTo(kb)
    }.thenComparator { a, b -> a.id.compareTo(b.id) }
    val sorted = if (ascending) records.sortedWith(comparator) else records.sortedWith(comparator.reversed())
    // Determinism for absent measurements: they stay at the end whatever the direction.
    return if (sort == MyFileSort.SIZE) {
        val known = sorted.filter { it.onDiskSize != null }
        val unknown = sorted.filter { it.onDiskSize == null }
        // `sorted` already honors the direction; reversing `known` again would flip it back.
        known + unknown
    } else sorted
}

internal fun myFileMatchesQuery(record: MyFileRecord, query: String): Boolean {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return true
    return record.name.lowercase(Locale.ROOT).contains(q) ||
        record.extension.lowercase(Locale.ROOT).contains(q) ||
        record.category.name.lowercase(Locale.ROOT).contains(q) ||
        record.sender.lowercase(Locale.ROOT).contains(q) ||
        record.id.lowercase(Locale.ROOT).contains(q)
}

/**
 * Rename validation mirrored in the UI so the dialog can speak before the engine refuses.
 * null means the name is usable; anything else is the reason it is not.
 */
internal fun renameProblem(name: String): String? {
    val cleaned = name.trim()
    return when {
        cleaned.isEmpty() -> "A file needs a name."
        cleaned == "." || cleaned == ".." -> "That name refers to a folder, not a file."
        cleaned.contains('/') || cleaned.contains('\\') -> "Path separators are not allowed in a name."
        cleaned.contains('\u0000') -> "The name contains characters the filesystem cannot store."
        cleaned.length > 255 -> "That name is longer than this filesystem accepts (255 characters)."
        else -> null
    }
}

/** What the page can honestly say about a location: the folder is app-owned; the name is the file's. */
internal fun relativeLocationLabel(path: String, receiveDir: String): String {
    val normalized = if (receiveDir.endsWith(File.separator)) receiveDir else receiveDir + File.separator
    return if (path.startsWith(normalized)) "received folder • " + path.removePrefix(normalized)
    else "outside the received folder — recorded path: " + File(path).name
}

@Composable
fun MyFilesScreen(
    history: List<TransferHistoryEntity>,
    receiveDir: String,
    volumeFreeBytes: Long,
    volumeTotalBytes: Long,
    onOpenFile: (String, (Boolean, String?) -> Unit) -> Unit,
    onSaveToDownloads: (String) -> Unit,
    onShareFile: (String) -> Unit,
    onRename: (String, String, (Boolean, String) -> Unit) -> Unit,
    onDeleteFile: (String, (Boolean, String) -> Unit) -> Unit,
    onRemoveRecord: (String) -> Unit,
    onOpenTransferDetails: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onMessage: (String) -> Unit,
    onOpenReceive: () -> Unit,
    onOpenTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val dateFormatter = remember { SimpleDateFormat("MMM d • HH:mm", Locale.getDefault()) }

    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<FileCategory?>(null) }
    var availabilityFilter by remember { mutableStateOf<Boolean?>(null) } // true=present, false=missing, null=all
    var recentOnly by remember { mutableStateOf(false) }
    var senderFilter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(MyFileSort.RECEIVED) }
    var ascending by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    var detailsFor by remember { mutableStateOf<MyFileRecord?>(null) }
    var renameFor by remember { mutableStateOf<MyFileRecord?>(null) }
    var deleteFor by remember { mutableStateOf<MyFileRecord?>(null) }
    var removeRecordFor by remember { mutableStateOf<MyFileRecord?>(null) }
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var bulkTargets by remember { mutableStateOf(setOf<String>()) }
    val bulkResults = remember { mutableStateMapOf<String, Boolean>() }

    // Existence and on-disk sizes are checked when the data changes or the user asks — never on
    // every recomposition — and the page says plainly that this can go stale.
    val all = remember(history, scan) { myFileRecords(history, scan > 0) }
    val presentCount = all.count { it.exists }
    val recordedTotal = all.sumOf { it.recordedSize }
    val measuredTotal = if (scan > 0) all.filter { it.exists }.sumOf { it.onDiskSize ?: 0L } else null

    val cutoff = remember { System.currentTimeMillis() - 7L * 24 * 3600 * 1000 }
    val filtered = all.filter { r ->
        (categoryFilter == null || r.category == categoryFilter) &&
            (availabilityFilter == null || r.exists == availabilityFilter) &&
            (!recentOnly || r.receivedAt >= cutoff) &&
            (senderFilter == null || r.sender == senderFilter) &&
            myFileMatchesQuery(r, search)
    }
    val visible = sortMyFiles(filtered, sort, ascending)
    val filtersActive = categoryFilter != null || availabilityFilter != null || recentOnly ||
        senderFilter != null || search.isNotBlank()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PulseSpacing.lg.dp)
            .testTag("myfiles_list"),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg.dp)
    ) {
        item {
            PulsePageHeader(
                title = "My files",
                subtitle = "Files this phone received through PulseDrop — nothing else on the device",
                status = {
                    PulseIconButton(
                        icon = Icons.Default.Refresh,
                        label = "Re-check files on disk",
                        onClick = {
                            scan++
                            selected = emptySet()
                            bulkResults.clear()
                            bulkTargets = emptySet()
                            onMessage("Re-checked against the disk. Availability can still change outside the app.")
                        },
                        modifier = Modifier.testTag("myfiles_refresh")
                    )
                }
            )
        }

        // ── Scope + storage summary ────────────────────────────────────────
        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("myfiles_summary_card")) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                ) {
                    Text(
                        text = "${all.size} tracked record${if (all.size == 1) "" else "s"} • " +
                            "$presentCount verified present right now",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    Text(
                        text = "PulseDrop can only see and manage the folder it owns on this phone " +
                            "(“${File(receiveDir).name}”). Files you move or delete with another app " +
                            "change here only after a re-check.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    // Three-up stat tiles cannot fit values like "Not scanned" at phone width
                    // without ellipsizing them, and a truncated number is worse than no number.
                    // A label-left/value-right list carries any length honestly.
                    StatLine("Recorded size", NetworkUtils.formatBytes(recordedTotal))
                    StatLine(
                        "Measured on disk",
                        measuredTotal?.let { NetworkUtils.formatBytes(it) } ?: "Not scanned"
                    )
                    StatLine(
                        "Volume free",
                        if (volumeFreeBytes >= 0) NetworkUtils.formatBytes(volumeFreeBytes) else "Unavailable"
                    )
                    if (volumeTotalBytes >= 0 && volumeFreeBytes >= 0) {
                        Text(
                            text = "Free space is for the whole storage volume this folder sits on — " +
                                "not a PulseDrop-only number. Tracked files above are PulseDrop's own.",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary
                        )
                    }
                }
            }
        }

        if (all.isEmpty()) {
            item {
                PulseStatePanel(
                    icon = Icons.Default.Folder,
                    title = "Nothing has been received yet",
                    message = "No completed incoming transfer has left a file on this phone. " +
                        "Records appear here as soon as a receipt completes.",
                    actionLabel = "Open Receive",
                    onAction = onOpenReceive,
                    modifier = Modifier.testTag("myfiles_empty")
                )
            }
            return@LazyColumn
        }

        if (presentCount == 0) {
            item {
                PulseStatePanel(
                    icon = Icons.Default.ErrorOutline,
                    title = "Every tracked file is missing from disk",
                    message = "The history still lists ${all.size} received file(s), but none of them " +
                        "exist at their recorded locations — they were moved or deleted with another app. " +
                        "The records stay; PulseDrop does not claim to control the files.",
                    actionLabel = "Open Transfers",
                    onAction = onOpenTransfers,
                    tone = PulseStatusTone.Warning,
                    modifier = Modifier.testTag("myfiles_all_missing")
                )
            }
        }

        // ── Search ─────────────────────────────────────────────────────────
        item {
            Box {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("myfiles_search"),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search name, type, sender, record id") }
                )
                if (search.isNotEmpty()) {
                    PulseIconButton(
                        icon = Icons.Default.Delete,
                        label = "Clear search",
                        onClick = { search = "" },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = PulseSpacing.sm.dp)
                            .testTag("myfiles_search_clear")
                    )
                }
            }
        }

        // ── Filters + sort ─────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FileCategory.entries.forEach { cat ->
                        PulseChip(
                            label = cat.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() },
                            selected = categoryFilter == cat,
                            onClick = {
                                categoryFilter = if (categoryFilter == cat) null else cat
                            },
                            modifier = Modifier.testTag("myfiles_filter_${cat.name.lowercase(Locale.ROOT)}")
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PulseChip(
                        label = "Present",
                        selected = availabilityFilter == true,
                        onClick = { availabilityFilter = if (availabilityFilter == true) null else true },
                        modifier = Modifier.testTag("myfiles_filter_present")
                    )
                    PulseChip(
                        label = "Missing",
                        selected = availabilityFilter == false,
                        onClick = { availabilityFilter = if (availabilityFilter == false) null else false },
                        modifier = Modifier.testTag("myfiles_filter_missing")
                    )
                    PulseChip(
                        label = "Last 7 days",
                        selected = recentOnly,
                        onClick = { recentOnly = !recentOnly },
                        modifier = Modifier.testTag("myfiles_filter_recent")
                    )
                    if (senderFilter != null) {
                        PulseChip(
                            label = "Sender: $senderFilter",
                            selected = true,
                            onClick = { senderFilter = null },
                            modifier = Modifier.testTag("myfiles_filter_sender_clear")
                        )
                    }
                    Box {
                        PulseChip(
                            label = "Sort: ${sort.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }} " +
                                if (ascending) "↑" else "↓",
                            onClick = { sortMenu = true },
                            modifier = Modifier.testTag("myfiles_sort_btn")
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            MyFileSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        sort = s
                                        sortMenu = false
                                    },
                                    modifier = Modifier.testTag("myfiles_sort_${s.name.lowercase(Locale.ROOT)}")
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (ascending) "Descending" else "Ascending") },
                                onClick = { ascending = !ascending },
                                modifier = Modifier.testTag("myfiles_sort_direction")
                            )
                        }
                    }
                    if (filtersActive) {
                        PulseChip(
                            label = "Clear all",
                            onClick = {
                                search = ""; categoryFilter = null; availabilityFilter = null
                                recentOnly = false; senderFilter = null
                            },
                            modifier = Modifier.testTag("myfiles_clear_filters")
                        )
                    }
                }
            }
        }

        // ── Bulk toolbar ───────────────────────────────────────────────────
        if (visible.isNotEmpty()) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = visible.isNotEmpty() && visible.all { it.id in selected },
                        onCheckedChange = { checked ->
                            selected = if (checked) visible.map { it.id }.toSet() else emptySet()
                        },
                        modifier = Modifier.testTag("myfiles_select_all")
                    )
                    Text(
                        text = if (selected.isEmpty()) "Select files"
                        else "${selected.size} of ${visible.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (selected.isNotEmpty()) {
                        PulseButton(
                            label = "Delete files",
                            tone = PulseButtonTone.Danger,
                            icon = Icons.Default.Delete,
                            onClick = {
                                bulkResults.clear()
                                bulkTargets = emptySet()
                                bulkDeleteConfirm = true
                            },
                            modifier = Modifier.testTag("myfiles_bulk_delete")
                        )
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                PulseStatePanel(
                    icon = Icons.Outlined.SearchOff,
                    title = "Nothing matches",
                    message = "${all.size} tracked file${if (all.size == 1) "" else "s"} still exist in the " +
                        "full scope — the search and filters are hiding them.",
                    actionLabel = "Clear filters",
                    onAction = {
                        search = ""; categoryFilter = null; availabilityFilter = null
                        recentOnly = false; senderFilter = null
                    },
                    modifier = Modifier.testTag("myfiles_no_results")
                )
            }
        }

        items(visible, key = { "file_" + it.id }) { record ->
            MyFileRow(
                record = record,
                selected = record.id in selected,
                onToggleSelect = {
                    selected = if (record.id in selected) selected - record.id else selected + record.id
                },
                onDetails = { detailsFor = record },
                onOpen = {
                    onOpenFile(record.path) { ok, msg ->
                        if (!ok && msg != null) onMessage(msg)
                    }
                },
                onSave = { onSaveToDownloads(record.path) },
                onShare = { onShareFile(record.path) },
                onRename = { renameFor = record },
                onDeleteFile = { deleteFor = record },
                onRemoveRecord = { removeRecordFor = record },
                onFilterBySender = { senderFilter = record.sender },
                dateFormatter = dateFormatter
            )
        }

        item { Spacer(modifier = Modifier.height(PulseSpacing.xxl.dp)) }
    }

    // ── Details dialog ─────────────────────────────────────────────────────
    detailsFor?.let { record ->
        AlertDialog(
            onDismissRequest = { detailsFor = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text(record.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    DetailLine2("Status", if (record.exists) "Present on disk" else "Missing — moved or deleted outside the app")
                    DetailLine2("Size", NetworkUtils.formatBytes(record.onDiskSize ?: record.recordedSize) +
                        if (record.onDiskSize != null && record.onDiskSize != record.recordedSize)
                            " (record said ${NetworkUtils.formatBytes(record.recordedSize)})" else "")
                    DetailLine2("Type", "${record.category.name.lowercase(Locale.ROOT)}" +
                        (if (record.extension.isNotBlank()) " • .${record.extension}" else ""))
                    DetailLine2("Received", dateFormatter.format(Date(record.receivedAt)))
                    DetailLine2("From", record.sender)
                    DetailLine2("Location", relativeLocationLabel(record.path, receiveDir))
                    DetailLine2("Record id", record.id)
                    Text(
                        "No content hash exists for this file: “completed” means the bytes arrived, " +
                            "not that they were verified, and no malware scanning has run.",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textTertiary
                    )
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    TextButton(
                        onClick = {
                            onCopyText(record.path)
                            onMessage("Location copied.")
                        },
                        modifier = Modifier.testTag("myfiles_details_copy")
                    ) { Text("Copy location") }
                    TextButton(
                        onClick = { detailsFor = null; onOpenTransferDetails(record.id) },
                        modifier = Modifier.testTag("myfiles_details_transfer")
                    ) { Text("Open transfer record") }
                }
            },
            dismissButton = {
                TextButton(onClick = { detailsFor = null }) { Text("Close") }
            }
        )
    }

    // ── Rename dialog ──────────────────────────────────────────────────────
    renameFor?.let { record ->
        var candidate by remember(record.id) { mutableStateOf(record.name) }
        var busy by remember(record.id) { mutableStateOf(false) }
        val problem = renameProblem(candidate)
        AlertDialog(
            onDismissRequest = { if (!busy) renameFor = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Rename file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    OutlinedTextField(
                        value = candidate,
                        onValueChange = { candidate = it },
                        singleLine = true,
                        label = { Text("New name") },
                        modifier = Modifier.fillMaxWidth().testTag("myfiles_rename_field")
                    )
                    if (problem != null) {
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger,
                            modifier = Modifier.testTag("myfiles_rename_problem"))
                    }
                    Text(
                        "The real file is renamed inside the received folder, and the record follows " +
                            "only if Android confirms. Other apps may still show the old name until they refresh.",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textTertiary
                    )
                }
            },
            confirmButton = {
                PulseButton(
                    label = if (busy) "Renaming…" else "Rename",
                    loading = busy,
                    enabled = problem == null && candidate.trim() != record.name,
                    disabledReason = when {
                        busy -> null
                        candidate.trim() == record.name -> "Type a different name first."
                        else -> "The name is not usable."
                    },
                    onClick = {
                        busy = true
                        onRename(record.id, candidate.trim()) { ok, msg ->
                            busy = false
                            onMessage(msg)
                            if (ok) { renameFor = null; scan++ }
                        }
                    },
                    modifier = Modifier.testTag("myfiles_rename_confirm")
                )
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }, enabled = !busy) { Text("Keep old name") }
            }
        )
    }

    // ── Delete-file confirmation ───────────────────────────────────────────
    deleteFor?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Delete “${record.name}”?") },
            text = {
                Text(
                    "This deletes the physical file from the received folder on this phone. " +
                        "The transfer record stays, marked missing — deleting a file and deleting " +
                        "history are different operations. This cannot be undone: there is no trash."
                )
            },
            confirmButton = {
                PulseButton(
                    label = "Delete file",
                    tone = PulseButtonTone.Danger,
                    onClick = {
                        onDeleteFile(record.id) { ok, msg ->
                            onMessage(msg)
                            if (ok) scan++
                        }
                        deleteFor = null
                    },
                    modifier = Modifier.testTag("myfiles_delete_confirm")
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("Cancel") }
            }
        )
    }

    // ── Remove-record confirmation ─────────────────────────────────────────
    removeRecordFor?.let { record ->
        AlertDialog(
            onDismissRequest = { removeRecordFor = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Remove the record for “${record.name}”?") },
            text = {
                Text(
                    "This removes only PulseDrop's history row. The physical file " +
                        (if (record.exists) "stays on disk and simply leaves this page’s scope."
                            else "is already gone; this clears the missing marker.")
                )
            },
            confirmButton = {
                PulseButton(
                    label = "Remove record",
                    onClick = {
                        onRemoveRecord(record.id)
                        removeRecordFor = null
                    },
                    modifier = Modifier.testTag("myfiles_remove_record_confirm")
                )
            },
            dismissButton = {
                TextButton(onClick = { removeRecordFor = null }) { Text("Cancel") }
            }
        )
    }

    // ── Bulk delete confirmation ───────────────────────────────────────────
    if (bulkDeleteConfirm) {
        val targets = all.filter { it.id in selected }
        val present = targets.count { it.exists }
        AlertDialog(
            onDismissRequest = { if (bulkTargets.isEmpty()) bulkDeleteConfirm = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Delete ${targets.size} file${if (targets.size == 1) "" else "s"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    Text(
                        "Physical files only — records stay. $present of ${targets.size} are actually " +
                            "on disk; the rest are already missing and nothing will happen to them. " +
                            "There is no trash: this cannot be undone."
                    )
                    if (bulkResults.isNotEmpty()) {
                        Text(
                            "In progress: ${bulkResults.values.count { it }} deleted, " +
                                "${bulkResults.values.count { !it }} failed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            modifier = Modifier.testTag("myfiles_bulk_progress")
                        )
                    }
                }
            },
            confirmButton = {
                PulseButton(
                    label = if (bulkTargets.isEmpty()) "Delete files" else "Deleting…",
                    tone = PulseButtonTone.Danger,
                    loading = bulkTargets.isNotEmpty() && bulkResults.size < bulkTargets.size,
                    enabled = bulkTargets.isEmpty(),
                    onClick = {
                        val queue = targets.filter { it.exists }
                        if (queue.isEmpty()) {
                            bulkDeleteConfirm = false
                            selected = emptySet()
                            onMessage("Nothing was deleted — every selected file is already missing. Records stay.")
                        } else {
                            val expected = queue.map { it.id }.toSet()
                            bulkTargets = expected
                            queue.forEach { t ->
                                onDeleteFile(t.id) { ok, _ ->
                                    bulkResults[t.id] = ok
                                    if (ok) scan++
                                    // The batch reports once, honestly: per-file outcomes, never a
                                    // blanket success. The last callback to land closes the dialog —
                                    // via a content check, because snapshot map key sets have no
                                    // content-based equals.
                                    if (expected.all { it in bulkResults }) {
                                        val done = bulkResults.values.count { it }
                                        val failed = expected.size - done
                                        onMessage(
                                            if (failed == 0) "Deleted $done file${if (done == 1) "" else "s"}."
                                            else "Deleted $done; $failed failed and are unchanged."
                                        )
                                        bulkDeleteConfirm = false
                                        selected = emptySet()
                                        bulkResults.clear()
                                        bulkTargets = emptySet()
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("myfiles_bulk_delete_confirm")
                )
            },
            dismissButton = {
                TextButton(onClick = { bulkDeleteConfirm = false }, enabled = bulkTargets.isEmpty()) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MyFileRow(
    record: MyFileRecord,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onDetails: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDeleteFile: () -> Unit,
    onRemoveRecord: () -> Unit,
    onFilterBySender: () -> Unit,
    dateFormatter: SimpleDateFormat
) {
    val c = LocalPulseColors.current
    PulseCard(modifier = Modifier.fillMaxWidth().testTag("myfiles_row_${record.id}")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PulseSpacing.md.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.testTag("myfiles_select_${record.id}")
            )
            Icon(
                imageVector = when (record.category) {
                    FileCategory.IMAGE -> Icons.Default.Image
                    FileCategory.VIDEO -> Icons.Default.VideoFile
                    FileCategory.AUDIO -> Icons.Default.AudioFile
                    FileCategory.ARCHIVE -> Icons.Default.Archive
                    FileCategory.DOCUMENT -> Icons.Default.Description
                    FileCategory.OTHER -> Icons.Default.Info
                },
                contentDescription = null,
                tint = if (record.exists) c.textSecondary else c.warning
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onDetails),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = NetworkUtils.formatBytes(record.onDiskSize ?: record.recordedSize) +
                        " • ${dateFormatter.format(Date(record.receivedAt))} • ${record.sender}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!record.exists) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = c.warning
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Missing on disk — moved or deleted outside the app",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.warning,
                            modifier = Modifier.testTag("myfiles_missing_${record.id}")
                        )
                    }
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                PulseIconButton(
                    icon = Icons.Default.MoreVert,
                    label = "Actions for ${record.name}",
                    onClick = { menu = true },
                    modifier = Modifier.testTag("myfiles_actions_${record.id}")
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (record.exists) "Open" else "Open (file missing)") },
                        enabled = record.exists,
                        leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                        onClick = { menu = false; onOpen() },
                        modifier = Modifier.testTag("myfiles_open_${record.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("Save to Downloads") },
                        enabled = record.exists,
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = { menu = false; onSave() },
                        modifier = Modifier.testTag("myfiles_save_${record.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        enabled = record.exists,
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menu = false; onShare() },
                        modifier = Modifier.testTag("myfiles_share_${record.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        enabled = record.exists,
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menu = false; onRename() },
                        modifier = Modifier.testTag("myfiles_rename_${record.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("Filter by this sender") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = { menu = false; onFilterBySender() },
                        modifier = Modifier.testTag("myfiles_sender_${record.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = { menu = false; onDetails() },
                        modifier = Modifier.testTag("myfiles_details_${record.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("Delete file…") },
                        enabled = record.exists,
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDeleteFile() },
                        modifier = Modifier.testTag("myfiles_deletefile_${record.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("Remove record…") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onRemoveRecord() },
                        modifier = Modifier.testTag("myfiles_removerecord_${record.id}")
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine2(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            color = c.textPrimary
        )
    }
}
