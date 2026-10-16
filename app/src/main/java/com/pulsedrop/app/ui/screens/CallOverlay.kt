package com.pulsedrop.app.ui.screens

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.call.CallParticipant
import com.pulsedrop.app.call.CallState
import com.pulsedrop.app.call.formatCallLength
import com.pulsedrop.app.ui.theme.PulseStageColors

@Composable
fun CallOverlay(
    state: CallState,
    remoteFrame: Bitmap?,
    remoteFrames: Map<String, Bitmap>,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onHangUp: () -> Unit,
    /** Hands the local camera the SurfaceView below, or null when it goes away. */
    onAttachPreview: (SurfaceHolder?) -> Unit = {},
    onSwitchCamera: () -> Unit = {},
    onMinimize: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The clock the user asks for the moment a call ends, shown while it is still up.
    val stage = PulseStageColors
    val anchor = state.connectedAt
    var seconds by remember(anchor) { mutableLongStateOf(0L) }
    LaunchedEffect(anchor) {
        if (anchor == 0L) return@LaunchedEffect
        while (true) {
            seconds = (System.currentTimeMillis() - anchor) / 1000L
            kotlinx.coroutines.delay(1000L)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stage.background)
            .systemBarsPadding()
            .testTag("call_overlay")
    ) {
        // Remote video / screen feed
        if (state.conference) {
            ConferenceGrid(
                participants = state.participants,
                frames = remoteFrames,
                showVideo = state.video || state.participants.any { it.sharing },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("conference_grid")
            )
        } else if (remoteFrame != null) {
            Image(
                bitmap = remoteFrame.asImageBitmap(),
                contentDescription = if (state.peerSharing) "Shared screen" else "Remote video",
                modifier = Modifier.fillMaxSize(),
                // A camera feed should fill the phone; a shared screen must stay whole.
                contentScale = if (state.peerSharing) ContentScale.Fit else ContentScale.Crop
            )
        }

        if (!state.conference && (remoteFrame != null || state.cameraOn)) {
            // The name and status sit over live video, so fade the top of the stage to black.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to stage.background.copy(alpha = 0.92f),
                            0.65f to stage.background.copy(alpha = 0.2f),
                            1f to Color.Transparent
                        )
                    )
            )
        }

        // Sharing banners ride above the feed so they are never hidden by a frame.
        if (state.sharing || state.peerSharing) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .testTag("share_banner"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.sharing) {
                    ShareBanner(
                        text = "You are sharing your screen",
                        background = stage.successTint,
                        foreground = stage.onSuccessTint,
                        onClick = onToggleScreenShare,
                        action = "Stop",
                        tag = "share_stop_banner"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (state.peerSharing) {
                    ShareBanner(
                        text = "${state.peerName.ifBlank { "They" }} is sharing their screen",
                        background = stage.accentTint,
                        foreground = stage.onAccentTint,
                        onClick = null,
                        action = "",
                        tag = "peer_share_banner"
                    )
                }
            }
        }

        // Tuck the call into a small bar so the rest of the app stays reachable.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(stage.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onMinimize
                )
                .testTag("call_minimize_btn"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Minimize call",
                tint = stage.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        if (state.cameraOn) {
            if (!state.conference && remoteFrame == null) {
                // Nothing from the other side yet: your own lens fills the stage so a video
                // call opens as a video screen, not a black avatar with a spinner.
                SelfPreview(
                    onAttach = onAttachPreview,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("call_self_preview")
                )
            } else {
                SelfPreview(
                    onAttach = onAttachPreview,
                    // Bottom corner, clear of both the peer's name and the control dock.
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 176.dp)
                        .size(width = 96.dp, height = 128.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, stage.borderStrong, RoundedCornerShape(14.dp))
                        .testTag("call_self_preview")
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            if (!state.conference && remoteFrame == null && !state.cameraOn) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(stage.surfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.peerName.take(1).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                        color = stage.onAccentTint
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = when {
                    state.conference -> "Group call"
                    else -> state.peerName.ifBlank { "Paired device" }
                },
                style = MaterialTheme.typography.headlineSmall,
                color = stage.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (state.conference && state.participants.isNotEmpty()) {
                Text(
                    text = state.participants.joinToString(" · ") { p ->
                        buildString {
                            append(p.name)
                            if (p.muted) append(" (muted)")
                            if (p.sharing) append(" (sharing)")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = stage.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("conference_roster")
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.active && !state.connected) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = stage.onAccentTint
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(
                    text = when {
                        state.connected -> buildString {
                            append(state.status.ifBlank { "Connected" })
                            append(" · ")
                            append(formatCallLength(seconds))
                            if (state.peerMuted) append(" · they are muted")
                        }
                        state.status.isNotBlank() -> state.status
                        else -> if (state.outgoing) "Ringing…" else "Connecting…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = stage.textSecondary,
                    modifier = Modifier.testTag("call_status_line")
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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
                Spacer(modifier = Modifier.size(12.dp))
                CallControl(
                    icon = if (state.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = if (state.cameraOn) "Cam off" else "Cam on",
                    selected = state.cameraOn,
                    onClick = onToggleCamera,
                    tag = "call_camera_btn"
                )
                if (state.cameraOn) {
                    Spacer(modifier = Modifier.size(12.dp))
                    CallControl(
                        icon = Icons.Default.SwapHoriz,
                        label = if (state.cameraFront) "Back" else "Front",
                        onClick = onSwitchCamera,
                        tag = "call_flip_btn"
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                // Sharing is a thing you do inside a live call, not at the ringing screen.
                if (state.connected) {
                    CallControl(
                        icon = if (state.sharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                        label = if (state.sharing) "Stop share" else "Share",
                        selected = state.sharing,
                        onClick = onToggleScreenShare,
                        tag = "call_share_btn"
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                }
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

/**
 * One tile per seat. Camera is off by default in a group call, so tiles fall back to initials
 * until a frame arrives - the roster still reads correctly either way.
 */
@Composable
private fun ConferenceGrid(
    participants: List<CallParticipant>,
    frames: Map<String, Bitmap>,
    showVideo: Boolean,
    modifier: Modifier = Modifier
) {
    val stage = PulseStageColors
    val rows = participants.chunked(if (participants.size > 2) 2 else 1)
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { p ->
                    val frame = frames[p.pid]?.asImageBitmap()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (rows.size > 1) 170.dp else 240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(stage.surfaceElevated)
                            .testTag("conf_tile_${p.pid}"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (frame != null && showVideo) {
                            Image(
                                bitmap = frame,
                                contentDescription = "${p.name} video",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = p.name.take(1).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (p.connected) stage.onAccentTint else stage.textTertiary
                            )
                        }
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = p.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = stage.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (p.muted) {
                                Spacer(modifier = Modifier.size(5.dp))
                                Icon(
                                    imageVector = Icons.Default.MicOff,
                                    contentDescription = "Muted",
                                    tint = stage.danger,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            if (p.sharing) {
                                Spacer(modifier = Modifier.size(5.dp))
                                Icon(
                                    imageVector = Icons.Default.ScreenShare,
                                    contentDescription = "Sharing screen",
                                    tint = stage.success,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        if (!p.connected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(stage.warningTint)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Ringing", style = MaterialTheme.typography.labelSmall, color = stage.onWarningTint)
                            }
                        }
                    }
                }
                // Keep an odd last row left-aligned instead of stretching one tile.
                if (row.size == 1 && participants.size > 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The local camera draws straight into this SurfaceView, so what you see is what the other
 * device receives. It is a self-view only - nothing is rendered back from the wire.
 */
@Composable
internal fun SelfPreview(
    onAttach: (SurfaceHolder?) -> Unit,
    modifier: Modifier = Modifier
) {
    val stage = PulseStageColors
    AndroidView(
        modifier = modifier.background(stage.surfaceElevated),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = onAttach(holder)
                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) = onAttach(null)
                })
            }
        }
    )
}

@Composable
private fun ShareBanner(
    text: String,
    background: Color,
    foreground: Color,
    onClick: (() -> Unit)?,
    action: String,
    tag: String
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(start = 14.dp, end = if (onClick != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(foreground)
        )
        Spacer(modifier = Modifier.size(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = foreground
        )
        if (onClick != null && action.isNotBlank()) {
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = background,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(foreground)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * The call shrunk to a pill: tap it to bring the stage back, red stays for hanging up.
 */
@Composable
fun CompactCallBar(
    state: CallState,
    onExpand: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stage = PulseStageColors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(stage.surfaceOverlay)
            .border(1.dp, stage.borderStrong, RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onExpand
            )
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("compact_call_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(stage.highlight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = state.peerName.take(1).uppercase().ifBlank { "P" },
                style = MaterialTheme.typography.titleMedium,
                color = stage.onAccentTint
            )
        }
        Spacer(modifier = Modifier.size(9.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = state.peerName.ifBlank { if (state.conference) "Group call" else "Call" },
                style = MaterialTheme.typography.labelLarge,
                color = stage.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    state.cameraOn || state.sharing -> "Video on · tap to expand"
                    state.connected -> "Connected · tap to expand"
                    else -> state.status.ifBlank { "Ringing…" }
                },
                style = MaterialTheme.typography.labelSmall,
                color = stage.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(stage.danger)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onHangUp
                )
                .testTag("compact_call_end"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = "End call",
                tint = stage.onDanger,
                modifier = Modifier.size(19.dp)
            )
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
    val stage = PulseStageColors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(
                    when {
                        danger -> stage.danger
                        selected -> stage.accent
                        else -> stage.surfaceElevated
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
                tint = if (selected) stage.onAccent else stage.textPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = stage.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}
