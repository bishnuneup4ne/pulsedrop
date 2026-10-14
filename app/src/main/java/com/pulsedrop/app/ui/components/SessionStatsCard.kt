package com.pulsedrop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.SharedFile
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.UiServerState
import com.pulsedrop.app.util.NetworkUtils

@Composable
fun SessionStatsCard(
    serverState: UiServerState,
    sharedFiles: List<SharedFile>,
    clipMessages: List<ClipMessage>,
    activeTransfers: List<TransferItem>,
    pairedCount: Int,
    peersCount: Int,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val hostedBytes = sharedFiles.sumOf { it.size }
    val queued = activeTransfers.count {
        it.status == TransferStatus.QUEUED || it.status == TransferStatus.IN_PROGRESS
    }
    val lastMessage = clipMessages.lastOrNull()

    val tiles = listOf(
        StatTile("Devices", peersCount.toString(), Icons.Default.SettingsInputAntenna, colorScheme.primary),
        StatTile("Messages", clipMessages.size.toString(), Icons.AutoMirrored.Filled.Chat, colorScheme.tertiary),
        StatTile("Transfers", queued.toString(), Icons.Default.Layers, colorScheme.secondary),
        StatTile("Hosted size", NetworkUtils.formatBytes(hostedBytes), Icons.Default.Storage, colorScheme.error),
        StatTile("Paired", pairedCount.toString(), Icons.Default.People, colorScheme.primary),
        StatTile("Uptime", uptimeOf(serverState), Icons.Default.History, colorScheme.tertiary)
    )

    Card(
        modifier = modifier.fillMaxWidth().testTag("session_stats_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Session",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = if (serverState.isRunning) "Live" else "Idle",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (serverState.isRunning) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }

            tiles.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { tile ->
                        Box(modifier = Modifier.weight(1f)) { StatTileView(tile) }
                    }
                    repeat(3 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Text(
                text = lastMessage
                    ?.let { "Last beam · ${it.sender}: ${it.text}" }
                    ?: "No text beams yet. Share a file or write a message to wake the session up.",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
            )
        }
    }
}

private data class StatTile(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val tint: Color
)

@Composable
private fun StatTileView(tile: StatTile) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tile.tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = tile.tint,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = tile.value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = tile.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant
        )
    }
}

private fun uptimeOf(state: UiServerState): String {
    if (!state.isRunning || state.startedAt <= 0L) return "-"
    val totalMinutes = (System.currentTimeMillis() - state.startedAt) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        totalMinutes < 1 -> "just now"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
