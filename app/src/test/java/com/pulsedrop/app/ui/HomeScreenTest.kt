package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.components.PulseSection
import com.pulsedrop.app.ui.screens.HomeScreen
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dashboard promises that what it shows is real: alerts only when something needs an answer,
 * counts that match their sources, and every control reaching an actual callback. These tests
 * hold it to that, one interaction at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Navigator {
        val visited = mutableListOf<PulseSection>()
    }

    private fun show(
        nav: Navigator = Navigator(),
        serverState: UiServerState = UiServerState(deviceName = "Test Phone"),
        activeTransfers: List<TransferItem> = emptyList(),
        pendingUploads: List<PendingUploadInfo> = emptyList(),
        incomingPairCount: Int = 0,
        callInProgress: Boolean = false,
        pairedDevices: List<PairedDevice> = emptyList(),
        peersCount: Int = 0,
        accepted: MutableList<String>? = null,
        history: List<TransferHistoryEntity> = emptyList()
    ) {
        compose.setContent {
            PulseDropTheme {
                HomeScreen(
                    serverState = serverState,
                    qrBitmap = null,
                    sharedFiles = emptyList(),
                    activeTransfers = activeTransfers,
                    history = history,
                    pairedDevices = pairedDevices,
                    peersCount = peersCount,
                    webClientsCount = 0,
                    pendingUploads = pendingUploads,
                    incomingPairCount = incomingPairCount,
                    relayHostName = null,
                    callInProgress = callInProgress,
                    isSharingScreen = false,
                    updateInfo = null,
                    onToggleServer = {},
                    onAddFiles = {},
                    onRemoveFile = {},
                    onClearFiles = {},
                    onAcceptUpload = { accepted?.add(it) },
                    onDeclineUpload = {},
                    onToggleScreenShare = {},
                    onUpdateCheck = {},
                    onNavigate = { nav.visited.add(it) }
                )
            }
        }
    }

    private fun transfer(id: String, status: TransferStatus) = TransferItem(
        id = id,
        fileName = "$id.bin",
        fileSize = 1000,
        bytesTransferred = 500,
        speedBytesPerSec = 100,
        direction = TransferDirection.INCOMING,
        status = status,
        progress = 0.5f,
        remoteDevice = "Peer"
    )

    @Test
    fun quietHomeShowsNoAlerts() {
        show()
        compose.onNodeWithText("Accept").assertDoesNotExist()
        compose.onNodeWithText("want to pair", substring = true).assertDoesNotExist()
        compose.onNodeWithText("transfer failed", substring = true).assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Nothing in transit", substring = true))
        compose.onNodeWithText("Nothing in transit", substring = true).assertIsDisplayed()
    }

    @Test
    fun connectionCardLeadsThePageAndTellsTheTruth() {
        show()
        compose.onNodeWithTag("server_card").assertIsDisplayed()
        compose.onNodeWithText("Stopped").assertIsDisplayed()
        // Not serving: the card asks to start, it never offers to copy a dead address.
        compose.onNodeWithTag("toggle_server_action_btn").assertIsDisplayed()
        compose.onNodeWithTag("copy_url_btn").assertDoesNotExist()
    }

    @Test
    fun runningServerMakesTheAddressCopyable() {
        show(serverState = UiServerState(deviceName = "Test Phone", isRunning = true))
        compose.onNodeWithText("Running · port 8080", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("copy_url_btn").assertIsDisplayed()
        compose.onNodeWithText("http://127.0.0.1:8080").assertIsDisplayed()
    }

    @Test
    fun devicesSummaryShowsRealCounts() {
        show(
            peersCount = 3,
            pairedDevices = listOf(
                PairedDevice(pid = "p1", name = "Laptop", pubB64 = "a", originIp = "10.0.0.2", originPort = 8080, verified = true),
                PairedDevice(pid = "p2", name = "Tablet", pubB64 = "b", originIp = "10.0.0.3", originPort = 8080, verified = false)
            )
        )
        compose.onNodeWithText("3 nearby • 2 paired").assertExists()
        compose.onNodeWithText("Not verified").assertExists()
    }

    @Test
    fun pendingUploadOffersTheRealDecision() {
        val accepted = mutableListOf<String>()
        show(
            pendingUploads = listOf(
                PendingUploadInfo("up1", "notes.txt", 2048, "Laptop")
            ),
            accepted = accepted
        )
        compose.onNodeWithText("notes.txt · 2.0 KB").assertExists()
        compose.onNodeWithText("Accept").performClick()
        assertEquals(listOf("up1"), accepted)
    }

    @Test
    fun pairRequestOnlyBannersWhenItExists() {
        show(incomingPairCount = 2)
        compose.onNodeWithText("devices want to pair", substring = true).assertExists()
    }

    @Test
    fun failedTransferPointsAtHistory() {
        val nav = Navigator()
        show(nav = nav, activeTransfers = listOf(transfer("t1", TransferStatus.FAILED)))
        compose.onNodeWithText("1 transfer failed", substring = true).assertExists()
        compose.onNodeWithText("History").performClick()
        assertEquals(listOf(PulseSection.TRANSFERS), nav.visited)
    }

    @Test
    fun screenShareChipIsAbsentWithoutACall() {
        show(callInProgress = false)
        compose.onNodeWithText("Share screen").assertDoesNotExist()
    }

    @Test
    fun screenShareChipAppearsDuringACall() {
        show(callInProgress = true)
        compose.onNodeWithText("Share screen").assertIsDisplayed()
    }

    @Test
    fun sectionLinksNavigateToRealScreens() {
        val nav = Navigator()
        show(nav = nav)
        compose.onNodeWithTag("home_manage_devices_btn").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("home_all_transfers_btn"))
        compose.onNodeWithTag("home_all_transfers_btn").performClick()
        assertEquals(listOf(PulseSection.DEVICES, PulseSection.TRANSFERS), nav.visited)
    }

    @Test
    fun transfersSectionListsFinishedTransfers() {
        show(
            history = listOf(
                TransferHistoryEntity(
                    id = "h1",
                    fileName = "report.pdf",
                    fileSize = 4096,
                    direction = "INCOMING",
                    status = "COMPLETED",
                    remoteDevice = "Laptop",
                    clientIp = null,
                    localFilePath = null,
                    mimeType = null,
                    timestamp = System.currentTimeMillis()
                )
            )
        )
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("report.pdf"))
        compose.onNodeWithText("report.pdf").assertExists()
        compose.onNodeWithText("Received · Laptop", substring = true).assertExists()
    }

    @Test
    fun inFlightTransfersReplaceTheEmptyState() {
        show(activeTransfers = listOf(transfer("t9", TransferStatus.IN_PROGRESS)))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("t9.bin"))
        compose.onNodeWithText("Nothing in transit", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("In transit • 1", substring = true).onFirst().assertExists()
    }
}
