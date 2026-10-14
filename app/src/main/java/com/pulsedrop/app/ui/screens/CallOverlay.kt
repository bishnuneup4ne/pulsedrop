package com.pulsedrop.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.call.CallState

@Composable
fun CallOverlay(
    state: CallState,
    remoteFrame: Bitmap?,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220))
            .systemBarsPadding()
            .testTag("call_overlay")
    ) {
        // Remote video / screen feed
        if (state.video && remoteFrame != null) {
            Image(
                bitmap = remoteFrame.asImageBitmap(),
                contentDescription = "Remote video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            if (!state.video || remoteFrame == null) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1D2939)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.peerName.take(1).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color(0xFF93C5FD)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = state.peerName.ifBlank { "Paired device" },
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.active && !state.connected) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF60A5FA)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(
                    text = when {
                        state.status.isNotBlank() -> state.status
                        !state.connected -> if (state.outgoing) "Ringing…" else "Connecting…"
                        else -> "Connected · encrypted"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9CA3AF)
                )
            }

            if (state.sharing) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "You are sharing your screen",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF34D399)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallControl(
                    icon = if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (state.muted) "Unmute" else "Mute",
                    selected = state.muted,
                    onClick = onToggleMute,
                    tag = "call_mute_btn"
                )
                if (state.video) {
                    Spacer(modifier = Modifier.size(18.dp))
                    CallControl(
                        icon = if (state.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = if (state.cameraOn) "Cam off" else "Cam on",
                        selected = !state.cameraOn,
                        onClick = onToggleCamera,
                        tag = "call_camera_btn"
                    )
                }
                Spacer(modifier = Modifier.size(18.dp))
                CallControl(
                    icon = if (state.sharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                    label = if (state.sharing) "Stop share" else "Share",
                    selected = state.sharing,
                    onClick = onToggleScreenShare,
                    tag = "call_share_btn"
                )
                Spacer(modifier = Modifier.size(18.dp))
                CallControl(
                    icon = Icons.Default.CallEnd,
                    label = "End",
                    danger = true,
                    onClick = onHangUp,
                    tag = "call_end_btn"
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String,
    selected: Boolean = false,
    danger: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(
                    when {
                        danger -> Color(0xFFDC2626)
                        selected -> Color(0xFF93C5FD)
                        else -> Color(0xFF1D2939)
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                )
                .testTag(tag),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color(0xFF0B1220) else Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF9CA3AF),
            textAlign = TextAlign.Center
        )
    }
}
