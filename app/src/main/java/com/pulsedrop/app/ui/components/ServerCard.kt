package com.pulsedrop.app.ui.components

import com.pulsedrop.app.ui.PulseEvents
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pulsedrop.app.ui.UiServerState
import kotlinx.coroutines.delay

/**
 * The one card a first-time visitor actually needs: is the server up, what is the address,
 * and the three ways to get that address onto another device. Everything here reflects the
 * real runtime state — no "Live" badge appears unless the socket is actually listening.
 */
@Composable
fun ServerCard(
    serverState: UiServerState,
    qrBitmap: Bitmap?,
    onToggleServer: () -> Unit,
    /** Name of the phone whose server this one is chatting through, when it is not the host. */
    relayHostName: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val c = com.pulsedrop.app.ui.theme.LocalPulseColors.current
    var showQrDialog by remember { mutableStateOf(false) }
    var copiedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(copiedAt) {
        if (copiedAt != 0L) {
            delay(2_500)
            copiedAt = 0L
        }
    }

    val running = serverState.isRunning
    val url = serverState.serverUrl

    fun copyUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PulseDrop address", url))
            copiedAt = System.currentTimeMillis()
            PulseEvents.post("Address copied")
        } else {
            PulseEvents.post("Could not reach the clipboard — select the address and copy it manually")
        }
    }

    fun shareUrl() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        try {
            context.startActivity(Intent.createChooser(send, "Share server address"))
        } catch (e: ActivityNotFoundException) {
            copyUrl()
            PulseEvents.post("No share app here — the address is on your clipboard")
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("server_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = c.surfaceElevated,
            contentColor = c.textPrimary
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (running) c.accent.copy(alpha = 0.35f) else c.borderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status line — the honest one.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (running) c.success else c.textTertiary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        running -> "Running · port ${serverState.port}"
                        relayHostName != null -> "Stopped · chatting through $relayHostName"
                        else -> "Stopped"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = c.textPrimary,
                    modifier = Modifier.testTag("server_status_line")
                )
                if (running && !serverState.networkName.isNullOrBlank()) {
                    Text(
                        text = " · ${serverState.networkName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // The address, big enough to read across a room.
            Text(
                text = url,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = if (running) c.textPrimary else c.textTertiary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_url_text")
            )
            Text(
                text = if (running)
                    "Open this address in a browser on any device on the same Wi-Fi."
                else
                    "Start the server, then open its address on the other device.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary
            )

            // Primary action: get the address onto the other device.
            Button(
                onClick = { if (running) copyUrl() else onToggleServer() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(if (running) "copy_url_btn" else "toggle_server_action_btn"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.onAccent
                )
            ) {
                Icon(
                    imageVector = if (running) Icons.Default.ContentCopy else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (running) {
                        if (copiedAt != 0L) "Copied!" else "Copy address"
                    } else "Start server",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showQrDialog = true },
                    enabled = running,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("open_qr_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("QR code", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { shareUrl() },
                    enabled = running,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("share_url_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (running) {
                TextButton(
                    onClick = onToggleServer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stop_server_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        tint = c.danger,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Stop server", color = c.danger, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showQrDialog) {
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                color = c.surfaceElevated,
                contentColor = c.textPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Scan to connect", style = MaterialTheme.typography.titleLarge)
                    if (qrBitmap != null) {
                        Surface(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            // A QR quiet zone has to be the code's own light mat, not a theme surface.
                            color = Color.White
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "QR code for $url",
                                    modifier = Modifier.size(200.dp)
                                )
                            }
                        }
                    } else {
                        PulseSkeleton(height = 200.dp, fixedWidth = 200.dp)
                        Text("Generating the code…", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { copyUrl() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (copiedAt != 0L) "Copied!" else "Copy")
                        }
                        OutlinedButton(
                            onClick = { shareUrl() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                    Button(
                        onClick = { showQrDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)
                    ) { Text("Close") }
                }
            }
        }
    }
}
