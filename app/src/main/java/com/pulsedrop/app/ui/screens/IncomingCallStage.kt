package com.pulsedrop.app.ui.screens

import android.view.SurfaceHolder
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.theme.LocalReducedMotion
import com.pulsedrop.app.ui.theme.PulseStageColors

/**
 * What a ringing screen has to say, whoever dialled.
 *
 * A call from a paired phone and a call from a browser tab used to be drawn by two different
 * composable trees - a Material dialog on one path, a hand-built page on the other - so the same
 * event looked like two different products. Both now describe the call and hand it to one screen.
 */
data class IncomingCallCard(
    val name: String,
    val detail: String,
    val groupNote: String? = null,
    val ringingLabel: String = "Ringing…",
    val hint: String,
    /** Null when this end only waits, so there is a single way out instead of an answer. */
    val acceptLabel: String? = "Accept",
    val declineLabel: String = "Decline",
    val acceptTag: String = "accept_call_btn",
    val declineTag: String = "decline_call_btn"
)

/**
 * What the dialling end can touch while the other side has not answered: your own live camera
 * and the two toggles that stay true the moment you set them. The peer's controls (share, end)
 * only appear once the call is actually answered, so nothing here promises a live connection.
 */
data class OutgoingCallMedia(
    val muted: Boolean,
    val cameraOn: Boolean,
    val onToggleMute: () -> Unit,
    val onToggleCamera: () -> Unit,
    val onAttachPreview: (SurfaceHolder?) -> Unit
)

/** The one ringing screen: an avatar that breathes, who is calling, and the way out. */
@Composable
fun IncomingCallStage(
    card: IncomingCallCard,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    media: OutgoingCallMedia? = null
) {
    val stage = PulseStageColors
    val reduced = LocalReducedMotion.current
    // The breathe is the only movement here; the buttons must stay exactly where a thumb already
    // expects them, so they never move with it.
    val pulse: Float
    val ringGlow: Float
    if (reduced) {
        pulse = 1f
        ringGlow = 0.8f
    } else {
        val transition = rememberInfiniteTransition(label = "ring")
        pulse = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse"
        ).value
        ringGlow = transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "ringGlow"
        ).value
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(stage.background)
            .systemBarsPadding()
            .testTag("incoming_call_stage")
    ) {
        if (media != null && media.cameraOn) {
            // Your own lens behind the ringing screen: what a video call should show
            // while nobody has answered yet.
            SelfPreview(
                onAttach = media.onAttachPreview,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("outgoing_call_preview")
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(stage.background.copy(alpha = 0.45f))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            Box(
                modifier = Modifier
                    .size((124 * pulse).dp)
                    .clip(CircleShape)
                    .background(stage.surfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = card.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = stage.onAccentTint
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = card.name,
                style = MaterialTheme.typography.headlineSmall,
                color = stage.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("incoming_call_name")
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = stage.textSecondary,
                textAlign = TextAlign.Center
            )
            card.groupNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = stage.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("incoming_call_group_note")
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = card.ringingLabel,
                style = MaterialTheme.typography.titleMedium,
                color = stage.onAccentTint.copy(alpha = ringGlow)
            )

            Spacer(modifier = Modifier.weight(1f))

            if (media != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    CallStageControl(
                        icon = if (media.muted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (media.muted) "Unmute" else "Mute",
                        selected = media.muted,
                        onClick = media.onToggleMute,
                        tag = "outgoing_call_mute_btn"
                    )
                    CallStageControl(
                        icon = if (media.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = if (media.cameraOn) "Cam off" else "Cam on",
                        selected = media.cameraOn,
                        onClick = media.onToggleCamera,
                        tag = "outgoing_call_cam_btn"
                    )
                }
                Spacer(modifier = Modifier.height(26.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (card.acceptLabel == null) Arrangement.Center
                    else Arrangement.SpaceEvenly
            ) {
                CallStageControl(
                    icon = Icons.Default.CallEnd,
                    label = card.declineLabel,
                    danger = true,
                    onClick = onDecline,
                    tag = card.declineTag
                )
                card.acceptLabel?.let { label ->
                    CallStageControl(
                        icon = Icons.Default.Call,
                        label = label,
                        accept = true,
                        onClick = onAccept,
                        tag = card.acceptTag
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = card.hint,
                style = MaterialTheme.typography.labelMedium,
                color = stage.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

/** The round call button the ringing screen and the live browser-call sheet both draw. */
@Composable
internal fun CallStageControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String,
    selected: Boolean = false,
    danger: Boolean = false,
    accept: Boolean = false
) {
    val stage = PulseStageColors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(
                    when {
                        danger -> stage.danger
                        accept -> stage.success
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
                tint = when {
                    danger -> stage.onDanger
                    accept -> stage.onSuccess
                    selected -> stage.onAccent
                    else -> stage.textPrimary
                },
                modifier = Modifier.size(27.dp)
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = stage.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}
