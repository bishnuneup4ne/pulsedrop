package com.pulsedrop.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pulsedrop.app.ui.UiServerState

@Composable
fun ServerCard(
    serverState: UiServerState,
    qrBitmap: Bitmap?,
    onToggleServer: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showQrDialog by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    val borderColor by animateColorAsState(
        targetValue = if (serverState.isRunning) colorScheme.primary.copy(alpha = 0.45f) else colorScheme.outlineVariant,
        label = "serverCardBorder"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status + security chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (serverState.isRunning) colorScheme.secondaryContainer
                            else colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (serverState.isRunning) colorScheme.secondary else colorScheme.outline
                            )
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = if (serverState.isRunning) "Live" else "Offline",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (serverState.isRunning) colorScheme.onSecondaryContainer
                        else colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (serverState.isPinRequired) colorScheme.primaryContainer else colorScheme.surfaceVariant
                        )
                        .clickable { onOpenSecuritySettings() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("security_pill_btn"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (serverState.isPinRequired) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Security",
                        tint = if (serverState.isPinRequired) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (serverState.isPinRequired) "PIN ••${serverState.pin.takeLast(2)}" else "Open access",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (serverState.isPinRequired) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant
                    )
                }
            }

            // Connection hero block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (serverState.isRunning) colorScheme.primary
                            else colorScheme.surfaceVariant
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.NetworkWifi,
                                contentDescription = null,
                                tint = if (serverState.isRunning) colorScheme.onPrimary else colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CONNECT VIA BROWSER",
                                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                                color = if (serverState.isRunning) colorScheme.onPrimary.copy(alpha = 0.85f)
                                else colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = serverState.serverUrl,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (serverState.isRunning) colorScheme.onPrimary else colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            FilledTonalButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("PulseDrop URL", serverState.serverUrl))
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (serverState.isRunning) colorScheme.onPrimary.copy(alpha = 0.2f) else colorScheme.secondaryContainer,
                                    contentColor = if (serverState.isRunning) colorScheme.onPrimary else colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier.testTag("copy_url_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        Text(
                            text = "Open this address on any PC, Mac, iPhone or tablet on the same network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (serverState.isRunning) colorScheme.onPrimary.copy(alpha = 0.85f)
                            else colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Secondary actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showQrDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_qr_btn"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.onSurface)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("QR Code", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(serverState.serverUrl)))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_browser_btn"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.onSurface)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Local", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Power toggle
            Button(
                onClick = onToggleServer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("toggle_server_action_btn"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serverState.isRunning) colorScheme.errorContainer else colorScheme.primary,
                    contentColor = if (serverState.isRunning) colorScheme.onErrorContainer else colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (serverState.isRunning) Icons.Default.StopCircle else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (serverState.isRunning) "Stop server" else "Start server",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    // QR dialog
    if (showQrDialog) {
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp)),
                color = colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Scan to connect",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "Point a phone or tablet camera here to open PulseDrop in the browser.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )

                    if (qrBitmap != null) {
                        Surface(
                            modifier = Modifier
                                .size(236.dp)
                                .clip(RoundedCornerShape(20.dp)),
                            color = Color.White,
                            shadowElevation = 2.dp
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "QR Code for ${serverState.serverUrl}",
                                    modifier = Modifier.size(212.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = serverState.serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.primary
                    )

                    Button(
                        onClick = { showQrDialog = false },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Close", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
