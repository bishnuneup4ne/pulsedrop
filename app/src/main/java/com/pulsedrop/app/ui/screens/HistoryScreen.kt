package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.util.NetworkUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyList: List<TransferHistoryEntity>,
    onOpenFile: (String?) -> Unit,
    onShareFile: (String?) -> Unit,
    onSaveToDownloads: (String?) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedType by remember { mutableStateOf("ALL") }
    var selectedIpFilter by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    val resetFilters = {
        selectedFilter = "ALL"
        selectedType = "ALL"
        selectedIpFilter = null
    }
    val activeFilterCount =
        (if (selectedFilter != "ALL") 1 else 0) +
            (if (selectedType != "ALL") 1 else 0) +
            (if (selectedIpFilter != null) 1 else 0)

    val uniqueIps = remember(historyList) {
        historyList.mapNotNull { it.clientIp }.filter { it.isNotBlank() }.distinct()
    }

    val filteredList = remember(historyList, selectedFilter, selectedType, selectedIpFilter) {
        historyList.filter { item ->
            val matchDirection = when (selectedFilter) {
                "RECEIVED" -> item.direction == "INCOMING"
                "SENT" -> item.direction == "OUTGOING"
                else -> true
            }
            val matchType = if (selectedType == "ALL") true else fileTypeOf(item.fileName) == selectedType
            val matchIp = if (selectedIpFilter != null) item.clientIp == selectedIpFilter else true
            matchDirection && matchType && matchIp
        }
    }

    val receivedCount = historyList.count { it.direction == "INCOMING" }
    val sentCount = historyList.count { it.direction == "OUTGOING" }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulseSectionHeader(
                    "History",
                    "${filteredList.size} of ${historyList.size} records",
                    modifier = Modifier.weight(1f)
                )

                if (historyList.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterPill(
                            activeCount = activeFilterCount,
                            onClick = { showFilterSheet = true },
                            modifier = Modifier.testTag("history_filter_btn")
                        )
                        TextButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.testTag("clear_history_btn")
                        ) {
                            Text(
                                "Clear all",
                                color = colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Summary stats
        if (historyList.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatTile(
                        label = "Received",
                        value = receivedCount.toString(),
                        tint = colorScheme.secondaryContainer,
                        onTint = colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Sent",
                        value = sentCount.toString(),
                        tint = colorScheme.primaryContainer,
                        onTint = colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Devices",
                        value = uniqueIps.size.toString(),
                        tint = colorScheme.tertiaryContainer,
                        onTint = colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (filteredList.isEmpty()) {
            item {
                if (historyList.isEmpty()) {
                    PulseStatePanel(
                        icon = Icons.Default.History,
                        title = "No transfers yet",
                        message = "Files sent or received through the browser or other phones will appear here."
                    )
                } else {
                    // Filters hid everything; saying "no transfers yet" would be a lie the user
                    // can disprove with the counter already printed above.
                    PulseStatePanel(
                        icon = Icons.Outlined.SearchOff,
                        title = "Nothing matches these filters",
                        message = "${historyList.size} transfer${if (historyList.size == 1) "" else "s"} " +
                            "are hidden by the current filters.",
                        actionLabel = "Clear filters",
                        onAction = resetFilters
                    )
                }
            }
        } else {
            items(filteredList, key = { it.id }) { item ->
                HistoryItemCard(
                    item = item,
                    onOpen = { onOpenFile(item.localFilePath) },
                    onShare = { onShareFile(item.localFilePath) },
                    onSave = { onSaveToDownloads(item.localFilePath) },
                    onDelete = { onDeleteItem(item.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filters",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (activeFilterCount > 0) {
                        TextButton(onClick = resetFilters) {
                            Text("Reset", color = colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Text(
                    text = "Direction",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "ALL" to "All",
                        "RECEIVED" to "Received",
                        "SENT" to "Sent"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = selectedFilter == key,
                            onClick = { selectedFilter = key },
                            label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "File type",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "ALL" to "Any",
                        "IMAGES" to "Images",
                        "VIDEOS" to "Video",
                        "AUDIO" to "Audio",
                        "DOCS" to "Docs",
                        "ARCHIVES" to "Archives"
                    ).forEach { (key, label) ->
                        val showChip = key == "ALL" || historyList.any { fileTypeOf(it.fileName) == key }
                        if (showChip) {
                            FilterChip(
                                selected = selectedType == key,
                                onClick = { selectedType = key },
                                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }

                if (uniqueIps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Device",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedIpFilter == null,
                            onClick = { selectedIpFilter = null },
                            label = { Text("Everyone", style = MaterialTheme.typography.labelLarge) },
                            shape = RoundedCornerShape(12.dp)
                        )
                        uniqueIps.forEach { ip ->
                            FilterChip(
                                selected = selectedIpFilter == ip,
                                onClick = { selectedIpFilter = if (selectedIpFilter == ip) null else ip },
                                label = { Text(ip, style = MaterialTheme.typography.labelLarge) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredList.size} of ${historyList.size} records",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { showFilterSheet = false },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Show results", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Clear history?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "All records will be removed from the log. Files already downloaded stay on your device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showClearConfirm = false
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError
                    )
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

@Composable
private fun FilterPill(
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val active = activeCount > 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) colorScheme.primaryContainer else colorScheme.surfaceVariant,
        contentColor = if (active) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text("Filters", style = MaterialTheme.typography.labelLarge)
            if (active) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = activeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color,
    onTint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(tint)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = onTint
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = onTint.copy(alpha = 0.8f)
        )
    }
}

/**
 * Rows stored before the server kept plain peer names read "Client (10.0.0.5)" or
 * "Paired • study-laptop"; the address only earns a place when it is the whole identity.
 */
private fun peerLabel(raw: String, ip: String?): String {
    val stripped = raw.substringBefore(" (").trim()
    val name = stripped.removePrefix("Paired •").trim()
    return when {
        name.isNotBlank() && name != "Client" && name != "Web Client" -> name
        !ip.isNullOrBlank() -> "Browser · $ip"
        else -> "Browser"
    }
}

private fun fileTypeOf(fileName: String): String {    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> "IMAGES"
        "mp4", "mkv", "mov", "webm", "avi", "3gp" -> "VIDEOS"
        "mp3", "wav", "flac", "m4a", "ogg", "aac", "opus" -> "AUDIO"
        "pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "epub" -> "DOCS"
        "zip", "rar", "7z", "tar", "gz", "bz2" -> "ARCHIVES"
        else -> "OTHER"
    }
}

@Composable
fun HistoryItemCard(
    item: TransferHistoryEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val isIncoming = item.direction == "INCOMING"
    val dateFormatter = remember { SimpleDateFormat("MMM d • HH:mm", Locale.getDefault()) }
    val timeFormatted = remember(item.timestamp) { dateFormatter.format(Date(item.timestamp)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isIncoming) colorScheme.secondaryContainer else colorScheme.tertiaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isIncoming)
                            Icons.Default.ArrowDownward
                        else
                            Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = if (isIncoming) colorScheme.onSecondaryContainer else colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(if (isIncoming) "From " else "To ")
                            append(peerLabel(item.remoteDevice, item.clientIp))
                            append(" • ").append(timeFormatted)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = NetworkUtils.formatBytes(item.fileSize),
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!item.localFilePath.isNullOrBlank()) {
                    IconButton(
                        onClick = onOpen,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.surfaceVariant)
                            .testTag("open_file_btn_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open file",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.surfaceVariant)
                            .testTag("share_file_btn_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share file",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = onSave,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorScheme.secondaryContainer)
                            .testTag("download_file_btn_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save to Downloads",
                            tint = colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colorScheme.surfaceVariant)
                        .testTag("delete_item_btn_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = colorScheme.error,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}
