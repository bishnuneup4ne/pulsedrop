package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.UpdateInfo

/**
 * The updater, shown as honestly as it can be shown: one GitHub source, one real download with
 * byte-true progress, one size check, and an install that only Android's own installer can
 * finish. Anything this app cannot do — channels, checksums of its own, rollback, silent
 * installs — is named as missing rather than dressed up as a control.
 */

internal fun updateCenterStatus(
    checking: Boolean,
    progress: Float?,
    apkReady: Boolean,
    info: UpdateInfo?,
    statusMessage: String?,
    lastAnswered: Boolean
): Pair<String, PulseStatusTone> = when {
    progress != null && !apkReady ->
        "Downloading v${info?.versionName ?: "?"} — ${(progress * 100).toInt()}%" to PulseStatusTone.Active
    apkReady -> "Downloaded and ready — Android's installer takes it from here" to PulseStatusTone.Positive
    checking -> "Asking GitHub…" to PulseStatusTone.Active
    info != null -> "Update available: v${info.versionName}" to PulseStatusTone.Warning
    statusMessage != null -> statusMessage to PulseStatusTone.Neutral
    // A check that answered "nothing to do" is an answer; "no check has answered" would be false.
    lastAnswered ->
        "GitHub's last answer: no update pending for this device" to PulseStatusTone.Positive
    else -> "No check has answered yet" to PulseStatusTone.Neutral
}

/** Minutes since the last check that actually reached GitHub; never means "it never did". */
internal fun lastCheckLine(lastSuccessElapsed: Long, nowElapsed: Long): String {
    if (lastSuccessElapsed <= 0L) return "A check has not reached GitHub in this session."
    val mins = ((nowElapsed - lastSuccessElapsed) / 60_000L).coerceAtLeast(0L)
    return when {
        mins < 1L -> "Last check reached GitHub under a minute ago."
        mins < 60L -> "Last check reached GitHub $mins minute(s) ago."
        else -> "Last check reached GitHub ${mins / 60} hour(s) ago."
    }
}

internal fun sizeLine(sizeBytes: Long): String =
    if (sizeBytes > 0) "GitHub says the install file is %.1f MB.".format(sizeBytes / 1048576.0)
    else "GitHub did not report a size for the install file."

@Composable
fun UpdateCenterScreen(
    installedVersion: String,
    buildCode: Int,
    isDebugBuild: Boolean,
    info: UpdateInfo?,
    statusMessage: String?,
    progress: Float?,
    apkReady: Boolean,
    checking: Boolean,
    canInstallPermission: Boolean,
    snoozedVersion: String?,
    lastCheckLine: String,
    lastAnswered: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAskInstallPermission: () -> Unit,
    onSnooze: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val (statusText, statusTone) =
        updateCenterStatus(checking, progress, apkReady, info, statusMessage, lastAnswered)

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("updatecenter_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("updatecenter_header"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Update Center", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text(
                    "Every state below comes from what the updater actually did in this session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = PulseSpacing.xs.dp)
                )
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("updatecenter_version_card")) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Installed: v$installedVersion (build $buildCode)" +
                                if (isDebugBuild) " — debug build" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textPrimary
                    )
                    Text(
                        "The version above is this APK's own build record, not a guess.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary
                    )
                    Text(lastCheckLine, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Text(
                        "Checks also run by themselves on launch and when the network returns.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("updatecenter_status_card")) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PulseStatus(text = "Status", tone = statusTone, detail = statusText)
                    if (progress != null && !apkReady) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().testTag("updatecenter_progress")
                        )
                    }
                    PulseButton(
                        if (checking) "Checking…" else "Check for updates",
                        onClick = onCheck,
                        tone = PulseButtonTone.Primary,
                        enabled = !checking,
                        disabledReason = "A check is already running.",
                        fullWidth = true,
                        modifier = Modifier.testTag("updatecenter_check")
                    )
                    if (statusMessage != null) {
                        Text(
                            "Last thing the updater said: $statusMessage",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                }
            }
        }

        if (info != null) {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("updatecenter_target_card")) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "v${info.versionName} is waiting",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary
                        )
                        Text(
                            info.releaseName.ifBlank { "PulseDrop ${info.versionName}" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                        Text(
                            if (info.notes.isNotBlank()) info.notes
                            else "GitHub has no release notes for this one. Nothing was invented to fill it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textPrimary
                        )
                        Text(sizeLine(info.sizeBytes), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                        if (apkReady) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PulseButton(
                                    "Install v${info.versionName}",
                                    onClick = { if (canInstallPermission) onInstall() else onAskInstallPermission() },
                                    tone = PulseButtonTone.Primary,
                                    modifier = Modifier.testTag("updatecenter_install")
                                )
                                Spacer(Modifier.width(PulseSpacing.md.dp))
                                Text(
                                    if (canInstallPermission) "Opens Android's installer"
                                    else "Android needs permission first",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textTertiary
                                )
                            }
                        } else {
                            PulseButton(
                                if (progress != null) "Downloading…" else "Download v${info.versionName}",
                                onClick = onDownload,
                                tone = PulseButtonTone.Primary,
                                enabled = progress == null,
                                disabledReason = "A download is already running.",
                                modifier = Modifier.testTag("updatecenter_download")
                            )
                            if (!canInstallPermission) {
                                Text(
                                    "Installing needs the \"unknown apps\" permission for this app; " +
                                            "the Install button will ask for it when the file lands.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textTertiary
                                )
                            }
                        }
                        if (progress == null && !apkReady) {
                            PulseButton(
                                "Not now for this version",
                                onClick = onSnooze,
                                tone = PulseButtonTone.Quiet,
                                modifier = Modifier.testTag("updatecenter_snooze")
                            )
                        }
                    }
                }
            }
        }

        if (snoozedVersion != null && info == null) {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("updatecenter_snoozed_card")) {
                    Text(
                        "v$snoozedVersion is snoozed: automatic checks will stay quiet about it. " +
                                "A manual check shows it again on purpose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("updatecenter_trust_card")) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "What \"verified\" means here",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    Text(
                        "The file comes over HTTPS from this project's own GitHub releases, and the " +
                                "downloaded size is compared against the size GitHub declared — a " +
                                "short file is deleted, never installed. Beyond that, trust is " +
                                "Android's: its installer refuses an update whose signing key differs " +
                                "from this app's. There is no separate checksum or signature step in " +
                                "the app, and this page will not claim one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("updatecenter_limits_card")) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "What this updater cannot do",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    Text(
                        "• One channel only: whatever GitHub publishes as the latest release. " +
                                "There is no beta or nightly to opt into.\n" +
                                "• Nothing downloads or installs by itself — both need your tap.\n" +
                                "• No silent install: Android's own dialog always finishes the job.\n" +
                                "• No rollback: an installed update cannot be undone from inside " +
                                "the app.\n" +
                                "• A cancelled or interrupted download leaves no partial file to " +
                                "resume — the next download starts over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    PulseButton(
                        "Read every release note",
                        onClick = onOpenWhatsNew,
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("updatecenter_whatsnew")
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(PulseSpacing.xs.dp))
        }
    }
}
