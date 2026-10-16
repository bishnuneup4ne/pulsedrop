package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseSearchField
import com.pulsedrop.app.ui.components.PulseSection
import com.pulsedrop.app.ui.components.formatAttachmentSize
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing
import java.util.Locale

/**
 * One search over the four things this app actually holds: the durable transfer log, the
 * received-file records derived from it, the device lists in memory, and this session's chat.
 * It opens no new store and reads no file contents — there is no indexer for that anywhere in
 * the app, and a search that quietly skipped half of what people expect is worse than one that
 * says what it covers.
 */

enum class SearchCategory { TRANSFERS, FILES, DEVICES, MESSAGES }

data class SearchHit(
    val key: String,
    val category: SearchCategory,
    val title: String,
    val subtitle: String,
    val timestamp: Long?
)

internal fun containsQuery(haystacks: List<String?>, query: String): Boolean {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return true
    return haystacks.filterNotNull().any { it.lowercase(Locale.ROOT).contains(q) }
}

internal fun searchTransfers(
    active: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    query: String
): List<SearchHit> = mergeTransfers(active, history)
    .filter { transferMatchesQuery(it, query) }
    .map { entry ->
        SearchHit(
            key = "t:${entry.id}",
            category = SearchCategory.TRANSFERS,
            title = entry.item.fileName,
            subtitle = (if (entry.item.direction.name == "INCOMING") "Received from " else "Sent to ") +
                    peerLabel(entry.item.remoteDevice, entry.item.clientIp.takeIf { it.isNotBlank() }) +
                    " · ${entry.rawStatus.lowercase(Locale.ROOT)}" +
                    (if (entry.isHistory) "" else " · in progress"),
            timestamp = entry.item.timestamp.takeIf { it > 0L }
        )
    }

internal fun searchFiles(history: List<TransferHistoryEntity>, query: String): List<SearchHit> =
    myFileRecords(history, scan = false)
        .filter { myFileMatchesQuery(it, query) }
        .map { rec ->
            SearchHit(
                key = "f:${rec.id}",
                category = SearchCategory.FILES,
                title = rec.name,
                subtitle = "Received from " + rec.sender + " · " +
                        formatAttachmentSize(rec.recordedSize) +
                        (if (rec.exists) "" else " · missing from disk"),
                timestamp = if (rec.receivedAt > 0L) rec.receivedAt else null
            )
        }

internal fun searchDevices(
    nearby: List<PeerDevice>,
    paired: List<PairedDevice>,
    query: String
): List<SearchHit> =
    nearby
        .filter { containsQuery(listOf(it.name, it.ip, it.appKind), query) }
        .map {
            SearchHit(
                key = "n:${it.id}",
                category = SearchCategory.DEVICES,
                title = it.name,
                subtitle = "Nearby · ${it.ip} · ${it.appKind}",
                timestamp = it.lastSeen.takeIf { ts -> ts > 0L }
            )
        } +
        paired
            .filter { containsQuery(listOf(it.name, it.originIp, it.pid), query) }
            .map {
                SearchHit(
                    key = "p:${it.pid}",
                    category = SearchCategory.DEVICES,
                    title = it.name,
                    subtitle = "Trusted · ${it.originIp}",
                    timestamp = it.createdAt.takeIf { ts -> ts > 0L }
                )
            }

/**
 * Deleted messages keep their bubble as a placeholder but their text is gone from the thread,
 * so searching it would resurface content the user removed on purpose.
 */
internal fun searchMessages(messages: List<ClipMessage>, query: String): List<SearchHit> =
    messages
        .filter { !it.deleted }
        .filter { containsQuery(listOf(it.text, it.sender, it.attachment?.name), query) }
        .map {
            SearchHit(
                key = "m:${it.id}",
                category = SearchCategory.MESSAGES,
                title = it.sender,
                subtitle = it.attachment?.let { a -> "File · ${a.name}" }
                    ?: it.text.replace('\n', ' '),
                timestamp = it.timestamp.takeIf { ts -> ts > 0L }
            )
        }

internal fun searchCounts(
    active: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    nearby: List<PeerDevice>,
    paired: List<PairedDevice>,
    messages: List<ClipMessage>,
    query: String
): Map<SearchCategory, Int> = mapOf(
    SearchCategory.TRANSFERS to searchTransfers(active, history, query).size,
    SearchCategory.FILES to searchFiles(history, query).size,
    SearchCategory.DEVICES to searchDevices(nearby, paired, query).size,
    SearchCategory.MESSAGES to searchMessages(messages, query).size
)

internal fun categoryTarget(category: SearchCategory): PulseSection = when (category) {
    SearchCategory.TRANSFERS -> PulseSection.TRANSFERS
    SearchCategory.FILES -> PulseSection.MY_FILES
    SearchCategory.DEVICES -> PulseSection.DEVICES
    SearchCategory.MESSAGES -> PulseSection.MESSAGES
}

