package com.pulsedrop.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import com.pulsedrop.app.R

@Composable
fun PulseHeader(
    isRunning: Boolean,
    deviceName: String,
    onToggleServer: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    // A perpetual infinite transition kept RenderThread spinning at ~75% CPU on
    // software-rendered emulators, so the live dot now pulses twice on state change.
    val pulseScale = remember { Animatable(1f) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            repeat(2) {
                pulseScale.animateTo(1.3f, tween(380))
                pulseScale.animateTo(1f, tween(380))
            }
        } else {
            pulseScale.snapTo(1f)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Brand mark
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_localdrop_logo),
                        contentDescription = "PulseDrop logo",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "PulseDrop",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .scale(pulseScale.value)
                                .clip(CircleShape)
                                .background(
                                    if (isRunning) colorScheme.secondary else colorScheme.outlineVariant
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRunning) "Live • $deviceName" else "Idle • $deviceName",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isRunning) colorScheme.secondary else colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            // Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("refresh_network_btn"),
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colorScheme.surfaceVariant,
                        contentColor = colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Network",
                        modifier = Modifier.size(20.dp)
                    )
                }

                val powerBg by animateColorAsState(
                    targetValue = if (isRunning) colorScheme.primary else colorScheme.surfaceVariant,
                    label = "powerColor"
                )
                val powerContent by animateColorAsState(
                    targetValue = if (isRunning) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                    label = "powerContentColor"
                )

                FilledTonalIconButton(
                    onClick = onToggleServer,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("toggle_server_btn"),
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = powerBg,
                        contentColor = powerContent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = if (isRunning) "Stop Server" else "Start Server",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
