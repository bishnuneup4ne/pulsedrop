package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.relay.RelayClient
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What this app can honestly diagnose. There is no persisted application log — this page says so
 * rather than inventing one — and every number here comes from a live flow, a real file on disk,
 * or the BuildConfig the APK was compiled with.
 */

internal data class CrashReportInfo(
    val occurredAt: Long,
    val path: String,
    val sizeBytes: Long
)

/** Reads the real crash folder. Report names are the wall-clock millis of the crash. */
internal fun crashReportsFrom(dir: File): List<CrashReportInfo> =
    dir.listFiles()
        ?.filter { it.name.endsWith(".txt") && it.name.dropLast(4).toLongOrNull() != null }
        ?.map {
            CrashReportInfo(
                occurredAt = it.name.dropLast(4).toLong(),
                path = it.absolutePath,
                sizeBytes = it.length()
            )
        }
        ?.sortedByDescending { it.occurredAt }
        ?: emptyList()

internal fun sessionAgeLabel(lastSuccessElapsed: Long, nowElapsed: Long): String = when {
    lastSuccessAtNone(lastSuccessElapsed) -> "Nothing has completed in this session yet."
    else -> "Last done ${((nowElapsed - lastSuccessElapsed) / 1000L).coerceAtLeast(0L)}s into this session."
}

private fun lastSuccessAtNone(v: Long) = v <= 0L

internal fun liveSpeedLabel(bytesPerSec: Long): String =
    if (bytesPerSec <= 0L) "Idle — no transfer is moving bytes right now."
    else NetworkUtils.formatSpeed(bytesPerSec)

@Composable
fun DiagnosticsScreen(
    crashDir: File,
    serverStartedAt: Long,
    serverRunning: Boolean,
    activeTransferCount: Int,
    liveSpeedBytesPerSec: Long,
    historyCompleted: Int,
    historyFailed: Int,
    updateStatusMessage: String?,
    updateLastSuccessElapsed: Long,
    relayHost: RelayClient.Host?,
    relayLastSync: Long,
    nowElapsedRealtime: () -> Long,
    onOpenReport: (String, ((Boolean, String?) -> Unit)?) -> Unit,
    onOpenNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val timeFormatter = remember { SimpleDateFormat("MMM d • HH:mm:ss", Locale.getDefault()) }

    var reports by remember { mutableStateOf<List<CrashReportInfo>>(emptyList()) }
    var readTick by remember { mutableStateOf(0) }
    var nowElapsed by remember { mutableLongStateOf(nowElapsedRealtime()) }

    LaunchedEffect(readTick, crashDir) {
        reports = crashReportsFrom(crashDir)
        nowElapsed = nowElapsedRealtime()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("diag_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item {
            Column {
                Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text(
                    "Only what this app can actually observe. There is no hidden log behind this page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 4.dp).testTag("diag_header")
                )
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("diag_runtime_card")) {
                DiagColumn {
                    DiagLabelledLine("Build", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                            if (BuildConfig.DEBUG) " — debug build" else "")
                    DiagLabelledLine("Package", BuildConfig.APPLICATION_ID)
                    DiagLabelledLine("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    DiagLabelledLine("Device", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
                    DiagLabelledLine("Server", networkUptimeLabel(serverStartedAt, System.currentTimeMillis()) +
                            if (serverRunning) "" else " — the server is stopped")
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("diag_transfer_card")) {
                DiagColumn {
                    PulseStatus(
                        text = "Transfer engine",
                        tone = if (activeTransferCount > 0) PulseStatusTone.Active else PulseStatusTone.Neutral,
                        detail = "$activeTransferCount ${if (activeTransferCount == 1) "transfer" else "transfers"} running • " +
                                liveSpeedLabel(liveSpeedBytesPerSec)
                    )
                    Text(
                        "History holds $historyCompleted completed and $historyFailed failed records. " +
                                "Speed is measured every 300 ms while a transfer runs — it is not averaged after the fact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("diag_update_card")) {
                DiagColumn {
                    PulseStatus(
                        text = "Updater",
                        tone = if (updateStatusMessage != null) PulseStatusTone.Warning else PulseStatusTone.Neutral,
                        detail = updateStatusMessage ?: "Quiet — no check or download is in flight."
                    )
                    Text(
                        sessionAgeLabel(updateLastSuccessElapsed, nowElapsed) +
                                " This clock is in-memory only: a reboot erases it and this page cannot see further back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("diag_relay_card")) {
                DiagColumn {
                    PulseStatus(
                        text = "Relay mailbox",
                        tone = if (relayHost != null) PulseStatusTone.Positive else PulseStatusTone.Neutral,
                        detail = relayLinkLabel(relayHost, relayLastSync, System.currentTimeMillis())
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        PulseButton("Network status", onClick = onOpenNetwork,
                            tone = PulseButtonTone.Quiet, modifier = Modifier.testTag("diag_open_network"))
                        PulseButton("Re-read this page", onClick = { readTick++ },
                            tone = PulseButtonTone.Quiet, modifier = Modifier.testTag("diag_reread"))
                    }
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("diag_crash_card")) {
                DiagColumn {
                    PulseStatus(
                        text = "Crash reports",
                        tone = if (reports.isEmpty()) PulseStatusTone.Positive else PulseStatusTone.Warning,
                        detail = if (reports.isEmpty()) "None kept on this device."
                        else "${reports.size} kept • newest ${timeFormatter.format(Date(reports.first().occurredAt))}"
                    )
                    Text(
                        "Reports stay in the app's private folder and nothing leaves the phone on its own. " +
                                "The oldest are dropped past twenty. Opening one shows the stack before it was truncated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        items(reports, key = { "crash_${it.occurredAt}" }) { report ->
            PulseCard(
                onClick = {
                    onOpenReport(report.path) { ok, msg -> if (!ok && msg != null) com.pulsedrop.app.ui.PulseEvents.post(msg) }
                },
                modifier = Modifier.fillMaxWidth().testTag("diag_crash_${report.occurredAt}")
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            timeFormatter.format(Date(report.occurredAt)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textPrimary
                        )
                        Text(
                            NetworkUtils.formatBytes(report.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                    Text("Open", style = MaterialTheme.typography.labelMedium, color = c.accent)
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("diag_nolog_card")) {
                DiagColumn {
                    Text("There is no application log", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text(
                        "This app never writes a log file, and modern Android does not let one app read another's " +
                                "system log either. Any page that showed “recent log lines” would be showing fiction, " +
                                "so this page shows none.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("diag_disclosure")) {
                DiagColumn {
                    Text("What this page cannot see", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    DiagDisclosureLine("Battery level, thermals and ANR data — the app holds no API for them.")
                    DiagDisclosureLine("Network quality history — speeds exist only while a transfer runs; nothing is sampled when idle.")
                    DiagDisclosureLine("Crashes that happened before the app's handler installed, or on a previous install of the app.")
                    DiagDisclosureLine("Anything deleted by a reinstall — the crash folder lives in app-private storage.")
                }
            }
        }
    }
}

@Composable
private fun DiagColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun DiagLabelledLine(label: String, value: String) {
    val c = LocalPulseColors.current
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.width(84.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = c.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DiagDisclosureLine(text: String) {
    val c = LocalPulseColors.current
    Row(Modifier.fillMaxWidth()) {
        Text("• ", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}
