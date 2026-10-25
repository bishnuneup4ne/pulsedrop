package com.pulsedrop.app.ui.screens

import android.view.SurfaceHolder
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CallEnd
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.call.CallState
import com.pulsedrop.app.server.WebCall
import com.pulsedrop.app.ui.theme.PulseStageColors
import kotlinx.coroutines.delay

/**
 * The browser-call sheet. Ringing reuses [IncomingCallStage] so a call from a tab and a call from
 * a paired phone look identical; only the answered hand-off, which no phone-side call has, is
 * drawn here.
 */
@Composable
fun WebCallScreen(
    call: WebCall,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    callState: CallState = CallState(),
    onToggleMute: () -> Unit = {},
    onToggleCamera: () -> Unit = {},
    onEnsureCamera: () -> Unit = {},
    onAttachPreview: (SurfaceHolder?) -> Unit = {}
) {
    val outgoing = call.outgoing
    val name = call.name.ifBlank { "Browser" }
    val groupNote = if (call.groupPids.isNotEmpty())
        "Group call — ${call.groupPids.size} paired device(s) join when you answer"
    else null

    if (call.state != "accepted") {
        if (outgoing && call.video) {
            LaunchedEffect(call.cid) { onEnsureCamera() }
        }
        IncomingCallStage(
            card = IncomingCallCard(
                name = name,
                detail = "${if (call.video) "Video" else "Voice"} call · ${call.ip}",
                groupNote = groupNote,
                ringingLabel = if (outgoing) "Ringing $name…" else "Ringing…",
                hint = if (outgoing)
                    "Waiting for the browser to answer. Cancel any time."
                else
                    "Accept to open the call screen, or decline to send the browser back to the chat.",
                acceptLabel = if (outgoing) null else "Accept",
                declineLabel = if (outgoing) "Cancel" else "Decline",
                acceptTag = "web_call_accept_btn",
                declineTag = if (outgoing) "web_call_cancel_btn" else "web_call_decline_btn"
            ),
            onAccept = onAccept,
            onDecline = onDecline,
            media = if (outgoing) OutgoingCallMedia(
                muted = callState.muted,
                cameraOn = callState.cameraOn,
                onToggleMute = onToggleMute,
                onToggleCamera = onToggleCamera,
                onAttachPreview = onAttachPreview
            ) else null
        )
        return
    }

    val stage = PulseStageColors
    var elapsed by remember(call.cid) { mutableLongStateOf(0L) }
    LaunchedEffect(call.cid) {
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
            Spacer(modifier = Modifier.height(64.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
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
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                color = stage.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${if (call.video) "Video" else "Voice"} call · ${call.ip}",
                style = MaterialTheme.typography.bodyMedium,
                color = stage.textSecondary,
                textAlign = TextAlign.Center
            )
            groupNote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = stage.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("web_call_group_note")
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

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

            Spacer(modifier = Modifier.weight(1f))

            // The live mute/camera/share controls belong to CallOverlay once media is up; this
            // sheet only needs a way out if the answer is slow to land.
            Row(horizontalArrangement = Arrangement.Center) {
                CallStageControl(
                    icon = Icons.Default.CallEnd,
                    label = "End",
                    danger = true,
                    onClick = onHangUp,
                    tag = "web_call_end_btn"
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                // "the secure link" was the old wording, and it is the claim this screen has no
                // business making: the certificate is this phone's own, so the browser's warning
                // proves encryption and nothing about who is on the other end. Saying which of the
                // two it proves is the point — a user who was told "secure" and then saw a warning
                // has been taught to click through warnings.
                text = "Live call with $name — encrypted with this phone's own certificate. " +
                    "The browser's warning means nobody vouched for this phone, not that the call is in the clear.",
                style = MaterialTheme.typography.labelMedium,
                color = stage.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .testTag("web_call_security_note")
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
