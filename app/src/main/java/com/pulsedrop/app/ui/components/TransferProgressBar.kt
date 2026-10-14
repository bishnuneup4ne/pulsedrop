package com.pulsedrop.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.util.NetworkUtils
import kotlinx.coroutines.flow.StateFlow

/**
 * Live transfer card driven by a StateFlow of active transfers: animated
 * progress, byte counters, speed, and status with smooth enter/exit.
 */
@Composable
fun TransferProgressBar(
    transfersFlow: StateFlow<Map<String, TransferItem>>,
    modifier: Modifier = Modifier
) {
    val transfersMap by transfersFlow.collectAsState()
    val activeTransfers = remember(transfersMap) {
        transfersMap.values.toList().sortedByDescending { it.timestamp }
    }

    AnimatedVisibility(
        visible = activeTransfers.isNotEmpty(),
        enter = fadeIn(tween(250)) + expandVertically(tween(250)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            activeTransfers.forEach { item ->
                TransferProgressItemCard(
                    item = item,
                    modifier = Modifier.testTag("transfer_progress_${item.id}")
                )
            }
        }
    }
}

@Composable
fun TransferProgressItemCard(
    item: TransferItem,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isUpload = item.direction == TransferDirection.OUTGOING
    val isCompleted = item.status == TransferStatus.COMPLETED
    val isFailed = item.status == TransferStatus.FAILED
    val isInProgress = item.status == TransferStatus.IN_PROGRESS

    val animatedProgress by animateFloatAsState(
        targetValue = item.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "transfer_progress_anim"
    )
    val progressPercent = (animatedProgress * 100).toInt()

    val progressColor by animateColorAsState(
        targetValue = when {
            isCompleted -> colorScheme.secondary
            isFailed -> colorScheme.error
            isUpload -> colorScheme.primary
            else -> colorScheme.tertiary
        },
        label = "progressColor"
    )

    val statusColor = when {
        isCompleted -> colorScheme.secondary
        isFailed -> colorScheme.error
        else -> colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp)
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
                        .background(progressColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isCompleted -> Icons.Default.CheckCircle
                            isFailed -> Icons.Default.Error
                            isUpload -> Icons.Default.ArrowUpward
                            else -> Icons.Default.ArrowDownward
                        },
                        contentDescription = if (isUpload) "Upload" else "Download",
                        tint = progressColor,
                        modifier = Modifier.size(20.dp)
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
                        text = (if (isUpload) "Sending to " else "Receiving from ") + item.remoteDevice,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = when {
                        isCompleted -> "100%"
                        isFailed -> "Failed"
                        else -> "$progressPercent%"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = progressColor,
                    modifier = Modifier.testTag("transfer_percentage_${item.id}")
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colorScheme.surfaceVariant)
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .testTag("transfer_progress_bar_${item.id}"),
                    color = progressColor,
                    trackColor = Color.Transparent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${NetworkUtils.formatBytes(item.bytesTransferred)} of ${NetworkUtils.formatBytes(item.fileSize)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isInProgress) {
                        Text(
                            text = NetworkUtils.formatSpeed(item.speedBytesPerSec),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.primary
                        )
                    }
                    Text(
                        text = when (item.status) {
                            TransferStatus.IN_PROGRESS -> if (isUpload) "Sending…" else "Receiving…"
                            TransferStatus.COMPLETED -> "Complete"
                            TransferStatus.FAILED -> item.errorMessage ?: "Failed"
                            TransferStatus.QUEUED -> "Queued"
                            TransferStatus.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