private val categoryOrder = listOf(
    SearchCategory.TRANSFERS,
    SearchCategory.FILES,
    SearchCategory.DEVICES,
    SearchCategory.MESSAGES
)

private fun categoryLabel(category: SearchCategory): String = when (category) {
    SearchCategory.TRANSFERS -> "Transfers"
    SearchCategory.FILES -> "Received files"
    SearchCategory.DEVICES -> "Devices"
    SearchCategory.MESSAGES -> "Messages (this session)"
}

@Composable
fun SearchScreen(
    activeTransfers: List<TransferItem>,
    history: List<TransferHistoryEntity>,
    nearbyPeers: List<PeerDevice>,
    pairedDevices: List<PairedDevice>,
    messages: List<ClipMessage>,
    onOpenSection: (PulseSection) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<SearchCategory?>(null) }

    val results = remember(activeTransfers, history, nearbyPeers, pairedDevices, messages, query) {
        searchTransfers(activeTransfers, history, query) +
            searchFiles(history, query) +
            searchDevices(nearbyPeers, pairedDevices, query) +
            searchMessages(messages, query)
    }
    val counts = remember(results) {
        categoryOrder.associateWith { cat -> results.count { it.category == cat } }
    }
    val visible = remember(results, category) {
        val filtered = if (category == null) results else results.filter { it.category == category }
        categoryOrder.mapNotNull { cat ->
            filtered.filter { it.category == cat }.takeIf { it.isNotEmpty() }?.let { cat to it }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("search_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xxl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item(key = "head_title") {
            Text(
                "Search",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
                modifier = Modifier.testTag("search_header")
            )
        }
        item(key = "head_note") {
            Text(
                "Names, senders, addresses and this session's chat text — " +
                        "never the contents inside a file, because nothing here reads those.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary
            )
        }
        item(key = "head_field") {
            PulseSearchField(
                value = text,
                onValueChange = { text = it },
                onDebouncedChange = { query = it.trim() },
                placeholder = "Search transfers, files, devices, messages",
                modifier = Modifier.fillMaxWidth().testTag("search_field")
            )
        }
        item(key = "head_chips") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                verticalAlignment = Alignment.CenterVertically,
                // Five labelled chips never fit a 360 dp phone; the row scrolls instead of
                // shrinking text into unreadability.
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                PulseChip(
                    label = "All",
                    selected = category == null,
                    onClick = { category = null },
                    modifier = Modifier.testTag("search_chip_all")
                )
                categoryOrder.forEach { cat ->
                    PulseChip(
                        label = categoryLabel(cat),
                        selected = category == cat,
                        badge = counts[cat] ?: 0,
                        onClick = { category = if (category == cat) null else cat },
                        modifier = Modifier.testTag("search_chip_${cat.name.lowercase(Locale.ROOT)}")
                    )
                }
            }
        }

        if (query.isEmpty()) {
            item(key = "guide") {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("search_guide")) {
                    Column(
                        Modifier.padding(PulseSpacing.lg.dp),
                        verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                    ) {
                        Text(
                            "What this page can search",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary
                        )
                        Text(
                            "· Transfers — file name, peer, address, id (the durable log plus whatever is moving now)\n" +
                                    "· Received files — name, type, sender (the same records My files shows)\n" +
                                    "· Devices — name, address, app kind, pairing id\n" +
                                    "· Messages — the text in this session's chat only\n\n" +
                                    "What it cannot: text inside a file, calls, and any chat from a previous " +
                                    "session — the app keeps none of those, and no index exists to look in.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                }
            }
        } else if (visible.isEmpty()) {
            item(key = "noresults") {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("search_noresults")) {
                    Column(
                        Modifier.padding(PulseSpacing.lg.dp),
                        verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)
                    ) {
                        Text(
                            "Nothing matches “$query”.",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary
                        )
                        Text(
                            "That is the honest count over transfers, received files, devices and " +
                                    "this session's messages. File contents and older sessions' chats " +
                                    "are not searched — this app does not store or index them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                }
            }
        } else {
            visible.forEach { (cat, hits) ->
                item(key = "group_${cat.name}") {
                    Text(
                        "${categoryLabel(cat)} · ${hits.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textTertiary,
                        modifier = Modifier.testTag("search_group_${cat.name.lowercase(Locale.ROOT)}")
                    )
                }
                items(hits, key = { it.key }) { hit ->
                    val index = hits.indexOf(hit)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PulseRadius.md.dp))
                            .clickable { onOpenSection(categoryTarget(hit.category)) }
                            .testTag("search_hit_${cat.name.lowercase(Locale.ROOT)}_$index")
                            .padding(PulseSpacing.sm.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                hit.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                hit.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(PulseSpacing.sm.dp))
                    }
                }
            }
        }
    }
}
