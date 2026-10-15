package com.pulsedrop.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PendingPairRequest
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.ui.screens.DevicesScreen
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Devices page makes three promises: what it counts is real, discovery never masquerades as
 * trust, and the one irreversible action asks first. Each test drives the page the way a finger
 * does and checks the callback that actually reaches the services behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DevicesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        val connected = mutableListOf<String>()
        val unpaired = mutableListOf<String>()
        val pairRequests = mutableListOf<String>()
        val verifiedToggles = mutableListOf<Pair<String, Boolean>>()
        var cancels = 0
        var disconnects = 0
        var transfersOpens = 0
    }

    private fun peer(
        id: String,
        name: String,
        ip: String,
        kind: String? = "LocalDrop"
    ) = PeerDevice(
        id = id,
        name = name,
        ip = ip,
        port = 8080,
        requiresPin = false,
        appKind = kind,
        hasServer = kind != "Browser"
    )

    private fun paired(
        pid: String,
        name: String,
        ip: String,
        verified: Boolean = false
    ) = PairedDevice(
        pid = pid,
        name = name,
        pubB64 = "cHVi",
        originIp = ip,
        originPort = 8080,
        verified = verified
    )

    private fun show(
        nearbyPeers: List<PeerDevice> = emptyList(),
        connectedPeer: UiPeerConnection? = null,
        peerConnecting: Boolean = false,
        pairedDevices: List<PairedDevice> = emptyList(),
        pendingOutgoing: PendingPairRequest? = null,
        incomingPairCount: Int = 0,
        scanning: Boolean = false,
        hooks: Hooks = Hooks()
    ) {
        compose.setContent {
            PulseDropTheme {
                DevicesScreen(
                    nearbyPeers = nearbyPeers,
                    connectedPeer = connectedPeer,
                    peerConnecting = peerConnecting,
                    pairedDevices = pairedDevices,
                    pendingOutgoing = pendingOutgoing,
                    incomingPairCount = incomingPairCount,
                    scanning = scanning,
                    activeTransfers = emptyList(),
                    history = emptyList(),
                    onRescan = {},
                    onSendFilesToPeer = { _, _, _ -> },
                    onConnectToAddress = { hooks.connected.add(it) },
                    onDisconnectPeer = { hooks.disconnects++ },
                    onPair = { hooks.pairRequests.add(it.id) },
                    onCancelPairRequest = { hooks.cancels++ },
                    onUnpair = { hooks.unpaired.add(it) },
                    safetyCodeOf = { pid -> if (pid == "pid1") "421337" else null },
                    onToggleVerified = { pid, v -> hooks.verifiedToggles.add(pid to v) },
                    onOpenTransfers = { hooks.transfersOpens++ }
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    // Only one editable node is on screen in the search tests; the manual-address field
    // lives near the page bottom and is not composed while these run.
    private fun searchField() = compose.onNode(hasSetTextAction())

    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun quietPageMakesNoClaims() {
        show()
        compose.onNodeWithText("Devices").assertIsDisplayed()
        compose.onNodeWithText("Idle").assertIsDisplayed()
        scrollToText("No devices found yet")
        compose.onNodeWithText("No devices found yet").assertIsDisplayed()
        // An honest empty page offers the real next step, not a fake pair list.
        scrollToText("Rescan now")
        compose.onNodeWithText("Rescan now").assertIsDisplayed()
    }

    @Test
    fun scanningShowsWorkInProgressNotAnEmptyClaim() {
        show(scanning = true)
        compose.onNodeWithText("Searching").assertIsDisplayed()
        compose.onNodeWithText("No devices found yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun searchKeepsMatchesAndNoResultsOffersClear() {
        val alpha = peer("a1", "Alpha", "192.168.1.11")
        val beta = peer("b2", "Beta", "192.168.1.12")
        show(nearbyPeers = listOf(alpha, beta))
        scrollTo("peer_row_b2")
        compose.onNodeWithTag("peer_row_b2").assertIsDisplayed()

        scrollTo("devices_search")
        searchField()
            .performTextInput("Alpha")
        compose.onNodeWithTag("peer_row_b2").assertDoesNotExist()
        scrollTo("peer_row_a1")
        compose.onNodeWithTag("peer_row_a1").assertIsDisplayed()

        scrollTo("devices_search")
        searchField()
            .performTextInput("nothing")
        scrollToText("No devices match")
        compose.onNodeWithText("No devices match", substring = true).assertIsDisplayed()
        scrollToText("Clear search")
        compose.onNodeWithText("Clear search").performClick()
        scrollTo("peer_row_b2")
        compose.onNodeWithTag("peer_row_b2").assertIsDisplayed()
    }

    @Test
    fun trustedTileLeavesDiscoveryBehind() {
        show(
            nearbyPeers = listOf(peer("a1", "Alpha", "192.168.1.11")),
            pairedDevices = listOf(paired("pid1", "Laptop", "192.168.1.20"))
        )
        scrollTo("devices_tile_trusted")
        compose.onNodeWithTag("devices_tile_trusted").performClick()
        compose.onNodeWithTag("peer_row_a1").assertDoesNotExist()
        scrollTo("paired_device_pid1")
        compose.onNodeWithTag("paired_device_pid1").assertIsDisplayed()
    }

    @Test
    fun outgoingInviteOffersCancelAndNothingElse() {
        val alpha = peer("a1", "Alpha", "192.168.1.11")
        val hooks = Hooks()
        show(
            nearbyPeers = listOf(alpha),
            pendingOutgoing = PendingPairRequest(peer = alpha, reqId = "r1"),
            hooks = hooks
        )
        scrollTo("peer_row_a1")
        compose.onNodeWithTag("cancel_pair_btn").assertIsDisplayed()
        compose.onNodeWithTag("pair_btn_a1").assertDoesNotExist()
        compose.onNodeWithText("Invite pending", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("cancel_pair_btn").performClick()
        assertEquals(1, hooks.cancels)
    }

    @Test
    fun revokeTrustAsksBeforeDeletingKeys() {
        val hooks = Hooks()
        show(
            pairedDevices = listOf(paired("pid1", "Laptop", "192.168.1.20", verified = true)),
            hooks = hooks
        )
        scrollTo("paired_device_pid1")
        compose.onNodeWithContentDescription("Actions for Laptop").performClick()
        compose.onNodeWithText("Revoke trust").performClick()
        compose.onNodeWithText("Revoke trust for Laptop?", substring = true).assertIsDisplayed()
        // The page is still showing the pair: the callback has not fired yet.
        assertEquals(emptyList<String>(), hooks.unpaired)
        compose.onNodeWithTag("confirm_unpair_pid1").performClick()
        assertEquals(listOf("pid1"), hooks.unpaired)
    }

    @Test
    fun keepingThePairChangesNothing() {
        val hooks = Hooks()
        show(
            pairedDevices = listOf(paired("pid1", "Laptop", "192.168.1.20", verified = true)),
            hooks = hooks
        )
        scrollTo("paired_device_pid1")
        compose.onNodeWithContentDescription("Actions for Laptop").performClick()
        compose.onNodeWithText("Revoke trust").performClick()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(emptyList<String>(), hooks.unpaired)
        compose.onNodeWithTag("paired_device_pid1").assertIsDisplayed()
    }

    @Test
    fun verifyDialogShowsTheRealSafetyCode() {
        val hooks = Hooks()
        show(
            pairedDevices = listOf(paired("pid1", "Laptop", "192.168.1.20")),
            hooks = hooks
        )
        scrollTo("verify_btn_pid1")
        compose.onNodeWithTag("verify_btn_pid1").performClick()
        compose.onNodeWithText("Safety code").assertIsDisplayed()
        compose.onNodeWithTag("safety_code_value").assertTextEquals("421 337")
        compose.onNodeWithTag("confirm_safety_code_btn").performClick()
        assertEquals(listOf("pid1" to true), hooks.verifiedToggles)
    }

    @Test
    fun detailsSheetSeparatesDiscoveryFromTrust() {
        val hooks = Hooks()
        show(
            nearbyPeers = listOf(peer("a1", "Alpha", "192.168.1.11")),
            hooks = hooks
        )
        scrollTo("peer_row_a1")
        compose.onNodeWithContentDescription("Actions for Alpha").performClick()
        compose.onNodeWithText("Details").performClick()
        // A discovered stranger is stated as a stranger, never as trusted.
        compose.onNodeWithText("Discovered, not trusted").assertIsDisplayed()
        // The sheet is taller than the test screen; existence is the promise, visibility is physics.
        // A stranger is offered pairing and never offered revocation.
        compose.onNodeWithTag("detail_pair_btn").assertExists()
        compose.onNodeWithTag("detail_unpair_btn").assertDoesNotExist()
    }

    @Test
    fun strangerPairButtonReachesThePairingService() {
        val hooks = Hooks()
        show(nearbyPeers = listOf(peer("a1", "Alpha", "192.168.1.11")), hooks = hooks)
        scrollTo("pair_btn_a1")
        compose.onNodeWithTag("pair_btn_a1").performClick()
        assertEquals(listOf("a1"), hooks.pairRequests)
    }

    @Test
    fun manualAddressConnectsExactlyWhatWasTyped() {
        val hooks = Hooks()
        show(hooks = hooks)
        scrollTo("manual_ip_input")
        compose.onNodeWithTag("manual_ip_input").performTextInput("192.168.5.5:8080")
        compose.onNodeWithTag("connect_peer_btn").performClick()
        assertEquals(listOf("192.168.5.5:8080"), hooks.connected)
    }

    @Test
    fun connectedPeerCanBeLetGo() {
        val hooks = Hooks()
        val alpha = peer("a1", "Alpha", "192.168.1.11")
        show(
            nearbyPeers = listOf(alpha),
            connectedPeer = UiPeerConnection(peer = alpha, label = "Laptop", requiresPin = false),
            hooks = hooks
        )
        scrollTo("peer_row_a1")
        // A connected peer is not offered a fresh Pair invite.
        compose.onNodeWithTag("pair_btn_a1").assertDoesNotExist()
        scrollTo("connected_peer_panel")
        compose.onNodeWithTag("connected_peer_panel").assertIsDisplayed()
        compose.onNodeWithTag("disconnect_peer_btn").performClick()
        assertEquals(1, hooks.disconnects)
    }

    @Test
    fun browserSessionIsNotOfferedAppPairing() {
        val hooks = Hooks()
        val tab = peer("w1", "Browser tab", "192.168.1.30", kind = "Browser")
        show(nearbyPeers = listOf(tab), hooks = hooks)
        scrollTo("peer_row_w1")
        compose.onNodeWithText("Web session").assertIsDisplayed()
        compose.onNodeWithTag("pair_btn_w1").assertDoesNotExist()
        assertTrue(hooks.pairRequests.isEmpty())
    }
}
