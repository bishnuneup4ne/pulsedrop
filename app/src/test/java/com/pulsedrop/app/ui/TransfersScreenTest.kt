package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.screens.TransferCategory
import com.pulsedrop.app.ui.screens.TransfersScreen
import com.pulsedrop.app.ui.screens.categoryOf
import com.pulsedrop.app.ui.screens.mergeTransfers
import com.pulsedrop.app.ui.screens.transferMatchesQuery
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Transfers page is a mirror of the real engine: merged live + durable rows, counts from
 * the full store, and only the actions the backend can actually honour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransfersScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        val opened = mutableListOf<String?>()
        val shared = mutableListOf<String?>()
        val saved = mutableListOf<String?>()
        val deleted = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        var clears = 0
        var sends = 0
        var receives = 0
        val details = mutableListOf<String>()
    }

    private fun screen(
        hooks: Hooks,
        active: List<TransferItem> = emptyList(),
        history: List<TransferHistoryEntity> = emptyList(),
        pending: List<PendingUploadInfo> = emptyList()
    ) {
        compose.setContent {
            PulseDropTheme {
                TransfersScreen(
                    activeTransfers = active,
                    history = history,
                    pendingUploads = pending,
                    onOpenFile = { hooks.opened += it },
                    onShareFile = { hooks.shared += it },
                    onSaveToDownloads = { hooks.saved += it },
                    onDeleteHistory = { hooks.deleted += it },
                    onDismissLive = { hooks.dismissed += it },
                    onClearAll = { hooks.clears++ },
                    onOpenSend = { hooks.sends++ },
                    onOpenReceive = { hooks.receives++ },
                    onOpenDetails = { hooks.details += it }
                )
            }
        }
    }

    private fun live(
        id: String = "t1",
        name: String = "report.pdf",
        status: TransferStatus = TransferStatus.IN_PROGRESS,
        direction: TransferDirection = TransferDirection.OUTGOING,
        error: String? = null,
        size: Long = 4096
    ) = TransferItem(
        id = id, fileName = name, fileSize = size,
        bytesTransferred = if (status == TransferStatus.COMPLETED) size else size / 2,
        speedBytesPerSec = 1024, direction = direction, status = status,
        progress = if (status == TransferStatus.COMPLETED) 1f else 0.5f,
        remoteDevice = "Work laptop", clientIp = "10.0.0.9",
        errorMessage = error, timestamp = 2000L
    )

    private fun hist(
        id: String = "h1",
        name: String = "photo.jpg",
        status: String = "COMPLETED",
        direction: String = "INCOMING",
        path: String? = null,
        error: String? = null,
        ts: Long = 1000L
    ) = TransferHistoryEntity(
        id = id, fileName = name, fileSize = 2048, direction = direction, status = status,
        remoteDevice = "Client (10.0.0.5)", clientIp = "10.0.0.5",
        localFilePath = path, mimeType = null, timestamp = ts, errorMessage = error
    )

    /** Rows live in a LazyColumn; anything below the viewport must be scrolled to first. */
    private fun scrollTo(tag: String) {
        compose.onNode(hasTestTag("transfers_list") and hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    private fun tap(tag: String) {
        if (tag.startsWith("transfers_row") || tag.startsWith("transfers_remove") ||
            tag.startsWith("transfers_open") || tag.startsWith("transfer_progress") ||
            tag.startsWith("transfers_pending_")
        ) {
            scrollTo(tag)
        }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun tapButton(text: String) {
        val matcher = hasText(text) and hasClickAction()
        compose.onNode(hasTestTag("transfers_list") and hasScrollToNodeAction())
            .performScrollToNode(matcher)
        compose.onNode(matcher).performClick()
    }

    // ---- Pure state-mapping and merge rules ----

    @Test
    fun everyStatusStringLandsInHonestBucket() {
        assertEquals(TransferCategory.ACTIVE, categoryOf("IN_PROGRESS"))
        assertEquals(TransferCategory.ACTIVE, categoryOf("QUEUED"))
        assertEquals(TransferCategory.COMPLETED, categoryOf("COMPLETED"))
        assertEquals(TransferCategory.FAILED, categoryOf("FAILED"))
        assertEquals(TransferCategory.CANCELLED, categoryOf("CANCELLED"))
        assertEquals(TransferCategory.UNKNOWN, categoryOf("FROZEN_BY_A_BUG"))
    }

    @Test
    fun durableRecordWinsOverTheLiveMapForTheSameId() {
        val merged = mergeTransfers(
            active = listOf(live(id = "x", status = TransferStatus.COMPLETED), live(id = "t1")),
            history = listOf(hist(id = "x", name = "durable.jpg"))
        )
        val terminal = merged.first { it.id == "x" }
        assertTrue("Room row must own the terminal state", terminal.isHistory)
        assertEquals("durable.jpg", terminal.item.fileName)
        assertEquals("the running row must still appear", 1, merged.count { it.id == "t1" })
        assertEquals(2, merged.size)
    }

    @Test
    fun searchReachesNameDeviceAddressAndId() {
        val entry = mergeTransfers(active = listOf(live(id = "abc123")), history = emptyList()).first()
        assertTrue(transferMatchesQuery(entry, "report"))
        assertTrue(transferMatchesQuery(entry, "laptop"))
        assertTrue(transferMatchesQuery(entry, "10.0.0.9"))
        assertTrue(transferMatchesQuery(entry, "ABC123"))
        assertFalse(transferMatchesQuery(entry, "nothing-here"))
    }

    // ---- Page behaviour ----

    @Test
    fun anEmptyStoreSaysSoAndOffersRealWorkNotDecorations() {
        val hooks = Hooks()
        screen(hooks)
        compose.onNodeWithText("No transfers yet").assertIsDisplayed()
        tapButton("Send a file")
        assertEquals(1, hooks.sends)
    }

    @Test
    fun aRunningRowShowsEngineProgressAndNoButtonsTheBackendCannotHonour() {
        val hooks = Hooks()
        screen(hooks, active = listOf(live()))
        compose.onNodeWithTag("transfers_row_t1").assertExists()
        compose.onNodeWithText("Sending to Work laptop").assertExists()
        // The engine has no cancel/pause/resume API; the page must not pretend otherwise.
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Resume").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun tilesKeepCountingTheFullStoreWhileSearchHidesRows() {
        val hooks = Hooks()
        screen(hooks, active = listOf(live(id = "a"), live(id = "b", name = "zeta.zip", status = TransferStatus.FAILED)))
        // The header counter reads the whole store: two rows, one of them truly in flight.
        compose.onNodeWithText("2 records • 1 in flight").assertExists()
        compose.onNodeWithTag("transfers_search").performTextInput("report")
        compose.onNodeWithTag("transfers_row_b").assertDoesNotExist()
        // Filtering the list must never rewrite the summary above it.
        compose.onNodeWithText("2 records • 1 in flight").assertExists()
        compose.onNodeWithTag("transfers_no_results").assertDoesNotExist()
        compose.onNodeWithTag("transfers_empty").assertDoesNotExist()
    }

    @Test
    fun aSearchThatHidesEverythingSaysWhatStillExists() {
        val hooks = Hooks()
        screen(hooks, active = listOf(live(id = "a")))
        compose.onNodeWithTag("transfers_search").performTextInput("qqzz")
        compose.onNodeWithTag("transfers_no_results").assertExists()
        tapButton("Clear filters")
        scrollTo("transfers_row_a")
        compose.onNodeWithTag("transfers_row_a").assertExists()
    }

    @Test
    fun failedRowCarriesItsRealReasonAndFailsIntoTheFailedBucket() {
        val hooks = Hooks()
        screen(hooks, active = listOf(live(id = "f", status = TransferStatus.FAILED, error = "Connection reset by peer")))
        compose.onNodeWithText("Connection reset by peer").assertExists()
        compose.onNodeWithTag("transfers_tile_failed").performClick()
        scrollTo("transfers_row_f")
        compose.onNodeWithTag("transfers_row_f").assertExists()
    }

    @Test
    fun openButtonOnlyAppearsWhenTheFileIsActuallyOnDisk() {
        val present = File.createTempFile("transfer-", ".bin").apply { deleteOnExit() }
        val gone = "/definitely/not/here.bin"
        val hooks = Hooks()
        screen(
            hooks,
            history = listOf(hist(id = "ok", path = present.absolutePath), hist(id = "gone", name = "gone.bin", path = gone))
        )
        tap("transfers_open_ok")
        assertEquals(listOf(present.absolutePath), hooks.opened)
        compose.onNodeWithTag("transfers_open_gone").assertDoesNotExist()
    }

    @Test
    fun removingAHistoryRecordConfirmsAndNeverTouchesFiles() {
        val hooks = Hooks()
        screen(hooks, history = listOf(hist(id = "h1")))
        tap("transfers_remove_h1")
        compose.onNodeWithText("Remove this record?").assertIsDisplayed()
        compose.onNodeWithTag("transfers_remove_cancel").performClick()
        assertTrue(hooks.deleted.isEmpty())

        tap("transfers_remove_h1")
        compose.onNodeWithTag("transfers_remove_confirm").performClick()
        assertEquals(listOf("h1"), hooks.deleted)
    }

    @Test
    fun dismissingAnUnpersistedRowIsNotCancellAndNotDeletion() {
        val hooks = Hooks()
        screen(hooks, active = listOf(live(id = "lp", status = TransferStatus.COMPLETED)))
        tap("transfers_remove_lp")
        compose.onNodeWithText("Hide this row?").assertIsDisplayed()
        compose.onNodeWithTag("transfers_remove_confirm").performClick()
        assertEquals(listOf("lp"), hooks.dismissed)
        assertTrue(hooks.deleted.isEmpty())
    }

    @Test
    fun awaitingApprovalPeeksButTheDecisionLivesOnReceive() {
        val hooks = Hooks()
        screen(hooks, pending = listOf(PendingUploadInfo("p1", "doc.txt", 128, "Laptop")))
        compose.onNodeWithTag("transfers_tile_awaiting").performClick()
        compose.onNodeWithTag("transfers_pending_panel").assertExists()
        scrollTo("transfers_pending_p1")
        compose.onNodeWithTag("transfers_pending_p1").assertExists()
        // No second approval desk on this page.
        compose.onNodeWithText("Accept").assertDoesNotExist()
        compose.onNodeWithText("Decline").assertDoesNotExist()
        tapButton("Open Receive")
        assertEquals(1, hooks.receives)
    }

    @Test
    fun tappingARowAsksForThatTransferByItsRealIdNotAnythingSheetLocal() {
        val hooks = Hooks()
        screen(
            hooks,
            active = listOf(live(id = "d", status = TransferStatus.IN_PROGRESS)),
            history = listOf(hist(id = "h9"))
        )
        tap("transfer_progress_d")
        assertEquals(listOf("d"), hooks.details)
        scrollTo("transfer_progress_h9")
        tap("transfer_progress_h9")
        assertEquals(listOf("d", "h9"), hooks.details)
    }

    @Test
    fun anUnrecognisedStateGetsAFallbackRowInsteadOfSilence() {
        val hooks = Hooks()
        screen(hooks, history = listOf(hist(id = "w", name = "weird.bin", status = "SOMETHING_ELSE")))
        scrollTo("transfers_row_w")
        compose.onNodeWithTag("transfers_row_w").assertExists()
        compose.onNodeWithText(
            "State “SOMETHING_ELSE” is not produced by the current engine — shown for honesty, with no actions beyond viewing details."
        ).assertExists()
    }
}
