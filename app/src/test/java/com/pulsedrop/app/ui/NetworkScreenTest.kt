package com.pulsedrop.app.ui

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.relay.RelayClient
import com.pulsedrop.app.server.WebClientInfo
import com.pulsedrop.app.ui.screens.NetworkScreen
import com.pulsedrop.app.ui.screens.linkStatusLabel
import com.pulsedrop.app.ui.screens.networkUptimeLabel
import com.pulsedrop.app.ui.screens.relayLinkLabel
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Network page is only worth opening if it never invents a measurement. These tests pin the
 * three ways it could: ages and uptimes computed from nothing, states that claim more than the
 * signal can carry, and buttons wired to the wrong real action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var toggles = 0
        var refreshes = 0
        var scans = 0
        var stops = 0
        var hotspot = 0
    }

    private fun screen(
        hooks: Hooks,
        serverRunning: Boolean = false,
        startedAt: Long = 0L,
        webClients: List<WebClientInfo> = emptyList(),
        nearbyPeers: List<PeerDevice> = emptyList(),
        scannedPeers: List<PeerDevice> = emptyList(),
        radarScanning: Boolean = false,
        relayHost: RelayClient.Host? = null,
        relayLastSync: Long = 0L
    ) {
        compose.setContent {
            PulseDropTheme {
                NetworkScreen(
                    serverRunning = serverRunning,
                    serverUrl = "http://10.0.2.16:8080",
                    startedAt = startedAt,
                    pinRequired = true,
                    autoAccept = true,
                    webClients = webClients,
                    nearbyPeers = nearbyPeers,
                    scannedPeers = scannedPeers,
                    radarScanning = radarScanning,
                    relayHost = relayHost,
                    relayLastSync = relayLastSync,
                    onToggleServer = { hooks.toggles++ },
                    onRefresh = { hooks.refreshes++ },
                    onScanRadar = { hooks.scans++ },
                    onStopRadar = { hooks.stops++ },
                    onOpenHotspotSettings = { hooks.hotspot++ }
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    // ── Pure mappings ──────────────────────────────────────────────────────

    @Test
    fun aStoppedServerHasNoUptimeToRead() {
        assertEquals("Not running", networkUptimeLabel(0L, 1_730_000_000_000L))
    }

    @Test
    fun uptimeCountsWholeUnitsFromARealStartMoment() {
        val now = 1_730_000_000_000L
        assertEquals("Up for 45s", networkUptimeLabel(now - 45_000L, now))
        assertEquals("Up for 5 min", networkUptimeLabel(now - 5 * 60_000L, now))
        assertEquals("Up for 1 h 20 min", networkUptimeLabel(now - 4_800_000L, now))
    }

    @Test
    fun uptimeNeverGoesNegativeWhenTheClockJumps() {
        val now = 1_730_000_000_000L
        assertEquals("Up for 0s", networkUptimeLabel(now + 60_000L, now))
    }

    @Test
    fun theLinkCheckNeverClaimsMoreThanWifiOrEthernet() {
        assertEquals("Wi-Fi or Ethernet link up", linkStatusLabel(true))
        assertEquals("No Wi-Fi or Ethernet link", linkStatusLabel(false))
    }

    @Test
    fun relayDistinguishesNoHostFromAHostNeverReached() {
        val now = 1_730_000_000_000L
        assertTrue(relayLinkLabel(null, 0L, now).startsWith("Not attached"))
        val host = RelayClient.Host("10.0.2.33", 8080, "dev-9", "Nova")
        val neverReached = relayLinkLabel(host, 0L, now)
        assertTrue(neverReached.contains("Attached to Nova at 10.0.2.33:8080"))
        assertTrue(neverReached.contains("no mailbox pull has finished yet"))
        val reached = relayLinkLabel(host, now - 12_000L, now)
        assertTrue(reached.contains("seen 12s ago"))
    }

    // ── Server card ────────────────────────────────────────────────────────

    @Test
    fun aStoppedServerSaysNobodyCanReachThePhone() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("network_server_status")
        compose.onNodeWithText("Stopped").assertExists()
        compose.onNodeWithText("Nothing on this network can reach the phone until the server starts.")
            .assertExists()
        compose.onNodeWithText("Start server").performClick()
        assertEquals(1, hooks.toggles)
    }

    @Test
    fun aRunningServerShowsItsRealUrlAndUptimeAndOffersStop() {
        val hooks = Hooks()
        val started = System.currentTimeMillis() - 90_000L
        screen(hooks, serverRunning = true, startedAt = started)
        scrollTo("network_server_url")
        compose.onNodeWithText("http://10.0.2.16:8080").assertExists()
        compose.onNodeWithText("Up for 1 min").assertExists()
        compose.onNodeWithText("Stop server").performClick()
        assertEquals(1, hooks.toggles)
    }

    // ── Browsers, peers, radar, relay ──────────────────────────────────────

    @Test
    fun anEmptyBrowserListSaysLastSeenNotLive() {
        val hooks = Hooks()
        screen(hooks, serverRunning = true)
        scrollTo("network_browsers_empty")
        compose.onNodeWithTag("network_browsers_empty").assertExists()
        compose.onNodeWithText("last-seen list", substring = true).assertExists()
    }

    @Test
    fun aBrowserRowCarriesNameAddressAndItsRealAge() {
        val hooks = Hooks()
        val client = WebClientInfo(
            ip = "10.0.2.7",
            name = "Chrome on Linux",
            userAgent = "Mozilla/5.0",
            lastSeen = System.currentTimeMillis() - 30_000L,
            local = false
        )
        screen(hooks, serverRunning = true, webClients = listOf(client))
        scrollTo("network_browser_10.0.2.7")
        compose.onNodeWithText("Chrome on Linux").assertExists()
        compose.onNodeWithText("10.0.2.7 • seen 30s ago").assertExists()
    }

    @Test
    fun aClientOnlyPeerIsNotShownAsDialable() {
        val hooks = Hooks()
        val peer = PeerDevice(
            id = "p1", name = "Mira", ip = "10.0.2.44", port = 8080,
            requiresPin = false, hasServer = false
        )
        screen(hooks, nearbyPeers = listOf(peer))
        scrollTo("network_peer_p1")
        compose.onNodeWithText("Mira • 10.0.2.44 — client only, nothing to dial").assertExists()
    }

    @Test
    fun aSweepInFlightOffersStopAndTheIdleSweepOffersScan() {
        val scanningHooks = Hooks()
        screen(scanningHooks, radarScanning = true)
        scrollTo("network_radar_status")
        compose.onNodeWithText("Sweeping this /24…").assertExists()
        compose.onNodeWithText("Stop sweep").performClick()
        assertEquals(1, scanningHooks.stops)
        assertEquals(0, scanningHooks.scans)
    }

    @Test
    fun idleRadarWithResultsCountsThemAndScanButtonStartsARealSweep() {
        val hooks = Hooks()
        val peers = listOf(
            PeerDevice(id = "a", name = "A", ip = "1", port = 1, requiresPin = false),
            PeerDevice(id = "b", name = "B", ip = "2", port = 2, requiresPin = false)
        )
        screen(hooks, scannedPeers = peers)
        scrollTo("network_radar_status")
        compose.onNodeWithText("2 servers found").assertExists()
        compose.onNodeWithText("Sweep this /24").performClick()
        assertEquals(1, hooks.scans)
    }

    @Test
    fun theRelayCardNamesTheHostItActuallyAttachedTo() {
        val hooks = Hooks()
        val host = RelayClient.Host("10.0.2.33", 8080, "dev-9", "Nova")
        screen(hooks, relayHost = host, relayLastSync = System.currentTimeMillis() - 5_000L)
        scrollTo("network_relay_status")
        compose.onNodeWithText(
            "Attached to Nova at 10.0.2.33:8080 — seen 5s ago", substring = true
        ).assertExists()
        compose.onNodeWithText("not an internet tunnel", substring = true).assertExists()
    }

    // ── Honest limits ──────────────────────────────────────────────────────

    @Test
    fun theDisclosureCardRefusesToFakeMeasurements() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("network_disclosure")
        compose.onNodeWithText("Ping, latency and signal strength", substring = true).assertExists()
        compose.onNodeWithText("only measurable while a file is actually moving", substring = true)
            .assertExists()
    }

    @Test
    fun theSsidIsDeclaredUnreadableNotBlank() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("network_device_card")
        compose.onNodeWithText("Wi-Fi name: not readable.", substring = true).assertExists()
    }

    @Test
    fun refreshRunsTheRealAddressRefreshAndReprobes() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("network_header")
        compose.onNodeWithTag("network_refresh").performClick()
        assertEquals(1, hooks.refreshes)
    }

    @Test
    fun hotspotSettingsOpensTheRealPanel() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("network_hotspot_settings")
        compose.onNodeWithTag("network_hotspot_settings").performClick()
        assertEquals(1, hooks.hotspot)
    }
}
