package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.R
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing

/**
 * Who this phone is to the network. There is no account behind a PulseDrop profile — the name
 * below and the key pair in this phone's storage are the whole identity, and this page shows
 * exactly those two things plus the counts that come from the real stores.
 */

internal fun profileNameOrNull(input: String): String? =
    input.trim().takeIf { it.isNotEmpty() }?.take(40)

internal fun groupedDeviceId(id: String): String =
    id.chunked(4).joinToString(" ")

@Composable
fun ProfileScreen(
    deviceName: String,
    deviceId: String,
    modelLabel: String,
    androidLabel: String,
    serverRunning: Boolean,
    pairedCount: Int,
    groupCount: Int,
    historyCount: Int,
    onRename: (String) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    var showRename by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(deviceName) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("profile_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("profile_header")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(PulseRadius.lg.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_localdrop_logo),
                            contentDescription = null,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                    Spacer(Modifier.width(PulseSpacing.md.dp))
                    Column(Modifier.weight(1f)) {
                        Text(deviceName, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                        Text(
                            "The name every peer and browser tab sees",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
                        )
                    }
                    PulseButton(
                        "Rename",
                        onClick = { draft = deviceName; showRename = true },
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("profile_edit_name")
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("profile_identity_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    Text("Device ID", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text(
                        groupedDeviceId(deviceId),
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textSecondary
                    )
                    Text(
                        "Derived from the public half of a key pair generated on this phone; the " +
                                "private half never leaves its storage, and any paired device can " +
                                "recompute this ID on its own. It is an address, not a secret.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("profile_device_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    ProfileLine("Hardware", modelLabel)
                    ProfileLine("Android", androidLabel)
                    PulseStatus(
                        text = "Server",
                        tone = if (serverRunning) PulseStatusTone.Positive else PulseStatusTone.Neutral,
                        detail = if (serverRunning) "Running — this phone is the room's hub"
                        else "Stopped — peers cannot reach this phone"
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("profile_counts_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    ProfileCountLine(
                        label = if (pairedCount == 1) "Trusted device" else "Trusted devices",
                        count = pairedCount,
                        onClick = onOpenDevices,
                        tag = "profile_count_devices"
                    )
                    ProfileCountLine(
                        label = if (groupCount == 1) "Chat group" else "Chat groups",
                        count = groupCount,
                        onClick = onOpenChats,
                        tag = "profile_count_groups"
                    )
                    ProfileCountLine(
                        label = if (historyCount == 1) "Transfer record" else "Transfer records",
                        count = historyCount,
                        onClick = onOpenTransfers,
                        tag = "profile_count_history"
                    )
                    ProfileCountLine(
                        label = "Security review",
                        count = null,
                        onClick = onOpenSecurity,
                        tag = "profile_count_security"
                    )
                }
            }
        }

        item {
            Text(
                "PulseDrop has no sign-in, no email and no password. Nothing on this page is " +
                        "stored anywhere but this phone, so there is no account to delete — " +
                        "uninstalling erases it all.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                modifier = Modifier.fillMaxWidth().testTag("profile_note")
            )
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename this device") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        label = { Text("New name") },
                        supportingText = { Text("Up to 40 characters. Peers see it immediately.") },
                        modifier = Modifier.fillMaxWidth().testTag("profile_name_field")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = profileNameOrNull(draft) != null,
                    onClick = {
                        profileNameOrNull(draft)?.let(onRename)
                        showRename = false
                    },
                    modifier = Modifier.testTag("profile_name_save")
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }, modifier = Modifier.testTag("profile_name_cancel")) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProfileLine(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProfileCountLine(
    label: String,
    count: Int?,
    onClick: () -> Unit,
    tag: String
) {
    val c = LocalPulseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = c.textPrimary,
            modifier = Modifier.weight(1f)
        )
        if (count != null) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = c.accent,
                modifier = Modifier.padding(end = PulseSpacing.sm.dp)
            )
        }
        PulseButton("Open", onClick = onClick, tone = PulseButtonTone.Quiet, modifier = Modifier.testTag(tag))
    }
}
