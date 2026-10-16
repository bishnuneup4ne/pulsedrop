package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.util.NetworkUtils
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseHairline
import com.pulsedrop.app.ui.components.PulseSearchField
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Activity — one feed of what this phone actually recorded.
 *
 * The list is assembled from the real sources and nothing else: the durable transfer log, the
 * live transfer map, and the creation timestamps that pairing and groups keep. Chat, calls,
 * pairing attempts and server start/stop are not persisted anywhere in this app, so they are
 * named as missing on the page rather than invented.
 */

enum class ActivityCategory { TRANSFER, DEVICE, COMMUNICATION }

enum class ActivityOutcome { SUCCESS, FAILURE, CANCELLED, LIVE, INFO }

data class ActivityItem(
    val id: String,
    val category: ActivityCategory,
    val outcome: ActivityOutcome,
    val timestamp: Long,
    val title: String,
    val detail: String,
    /** Non-null when a real detail page exists for this record. */
    val transferId: String? = null,
    /** Live rows die with the session; the page says so instead of hiding it. */
    val sessionOnly: Boolean = false
)

/** Browser rows were historically stored as "Client (1.2.3.4)" or "Web Client". */
internal fun activityPeerLabel(remoteDevice: String, clientIp: String?): String {
    val name = remoteDevice.trim()
    return when {
        name.isEmpty() -> clientIp?.takeIf { it.isNotBlank() } ?: "Unknown device"
        name == "Web Client" || name.startsWith("Client (") -> "Browser"
        name.startsWith("Paired • ") -> name.removePrefix("Paired • ").ifBlank { "Paired device" }
        else -> name
    }
}

internal fun buildActivityItems(
    history: List<TransferHistoryEntity>,
    activeTransfers: List<TransferItem>,
    pairedDevices: List<PairedDevice>,
    groups: List<ChatGroup>
): List<ActivityItem> {
    val items = mutableListOf<ActivityItem>()

    history.forEach { row ->
        val failed = row.status != "COMPLETED"
        items += ActivityItem(
            id = "transfer:${row.id}",
            category = ActivityCategory.TRANSFER,
            outcome = if (failed) ActivityOutcome.FAILURE else ActivityOutcome.SUCCESS,
            timestamp = row.timestamp,
            title = when {
                failed && row.direction == "INCOMING" -> "Incoming transfer failed"
                failed -> "Transfer failed"
                row.direction == "INCOMING" -> "Received ${row.fileName}"
                else -> "Sent ${row.fileName}"
            },
            detail = buildString {
                append(activityPeerLabel(row.remoteDevice, row.clientIp))
                append(" • ")
                append(NetworkUtils.formatBytes(row.fileSize))
                if (failed && !row.errorMessage.isNullOrBlank()) append(" • ${row.errorMessage}")
            },
            transferId = row.id
        )
    }

    // A finished transfer can sit in both stores at once; the durable row wins so the feed
    // never shows the same id twice.
    val durableIds = history.map { it.id }.toSet()
    activeTransfers.forEach { live ->
        if (live.id in durableIds) return@forEach
        if (live.status == TransferStatus.COMPLETED) return@forEach
        val cancelled = live.status == TransferStatus.CANCELLED
        items += ActivityItem(
            id = "live:${live.id}",
            category = ActivityCategory.TRANSFER,
            outcome = when {
                cancelled -> ActivityOutcome.CANCELLED
                else -> ActivityOutcome.LIVE
            },
            timestamp = live.timestamp,
            title = when {
                cancelled -> "Transfer cancelled"
                live.status == TransferStatus.QUEUED -> "Queued ${live.fileName}"
                else -> "Sending ${live.fileName}"
            }.let {
                if (live.direction.name == "INCOMING" && !cancelled && live.status == TransferStatus.IN_PROGRESS)
                    "Receiving ${live.fileName}"
                else it
            },
            detail = buildString {
                append(activityPeerLabel(live.remoteDevice, live.clientIp.nullIfBlank()))
                append(" • ")
                append(NetworkUtils.formatBytes(live.bytesTransferred))
                append(" of ")
                append(NetworkUtils.formatBytes(live.fileSize))
            },
            transferId = live.id,
            sessionOnly = true
        )
    }

    pairedDevices.forEach { p ->
        items += ActivityItem(
            id = "pair:${p.pid}",
            category = ActivityCategory.DEVICE,
            outcome = ActivityOutcome.INFO,
            timestamp = p.createdAt,
            title = "Paired with ${p.name}",
            detail = if (p.verified)
                "Safety code confirmed on both devices"
            else
                "Identity unverified — the name proves nothing until the code is checked"
        )
    }

    groups.forEach { g ->
        items += ActivityItem(
            id = "group:${g.id}",
            category = ActivityCategory.COMMUNICATION,
            outcome = ActivityOutcome.INFO,
            timestamp = g.createdAt,
            title = "Group “${g.name}” created",
            detail = "${g.memberPids.size} device${if (g.memberPids.size == 1) "" else "s"}"
        )
    }

    return items.sortedByDescending { it.timestamp }
}

