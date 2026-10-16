package com.pulsedrop.app.ui.screens

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Pure mappings ──────────────────────────────────────────────────────────

internal data class MediaFacts(
    val durationSeconds: Long? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null
) {
    val isEmpty: Boolean get() = durationSeconds == null && widthPx == null && heightPx == null
}

/**
 * Media the library may show: completed incoming rows classified as image, video or audio by the
 * same extension/MIME rules My Files uses. Documents and archives are not media and never appear
 * here just to fill the grid.
 */
internal fun mediaRecords(history: List<TransferHistoryEntity>, scan: Boolean = true): List<MyFileRecord> =
    myFileRecords(history, scan).filter {
        it.category == FileCategory.IMAGE || it.category == FileCategory.VIDEO ||
            it.category == FileCategory.AUDIO
    }

internal fun mediaKindLabel(category: FileCategory): String = when (category) {
    FileCategory.IMAGE -> "Image"
    FileCategory.VIDEO -> "Video"
    FileCategory.AUDIO -> "Audio"
    else -> "Media"
}

internal fun formatMediaDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * Reads what the file itself reveals, right now, on demand.
 *
 * Images get real pixel bounds from the decoder; video and audio get duration and — for video —
 * frame size from the platform retriever. A file that will not answer gets nulls, and the UI
 * says so instead of guessing.
 */
internal fun readMediaFacts(record: MyFileRecord): MediaFacts {
    if (!record.exists) return MediaFacts()
    val path = record.path
    return when (record.category) {
        FileCategory.IMAGE -> {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(path, opts) }
            MediaFacts(
                widthPx = opts.outWidth.takeIf { it > 0 },
                heightPx = opts.outHeight.takeIf { it > 0 }
            )
        }
        FileCategory.VIDEO, FileCategory.AUDIO -> {
            val r = MediaMetadataRetriever()
            try {
                runCatching { r.setDataSource(path) }.getOrNull()
                val dur = runCatching {
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }.getOrNull()
                val w = runCatching {
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                }.getOrNull()
                val h = runCatching {
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                }.getOrNull()
                MediaFacts(
                    durationSeconds = dur?.div(1000L)?.takeIf { it > 0 },
                    widthPx = if (record.category == FileCategory.VIDEO) w else null,
                    heightPx = if (record.category == FileCategory.VIDEO) h else null
                )
            } catch (_: Exception) {
                MediaFacts()
            } finally {
                runCatching { r.release() }
            }
        }
        else -> MediaFacts()
    }
}

private val stampFormat = SimpleDateFormat("MMM d • HH:mm", Locale.getDefault())

// ── Screen ───────────────────────────────────────────────────────────────

