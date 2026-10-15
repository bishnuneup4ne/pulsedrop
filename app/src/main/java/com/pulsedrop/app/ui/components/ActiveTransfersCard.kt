package com.pulsedrop.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.util.NetworkUtils

@Composable
fun ActiveTransfersCard(
    activeTransfers: List<TransferItem>,
    modifier: Modifier = Modifier
) {
    if (activeTransfers.isEmpty()) return
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.primaryContainer.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "In transit • ${activeTransfers.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onPrimaryContainer
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = NetworkUtils.formatSpeed(activeTransfers.sumOf { it.speedBytesPerSec }),
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.primary
                    )
                }
            }

            activeTransfers.forEach { item ->
                TransferProgressRow(item = item)
            }
        }
    }
}

@Composable
fun TransferProgressRow(item: TransferItem) {
    val colorScheme = MaterialTheme.colorScheme
    val isIncoming = item.direction == TransferDirection.INCOMING
    val isCompleted = item.status == TransferStatus.COMPLETED
    val isFailed = item.status == TransferStatus.FAILED

    val animatedProgress by animateFloatAsState(
        targetValue = item.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "transfer_progress_anim"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surface)
            .padding(14.dp)
            .testTag("transfer_progress_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isIncoming) colorScheme.secondaryContainer else colorScheme.tertiaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isCompleted -> Icons.Default.CheckCircle
                            isFailed -> Icons.Default.Error
                            isIncoming -> Icons.Default.ArrowDownward
                            else -> Icons.Default.ArrowUpward
                        },
                        contentDescription = if (isIncoming) "Download" else "Upload",
                        tint = when {
                            isCompleted -> colorScheme.secondary
                            isFailed -> colorScheme.error
                            isIncoming -> colorScheme.onSecondaryContainer
                            else -> colorScheme.onTertiaryContainer
                        },
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = (if (isIncoming) "Receiving from " else "Sending to ") + item.remoteDevice,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = when {
                    isCompleted -> "100%"
                    isFailed -> "Failed"
                    else -> "${(animatedProgress * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isFailed -> colorScheme.error
                    else -> colorScheme.primary
                },
                modifier = Modifier.testTag("transfer_percentage_${item.id}")
            )
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = when {
                isCompleted -> colorScheme.secondary
                isFailed -> colorScheme.error
                else -> colorScheme.primary
            },
            trackColor = colorScheme.surfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = NetworkUtils.formatBytes(item.bytesTransferred) + " of " + NetworkUtils.formatBytes(item.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    item.status == TransferStatus.IN_PROGRESS ->
                        NetworkUtils.formatSpeed(item.speedBytesPerSec)
                    isCompleted -> "Complete"
                    isFailed -> item.errorMessage ?: "Failed"
                    item.status == TransferStatus.QUEUED -> "Queued"
                    else -> "Cancelled"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isCompleted -> colorScheme.secondary
                    isFailed -> colorScheme.error
                    else -> colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
