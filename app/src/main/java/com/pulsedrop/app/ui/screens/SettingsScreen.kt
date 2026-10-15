package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.ui.UiServerState
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseDivider
import com.pulsedrop.app.ui.components.PulseListItem
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseSwitch
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.UpdateInfo

private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Every switch here drives a real behaviour: the theme store, the server's upload gate, the
 * PIN the browser sees, the update checker. Nothing decorative, nothing that needs a restart.
 */
@Composable
fun SettingsSheet(
    serverState: UiServerState,
    pendingUploads: List<PendingUploadInfo>,
    pairedCount: Int,
    updateInfo: UpdateInfo?,
    updateStatus: String?,
    themeChoice: String,
    onThemeChoice: (String) -> Unit,
    onAutoAcceptChange: (Boolean) -> Unit,
    onAcceptUpload: (String) -> Unit,
    onDeclineUpload: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onSetPinRequired: (Boolean) -> Unit,
    onRegeneratePin: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PulseSpacing.lg.dp)
            .padding(bottom = PulseSpacing.xl.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = c.textPrimary,
            modifier = Modifier.padding(bottom = PulseSpacing.xs.dp)
        )

        PulseSectionHeader(title = "Appearance")
        listOf("System default", "Light", "Dark").forEach { choice ->
            PulseListItem(
                title = choice,
                selected = choice == themeChoice,
                onClick = { onThemeChoice(choice) },
                trailing = {
                    if (choice == themeChoice) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = c.accent)
                    }
                },
                modifier = Modifier.testTag("settings_theme_${choice.lowercase().replace(' ', '_')}")
            )
        }

        PulseDivider()
        PulseSectionHeader(
            title = "Files",
            detail = if (serverState.autoAcceptTransfers)
                "Incoming uploads land in the received folder as they arrive."
            else
                "Incoming uploads wait on this phone until you accept or decline them."
        )
        PulseSwitch(
            checked = serverState.autoAcceptTransfers,
            onCheckedChange = onAutoAcceptChange,
            label = "Accept files automatically",
            detail = "Turn off to approve every file on the phone first",
            modifier = Modifier.testTag("settings_auto_accept")
        )
        if (pendingUploads.isNotEmpty()) {
            Spacer(Modifier.height(PulseSpacing.xs.dp))
            pendingUploads.forEach { upload ->
                PulseCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = upload.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = c.textPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = "${bytesLabel(upload.fileSize)} · from ${upload.fromDevice}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = PulseSpacing.sm.dp)
                        ) {
                            PulseButton(
                                label = "Accept",
                                onClick = { onAcceptUpload(upload.id) },
                                modifier = Modifier.testTag("pending_accept_${upload.id}")
                            )
                            PulseButton(
                                label = "Decline",
                                onClick = { onDeclineUpload(upload.id) },
                                tone = PulseButtonTone.Danger,
                                modifier = Modifier.testTag("pending_decline_${upload.id}")
                            )
                        }
                    }
                }
            }
        }

        PulseDivider()
        PulseSectionHeader(title = "Security")
        PulseSwitch(
            checked = serverState.isPinRequired,
            onCheckedChange = onSetPinRequired,
            label = "Require PIN in browsers",
            detail = if (serverState.isPinRequired)
                "PIN ${serverState.pin} — anyone opening the address must type it"
            else "The address opens without a code",
            modifier = Modifier.testTag("settings_pin_required")
        )
        PulseListItem(
            title = "New PIN",
            subtitle = "Replaces the current browser code",
            leading = { Icon(Icons.Default.Refresh, contentDescription = null, tint = c.textSecondary) },
            onClick = onRegeneratePin,
            modifier = Modifier.testTag("settings_regenerate_pin")
        )
        PulseListItem(
            title = "Paired devices",
            subtitle = if (pairedCount == 0)
                "None yet — pair from the Radar tab"
            else "$pairedCount trusted ${if (pairedCount == 1) "phone" else "phones"}",
            leading = { Icon(Icons.Default.Security, contentDescription = null, tint = c.textSecondary) },
            onClick = onOpenDevices,
            modifier = Modifier.testTag("settings_open_devices")
        )

        PulseDivider()
        PulseSectionHeader(title = "Server")
        PulseListItem(
            title = "Open the server card",
            subtitle = "${serverState.ipAddress}:${serverState.port} · ${
                if (serverState.isRunning) "serving now" else "not serving"
            }",
            leading = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = c.textSecondary) },
            onClick = onOpenServer,
            modifier = Modifier.testTag("settings_open_server")
        )

        PulseDivider()
        PulseSectionHeader(title = "Updates")
        Text(
            text = "PulseDrop v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textPrimary
        )
        if (updateStatus != null) {
            Text(
                text = updateStatus,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary
            )
        }
        if (updateInfo != null) {
            Text(
                text = "v${updateInfo.versionName} is available",
                style = MaterialTheme.typography.bodySmall,
                color = c.accent
            )
        }
        PulseButton(
            label = "Check for updates",
            onClick = onCheckUpdate,
            tone = PulseButtonTone.Neutral,
            modifier = Modifier
                .padding(top = PulseSpacing.xs.dp)
                .testTag("settings_check_update")
        )

        PulseDivider()
        PulseSectionHeader(title = "About")
        Text(
            text = "PulseDrop is free for noncommercial use (PolyForm Noncommercial 1.0.0). " +
                "Everything stays on your network; pairing is end-to-end encrypted.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp),
            modifier = Modifier.padding(top = PulseSpacing.xs.dp)
        ) {
            Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = c.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "github.com/bishnuneup4ne/pulsedrop",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
