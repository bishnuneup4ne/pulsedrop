package com.pulsedrop.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.server.WebCall
import com.pulsedrop.app.ui.theme.PulseStageColors
import kotlinx.coroutines.delay


@Composable
fun WebCallScreen(
    call: WebCall,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit
) {
    val stage = PulseStageColors
    val accepted = call.state == "accepted"
    // Outgoing means this phone dialled the browser and is waiting on the other end.
    val outgoing = call.outgoing
    var elapsed by remember(call.cid) { mutableLongStateOf(0L) }

    LaunchedEffect(accepted, call.cid) {
        if (!accepted) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsed++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(stage.background)
            .systemBarsPadding()
            .testTag("web_call_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            Box(
                modifier = Modifier
                    .size(if (accepted) 96.dp else 124.dp)
                    .clip(CircleShape)
                    .background(stage.surfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = call.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = stage.onAccentTint
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = call.name.ifBlank { "Browser" },
                style = MaterialTheme.typography.headlineSmall,
                color = stage.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${if (call.video) "Video" else "Voice"} call · ${call.ip}",
                style = MaterialTheme.typography.bodyMedium,
                color = stage.textSecondary
            )
            if (call.groupPids.isNotEmpty()) {
                Text(
                    text = "Group call — ${call.groupPids.size} paired device(s) join when you answer",
                    style = MaterialTheme.typography.bodySmall,
                    color = stage.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("web_call_group_note")
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            AnimatedVisibility(visible = !accepted, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = if (outgoing) "Ringing ${call.name.ifBlank { "the browser" }}…" else "Ringing…",
                    style = MaterialTheme.typography.titleMedium,
                    color = stage.onAccentTint
                )
            }
            AnimatedVisibility(visible = accepted, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (outgoing) "Answered" else "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = stage.success
                    )
                    Text(
                        text = formatDuration(elapsed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = stage.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = accepted,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                // The live mute/camera/share controls belong to CallOverlay once media is up;
                // this sheet only needs a way out if the answer is slow to land.
                Row(horizontalArrangement = Arrangement.Center) {
                    WebCallControl(
                        icon = Icons.Default.CallEnd,
                        label = "End",
                        danger = true,
                        onClick = onHangUp,
                        tag = "web_call_end_btn"
                    )
                }
            }

            AnimatedVisibility(
                visible = !accepted,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (outgoing) Arrangement.Center else Arrangement.SpaceEvenly
                ) {
                    if (outgoing) {
                        WebCallControl(
                            icon = Icons.Default.CallEnd,
                            label = "Cancel",
                            danger = true,
                            onClick = onDecline,
                            tag = "web_call_cancel_btn"
                        )
                    } else {
                        WebCallControl(
                            icon = Icons.Default.CallEnd,
                            label = "Decline",
                            danger = true,
                            onClick = onDecline,
                            tag = "web_call_decline_btn"
                        )
                        WebCallControl(
                            icon = Icons.Default.Call,
                            label = "Accept",
                            accept = true,
                            onClick = onAccept,
                            tag = "web_call_accept_btn"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = when {
                    !accepted && outgoing ->
                        "Waiting for the browser to answer. Cancel any time."
                    accepted ->
                        "Opening the live call with ${call.name.ifBlank { "the browser" }} — " +
                            "voice and video flow over the secure link."
                    else ->
                        "Accept to open the call screen, or decline to send the browser back to the chat."
                },
                style = MaterialTheme.typography.labelMedium,
                color = stage.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun WebCallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
