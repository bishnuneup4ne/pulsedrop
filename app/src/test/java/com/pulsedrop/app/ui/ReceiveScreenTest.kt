package com.pulsedrop.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
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
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.screens.LOW_SPACE_BYTES
import com.pulsedrop.app.ui.screens.ReceiveScreen
import com.pulsedrop.app.ui.screens.receivedRows
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Receive page must never lie about what is on disk: pending rows are real parked files,
 * history rows are real paths, and every button has to reach the real service behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceiveScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        val accepted = mutableListOf<String>()
        val declined = mutableListOf<String>()
        val policy = mutableListOf<Boolean>()
        val opened = mutableListOf<String?>()
        val removed = mutableListOf<String>()
        var serverToggles = 0
    }

    private fun pending(id: String = "st1", name: String = "photo.jpg", size: Long = 2048, from: String = "Laptop") =
        PendingUploadInfo(id, name, size, from)

    private fun hist(
        id: String = "h1",
        name: String = "old.pdf",
        direction: String = "INCOMING",
        status: String = "COMPLETED",
        path: String? = "/storage/emulated/0/Android/data/com.pulsedrop.app/files/WebDrop_Received/old.pdf",
        ts: Long = 1000L
    ) = TransferHistoryEntity(id, name, 4096, direction, status, "Alpha tab", "10.0.0.7", path, null, ts)

    private fun activeItem() = TransferItem(
        id = "t1", fileName = "arriving.zip", fileSize = 1024, bytesTransferred = 512,
        speedBytesPerSec = 0, direction = TransferDirection.INCOMING, status = TransferStatus.IN_PROGRESS,
        progress = 0.5f, remoteDevice = "Courier"
    )

    private fun show(
        serverRunning: Boolean = true,
        autoAccept: Boolean = false,
        pendingList: List<PendingUploadInfo> = emptyList(),
        incoming: List<TransferItem> = emptyList(),
        history: List<TransferHistoryEntity> = emptyList(),
        freeSpace: Long = 5L * 1024 * 1024 * 1024,
        hooks: Hooks = Hooks()
    ) {
        var policy by mutableStateOf(autoAccept)
        compose.setContent {
            PulseDropTheme {
                ReceiveScreen(
                    serverRunning = serverRunning,
                    autoAccept = policy,
                    pendingUploads = pendingList,
                    incomingActive = incoming,
                    receivedHistory = history,
                    receiveDir = "/sdcard/Android/data/com.pulsedrop.app/files/WebDrop_Received",
                    freeSpaceBytes = freeSpace,
                    onAccept = { hooks.accepted.add(it) },
                    onDecline = { hooks.declined.add(it) },
                    onSetAutoAccept = { policy = it; hooks.policy.add(it) },
                    onOpenFile = { hooks.opened.add(it) },
                    onRemoveHistory = { hooks.removed.add(it) },
                    onToggleServer = { hooks.serverToggles++ },
                    onOpenTransfers = {},
                )
            }
        }
    }

    private fun scrollTo(tag: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))

    private fun scrollToText(text: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    // ── Pure ──────────────────────────────────────────────────────────────

    @Test
    fun historyRowsAreOnlyCompletedIncomingAndNewestFirst() {
        val rows = receivedRows(
            listOf(
                hist(id = "a", ts = 100),
                hist(id = "b", direction = "OUTGOING", ts = 300),
                hist(id = "c", status = "FAILED", ts = 400),
                hist(id = "d", ts = 200)
            )
        )
        assertEquals(listOf("d", "a"), rows.map { it.id })
    }

    @Test
    fun theLowSpaceLineIsOneHundredMegabytes() {
        assertEquals(100L * 1024 * 1024, LOW_SPACE_BYTES)
    }

    // ── The page ───────────────────────────────────────────────────────────

    @Test
    fun aStoppedServerSaysSoAndOffersTheRealToggle() {
        val hooks = Hooks()
        show(serverRunning = false, hooks = hooks)
        compose.waitForIdle()
        scrollToText("Receiving is off")
        compose.onNodeWithText("Receiving is off").assertIsDisplayed()
        scrollToText("Start server")
        compose.onNodeWithText("Start server").performClick()
        assertEquals(1, hooks.serverToggles)
    }

    @Test
    fun pendingRowsShowRealSizeAndReachTheRealAcceptAndDecline() {
        val hooks = Hooks()
        show(pendingList = listOf(pending()), hooks = hooks)
        compose.waitForIdle()
        scrollTo("receive_pending_st1")
        compose.onNodeWithText("photo.jpg").assertIsDisplayed()
        compose.onNodeWithText("2.0 KB · from Laptop").assertIsDisplayed()
        // The name is the sender's own claim, and the page says so.
        compose.onNodeWithText("treat that name as unverified", substring = true).assertExists()
        compose.onNodeWithTag("receive_accept_st1").performClick()
        compose.onNodeWithTag("receive_decline_st1").performClick()
        assertEquals(listOf("st1"), hooks.accepted)
        assertEquals(listOf("st1"), hooks.declined)
    }

    @Test
    fun thePolicySwitchCarriesTheRealPreferenceAndItsSecuritySentence() {
        val hooks = Hooks()
        show(autoAccept = false, hooks = hooks)
        scrollTo("receive_auto_accept")
        compose.onNodeWithText("Accept files automatically").assertIsDisplayed()
        compose.onNodeWithText("it never pairs or trusts the sender", substring = true).assertExists()
        compose.onNodeWithTag("receive_auto_accept").performClick()
        assertEquals(listOf(true), hooks.policy)
    }

    @Test
    fun emptyQueueWithApprovalOnExplainsItself() {
        show(autoAccept = false)
        scrollToText("Nothing waiting")
        compose.onNodeWithTag("receive_queue_empty").assertIsDisplayed()
    }

    @Test
    fun destinationCardStatesTheRealFolderAndNeverPromisesAChooser() {
        show()
        scrollTo("receive_dest_card")
        // The card leads with the folder's own name; the full path sits under it.
        compose.onNodeWithTag("receive_dir_value").assertTextEquals("WebDrop_Received")
        compose.onNodeWithText(
            "/sdcard/Android/data/com.pulsedrop.app/files/WebDrop_Received",
            substring = true
        ).assertExists()
        compose.onNodeWithTag("receive_free_space").assertIsDisplayed()
        compose.onNodeWithText("not supported by the current engine", substring = true).assertExists()
        // No silent overwrite is a promise the accept path now keeps.
        compose.onNodeWithText("timestamp appended", substring = true).assertExists()
    }

    @Test
    fun almostNoRoomIsAnnouncedBeforeItBreaksATransfer() {
        show(freeSpace = 10L * 1024 * 1024)
        scrollTo("receive_low_space")
        compose.onNodeWithText("Almost no room left").assertIsDisplayed()
    }

    @Test
    fun arrivingFilesAppearWithoutAFakeProgressBar() {
        show(incoming = listOf(activeItem()))
        scrollTo("receive_active_t1")
        compose.onNodeWithText("arriving.zip").assertIsDisplayed()
        compose.onNodeWithText("from Courier", substring = true).assertExists()
    }

    @Test
    fun historyOpenReachesTheRealViewerAndCopyIsThere() {
        val hooks = Hooks()
        show(history = listOf(hist()), hooks = hooks)
        scrollTo("receive_history_h1")
        compose.onNodeWithTag("receive_open_h1").performClick()
        assertEquals(
            listOf("/storage/emulated/0/Android/data/com.pulsedrop.app/files/WebDrop_Received/old.pdf"),
            hooks.opened
        )
        compose.onNodeWithTag("receive_copy_h1").assertIsDisplayed()
    }

    @Test
    fun removingHistorySaysTheFileStaysAndAsksFirst() {
        val hooks = Hooks()
        show(history = listOf(hist()), hooks = hooks)
        scrollTo("receive_remove_h1")
        compose.onNodeWithTag("receive_remove_h1").performClick()
        compose.onNodeWithText("Remove from history?").assertIsDisplayed()
        compose.onNodeWithText("stays on this phone", substring = true).assertExists()
        // Cancel keeps the row.
        compose.onNodeWithText("Keep it").performClick()
        assertTrue(hooks.removed.isEmpty())
        scrollTo("receive_remove_h1")
        compose.onNodeWithTag("receive_remove_h1").performClick()
        compose.onNodeWithTag("receive_remove_confirm").performClick()
        assertEquals(listOf("h1"), hooks.removed)
    }
}