private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

@Composable
fun ActivityScreen(
    history: List<TransferHistoryEntity>,
    activeTransfers: List<TransferItem>,
    pairedDevices: List<PairedDevice>,
    groups: List<ChatGroup>,
    onOpenTransferDetails: (String) -> Unit,
    onOpenDevices: () -> Unit
) {
    val c = LocalPulseColors.current
    var category by remember { mutableStateOf("All") }
    var outcome by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var detailItem by remember { mutableStateOf<ActivityItem?>(null) }

    val items = remember(history, activeTransfers, pairedDevices, groups) {
        buildActivityItems(history, activeTransfers, pairedDevices, groups)
    }

    val filtered = items.filter { item ->
        val categoryOk = when (category) {
            "Transfers" -> item.category == ActivityCategory.TRANSFER
            "Devices" -> item.category == ActivityCategory.DEVICE
            "Communication" -> item.category == ActivityCategory.COMMUNICATION
            else -> true
        }
        val outcomeOk = when (outcome) {
            "Success" -> item.outcome == ActivityOutcome.SUCCESS
            "Failed" -> item.outcome == ActivityOutcome.FAILURE
            "Cancelled" -> item.outcome == ActivityOutcome.CANCELLED
            "In progress" -> item.outcome == ActivityOutcome.LIVE
            else -> true
        }
        val q = query.trim().lowercase(Locale.getDefault())
        val queryOk = q.isEmpty() ||
            item.title.lowercase(Locale.getDefault()).contains(q) ||
            item.detail.lowercase(Locale.getDefault()).contains(q)
        categoryOk && outcomeOk && queryOk
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PulseSpacing.lg.dp)
                .padding(top = PulseSpacing.lg.dp),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
        ) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            Text(
                "Everything here comes from records this phone actually keeps. " +
                    "What is not stored is named below, not invented.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary
            )
            PulseSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search file, device, outcome",
                modifier = Modifier.testTag("activity_search")
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                listOf("All", "Transfers", "Devices", "Communication").forEach { label ->
                    PulseChip(
                        label = label,
                        selected = category == label,
                        onClick = { category = label },
                        modifier = Modifier.testTag("activity_cat_$label")
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                listOf("All", "Success", "Failed", "Cancelled", "In progress").forEach { label ->
                    PulseChip(
                        label = label,
                        selected = outcome == label,
                        onClick = { outcome = label },
                        modifier = Modifier.testTag("activity_out_$label")
                    )
                }
            }
        }

        when {
            items.isEmpty() -> PulseStatePanel(
                icon = Icons.Filled.SwapHoriz,
                title = "Nothing recorded yet",
                message = "Completed and failed transfers are kept on this phone and will show " +
                    "up here. Pairings and group creations appear as they happen.",
                modifier = Modifier.testTag("activity_empty")
            )

            filtered.isEmpty() -> PulseStatePanel(
                icon = Icons.Filled.Close,
                title = "No events match",
                message = "${items.size} recorded event${if (items.size == 1) "" else "s"} are " +
                    "hidden by the current search and filters.",
                actionLabel = "Reset filters",
                onAction = { category = "All"; outcome = "All"; query = "" },
                modifier = Modifier.testTag("activity_no_results")
            )

            else -> ActivityList(
                items = filtered,
                modifier = Modifier.weight(1f),
                onOpen = { detailItem = it }
            )
        }
    }

    detailItem?.let { item ->
        ActivityDetailDialog(
            item = item,
            onDismiss = { detailItem = null },
            onOpenTransferDetails = { onOpenTransferDetails(item.transferId!!) },
            onOpenDevices = onOpenDevices
        )
    }
}

