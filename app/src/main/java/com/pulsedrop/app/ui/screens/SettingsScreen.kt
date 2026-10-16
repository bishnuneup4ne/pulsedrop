package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.ui.UiServerState
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowGroup
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.UpdateInfo

private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** Instagram's toggle: the switch rides at the end of the row, styled from the semantic palette. */
@Composable
private fun RowSwitch(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val c = LocalPulseColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.semantics { contentDescription = label },
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.accent,
            checkedThumbColor = c.onAccent,
            uncheckedTrackColor = c.borderStrong,
            uncheckedThumbColor = c.surfaceElevated,
            uncheckedBorderColor = c.borderSubtle
        )
    )
}

/**
 * Every switch here drives a real behaviour: the theme store, the server's upload gate, the
 * PIN the browser sees, the update checker. Nothing decorative, nothing that needs a restart.
 * Laid out as Instagram's "Settings and activity": a small gray label, then rows of
 * icon + title + subtitle with a switch (or a chevron) at the end, hairlines inset to the text.
 */
@Composable
fun SettingsSheet(
    serverState: UiServerState,
    pendingUploads: List<PendingUploadInfo>,
    pairedCount: Int,
    updateInfo: UpdateInfo?,
    updateStatus: String?,
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
        // ── Files ───────────────────────────────────────────────────────────
        PulseRowLabel("Files")
        PulseRowGroup {
            PulseRow(
                title = "Accept files automatically",
                icon = Icons.Default.Download,
                subtitle = "Turn off to approve every file on the phone first",
                onClick = { onAutoAcceptChange(!serverState.autoAcceptTransfers) },
                trailing = {
                    RowSwitch(
                        checked = serverState.autoAcceptTransfers,
                        label = "Accept files automatically",
                        onCheckedChange = onAutoAcceptChange
                    )
                },
                modifier = Modifier.testTag("settings_auto_accept")
            )
        }
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

        // ── Security ────────────────────────────────────────────────────────
        PulseRowLabel("Security")
        PulseRowGroup {
            PulseRow(
                title = "Require PIN in browsers",
                icon = Icons.Default.Key,
                subtitle = if (serverState.isPinRequired)
                    "PIN ${serverState.pin} — anyone opening the address must type it"
                else "The address opens without a code",
                onClick = { onSetPinRequired(!serverState.isPinRequired) },
                trailing = {
                    RowSwitch(
                        checked = serverState.isPinRequired,
                        label = "Require PIN in browsers",
                        onCheckedChange = onSetPinRequired
                    )
                },
                modifier = Modifier.testTag("settings_pin_required")
            )
            PulseRowDivider(insetStart = 54)
            PulseRow(
                title = "New PIN",
                icon = Icons.Default.Refresh,
                subtitle = "Replaces the current browser code",
                onClick = onRegeneratePin,
                modifier = Modifier.testTag("settings_regenerate_pin")
            )
            PulseRowDivider(insetStart = 54)
            PulseRow(
                title = "Paired devices",
                icon = Icons.Default.Security,
                subtitle = if (pairedCount == 0)
                    "None yet — pair from the Radar tab"
                else "$pairedCount trusted ${if (pairedCount == 1) "phone" else "phones"}",
                onClick = onOpenDevices,
                modifier = Modifier.testTag("settings_open_devices")
            )
        }

        // ── Server ──────────────────────────────────────────────────────────
        PulseRowLabel("Server")
        PulseRowGroup {
            PulseRow(
                title = "Open the server card",
                icon = Icons.Default.FolderOpen,
                subtitle = "${serverState.ipAddress}:${serverState.port} · ${
                    if (serverState.isRunning) "serving now" else "not serving"
                }",
                onClick = onOpenServer,
                modifier = Modifier.testTag("settings_open_server")
            )
        }

        // ── Updates ─────────────────────────────────────────────────────────
        PulseRowLabel("Updates")
        PulseRowGroup {
            PulseRow(
                title = "PulseDrop v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                icon = Icons.Default.Info,
                subtitle = updateStatus,
                showChevron = false
            )
            if (updateInfo != null) {
                PulseRowDivider(insetStart = 54)
                PulseRow(
                    title = "v${updateInfo.versionName} is available",
                    icon = Icons.Default.TrendingUp,
                    titleColor = c.accent,
                    showChevron = false
                )
            }
            PulseRowDivider(insetStart = 54)
            PulseRow(
                title = "Check for updates",
                icon = Icons.Default.Refresh,
                onClick = onCheckUpdate,
                showChevron = false,
                modifier = Modifier.testTag("settings_check_update")
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}
