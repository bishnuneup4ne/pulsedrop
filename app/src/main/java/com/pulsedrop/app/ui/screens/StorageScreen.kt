package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import java.io.File

/**
 * Storage, measured the only way this app can: by reading its own folders and files right now.
 * Every number is a real StatFs or File.length() result, and the page says plainly what it
 * cannot see — other apps' storage, and anything outside the directories this app owns.
 */

data class FolderUsage(
    val id: String,
    val label: String,
    val note: String,
    val bytes: Long,
    val fileCount: Int,
    val truncated: Boolean,
    val missing: Boolean
)

data class LogSummary(
    val rows: Int,
    val receivedCount: Int,
    val sentCount: Int,
    val failedCount: Int,
    /** Recorded sizes of COMPLETED rows only — a failed row's size is what was attempted, not what landed. */
    val receivedBytes: Long,
    val sentBytes: Long
)

internal fun summarizeLog(history: List<TransferHistoryEntity>): LogSummary {
    var received = 0
    var sent = 0
    var failed = 0
    var receivedBytes = 0L
    var sentBytes = 0L
    for (row in history) {
        val ok = row.status.equals("COMPLETED", ignoreCase = true)
        if (!ok) failed++
        when {
            row.direction == "INCOMING" && ok -> { received++; receivedBytes += row.fileSize }
            row.direction == "OUTGOING" && ok -> { sent++; sentBytes += row.fileSize }
        }
    }
    return LogSummary(history.size, received, sent, failed, receivedBytes, sentBytes)
}

