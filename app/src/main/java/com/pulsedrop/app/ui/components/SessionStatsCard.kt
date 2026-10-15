package com.pulsedrop.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.SharedFile
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.ui.UiServerState
import com.pulsedrop.app.ui.theme.LocalReducedMotion
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
    val summary = listOfNotNull(
        "${clipMessages.size} ${plural(clipMessages.size, "message")}",
        "${sharedFiles.size} ${plural(sharedFiles.size, "file")} hosted".takeIf { sharedFiles.isNotEmpty() },
        NetworkUtils.formatBytes(hostedBytes).takeIf { hostedBytes > 0 },
        "${activeTransfers.size} ${plural(activeTransfers.size, "transfer")}".takeIf { activeTransfers.isNotEmpty() }
    ).joinToString("  ·  ")

    Card(
        modifier = modifier.fillMaxWidth().testTag("session_stats_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            colorScheme.primary.copy(alpha = 0.12f),
                            colorScheme.tertiary.copy(alpha = 0.06f),
                            colorScheme.surfaceContainerLow
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (serverState.isRunning) {
                        LiveDot(color = colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "This session",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                }
                Text(
                    text = if (serverState.isRunning) "LIVE" else "IDLE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (serverState.isRunning) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Stat("Devices", peersCount.toString(), Modifier.weight(1f))
                StatRule()
                Stat("Paired", pairedCount.toString(), Modifier.weight(1f))
                StatRule()
                Stat("Uptime", uptimeOf(serverState), Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = summary.ifBlank { "Nothing moved yet - drop a file or send a beam." },
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatRule() {
    Box(
        modifier = Modifier
            .height(26.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    )
}

private fun plural(count: Int, word: String) = if (count == 1) word else "${word}s"

@Composable
private fun LiveDot(color: Color) {
    val pulse = if (LocalReducedMotion.current) {
        0.8f
    } else {
        val transition = rememberInfiniteTransition(label = "live_dot")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "live_dot_alpha"
        ).value
    }
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = pulse))
    )
}

private fun uptimeOf(state: UiServerState): String {
    if (!state.isRunning || state.startedAt <= 0L) return "-"
    val totalMinutes = (System.currentTimeMillis() - state.startedAt) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        totalMinutes < 1 -> "now"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
