package com.pulsedrop.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.server.WebClientInfo
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseDivider
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.components.PulseSwitch
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Pure mappings (unit-testable, no fabricated states) ────────────────────

/** The three runtime permissions PulseDrop actually asks the OS for. */
internal data class PermissionFact(
    val key: String,
    val label: String,
    val why: String,
    val requiredFor: String
)

internal val SECURITY_PERMISSIONS = listOf(
    PermissionFact(
        Manifest.permission.RECORD_AUDIO,
        "Microphone",
        "Voice in calls between phones and browsers.",
        "Calls"
    ),
    PermissionFact(
        Manifest.permission.CAMERA,
        "Camera",
        "Video in calls and QR scanning of a peer address.",
        "Video calls, QR pairing"
    ),
    PermissionFact(
        Manifest.permission.POST_NOTIFICATIONS,
        "Notifications",
        "Alerts for incoming calls, transfers and the running server.",
        "Call ringing, server notice"
    )
)

internal fun permissionGranted(context: Context, permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Security advice, only ever generated from a condition the app is actually in.
 *
 * Every entry names the triggering state and what the action changes. There is no permanent
 * checklist and nothing is flagged as risk that the code does not treat as a trade-off.
 */
internal data class SecurityAdvice(
    val id: String,
    val title: String,
    val because: String,
    val effect: String,
    val reversible: String
)

internal fun securityRecommendations(
    serverRunning: Boolean,
    pinRequired: Boolean,
    autoAccept: Boolean,
    unverifiedPairs: Int,
    micGranted: Boolean
): List<SecurityAdvice> {
    val out = mutableListOf<SecurityAdvice>()
    if (serverRunning && !pinRequired) {
        out += SecurityAdvice(
            id = "pin",
            title = "Require a PIN in browsers",
            because = "The server is running and PIN entry is off, so any device on this network " +
                "can open the page and send or fetch files.",
            effect = "Browser tabs must type the PIN before they see anything but the login box.",
            reversible = "Turn it back off at any time."
        )
    }
    if (autoAccept) {
        out += SecurityAdvice(
            id = "autoaccept",
            title = "Ask before files land",
            because = "Auto-accept is on: uploads from the browser page are written straight into " +
                "the received folder without a prompt.",
            effect = "Uploads wait in a pending list until you accept or decline each one.",
            reversible = "Turn auto-accept back on at any time."
        )
    }
    if (unverifiedPairs > 0) {
        out += SecurityAdvice(
            id = "verify",
            title = "Verify $unverifiedPairs pairing${if (unverifiedPairs == 1) "" else "s"}",
            because = "A pairing is only as trusted as the 6-digit safety code read on both " +
                "devices. These pairings were never confirmed.",
            effect = "Marking codes as matched records the pairing as verified on this phone.",
            reversible = "The mark can be cleared again."
        )
    }
    if (!micGranted) {
        out += SecurityAdvice(
            id = "mic",
            title = "Microphone is not granted",
            because = "Calls start silently: the OS has not granted RECORD_AUDIO.",
            effect = "Granting it in system settings lets calls carry voice.",
            reversible = "Revoke it in system settings."
        )
    }
    return out
}

/**
 * What is encrypted, stated exactly as the transport code implements it.
 *
 * These lines are not status readouts — the properties do not change at runtime. They are the
 * implementation's guarantees, with the distinction between transport encryption and
 * end-to-end encryption kept intact.
 */
internal data class EncryptionFact(
    val key: String,
    val subject: String,
    val state: String,
    val positive: Boolean,
    val note: String
)

internal fun encryptionFacts(): List<EncryptionFact> = listOf(
    EncryptionFact(
        key = "pair_chat",
        subject = "Chat and files with paired devices",
        state = "End-to-end encrypted",
        positive = true,
        note = "AES-256-GCM under a key only the two phones hold. Identity is only as strong as " +
            "the safety code you confirmed on both screens."
    ),
    EncryptionFact(
        key = "browser_tls",
        subject = "The web page this phone serves",
        state = "Encrypted in transit, unverified certificate",
        positive = true,
        note = "A self-signed certificate generated when the server starts. The browser warning " +
            "you accept means nothing verified it, and there is no certificate pinning."
    ),
    EncryptionFact(
        key = "browser_data",
        subject = "Browser chat, uploads and web calls",
        state = "Not end-to-end encrypted",
        positive = false,
        note = "They stop being encrypted at this phone — it holds the plaintext by design."
    ),
    EncryptionFact(
        key = "call_media",
        subject = "Voice and video between two PulseDrop phones",
        state = "Not encrypted",
        positive = false,
        note = "Call media rides a raw LAN socket. Anyone on this network can read those frames."
    ),
    EncryptionFact(
        key = "discovery",
        subject = "Discovery and the pairing handshake",
        state = "Cleartext on the LAN",
        positive = false,
        note = "Broadcasts and pairing requests carry names and public keys in the open — " +
            "public keys are meant for that; the safety code is how you check them."
    )
)

/**
 * A browser entry is only a last-seen record, never a session the app controls.
 * The wording here keeps that line: entries expire, they are not signed in.
 */
internal fun browserAgeLabel(lastSeen: Long, now: Long): String {
    val seconds = ((now - lastSeen) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "seen ${seconds}s ago"
        else -> "seen ${seconds / 60}m ago"
    }
}

private fun shortPid(pid: String): String =
    if (pid.length <= 10) pid else pid.take(8) + "…"

private fun dayStamp(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault()).format(Date(millis))

// ── Screen ────────────────────────────────────────────────────────────────

@Composable
fun SecurityScreen(
    serverRunning: Boolean,
    pinRequired: Boolean,
    autoAccept: Boolean,
    pairedDevices: List<PairedDevice>,
    webClients: List<WebClientInfo>,
    onSetPinRequired: (Boolean) -> Unit,
    onRegeneratePin: () -> Unit,
    onSetAutoAccept: (Boolean) -> Unit,
    onUnpair: (String) -> Unit,
    safetyCode: (String) -> String?,
    onOpenDevices: () -> Unit
) {
    val c = LocalPulseColors.current
    val context = LocalContext.current

    // Permission state is re-read when the user asks, never assumed. The tick is the only thing
    // that recomputes the snapshot, and the timestamp records that a check actually ran.
    var permTick by remember { mutableIntStateOf(0) }
    var lastCheckedAt by remember { mutableLongStateOf(0L) }
    val granted = remember(permTick, context) {
        SECURITY_PERMISSIONS.associate { it.key to permissionGranted(context, it.key) }
    }
    val micGranted = granted[Manifest.permission.RECORD_AUDIO] == true

    var pendingUnpair by remember { mutableStateOf<PairedDevice?>(null) }
    var pendingPinReset by remember { mutableStateOf(false) }
    var codeDevice by remember { mutableStateOf<PairedDevice?>(null) }

    val unverified = pairedDevices.count { !it.verified }
    val advice = securityRecommendations(
        serverRunning = serverRunning,
        pinRequired = pinRequired,
        autoAccept = autoAccept,
        unverifiedPairs = unverified,
        micGranted = micGranted
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("security_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.lg.dp,
            end = PulseSpacing.lg.dp,
            bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = PulseSpacing.lg.dp)
                    .testTag("security_header")
            ) {
                Text(
                    "Security center",
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.textPrimary
                )
                Text(
                    "What PulseDrop actually protects, what it cannot, and what you can change — " +
                        "read from this installation's real state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulseButton(
                    label = "Check permissions again",
                    onClick = {
                        permTick++
                        lastCheckedAt = System.currentTimeMillis()
                    },
                    tone = PulseButtonTone.Neutral,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.testTag("security_check")
                )
                if (lastCheckedAt > 0L) {
                    Text(
                        "Checked ${dayStamp(lastCheckedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                        modifier = Modifier
                            .padding(start = PulseSpacing.md.dp)
                            .testTag("security_lastchecked")
                    )
                }
            }
        }

        // ── Overview: four cards, each wired to a live value ───────────────
        item { PulseSectionHeader("Overview") }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
            ) {
                PulseCard(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                        Text(
                            "Device trust",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.textSecondary
                        )
                        PulseStatus(
                            text = when {
                                pairedDevices.isEmpty() -> "No pairings"
                                unverified > 0 -> "$unverified unverified"
                                else -> "All verified"
                            },
                            tone = when {
                                pairedDevices.isEmpty() -> PulseStatusTone.Neutral
                                unverified > 0 -> PulseStatusTone.Warning
                                else -> PulseStatusTone.Positive
                            },
                            detail = "${pairedDevices.size} paired on this phone"
                        )
                    }
                }
                PulseCard(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                        Text(
                            "Browser access",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.textSecondary
                        )
                        PulseStatus(
                            text = when {
                                !serverRunning -> "Server off"
                                pinRequired -> "PIN required"
                                else -> "Open to the network"
                            },
                            tone = when {
                                !serverRunning -> PulseStatusTone.Neutral
                                pinRequired -> PulseStatusTone.Positive
                                else -> PulseStatusTone.Warning
                            },
                            detail = if (serverRunning) "${webClients.size} browser(s) seen recently"
                            else "Nothing is reachable until you serve"
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
            ) {
                PulseCard(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                        Text(
                            "Incoming files",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.textSecondary
                        )
                        PulseStatus(
                            text = if (autoAccept) "Auto-accept is on" else "Asks first",
                            tone = if (autoAccept) PulseStatusTone.Warning else PulseStatusTone.Positive,
                            detail = if (autoAccept) "Uploads land without a prompt"
                            else "Uploads wait until you accept"
                        )
                    }
                }
                PulseCard(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                        Text(
                            "Permissions",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.textSecondary
                        )
                        val n = granted.values.count { it }
                        PulseStatus(
                            text = "$n of ${SECURITY_PERMISSIONS.size} granted",
                            tone = if (n == SECURITY_PERMISSIONS.size) PulseStatusTone.Positive
                            else PulseStatusTone.Warning,
                            detail = if (lastCheckedAt > 0L) "From the OS, this check"
                            else "Tap check to read the OS"
                        )
                    }
                }
            }
        }

        // ── Recommended actions: only conditions that are really true ──────
        if (advice.isNotEmpty()) {
            item {
                PulseSectionHeader(
                    "Worth changing",
                    "Each one is triggered by a state this phone is in right now."
                )
            }
            items(advice, key = { "rec_${it.id}" }) { a ->
                PulseCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("security_rec_${a.id}")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                        Text(a.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(a.because, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        Text(
                            "What it changes: ${a.effect} ${a.reversible}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                            when (a.id) {
                                "pin" -> PulseButton(
                                    label = "Require a PIN",
                                    onClick = { onSetPinRequired(true) },
                                    tone = PulseButtonTone.Neutral,
                                    modifier = Modifier.testTag("security_rec_action_pin")
                                )
                                "autoaccept" -> PulseButton(
                                    label = "Ask before files land",
                                    onClick = { onSetAutoAccept(false) },
                                    tone = PulseButtonTone.Neutral,
                                    modifier = Modifier.testTag("security_rec_action_autoaccept")
                                )
                                "verify" -> PulseButton(
                                    label = "Open devices",
                                    onClick = onOpenDevices,
                                    tone = PulseButtonTone.Neutral,
                                    modifier = Modifier.testTag("security_rec_action_verify")
                                )
                                "mic" -> PulseButton(
                                    label = "Open app settings",
                                    onClick = { openAppSettings(context) },
                                    tone = PulseButtonTone.Neutral,
                                    modifier = Modifier.testTag("security_rec_action_mic")
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Browser access controls ────────────────────────────────────────
        item { PulseSectionHeader("Browser access") }

        item {
            PulseCard(Modifier.fillMaxWidth()) {
                Column {
                    PulseSwitch(
                        checked = pinRequired,
                        onCheckedChange = onSetPinRequired,
                        label = "Require a PIN in browsers",
                        detail = "Anyone opening the address must type it first. Pairing, call and " +
                            "relay endpoints do not use this PIN — they are gated by keys instead.",
                        modifier = Modifier.testTag("security_pin_toggle")
                    )
                    PulseDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "The PIN itself is never shown on this page. Regenerating it " +
                                "invalidates every browser tab that is open right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        PulseButton(
                            label = "New PIN",
                            onClick = { pendingPinReset = true },
                            tone = PulseButtonTone.Neutral,
                            modifier = Modifier.testTag("security_pin_regen")
                        )
                    }
                }
            }
        }

        // ── Incoming files control ─────────────────────────────────────────
        item {
            PulseCard(Modifier.fillMaxWidth()) {
                PulseSwitch(
                    checked = autoAccept,
                    onCheckedChange = onSetAutoAccept,
                    label = "Accept browser files without asking",
                    detail = "Off: uploads wait in a pending list; declining deletes the file. " +
                        "On: they are written straight to the received folder. This gates " +
                        "browser uploads only.",
                    modifier = Modifier.testTag("security_autoaccept_toggle")
                )
            }
        }

        // ── Trusted devices ────────────────────────────────────────────────
        item {
            PulseSectionHeader(
                "Trusted devices",
                "Read from this phone's pairing store — not a directory of everyone nearby",
                action = {
                    TextButton(
                        onClick = onOpenDevices,
                        modifier = Modifier.testTag("security_open_devices")
                    ) {
                        Text("Manage on Devices")
                    }
                }
            )
        }
        if (pairedDevices.isEmpty()) {
            item {
                PulseCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("security_devices_empty")
                ) {
                    Text(
                        "No devices are paired on this phone. Pairing is what enables encrypted " +
                            "chat and encrypted transfers — nothing here is waiting to be trusted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        } else {
            items(pairedDevices, key = { "pair_${it.pid}" }) { d ->
                PulseCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("security_row_${d.pid}")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                d.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = c.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            PulseStatus(
                                text = if (d.verified) "Code confirmed" else "Code not checked",
                                tone = if (d.verified) PulseStatusTone.Positive
                                else PulseStatusTone.Warning
                            )
                        }
                        Text(
                            "Paired ${dayStamp(d.createdAt)} • id ${shortPid(d.pid)}" +
                                if (d.appVersion.isNotBlank()) " • app ${d.appVersion}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                            PulseButton(
                                label = "Safety code",
                                onClick = { codeDevice = d },
                                tone = PulseButtonTone.Quiet,
                                modifier = Modifier.testTag("security_code_${d.pid}")
                            )
                            PulseButton(
                                label = "Unpair",
                                onClick = { pendingUnpair = d },
                                tone = PulseButtonTone.Danger,
                                modifier = Modifier.testTag("security_unpair_${d.pid}")
                            )
                        }
                    }
                }
            }
        }

        // ── Browsers seen by the server ────────────────────────────────────
        item { PulseSectionHeader("Browsers on this network") }
        if (!serverRunning) {
            item {
                PulseCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("security_browsers_stopped")
                ) {
                    Text(
                        "The server is stopped, so no browser can reach this phone and nothing " +
                            "is listed below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        } else if (webClients.isEmpty()) {
            item {
                PulseCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("security_browsers_empty")
                ) {
                    Text(
                        "No browser has requested a page since the server started, as far as the " +
                            "last-90-seconds window can tell.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        } else {
            val now = System.currentTimeMillis()
            items(webClients, key = { "web_${it.ip}" }) { w ->
                PulseCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("security_browser_${w.ip}")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                w.name.ifBlank { "Unnamed browser" },
                                style = MaterialTheme.typography.titleSmall,
                                color = c.textPrimary
                            )
                            Text(
                                "${w.ip} • ${browserAgeLabel(w.lastSeen, now)}" +
                                    if (w.local) " • this device" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textTertiary
                            )
                        }
                        PulseStatus(text = "Last seen", tone = PulseStatusTone.Neutral)
                    }
                }
            }
            item {
                PulseCard(
                    Modifier
                        .fillMaxWidth()
                        .testTag("security_browsers_note")
                ) {
                    Text(
                        "These are last-seen records, not sessions. PulseDrop cannot disconnect a " +
                            "browser: an entry leaves this list 90 seconds after its last " +
                            "request, and a browser that knows the PIN can simply come back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        // ── What is encrypted ──────────────────────────────────────────────
        item {
            PulseSectionHeader(
                "What is encrypted",
                "Properties of the transports as this build implements them — not live " +
                    "measurements"
            )
        }
        items(encryptionFacts(), key = { "e2e_${it.key}" }) { f ->
            PulseCard(
                Modifier
                    .fillMaxWidth()
                    .testTag("security_e2e_${f.key}")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    Text(f.subject, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    PulseStatus(
                        text = f.state,
                        tone = if (f.positive) PulseStatusTone.Positive else PulseStatusTone.Warning
                    )
                    Text(f.note, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
        }

        // ── Permissions ───────────────────────────────────────────────────
        item {
            PulseSectionHeader(
                "App permissions",
                "Read from the OS when you tap check — PulseDrop cannot grant these itself",
                action = {
                    TextButton(
                        onClick = { openAppSettings(context) },
                        modifier = Modifier.testTag("security_perm_settings")
                    ) {
                        Text("Open settings")
                    }
                }
            )
        }
        items(SECURITY_PERMISSIONS, key = { "perm_${it.key}" }) { p ->
            val on = granted[p.key] == true
            PulseCard(
                Modifier
                    .fillMaxWidth()
                    .testTag("security_perm_${p.key}")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(p.why, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        Text(
                            "Needed for: ${p.requiredFor}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                    PulseStatus(
                        text = if (on) "Granted" else "Not granted",
                        tone = if (on) PulseStatusTone.Positive else PulseStatusTone.Warning
                    )
                }
            }
        }
        item {
            PulseCard(Modifier.fillMaxWidth()) {
                Text(
                    "Storage needs no permission here: files are picked and saved through the " +
                        "system picker. Network access is granted at install and cannot be " +
                        "switched off per app on this Android version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary
                )
            }
        }

        // ── Update integrity ───────────────────────────────────────────────
        item { PulseSectionHeader("Update integrity") }
        item {
            PulseCard(
                Modifier
                    .fillMaxWidth()
                    .testTag("security_update")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    PulseStatus(
                        text = "Checked by Android, not by the app",
                        tone = PulseStatusTone.Warning
                    )
                    Text(
                        "Updates download over HTTPS from this project's GitHub Releases. The " +
                            "app verifies only the downloaded file's size — there is no " +
                            "checksum or publisher-signature check in app code. The operating " +
                            "system's own signature rule at install time is what stops a " +
                            "mismatched APK.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        // ── What this page cannot promise ──────────────────────────────────
        item {
            PulseCard(
                Modifier
                    .fillMaxWidth()
                    .testTag("security_disclosure")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    Text(
                        "What PulseDrop does not do",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    Text(
                        "No malware scanning, no firewall, no intrusion detection, no remote " +
                            "logout, no way to disconnect a device that is already connected. " +
                            "Failed PIN guesses and declined uploads are counted in memory only " +
                            "— they leave no record after the app exits, so there is no security " +
                            "audit history on this page. Nothing here is a security score: a " +
                            "list of real states beats one reassuring number.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────
    pendingUnpair?.let { d ->
        AlertDialog(
            onDismissRequest = { pendingUnpair = null },
            title = { Text("Unpair ${d.name}?") },
            text = {
                Text(
                    "This removes this phone's copy of the pairing. Encrypted chat, calls and " +
                        "encrypted transfers with it stop working immediately. " +
                        "The other device is not notified — it keeps its copy and can send a " +
                        "new pairing request, which you would then be asked to accept. " +
                        "Browser access, if any, is not affected. " +
                        "This changes only this installation, and there is no undo: restoring " +
                        "trust means pairing again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUnpair?.let { onUnpair(it.pid) }
                        pendingUnpair = null
                    },
                    modifier = Modifier.testTag("security_unpair_confirm")
                ) { Text("Unpair", color = LocalPulseColors.current.danger) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingUnpair = null },
                    modifier = Modifier.testTag("security_unpair_cancel")
                ) { Text("Keep it") }
            },
            modifier = Modifier.testTag("security_unpair_dialog")
        )
    }

    if (pendingPinReset) AlertDialog(
        onDismissRequest = { pendingPinReset = false },
        title = { Text("Generate a new browser PIN?") },
        text = {
            Text(
                "The old PIN stops working at once. Every browser tab already open will be " +
                    "locked out until someone types the new one. The new PIN is shown on the " +
                    "Home server card and in Settings, never here. This affects only this " +
                    "phone's server.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRegeneratePin()
                    pendingPinReset = false
                },
                modifier = Modifier.testTag("security_pin_regen_confirm")
            ) { Text("Generate") }
        },
        dismissButton = {
            TextButton(
                onClick = { pendingPinReset = false },
                modifier = Modifier.testTag("security_pin_regen_cancel")
            ) { Text("Cancel") }
        },
        modifier = Modifier.testTag("security_pin_regen_dialog")
    )

    codeDevice?.let { d ->
        val code = safetyCode(d.pid)
        AlertDialog(
            onDismissRequest = { codeDevice = null },
            title = { Text("Safety code — ${d.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    Text(
                        code ?: "No code is available for this pairing — the peer's key is " +
                            "missing from this phone's store.",
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.textPrimary,
                        modifier = Modifier
                            .semantics { contentDescription = "Safety code for ${d.name}" }
                            .testTag("security_code_value")
                    )
                    Text(
                        "Open the same screen on the other device and compare. Only when the " +
                            "two numbers match has anyone confirmed the identity behind this " +
                            "pairing; marking it here records your confirmation, it does not " +
                            "verify anything by itself. Verification is done on the Devices " +
                            "page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        codeDevice = null
                        onOpenDevices()
                    },
                    modifier = Modifier.testTag("security_code_go_devices")
                ) { Text("Verify on Devices") }
            },
            dismissButton = {
                TextButton(onClick = { codeDevice = null }) { Text("Close") }
            },
            modifier = Modifier.testTag("security_code_dialog")
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