@Composable
fun MediaScreen(
    history: List<TransferHistoryEntity>,
    onOpenFile: (String) -> Unit,
    onOpenTransferDetails: (String) -> Unit
) {
    val c = LocalPulseColors.current
    var tab by remember { mutableStateOf("All") }
    var detail by remember { mutableStateOf<MyFileRecord?>(null) }

    val records = remember(history) { mediaRecords(history) }
    val shown = records.filter {
        tab == "All" || mediaKindLabel(it.category) == tab
    }
    val counts = records.groupingBy { mediaKindLabel(it.category) }.eachCount()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PulseSpacing.lg.dp)
                .padding(top = PulseSpacing.lg.dp),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
        ) {
            Text("Media library", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            Text(
                "Images, video and audio that came through PulseDrop and are still in its " +
                    "received folder. Not a browser of the phone's other media — Android does " +
                    "not give this app that right.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                listOf("All", "Image", "Video", "Audio").forEach { label ->
                    val n = if (label == "All") records.size else counts[label] ?: 0
                    PulseChip(
                        label = "$label ($n)",
                        selected = tab == label,
                        onClick = { tab = label },
                        modifier = Modifier.testTag("media_tab_$label")
                    )
                }
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PulseCard(Modifier.padding(PulseSpacing.lg.dp).testTag("media_empty")) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                        Icon(Icons.Filled.Photo, null, tint = c.accent)
                        Text("No media received yet", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(
                            "Photos, clips and songs you receive land here with the real size and " +
                                "date the transfer recorded. Nothing is pre-filled to make the " +
                                "grid look lived-in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
                        )
                    }
                }
            }
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PulseCard(Modifier.padding(PulseSpacing.lg.dp).testTag("media_none_match")) {
                    Text(
                        "No ${tab.lowercase(Locale.getDefault())}s have arrived. " +
                            "${records.size} media file${if (records.size == 1) "" else "s"} " +
                            "of the other kinds are here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PulseSpacing.md.dp)
                    .testTag("media_grid"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(PulseSpacing.sm.dp),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
            ) {
                items(shown, key = { it.id }) { r ->
                    MediaTile(r) { detail = r }
                }
                item {
                    PulseCard(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = PulseSpacing.md.dp)
                            .testTag("media_disclosure")
                    ) {
                        Text(
                            "Thumbnails are decoded from the real file, so a missing file shows a " +
                                "gap, not a stand-in. Duration and pixel size are read from the " +
                                "file when you open it — the app keeps no media cache and no " +
                                "history of media you deleted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                }
            }
        }
    }

    detail?.let { r ->
        MediaDetail(
            record = r,
            onOpen = { onOpenFile(r.path) },
            onDetails = { onOpenTransferDetails(r.id) },
            onDismiss = { detail = null }
        )
    }
}

@Composable
private fun MediaTile(record: MyFileRecord, onClick: () -> Unit) {
    val c = LocalPulseColors.current
    PulseCard(
        onClick = onClick,
        contentPadding = PulseSpacing.sm.dp,
        modifier = Modifier.testTag("media_tile_${record.id}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(PulseRadius.md.dp))
                    .background(c.surface)
            ) {
                when {
                    !record.exists -> {
                        Icon(
                            Icons.Filled.BrokenImage,
                            contentDescription = "Missing on disk",
                            tint = c.textTertiary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    record.category == FileCategory.IMAGE -> {
                        AsyncImage(
                            model = File(record.path),
                            contentDescription = record.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    record.category == FileCategory.VIDEO -> {
                        Icon(
                            Icons.Filled.Movie,
                            contentDescription = "Video",
                            tint = c.accent,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = "Audio",
                            tint = c.accent,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                PulseChip(
                    label = mediaKindLabel(record.category),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(PulseSpacing.xs.dp)
                        .testTag("media_kind_${record.id}")
                )
            }
            Text(
                record.name,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${NetworkUtils.formatBytes(record.onDiskSize ?: record.recordedSize)} • " +
                    "${stampFormat.format(Date(record.receivedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary
            )
        }
    }
}

@Composable
private fun MediaDetail(
    record: MyFileRecord,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalPulseColors.current
    var facts by remember(record.id) { mutableStateOf<MediaFacts?>(null) }
    LaunchedEffect(record.id) {
        facts = withContext(Dispatchers.IO) { readMediaFacts(record) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                Detail2("Kind", mediaKindLabel(record.category))
                Detail2(
                    "Size",
                    if (record.exists) NetworkUtils.formatBytes(record.onDiskSize ?: record.recordedSize)
                    else "Recorded ${NetworkUtils.formatBytes(record.recordedSize)} — file is gone from disk"
                )
                Detail2("Received", stampFormat.format(Date(record.receivedAt)))
                Detail2("From", record.sender)
                when {
                    !record.exists -> PulseStatus(
                        text = "Missing on disk",
                        tone = PulseStatusTone.Warning,
                        detail = "Moved or deleted outside the app"
                    )
                    facts == null -> Text(
                        "Reading the file…",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary
                    )
                    facts?.isEmpty == true -> Text(
                        "The file would not reveal a duration or size — only what the transfer " +
                            "record itself holds is shown above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                        modifier = Modifier.testTag("media_facts_none")
                    )
                    else -> {
                        facts?.durationSeconds?.let {
                            Detail2("Duration", formatMediaDuration(it))
                        }
                        facts?.let {
                            if (it.widthPx != null && it.heightPx != null) {
                                Detail2("Dimensions", "${it.widthPx} × ${it.heightPx} px")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onOpen,
                enabled = record.exists,
                modifier = Modifier.testTag("media_detail_open")
            ) { Text("Open") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDetails, modifier = Modifier.testTag("media_detail_transfer")) {
                    Text("Transfer record")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        modifier = Modifier.testTag("media_detail")
    )
}

@Composable
private fun Detail2(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
    }
}