/** Null when the volume could not be read; never a made-up fraction. */
internal fun volumeUsedFraction(freeBytes: Long, totalBytes: Long): Float? {
    if (freeBytes < 0L || totalBytes <= 0L || freeBytes > totalBytes) return null
    return ((totalBytes - freeBytes).toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

internal fun storageFolderRows(extRoot: File, filesDir: File, cacheDir: File): List<Pair<FolderUsage, File>> = listOf(
    FolderUsage("received", "Received files", "Everything accepted from browsers or peers.", 0L, 0, false, false) to
        File(extRoot, PulseDropReceivedDirName),
    FolderUsage("pending", "Waiting for a decision", "Uploads held while auto-accept is off.", 0L, 0, false, false) to
        File(extRoot, "WebDrop_Pending"),
    FolderUsage("chat", "Chat attachments", "Cleared on every launch — chats live in memory, so these would be orphans.", 0L, 0, false, false) to
        File(extRoot, "WebDrop_Chat"),
    FolderUsage("updates", "Update downloads", "APKs the updater fetched before an install.", 0L, 0, false, false) to
        File(extRoot, "updates"),
    FolderUsage("crashes", "Crash reports", "Kept until the next launch, if you choose to share one.", 0L, 0, false, false) to
        File(filesDir, "crashes"),
    FolderUsage("cache", "Cache", "The server writes through here mid-transfer; Android may purge it any time.", 0L, 0, false, false) to
        cacheDir
)

private const val PulseDropReceivedDirName = "WebDrop_Received"

/** Measures for real. Call off the main thread — the folder walks are capped but not free. */
internal fun measureStorage(extRoot: File, filesDir: File, cacheDir: File, dbName: String): StorageSnapshot {
    var free = -1L
    var total = -1L
    try {
        val fs = android.os.StatFs(extRoot.absolutePath)
        free = fs.availableBytes
        total = fs.totalBytes
    } catch (_: Exception) {
    }
    val folders = storageFolderRows(extRoot, filesDir, cacheDir).map { (usage, dir) ->
        val exists = dir.exists()
        val scan = folderTotal(dir)
        usage.copy(
            bytes = if (exists) scan.totalBytes else 0L,
            fileCount = scan.fileCount,
            truncated = scan.truncated,
            missing = !exists
        )
    }
    val dbDir = File(filesDir.parentFile, "databases")
    var dbBytes = 0L
    for (suffix in listOf("", "-wal", "-shm")) {
        val probe = File(dbDir, "$dbName$suffix")
        if (probe.exists()) dbBytes += probe.length()
    }
    var prefsBytes = 0L
    File(filesDir, "shared_prefs").listFiles()?.forEach { f ->
        if (f.name.endsWith(".xml")) prefsBytes += f.length()
    }
    return StorageSnapshot(free, total, folders, dbBytes, prefsBytes)
}

data class StorageSnapshot(
    val volumeFreeBytes: Long,
    val volumeTotalBytes: Long,
    val folders: List<FolderUsage>,
    val databaseBytes: Long,
    val prefsBytes: Long
)

@Composable
fun StorageScreen(
    snapshot: StorageSnapshot?,
    measuring: Boolean,
    history: List<TransferHistoryEntity>,
    onClearLog: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenMyFiles: () -> Unit,
    onOpenTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    var confirmClear by remember { mutableStateOf(false) }
    val log = remember(history) { summarizeLog(history) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("storage_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xxl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                Text(
                    "Storage & data",
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    modifier = Modifier.testTag("storage_header")
                )
                Text(
                    "Every number here was read from disk on this page, for the folders this app " +
                            "owns. It cannot see other apps' storage — Android does not let it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    TextButton(onClick = onRefresh, modifier = Modifier.testTag("storage_refresh")) {
                        Text(if (measuring) "Measuring…" else "Measure again")
                    }
                }
            }
        }

        item(key = "volume") {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("storage_volume")) {
                Column(
                    Modifier.padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                ) {
                    Text(
                        "This device's shared storage",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textPrimary
                    )
                    val frac = snapshot?.let { volumeUsedFraction(it.volumeFreeBytes, it.volumeTotalBytes) }
                    if (snapshot == null || frac == null) {
                        Text(
                            if (measuring) "Measuring the volume…" else "The volume could not be read right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().testTag("storage_volume_bar")
                        )
                        Text(
                            "${NetworkUtils.formatBytes(snapshot.volumeTotalBytes - snapshot.volumeFreeBytes)} used of " +
                                    "${NetworkUtils.formatBytes(snapshot.volumeTotalBytes)} · " +
                                    "${NetworkUtils.formatBytes(snapshot.volumeFreeBytes)} free",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                }
            }
        }

        item(key = "folders_title") {
            Text(
                "What this app keeps",
                style = MaterialTheme.typography.titleSmall,
                color = c.textTertiary
            )
        }
        val folders = snapshot?.folders ?: emptyList()
        items(folders.size, key = { "folder_$it" }) { i ->
            val f = folders[i]
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("storage_folder_${f.id}")) {
                Column(
                    Modifier.padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (f.missing) "never used"
                            else if (f.truncated) "${NetworkUtils.formatBytes(f.bytes)}+ · ${f.fileCount}+ files"
                            else "${NetworkUtils.formatBytes(f.bytes)} · ${f.fileCount} ${if (f.fileCount == 1) "file" else "files"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.textSecondary
                        )
                    }
                    Text(
                        f.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }
        if (snapshot == null) {
            item(key = "folders_measuring") {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("storage_folders_pending")) {
                    Text(
                        if (measuring) "Walking the folders…" else "No measurement yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                        modifier = Modifier.padding(PulseSpacing.lg.dp)
                    )
                }
            }
        }

        item(key = "log") {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("storage_log")) {
                Column(
                    Modifier.padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                ) {
                    Text(
                        "Transfer log",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textPrimary
                    )
                    Text(
                        "${log.rows} rows · ${log.receivedCount} received (${NetworkUtils.formatBytes(log.receivedBytes)}) · " +
                                "${log.sentCount} sent (${NetworkUtils.formatBytes(log.sentBytes)}) · ${log.failedCount} failed\n" +
                                "Bytes count only completed transfers — a failed row's size is what was " +
                                "attempted, not what landed. The log keeps names, sizes, peers and times, " +
                                "never file contents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                        TextButton(onClick = onOpenTransfers, modifier = Modifier.testTag("storage_open_transfers")) {
                            Text("View transfers")
                        }
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = log.rows > 0,
                            modifier = Modifier.testTag("storage_clear_log")
                        ) {
                            Text("Erase the log", color = c.textPrimary)
                        }
                    }
                }
            }
        }

        item(key = "records") {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("storage_records")) {
                Column(
                    Modifier.padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                ) {
                    Text(
                        "Records and keys",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textPrimary
                    )
                    Text(
                        snapshot?.let {
                            "Transfer database: ${NetworkUtils.formatBytes(it.databaseBytes)} · " +
                                    "Settings, pairings and this device's identity keys: ${NetworkUtils.formatBytes(it.prefsBytes)}"
                        } ?: "Measuring…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                    Text(
                        "Sizes only, on purpose: the identity store holds the keys that sign pairings, " +
                                "and this app never shows or ships their contents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        item(key = "cannot") {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("storage_cannot")) {
                Column(
                    Modifier.padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                ) {
                    Text(
                        "What this page cannot do",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textPrimary
                    )
                    Text(
                        "· It cannot measure other apps' storage — Android gives an app its own folders only.\n" +
                                "· There is no recycle bin: a delete here or in File Manager is gone at once.\n" +
                                "· Erasing the log removes its rows, not the files it described.\n" +
                                "· Cache and update downloads are managed by the pages that use them, not a " +
                                "one-tap wipe while a transfer might be running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                    Spacer(Modifier.height(PulseSpacing.xs.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                        TextButton(onClick = onOpenFileManager, modifier = Modifier.testTag("storage_open_files")) {
                            Text("Open file manager")
                        }
                        TextButton(onClick = onOpenMyFiles, modifier = Modifier.testTag("storage_open_myfiles")) {
                            Text("Open received files")
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Erase the transfer log?") },
            text = {
                Text(
                    "This deletes all ${log.rows} rows from the transfer database on this device. " +
                            "The files themselves stay exactly where they are — Received files, chat " +
                            "folders and anything you sent are untouched. There is no undo and no recycle bin."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearLog()
                    },
                    modifier = Modifier.testTag("storage_confirm_clear")
                ) { Text("Erase ${log.rows} rows") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, modifier = Modifier.testTag("storage_cancel_clear")) {
                    Text("Keep it")
                }
            }
        )
    }
}