@Composable
private fun ActivityList(
    items: List<ActivityItem>,
    modifier: Modifier = Modifier,
    onOpen: (ActivityItem) -> Unit
) {
    val c = LocalPulseColors.current
    // Group by local calendar day. Every timestamp in the app is wall-clock epoch ms written
    // by this device, so the platform zone is the correct one to read them back in.
    val groups = remember(items) { groupByDay(items) }
    LazyColumn(
        modifier
            .fillMaxWidth()
            .testTag("activity_list")
    ) {
        groups.forEachIndexed { index, (label, dayItems) ->
            item(key = "header:$label") {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = c.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = PulseSpacing.lg.dp,
                            end = PulseSpacing.lg.dp,
                            top = if (index == 0) PulseSpacing.md.dp else PulseSpacing.lg.dp,
                            bottom = PulseSpacing.xs.dp
                        )
                        .testTag("activity_header_$label")
                )
            }
            items(dayItems, key = { it.id }) { item ->
                ActivityRow(item = item, onClick = { onOpen(item) })
            }
        }
        item(key = "disclosure") {
            Surface(
                shape = RoundedCornerShape(PulseRadius.lg.dp),
                color = c.surfaceElevated,
                border = androidx.compose.foundation.BorderStroke(PulseHairline, c.borderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PulseSpacing.lg.dp)
                    .testTag("activity_disclosure")
            ) {
                Column(
                    Modifier.padding(PulseSpacing.lg.dp),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                ) {
                    Text(
                        "What this phone does not keep",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    Text(
                        "Chat messages, call history, pairing attempts and server start/stop " +
                            "leave no record after the app exits — they cannot appear here. " +
                            "Rows marked “this session” end with it. Transfer records stay until " +
                            "you clear them under Transfers; history lives on this phone only " +
                            "and is never synced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }
    }
}

internal fun groupByDay(items: List<ActivityItem>): List<Pair<String, List<ActivityItem>>> {
    val fmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun labelFor(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return when {
            sameDay(cal, today) -> "Today"
            sameDay(cal, yesterday) -> "Yesterday"
            else -> fmt.format(Date(timestamp))
        }
    }
    // Single pass in the caller's newest-first order, so day headings keep that order too.
    val byDay = LinkedHashMap<String, MutableList<ActivityItem>>()
    items.forEach { item ->
        byDay.getOrPut(labelFor(item.timestamp)) { mutableListOf() }.add(item)
    }
    return byDay.map { it.key to it.value.toList() }
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

@Composable
private fun ActivityRow(item: ActivityItem, onClick: () -> Unit) {
    val c = LocalPulseColors.current
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val (icon, tint, label) = when (item.outcome) {
        ActivityOutcome.SUCCESS -> Triple(Icons.Filled.CheckCircle, c.success, "Success")
        ActivityOutcome.FAILURE -> Triple(Icons.Filled.Close, c.danger, "Failed")
        ActivityOutcome.CANCELLED -> Triple(Icons.Filled.Close, c.warning, "Cancelled")
        ActivityOutcome.LIVE -> Triple(Icons.Filled.Radio, c.accent, "In progress")
        ActivityOutcome.INFO -> Triple(Icons.Filled.Link, c.textTertiary, "Recorded")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PulseSpacing.lg.dp,
                vertical = PulseSpacing.md.dp
            )
            .testTag("activity_row_${item.id}"),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(tint.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.sessionOnly) {
                Text(
                    "this session",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.warning,
                    modifier = Modifier.testTag("activity_session_${item.id}")
                )
            }
        }
        Text(
            timeFmt.format(Date(item.timestamp)),
            style = MaterialTheme.typography.labelMedium,
            color = c.textTertiary,
            modifier = Modifier.semantics {
                contentDescription = "$label at ${timeFmt.format(Date(item.timestamp))}"
            }
        )
    }
}

@Composable
private fun ActivityDetailDialog(
    item: ActivityItem,
    onDismiss: () -> Unit,
    onOpenTransferDetails: () -> Unit,
    onOpenDevices: () -> Unit
) {
    val c = LocalPulseColors.current
    val stamp = remember { SimpleDateFormat("EEEE, MMM d • HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            when {
                item.transferId != null -> TextButton(
                    onClick = onOpenTransferDetails,
                    modifier = Modifier.testTag("activity_detail_open")
                ) { Text("Open transfer details") }
                item.category == ActivityCategory.DEVICE -> TextButton(
                    onClick = onOpenDevices,
                    modifier = Modifier.testTag("activity_detail_devices")
                ) { Text("View devices") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(item.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier
                    .testTag("activity_detail")
                    .widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
            ) {
                DetailRow("When", stamp.format(Date(item.timestamp)))
                DetailRow("Outcome", when (item.outcome) {
                    ActivityOutcome.SUCCESS -> "Completed successfully"
                    ActivityOutcome.FAILURE -> "Failed"
                    ActivityOutcome.CANCELLED -> "Cancelled"
                    ActivityOutcome.LIVE -> "In progress now"
                    ActivityOutcome.INFO -> "Recorded fact"
                })
                DetailRow("Category", when (item.category) {
                    ActivityCategory.TRANSFER -> "File transfer"
                    ActivityCategory.DEVICE -> "Device"
                    ActivityCategory.COMMUNICATION -> "Communication"
                })
                DetailRow("Detail", item.detail)
                if (item.sessionOnly) {
                    Text(
                        "This row is live state, not a stored record — it disappears with the session.",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.warning
                    )
                }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = c.textTertiary,
            modifier = Modifier.widthIn(min = 72.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
    }
}

