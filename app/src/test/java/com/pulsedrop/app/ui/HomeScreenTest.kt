package com.pulsedrop.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * badges only for actual counts, and every control reaching an actual callback. These tests hold
 * it to that, one interaction at a time.
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
        activeTransfers: List<TransferItem> = emptyList(),
        pendingUploads: List<PendingUploadInfo> = emptyList(),
        incomingPairCount: Int = 0,
        callInProgress: Boolean = false,
        pairedDevices: List<PairedDevice> = emptyList(),
        peersCount: Int = 0,
        messagesCount: Int = 0,
        installs: Int? = null,
        accepted: MutableList<String>? = null,
        history: List<TransferHistoryEntity> = emptyList()
    ) {
        compose.setContent {
            PulseDropTheme {
                HomeScreen(
                    serverState = UiServerState(deviceName = "Test Phone"),
                    qrBitmap = null,
                    sharedFiles = emptyList(),
                    activeTransfers = activeTransfers,
                    history = history,
                    messagesCount = messagesCount,
                    pairedDevices = pairedDevices,
                    peersCount = peersCount,
                    webClientsCount = 0,
                    pendingUploads = pendingUploads,
                    incomingPairCount = incomingPairCount,
                    relayHostName = null,
                    callInProgress = callInProgress,
                    isSharingScreen = false,
                    updateInfo = null,
                    updateProgress = null,
                    updateStatus = null,
                    appVersion = "1.7 (9)",
                    installs = installs,
                    onToggleServer = {},
                    onOpenSecuritySettings = {},
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
    fun statsTilesShowRealCounts() {
        show(peersCount = 3, messagesCount = 5)
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
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
    fun quickActionsNavigateToRealSections() {
        val nav = Navigator()
        show(nav = nav)
        compose.onNodeWithText("Send files").performClick()
        compose.onNodeWithText("Transfers").performClick()
        assertEquals(listOf(PulseSection.SEND_FILES, PulseSection.TRANSFERS), nav.visited)
    }

    @Test
    fun installCountIsHiddenUntilItArrives() {
        show(installs = null)
        compose.onNodeWithText("installs", substring = true).assertDoesNotExist()
    }

    @Test
    fun installCountShowsTheRealNumberOnceKnown() {
        show(installs = 4213)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("home_app_health"))
        compose.onNodeWithText("installs of this release", substring = true).assertIsDisplayed()
    }

    @Test
    fun recentActivityListsFinishedTransfers() {
        val nav = Navigator()
        show(
            nav = nav,
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
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("home_recent_activity"))
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithText("Received · Laptop", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("home_recent_activity").performClick()
        assertEquals(listOf(PulseSection.ACTIVITY), nav.visited)
    }

    @Test
    fun inFlightTransfersReplaceTheEmptyState() {
        show(activeTransfers = listOf(transfer("t9", TransferStatus.IN_PROGRESS)))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("In transit • 1", substring = true))
        compose.onNodeWithText("Nothing in transit", substring = true).assertDoesNotExist()
        compose.onNodeWithText("In transit • 1", substring = true).assertIsDisplayed()
    }
}
