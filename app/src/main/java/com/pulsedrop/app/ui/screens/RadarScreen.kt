package com.pulsedrop.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.PendingPairRequest
import com.pulsedrop.app.ui.UiPeerConnection
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatePanel
import com.pulsedrop.app.ui.components.PulseSkeletonList
import com.pulsedrop.app.ui.components.PeerPinInputDialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadarScreen(
    nearbyPeers: List<PeerDevice>,
    connectedPeer: UiPeerConnection?,
    peerConnecting: Boolean,
    pairedDevices: List<PairedDevice>,
    pendingOutgoing: PendingPairRequest?,
    scanning: Boolean,
    onSendFilesToPeer: (PeerDevice, List<Uri>, String?) -> Unit,
    onConnectToAddress: (String) -> Unit,
    onDisconnectPeer: () -> Unit,
    onPair: (PeerDevice) -> Unit,
    onCancelPairRequest: () -> Unit,
    onUnpair: (String) -> Unit,
    safetyCodeOf: (String) -> String?,
    onToggleVerified: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    var verifying by remember { mutableStateOf<PairedDevice?>(null) }
    var selectedPeerForSend by remember { mutableStateOf<PeerDevice?>(null) }
    var pendingUrisForPeer by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var peerRequiringPin by remember { mutableStateOf<PeerDevice?>(null) }
    var manualIp by remember { mutableStateOf("") }

    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the address QR code shown on the other device")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            manualIp = contents
            onConnectToAddress(contents)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scannerLauncher.launch(scanOptions)
        } else {
            Toast.makeText(context, "Camera permission is needed to scan a QR code", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchScanner() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) scannerLauncher.launch(scanOptions)
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val peer = selectedPeerForSend
        if (uris.isNotEmpty() && peer != null) {
            if (peer.requiresPin) {
                pendingUrisForPeer = uris
                peerRequiringPin = peer
            } else {
                onSendFilesToPeer(peer, uris, null)
                Toast.makeText(context, "Sending ${uris.size} file(s) to ${peer.name}…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Sweeping rings only cost frames while a scan is actually running.
    val sweep = if (scanning) {
        val infiniteTransition = rememberInfiniteTransition(label = "radar")
        val radius1 by infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "radius1"
        )
        val radius2 by infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, delayMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "radius2"
        )
        radius1 to radius2
    } else {
        0.55f to 0.9f
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Discovery hero
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Device radar",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "Find other PulseDrop phones and open web servers on this network",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                    )

                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val maxR = size.minDimension / 2f
                            drawCircle(
                                color = colorScheme.outlineVariant.copy(alpha = 0.5f),
                                radius = maxR * 0.4f,
                                style = Stroke(1.5f)
                            )
                            drawCircle(
                                color = colorScheme.outlineVariant.copy(alpha = 0.7f),
                                radius = maxR * 0.7f,
                                style = Stroke(1.5f)
                            )
                            drawCircle(
                                color = colorScheme.outlineVariant.copy(alpha = 0.5f),
                                radius = maxR,
                                style = Stroke(1.5f)
                            )
                            drawCircle(
                                color = colorScheme.primary.copy(alpha = (1f - sweep.first).coerceIn(0f, 0.45f)),
                                radius = maxR * sweep.first,
                                style = Stroke(2.5f)
                            )
                            drawCircle(
                                color = colorScheme.primary.copy(alpha = (1f - sweep.second).coerceIn(0f, 0.45f)),
                                radius = maxR * sweep.second,
                                style = Stroke(2.5f)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when {
                            nearbyPeers.isNotEmpty() -> "${nearbyPeers.size} device(s) in range"
                            scanning -> "Scanning the network…"
                            else -> "Discovering devices…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (nearbyPeers.isEmpty()) colorScheme.onSurfaceVariant else colorScheme.primary
                    )

                    // Show what was found right at the top of the radar, not only further down.
                    if (nearbyPeers.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 3
                        ) {
                            nearbyPeers.take(6).forEach { p ->
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(colorScheme.secondaryContainer)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = p.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 150.dp)
                                    )
                                }
                            }
                            if (nearbyPeers.size > 6) {
                                Text(
                                    text = "+${nearbyPeers.size - 6} more",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Pairing sends an invite. You both must accept — then files and messages travel encrypted between your two devices only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // Peers
        item {
            PulseSectionHeader("Nearby devices", if (nearbyPeers.isEmpty()) null else "Tap send to pick files")
        }

        if (nearbyPeers.isEmpty()) {
            item {
                if (scanning) {
                    // The sweep is still running. A skeleton says "working on it"; "no devices
                    // found yet" would be a claim about the network we have not finished making.
                    PulseSkeletonList(rows = 2)
                } else {
                    PulseStatePanel(
                        icon = Icons.Default.Smartphone,
                        title = "No devices found yet",
                        message = "Open PulseDrop on another phone connected to the same Wi-Fi or " +
                            "hotspot, or use direct IP below."
                    )
                }
            }
        } else {
            items(nearbyPeers) { peer ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (peer.appKind == "Browser") Icons.Default.Language
                                else if (peer.name.contains("PC", ignoreCase = true) || peer.name.contains("Mac", ignoreCase = true))
                                    Icons.Default.Computer
                                else
                                    Icons.Default.Smartphone,
                                contentDescription = null,
                                tint = colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = peer.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colorScheme.onSurface,
                                    maxLines = 1
                                )
                                if (peer.requiresPin) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "PIN protected",
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (peer.appKind == "Browser") peer.ip else "${peer.ip}:${peer.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            when (peer.appKind) {
                                "LocalDrop" -> Text(
                                    text = "PulseDrop app",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.primary
                                )
                                "Browser" -> Text(
                                    text = "Browser session on this network",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.secondary
                                )
                            }
                        }

                        val peerPaired = pairedDevices.any { it.originIp == peer.ip }
                        val peerPending = pendingOutgoing?.peer?.id == peer.id

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    selectedPeerForSend = peer
                                    filePicker.launch(arrayOf("*/*"))
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("send_to_peer_${peer.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Send", style = MaterialTheme.typography.labelLarge)
                            }
                            when {
                                peerPaired -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.testTag("paired_badge_${peer.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Paired · E2E",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.primary
                                    )
                                }
                                peerPending -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.testTag("pair_pending_${peer.id}")
                                ) {
                                    Text(
                                        text = "Invite pending…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    TextButton(
                                        onClick = onCancelPairRequest,
                                        modifier = Modifier.testTag("cancel_pair_btn")
                                    ) {
                                        Text("Cancel", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                peer.appKind == "Browser" -> Text(
                                    text = "Web session",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorScheme.onSurfaceVariant
                                )
                                else -> FilledTonalButton(
                                    onClick = { onPair(peer) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("pair_btn_${peer.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Pair", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Paired devices
        if (pairedDevices.isNotEmpty()) {
            item {
                PulseSectionHeader("Paired devices", "Encrypted channel open")
            }
            items(pairedDevices) { paired ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("paired_device_${paired.pid}"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = paired.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = "Pair ${paired.pid.take(6).uppercase()} · ${paired.originIp}:${paired.originPort}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (paired.verified) Icons.Default.VerifiedUser
                                    else Icons.Default.GppMaybe,
                                    contentDescription = null,
                                    tint = if (paired.verified) colorScheme.primary else colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (paired.verified) "Safety code confirmed"
                                    else "Not verified · tap to compare codes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (paired.verified) colorScheme.primary else colorScheme.error
                                )
                            }
                        }
                        IconButton(
                            onClick = { verifying = paired },
                            modifier = Modifier.testTag("verify_btn_${paired.pid}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "Compare safety codes",
                                tint = if (paired.verified) colorScheme.onSurfaceVariant else colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { onUnpair(paired.pid) },
                            modifier = Modifier.testTag("unpair_btn_${paired.pid}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = "Unpair",
                                tint = colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Connected peer panel
        item {
            AnimatedVisibility(visible = connectedPeer != null || peerConnecting) {
                ConnectedPeerPanel(
                    connection = connectedPeer,
                    connecting = peerConnecting,
                    onSendFiles = { peer ->
                        selectedPeerForSend = peer
                        filePicker.launch(arrayOf("*/*"))
                    },
                    onDisconnect = onDisconnectPeer
                )
            }
        }

        // Direct address / QR connect
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Connect by QR or address",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Scan the QR on the other device, or type its address",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        placeholder = { Text("192.168.1.55:8080") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val clean = manualIp.trim()
                                if (clean.isNotBlank()) onConnectToAddress(clean)
                                else Toast.makeText(context, "Enter an address or scan a QR", Toast.LENGTH_SHORT).show()
                            }
                        ),
                        leadingIcon = { Icon(Icons.Default.Link, null, tint = colorScheme.onSurfaceVariant) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_ip_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { launchScanner() },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .weight(1f)
                                .testTag("scan_qr_btn")
                        ) {
                            Icon(Icons.Default.QrCodeScanner, "Scan QR", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan QR", style = MaterialTheme.typography.labelLarge)
                        }

                        Button(
                            onClick = {
                                val clean = manualIp.trim()
                                if (clean.isNotBlank()) {
                                    onConnectToAddress(clean)
                                } else {
                                    Toast.makeText(context, "Enter an address or scan a QR", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !peerConnecting,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .weight(1f)
                                .testTag("connect_peer_btn")
                        ) {
                            if (peerConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colorScheme.onPrimary
                                )
                            } else {
                                Text("Connect", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }

    if (peerRequiringPin != null) {
        PeerPinInputDialog(
            peerName = peerRequiringPin!!.name,
            onDismiss = {
                peerRequiringPin = null
                pendingUrisForPeer = emptyList()
            },
            onSubmit = { enteredPin ->
                val peer = peerRequiringPin
                val uris = pendingUrisForPeer
                if (peer != null && uris.isNotEmpty()) {
                    onSendFilesToPeer(peer, uris, enteredPin)
                    Toast.makeText(context, "Sending ${uris.size} file(s) to ${peer.name}…", Toast.LENGTH_SHORT).show()
                }
                peerRequiringPin = null
                pendingUrisForPeer = emptyList()
            }
        )
    }

    val verifyTarget = verifying
    if (verifyTarget != null) {
        val code = safetyCodeOf(verifyTarget.pid)
        AlertDialog(
            onDismissRequest = { verifying = null },
            icon = {
                Icon(
                    imageVector = if (verifyTarget.verified) Icons.Default.VerifiedUser else Icons.Default.Key,
                    contentDescription = null,
                    tint = if (verifyTarget.verified) colorScheme.primary else colorScheme.error
                )
            },
            title = { Text("Safety code") },
            text = {
                Column {
                    Text(
                        text = "Read these six digits aloud on ${verifyTarget.name} and check they match " +
                                "what that device shows for this pairing. Equal codes mean nobody is " +
                                "in the middle; different codes mean the channel is being intercepted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = code?.chunked(3)?.joinToString(" ") ?: "Unavailable",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (code != null) colorScheme.onSurface else colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("safety_code_value")
                    )
                    if (verifyTarget.verified) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "You already confirmed this pairing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onToggleVerified(verifyTarget.pid, !verifyTarget.verified)
                        verifying = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    enabled = code != null,
                    modifier = Modifier.testTag("confirm_safety_code_btn")
                ) {
                    Text(if (verifyTarget.verified) "Mark unverified" else "Codes match")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { verifying = null },
                    modifier = Modifier.testTag("close_safety_code_btn")
                ) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ConnectedPeerPanel(
    connection: UiPeerConnection?,
    connecting: Boolean,
    onSendFiles: (PeerDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connected_peer_panel"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.primaryContainer.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connecting || connection == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (connection != null) connection.label else "Connecting…",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (connection != null) {
                        Text(
                            text = connection.peer.let { "${it.ip}:${it.port}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (connection?.requiresPin == true) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Needs PIN for sending",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (connection != null) {
                Text(
                    text = "Send pushes files straight to this device. To receive here, keep your own server on — the other device sends to you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onSendFiles(connection.peer) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("send_to_connected_peer")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send files", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = onDisconnect,
                        modifier = Modifier.testTag("disconnect_peer_btn")
                    ) {
                        Text("Disconnect", color = colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
