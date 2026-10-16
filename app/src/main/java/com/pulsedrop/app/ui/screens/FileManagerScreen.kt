package com.pulsedrop.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseCard
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
 * A browser over the folders this app actually owns. Nothing here reaches shared storage —
 * on this app's permission set that is impossible, and the page says so instead of pretending.
 */

data class ManagedRoot(
    val id: String,
    val label: String,
    val note: String,
    val dir: File,
    val readOnly: Boolean
)

internal data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** Set when a transfer-history record points at this exact path. */
    val recordId: String?
)

internal data class FolderScan(val totalBytes: Long, val fileCount: Int, val truncated: Boolean)

/**
 * A real listing of a real directory. Folders first, then files, both by name.
 * [limit] keeps one enormous drop from freezing the list; the UI discloses the cut.
 */
internal fun listFolder(
    dir: File,
    recordIdsByPath: Map<String, String> = emptyMap(),
    limit: Int = 400
): List<FileEntry> {
    val children = dir.listFiles()?.filter { it.name.isNotBlank() } ?: return emptyList()
    val sorted = children.sortedWith(
        compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) }
    )
    return sorted.asSequence().take(limit).map { f ->
        FileEntry(
            name = f.name,
            path = f.absolutePath,
            isDirectory = f.isDirectory,
            sizeBytes = if (f.isDirectory) 0L else f.length(),
            modifiedAt = f.lastModified(),
            recordId = if (f.isDirectory) null else recordIdsByPath[f.absolutePath]
        )
    }.toList()
}

/** Sums real bytes with a hard entry cap so a stray deep tree cannot hang the scan. */
internal fun folderTotal(dir: File, maxEntries: Int = 2000): FolderScan {
    if (!dir.exists()) return FolderScan(0L, 0, false)
    var bytes = 0L
    var files = 0
    var truncated = false
    val stack = ArrayDeque<File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val children = current.listFiles() ?: continue
        for (child in children) {
            if (child.isDirectory) {
                stack.addLast(child)
            } else {
                if (files >= maxEntries) {
                    truncated = true
                    break
                }
                bytes += child.length()
                files++
            }
        }
        if (truncated) break
    }
    return FolderScan(bytes, files, truncated)
}

internal fun volumeLabel(freeBytes: Long, totalBytes: Long): String = when {
    freeBytes < 0 || totalBytes < 0 -> "This volume's numbers are unavailable right now."
    else -> "${NetworkUtils.formatBytes(freeBytes)} free of ${NetworkUtils.formatBytes(totalBytes)}"
}

