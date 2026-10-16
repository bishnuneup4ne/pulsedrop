package com.pulsedrop.app.ui.screens

import com.pulsedrop.app.ui.PulseEvents
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.UiServerState
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PREFS = "localdrop_hotspot"

@Composable
fun HotspotGuideScreen(
    serverState: UiServerState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val pulse = LocalPulseColors.current
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var hotspotOn by remember { mutableStateOf(false) }
    var confirmedOff by remember { mutableStateOf(false) }
    var manuallyHidden by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf(prefs.getString("ssid", "") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var revealPass by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    // Best-effort detection; the guided confirmation covers OEMs that hide
    // tether state from third-party apps. Only a real off→on transition re-opens the
    // card, so the screen stops re-announcing the hotspot a second after hiding it.
    LaunchedEffect(Unit) {
        var lastActive = HotspotState.isActive(context)
        hotspotOn = lastActive
        while (true) {
            delay(2500)
            // ConnectivityManager binder calls plus a Settings.Global query: keep them off the main thread.
            val active = withContext(Dispatchers.IO) { HotspotState.isActive(context) }
            if (active && !lastActive && !manuallyHidden) {
                hotspotOn = true
                confirmedOff = false
            }
            if (!active && lastActive) {
                hotspotOn = false
                confirmedOff = false
                manuallyHidden = false
            }
            lastActive = active
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorScheme.primary)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colorScheme.onPrimary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiTethering,
                            contentDescription = null,
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Text(
                        text = "Hotspot mode",
                        style = MaterialTheme.typography.displaySmall,
                        color = colorScheme.onPrimary
                    )
                    Text(
                        text = "Share files anywhere — no router, no internet, no data used. Turn your hotspot on and the network name and password appear here for the other device to join.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onPrimary.copy(alpha = 0.9f)
                    )

                    Button(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                try {
                                    context.startActivity(
                                        Intent("android.settings.TETHER_SETTINGS")
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (ex: Exception) {
                                    PulseEvents.post("Open Tethering in Settings")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("open_hotspot_settings_btn"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.surface,
                            contentColor = colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hotspot settings", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (!hotspotOn) {
            // Waiting card — nothing about pairing until the hotspot is actually on
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hotspot_waiting_card"),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(pulse.warning)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Waiting for hotspot",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Turn the hotspot on with the button above. This screen recognises it automatically — or confirm below and your network details appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = {
                                hotspotOn = true
                                confirmedOff = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("hotspot_confirm_on"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("I've turned it on", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        } else {
            // Network credentials card — hotspot ID + password, nothing about the app
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hotspot_network_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(pulse.success)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (confirmedOff) "Hotspot on — tap to adjust" else "Hotspot detected",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            IconButton(
                                onClick = { showEditDialog = true },
                                modifier = Modifier.testTag("hotspot_edit_btn")
                            ) {
                                Icon(Icons.Default.Edit, "Edit network info", tint = colorScheme.primary)
                            }
                        }

                        if (ssid.isBlank() || pass.isBlank()) {
                            Text(
                                text = "Android doesn't let apps read the hotspot name and password. Enter them once from your hotspot settings — they stay on this phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showEditDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("hotspot_add_creds"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Add hotspot name & password", style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            val clipboardManager = LocalClipboardManager.current
                            CredentialRow(
                                label = "Network name",
                                value = ssid,
                                visible = true,
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(ssid))
                                    PulseEvents.post("Name copied")
                                }
                            )
                            CredentialRow(
                                label = "Password",
                                value = pass,
                                visible = revealPass,
                                onToggleVisibility = { revealPass = !revealPass },
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(pass))
                                    PulseEvents.post("Password copied")
                                }
                            )

                            // Encoding a QR bitmap is CPU work: do it off the composition
                            // thread so typing the password does not drop frames.
                            var wifiQrBitmap by remember(ssid, pass) {
                                mutableStateOf<android.graphics.Bitmap?>(null)
                            }
                            LaunchedEffect(ssid, pass) {
                                wifiQrBitmap = withContext(Dispatchers.Default) {
                                    QrCodeGenerator.generateQrBitmap(
                                        "WIFI:T:WPA;S:${wifiQrEscape(ssid)};P:${wifiQrEscape(pass)};;",
                                        512,
                                        512
                                    )
                                }
                            }
                            val wifiQr = wifiQrBitmap
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (wifiQr != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(160.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            // The quiet zone of a QR code is part of the code, not a theme colour.
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = wifiQr.asImageBitmap(),
                                            contentDescription = "Scan to join hotspot",
                                            modifier = Modifier
                                                .size(140.dp)
                                                .testTag("hotspot_wifi_qr")
                                        )
                                    }
                                }
                                Text(
                                    text = "Phones scan this to join instantly.\nPC and Mac: type the name and password above.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }

                            TextButton(
                                onClick = {
                                    hotspotOn = false
                                    confirmedOff = false
                                    // Keep it hidden until detection sees the hotspot actually
                                    // cycle off and back on — no re-opening 2s later.
                                    manuallyHidden = true
                                },
                                modifier = Modifier.testTag("hotspot_turned_off")
                            ) {
                                Text(
                                    "Hotspot switched off? Hide details",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            PulseSectionHeader("Setup", "Four steps, about a minute")
        }

        val steps = listOf(
            "Turn on this phone's hotspot" to "Personal Hotspot in system settings. Mobile data can stay off — only the local Wi-Fi link matters.",
            "Join the network" to "On the other device connect to the hotspot name and password shown on this screen. Scan the code on a phone, type it on a computer.",
            "Open PulseDrop" to "Use Radar on the other phone to find this device, or open the browser address from the Server tab on a computer.",
            "Transfer" to "Drop files either way. Size doesn't matter — even 4K video moves at the full speed of the Wi-Fi link."
        )

        steps.forEachIndexed { index, (title, description) ->
            item {
                StepRow(number = index + 1, title = title, description = description)
            }
        }

        // Diagnostics
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Network details", style = MaterialTheme.typography.titleMedium)
                    }

                    DiagnosticsRow(label = "Local IP", value = serverState.ipAddress)
                    DiagnosticsRow(label = "Port", value = serverState.port.toString())
                    DiagnosticsRow(
                        label = "Security",
                        value = if (serverState.isPinRequired) "PIN required" else "Open access"
                    )
                    DiagnosticsRow(label = "Engine", value = "NanoHTTPD", last = true)
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (showEditDialog) {
        var newSsid by remember { mutableStateOf(ssid) }
        var newPass by remember { mutableStateOf(pass) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Hotspot network", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newSsid,
                        onValueChange = { newSsid = it },
                        label = { Text("Network name (ID)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hotspot_ssid_field")
                    )
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (revealPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrect = false,
                            keyboardType = KeyboardType.Password
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hotspot_pass_field")
                    )
                    Text(
                        text = "Copy these exactly from your hotspot settings. Stored only on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        ssid = newSsid.trim()
                        pass = newPass
                        prefs.edit()
                            .putString("ssid", ssid)
                            .putString("pass", pass)
                            .apply()
                        showEditDialog = false
                    },
                    shape = RoundedCornerShape(14.dp),
                    enabled = newSsid.isNotBlank() && newPass.isNotBlank(),
                    modifier = Modifier.testTag("hotspot_save_btn")
                ) {
                    Text("Save", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    visible: Boolean,
    onCopy: () -> Unit,
    onToggleVisibility: (() -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (visible) value else "••••••••",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onToggleVisibility != null) {
                IconButton(onClick = onToggleVisibility, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (visible)
                            Icons.Default.VisibilityOff
                        else
                            Icons.Default.Visibility,
                        contentDescription = if (visible) "Hide" else "Show",
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun wifiQrEscape(s: String): String =
    s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")

@Composable
private fun StepRow(number: Int, title: String, description: String) {
    val colorScheme = MaterialTheme.colorScheme
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
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsRow(label: String, value: String, last: Boolean = false) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
        }
        if (!last) {
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

internal object HotspotState {
    fun isActive(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (n in cm.allNetworks) {
                    val info: NetworkInfo? = try { cm.getNetworkInfo(n) } catch (e: Exception) { null }
                    if (info != null && (
                            info.typeName?.contains("tether", ignoreCase = true) == true ||
                                info.subtype == 32898 /* TYPE_TETHER */
                            )
                    ) {
                        return true
                    }
                }
            }
            try {
                Settings.Global.getInt(context.contentResolver, "tether_on", 0) == 1
            } catch (e: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
