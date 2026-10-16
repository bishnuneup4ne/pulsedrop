package com.pulsedrop.app.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.relay.RelayClient
import com.pulsedrop.app.server.WebClientInfo
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Pure mappings over observed state ─────────────────────────────────────

/** Uptime is real only while the server runs; startedAt is 0 otherwise, and 0 is not "epoch". */
internal fun networkUptimeLabel(startedAt: Long, now: Long): String {
    if (startedAt <= 0L) return "Not running"
    val seconds = ((now - startedAt) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "Up for ${seconds}s"
        seconds < 3600 -> "Up for ${seconds / 60} min"
        else -> "Up for ${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

/**
 * The only link check this app is allowed to make without location permission: is there a
 * Wi-Fi/Ethernet transport. Cellular answers "no" here too, so the label says exactly that.
 */
internal fun linkStatusLabel(wifiOrEthernet: Boolean): String =
    if (wifiOrEthernet) "Wi-Fi or Ethernet link up" else "No Wi-Fi or Ethernet link"

internal fun relayLinkLabel(host: RelayClient.Host?, lastSync: Long, now: Long): String = when {
    host == null -> "Not attached — this phone answers for itself"
    lastSync <= 0L -> "Attached to ${host.name} at ${host.ip}:${host.port} — no mailbox pull has finished yet"
    else -> "Attached to ${host.name} at ${host.ip}:${host.port} — ${browserAgeLabel(lastSync, now)}"
}

private fun peerRowLabel(peer: PeerDevice): String =
    if (peer.hasServer) "${peer.name} • ${peer.ip}:${peer.port}"
    else "${peer.name} • ${peer.ip} — client only, nothing to dial"

// ── Screen ────────────────────────────────────────────────────────────────

@Composable
fun NetworkScreen(
    serverRunning: Boolean,
    serverUrl: String,
    startedAt: Long,
    pinRequired: Boolean,
    autoAccept: Boolean,
    webClients: List<WebClientInfo>,
    nearbyPeers: List<PeerDevice>,
    scannedPeers: List<PeerDevice>,
    radarScanning: Boolean,
    relayHost: RelayClient.Host?,
    relayLastSync: Long,
    onToggleServer: () -> Unit,
    onRefresh: () -> Unit,
    onScanRadar: () -> Unit,
    onStopRadar: () -> Unit,
    onOpenHotspotSettings: () -> Unit
) {
    val c = LocalPulseColors.current
    val context = LocalContext.current

    // System reads (interfaces, connectivity, tether state) are binder calls: they run off the
    // main thread when the screen opens and when the user asks again, never per recomposition.
    // Ages and uptime recompute from the same reading, so "Read the network again" refreshes all
    // of them at once rather than pretending to stream.
    var probeTick by remember { mutableIntStateOf(0) }
    var linkOk by remember { mutableStateOf(false) }
    var localIp by remember { mutableStateOf("reading…") }
    var hotspotOn by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(probeTick) {
        withContext(Dispatchers.IO) {
            linkOk = NetworkUtils.isWifiOrHotspotConnected(context)
            localIp = NetworkUtils.getLocalIpAddress()
            hotspotOn = HotspotState.isActive(context)
        }
        nowMs = System.currentTimeMillis()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("network_list"),
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
                    .testTag("network_header")
            ) {
                Text(
                    "Network status",
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.textPrimary
                )
                Text(
                    "Everything below is read from this phone right now. No ping, no speed test, " +
                        "no Wi-Fi name — this app cannot measure those, so it does not show them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary
                )
                PulseButton(
                    label = "Read the network again",
                    onClick = {
                        onRefresh()
                        probeTick++
                    },
                    tone = PulseButtonTone.Neutral,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier
                        .padding(top = PulseSpacing.md.dp)
                        .testTag("network_refresh")
                )
            }
        }

        // ── This device ────────────────────────────────────────────────────
        item { PulseSectionHeader("This device") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_device_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseStatus(
                        text = linkStatusLabel(linkOk),
                        tone = if (linkOk) PulseStatusTone.Positive else PulseStatusTone.Negative,
                        detail = "Cellular counts as no link: this check only sees Wi-Fi and Ethernet.",
                        modifier = Modifier.testTag("network_link_status")
                    )
                    Text(
                        "Local IPv4 address: $localIp",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textPrimary,
                        modifier = Modifier.testTag("network_localip")
                    )
                    Text(
                        "Only the first address is read (Wi-Fi, then hotspot, then Ethernet). " +
                            "IPv6 and DNS servers are not shown because the app does not read them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "Wi-Fi name: not readable. Android only hands it to apps holding location " +
                            "permission, which this app never asks for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    PulseStatus(
                        text = if (hotspotOn) "Hotspot appears to be on" else "Hotspot appears to be off",
                        tone = if (hotspotOn) PulseStatusTone.Positive else PulseStatusTone.Neutral,
                        detail = "Best effort: some phones hide tether state from apps.",
                        modifier = Modifier.testTag("network_hotspot")
                    )
                    PulseButton(
                        label = "Hotspot settings",
                        onClick = onOpenHotspotSettings,
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("network_hotspot_settings")
                    )
                }
            }
        }

        // ── PulseDrop server ───────────────────────────────────────────────
        item { PulseSectionHeader("PulseDrop server") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_server_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseStatus(
                        text = if (serverRunning) "Running" else "Stopped",
                        tone = if (serverRunning) PulseStatusTone.Positive else PulseStatusTone.Neutral,
                        modifier = Modifier.testTag("network_server_status")
                    )
                    if (serverRunning) {
                        Text(
                            serverUrl,
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textPrimary,
                            modifier = Modifier.testTag("network_server_url")
                        )
                        Text(
                            networkUptimeLabel(startedAt, nowMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            modifier = Modifier.testTag("network_server_uptime")
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)
                        ) {
                            PulseChip(
                                label = if (pinRequired) "PIN required" else "No PIN",
                                selected = pinRequired
                            )
                            PulseChip(
                                label = if (autoAccept) "Auto-accept on" else "Ask first",
                                selected = autoAccept
                            )
                        }
                    } else {
                        Text(
                            "Nothing on this network can reach the phone until the server starts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
                        )
                    }
                    PulseButton(
                        label = if (serverRunning) "Stop server" else "Start server",
                        onClick = onToggleServer,
                        tone = if (serverRunning) PulseButtonTone.Danger else PulseButtonTone.Primary,
                        modifier = Modifier.testTag("network_server_toggle")
                    )
                }
            }
        }

        // ── Browser clients ────────────────────────────────────────────────
        item { PulseSectionHeader("Browsers pointed at this phone") }

        if (webClients.isEmpty()) {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_browsers_empty")) {
                    Text(
                        if (serverRunning) {
                            "No browser has requested a page in the last 90 seconds. This is a " +
                                "last-seen list, not live connections — it cannot say who is " +
                                "connected right now, only who asked recently."
                        } else {
                            "The server is stopped, so no browser can be pointed at it."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                }
            }
        } else {
            items(webClients, key = { "browser-${it.ip}" }) { client ->
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_browser_${client.ip}")) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            client.name.ifBlank { "Browser at ${client.ip}" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textPrimary
                        )
                        Text(
                            "${client.ip} • ${browserAgeLabel(client.lastSeen, nowMs)}" +
                                if (client.local) " • this device (via adb)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
                        )
                    }
                }
            }
        }

        // ── Phones announcing themselves ───────────────────────────────────
        item { PulseSectionHeader("PulseDrop phones on this network") }

        if (nearbyPeers.isEmpty()) {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_peers_empty")) {
                    Text(
                        "No phone has announced itself in the last 25 seconds. Discovery listens " +
                            "for LAN broadcasts only — phones on another network, or with the app " +
                            "closed, are invisible to it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                }
            }
        } else {
            items(nearbyPeers, key = { "beacon-${it.id}" }) { peer ->
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_peer_${peer.id}")) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            peerRowLabel(peer),
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textPrimary
                        )
                        Text(
                            if (peer.requiresPin) "Wants a PIN when you connect" else "Open access",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
                        )
                    }
                }
            }
        }

        // ── Radar ──────────────────────────────────────────────────────────
        item { PulseSectionHeader("Radar (address sweep)") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_radar_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseStatus(
                        text = when {
                            radarScanning -> "Sweeping this /24…"
                            scannedPeers.isNotEmpty() -> "${scannedPeers.size} server${if (scannedPeers.size == 1) "" else "s"} found"
                            else -> "Idle — no sweep results to show"
                        },
                        tone = if (radarScanning) PulseStatusTone.Active else PulseStatusTone.Neutral,
                        modifier = Modifier.testTag("network_radar_status")
                    )
                    Text(
                        "The sweep tries port 8080 on every address of this /24, IPv4 only. " +
                            "A failed sweep and an empty network look identical here — the " +
                            "scanner cannot tell them apart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    PulseButton(
                        label = if (radarScanning) "Stop sweep" else "Sweep this /24",
                        onClick = { if (radarScanning) onStopRadar() else onScanRadar() },
                        tone = PulseButtonTone.Neutral,
                        modifier = Modifier.testTag("network_radar_scan")
                    )
                }
            }
        }

        // ── Relay host ─────────────────────────────────────────────────────
        item { PulseSectionHeader("Mailbox through a paired host") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_relay_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    Text(
                        relayLinkLabel(relayHost, relayLastSync, nowMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textPrimary,
                        modifier = Modifier.testTag("network_relay_status")
                    )
                    Text(
                        "This is not an internet tunnel: the host is another PulseDrop phone's " +
                            "server on the same network, carrying messages for phones that are " +
                            "not serving themselves.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        // ── Honest limits ──────────────────────────────────────────────────
        item { PulseSectionHeader("What this screen cannot tell you") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("network_disclosure")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    Text(
                        "• Speed: only measurable while a file is actually moving; an idle " +
                            "network has no speed to display.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "• Ping, latency and signal strength: the app never measures these, so " +
                            "no number here is ever a substitute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "• Who is attached to your router or hotspot: Android does not expose " +
                            "that to apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "• Whether the internet is reachable: the app knows only that its own " +
                            "update check succeeded or failed, which is not shown here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }
    }
}