@Composable
fun FileManagerScreen(
    roots: List<ManagedRoot>,
    recordIdsByPath: Map<String, String>,
    volumeFreeBytes: Long,
    volumeTotalBytes: Long,
    onOpen: (String, ((Boolean, String?) -> Unit)?) -> Unit,
    onShare: (String) -> Unit,
    onSaveToDownloads: (String) -> Unit,
    onRename: (String, String, (Boolean, String) -> Unit) -> Unit,
    onDelete: (path: String, recordId: String?, onResult: (Boolean, String) -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val dateFormatter = remember { SimpleDateFormat("MMM d • HH:mm", Locale.getDefault()) }

    var stack by remember { mutableStateOf(listOf<String>()) }
    var refreshTick by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var truncatedList by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }
    var pendingRename by remember { mutableStateOf<FileEntry?>(null) }

    val currentDir = stack.lastOrNull()?.let { File(it) }
    val currentRoot = stack.firstOrNull()?.let { first -> roots.firstOrNull { it.dir.absolutePath == first } }

    LaunchedEffect(stack, refreshTick) {
        val dir = currentDir
        if (dir == null) {
            entries = emptyList()
            truncatedList = false
            return@LaunchedEffect
        }
        val listed = listFolder(dir, recordIdsByPath)
        truncatedList = listed.size >= 400
        entries = listed
    }

    BackHandler(enabled = stack.isNotEmpty()) {
        stack = stack.dropLast(1)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("files_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item {
            Column {
                Text("File manager", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text(
                    "Only the folders this app owns. Every size and date here is read from disk, not remembered from a database.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 4.dp).testTag("files_header")
                )
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("files_storage_card")) {
                PulseStatus(
                    text = "Phone storage",
                    tone = if (volumeFreeBytes >= 0) PulseStatusTone.Neutral else PulseStatusTone.Warning,
                    detail = volumeLabel(volumeFreeBytes, volumeTotalBytes)
                )
            }
        }

        if (currentDir == null) {
            items(roots, key = { "root_${it.id}" }) { root ->
                RootCard(
                    root = root,
                    onClick = { stack = listOf(root.dir.absolutePath) },
                    modifier = Modifier.fillMaxWidth().testTag("files_root_${root.id}")
                )
            }
        } else {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().testTag("files_breadcrumbs")
                ) {
                    IconButton(
                        onClick = { stack = stack.dropLast(1) },
                        modifier = Modifier.testTag("files_up")
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Go up one folder",
                            tint = c.textSecondary)
                    }
                    Text(
                        text = currentDir.name.ifBlank { currentDir.absolutePath },
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { refreshTick++ },
                        modifier = Modifier.testTag("files_refresh")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-read this folder", tint = c.textSecondary)
                    }
                }
                if (currentRoot?.readOnly == true) {
                    Text(
                        "This folder belongs to a mechanism, not to you — it is listed read-only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.warning,
                        modifier = Modifier.padding(top = 2.dp).testTag("files_readonly_note")
                    )
                }
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "Nothing in this folder right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textTertiary,
                        modifier = Modifier.testTag("files_empty")
                    )
                }
            }
            if (truncatedList) {
                item {
                    Text(
                        "This folder holds more than 400 entries — only the first 400 are listed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.warning,
                        modifier = Modifier.testTag("files_list_truncated")
                    )
                }
            }
            items(entries, key = { "e_${it.path}" }) { entry ->
                FileRow(
                    entry = entry,
                    dateLabel = dateFormatter.format(Date(entry.modifiedAt)),
                    canBrowse = !entry.isDirectory && currentRoot?.readOnly != true,
                    onEnter = { stack = stack + entry.path },
                    onOpen = {
                        onOpen(entry.path) { ok, msg ->
                            if (!ok && msg != null) onMessage(msg)
                        }
                    },
                    onShare = { onShare(entry.path) },
                    onSave = { onSaveToDownloads(entry.path) },
                    onRenameClick = { pendingRename = entry },
                    onDeleteClick = { if (currentRoot?.readOnly != true) pendingDelete = entry },
                    modifier = Modifier.fillMaxWidth().testTag("files_row_${entry.name}")
                )
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("files_disclosure")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What this page is not", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    DisclosureLine("It cannot browse shared storage — no photos, no Downloads folder, no SD card. This app holds no permission that would allow that, and it does not ask for one.")
                    DisclosureLine("Deleting is final. There is no recycle bin and no undo.")
                    if (currentRoot?.readOnly == true) {
                        DisclosureLine("Rows here are view-only on purpose: deleting them mid-transfer or mid-update breaks the mechanism that wrote them.")
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        val hasRecord = entry.recordId != null
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    if (hasRecord)
                        "This file is in the received folder (${NetworkUtils.formatBytes(entry.sizeBytes)}). The transfer-history record stays behind as a missing-file marker — deleting the file does not erase the record."
                    else
                        "${NetworkUtils.formatBytes(entry.sizeBytes)}, gone immediately. Nothing in this app keeps a copy."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(entry.path, entry.recordId) { ok, msg ->
                            onMessage(msg)
                            if (ok) refreshTick++
                        }
                    },
                    modifier = Modifier.testTag("files_delete_confirm")
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.testTag("files_delete_cancel")
                ) { Text("Keep it") }
            }
        )
    }

    pendingRename?.let { entry ->
        var name by remember(entry.path) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename file") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("New name") },
                        modifier = Modifier.fillMaxWidth().testTag("files_rename_field")
                    )
                    Text(
                        "Only files with a transfer record can be renamed here, so the record keeps pointing at the real path.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = entry.recordId
                        pendingRename = null
                        if (id != null) {
                            onRename(id, name) { ok, msg -> onMessage(msg); if (ok) refreshTick++ }
                        }
                    },
                    enabled = entry.recordId != null && name.isNotBlank(),
                    modifier = Modifier.testTag("files_rename_save")
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RootCard(root: ManagedRoot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPulseColors.current
    val scan = remember(root.dir.absolutePath) { folderTotal(root.dir) }
    PulseCard(onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = c.accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(root.label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(root.note, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Text(
                    text = when {
                        !root.dir.exists() -> "Does not exist yet — it is created the first time something lands here."
                        scan.truncated -> "${scan.fileCount}+ ${if (scan.fileCount == 1) "file" else "files"} • ${NetworkUtils.formatBytes(scan.totalBytes)} (partial scan)"
                        else -> "${scan.fileCount} ${if (scan.fileCount == 1) "file" else "files"} • ${NetworkUtils.formatBytes(scan.totalBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (root.readOnly) {
                Text("View only", style = MaterialTheme.typography.labelSmall, color = c.warning)
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    dateLabel: String,
    canBrowse: Boolean,
    onEnter: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    var menuOpen by remember(entry.path) { mutableStateOf(false) }
    PulseCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.FileOpen,
                contentDescription = null,
                tint = if (entry.isDirectory) c.accent else c.textTertiary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = entry.isDirectory, onClick = { if (entry.isDirectory) onEnter() })
            ) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.isDirectory) "Folder • tap to open"
                    else "${NetworkUtils.formatBytes(entry.sizeBytes)} • $dateLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary
                )
            }
            if (!entry.isDirectory) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("files_menu_${entry.name}")
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${entry.name}",
                            tint = c.textSecondary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                            onClick = { menuOpen = false; onOpen() },
                            modifier = Modifier.testTag("files_act_open_${entry.name}")
                        )
                        if (canBrowse) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = { menuOpen = false; onShare() },
                                modifier = Modifier.testTag("files_act_share_${entry.name}")
                            )
                            DropdownMenuItem(
                                text = { Text("Save to Downloads") },
                                leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                                onClick = { menuOpen = false; onSave() },
                                modifier = Modifier.testTag("files_act_save_${entry.name}")
                            )
                            DropdownMenuItem(
                                text = { Text(if (entry.recordId != null) "Rename" else "Rename — needs a transfer record") },
                                enabled = entry.recordId != null,
                                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                                onClick = { menuOpen = false; onRenameClick() },
                                modifier = Modifier.testTag("files_act_rename_${entry.name}")
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; onDeleteClick() },
                                modifier = Modifier.testTag("files_act_delete_${entry.name}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclosureLine(text: String) {
    val c = LocalPulseColors.current
    Row(Modifier.fillMaxWidth()) {
        Text("• ", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}
