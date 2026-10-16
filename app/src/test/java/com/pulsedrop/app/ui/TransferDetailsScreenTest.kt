package com.pulsedrop.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.PendingUploadInfo
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.screens.TransferDetailModel
import com.pulsedrop.app.ui.screens.TransferDetailsScreen
import com.pulsedrop.app.ui.screens.detailStateLabel
import com.pulsedrop.app.ui.screens.detailStateTone
import com.pulsedrop.app.ui.screens.diagnosticsText
import com.pulsedrop.app.ui.screens.etaSecondsLeft
import com.pulsedrop.app.ui.screens.errorCategory
import com.pulsedrop.app.ui.screens.formatEta
import com.pulsedrop.app.ui.screens.resolveTransferDetail
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The details page must operate on the one real record behind the id — no invented progress,
 * no fabricated diagnostics, no actions the transfer engine cannot honour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferDetailsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var backs = 0
        val opened = mutableListOf<String?>()
        val saved = mutableListOf<String?>()
        val accepted = mutableListOf<String>()
        val declined = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        var sends = 0
        val copies = mutableListOf<String>()
    }

    private fun screen(
        hooks: Hooks,
        id: String?,
        active: List<TransferItem> = emptyList(),
        history: List<TransferHistoryEntity> = emptyList(),
        pending: List<PendingUploadInfo> = emptyList(),
        paired: List<PairedDevice> = emptyList(),
        peers: List<PeerDevice> = emptyList()
    ) {
        compose.setContent {
            PulseDropTheme {
                TransferDetailsScreen(
                    transferId = id,
                    activeTransfers = active,
                    history = history,
                    pendingUploads = pending,
                    pairedDevices = paired,
                    nearbyPeers = peers,
                    onBack = { hooks.backs++ },
                    onOpenFile = { hooks.opened += it },
                    onSaveToDownloads = { hooks.saved += it },
                    onAcceptPending = { hooks.accepted += it },
                    onDeclinePending = { hooks.declined += it },
                    onDeleteHistory = { hooks.deleted += it },
                    onDismissLive = { hooks.dismissed += it },
                    onOpenSend = { hooks.sends++ },
                    onCopyText = { hooks.copies += it }
                )
            }
        }
    }

    private fun live(
        id: String = "t1",
        name: String = "report.pdf",
        status: TransferStatus = TransferStatus.IN_PROGRESS,
        direction: TransferDirection = TransferDirection.OUTGOING,
        size: Long = 4096,
        done: Long = size / 2,
        speed: Long = 1024,
        error: String? = null,
        path: String? = null
    ) = TransferItem(
        id = id, fileName = name, fileSize = size, bytesTransferred = done,
        speedBytesPerSec = speed, direction = direction, status = status,
        progress = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f,
        remoteDevice = "Work laptop", clientIp = "10.0.0.9",
        localFilePath = path, errorMessage = error, timestamp = 2000L
    )

    private fun hist(
        id: String = "h1",
        name: String = "photo.jpg",
        status: String = "COMPLETED",
        direction: String = "INCOMING",
        path: String? = null,
        error: String? = null
    ) = TransferHistoryEntity(
        id = id, fileName = name, fileSize = 2048, direction = direction, status = status,
        remoteDevice = "Client (10.0.0.5)", clientIp = "10.0.0.5",
        localFilePath = path, mimeType = null, timestamp = 1000L, errorMessage = error
    )

    private fun pend(id: String = "p1") =
        PendingUploadInfo(id = id, fileName = "invoice.pdf", fileSize = 8192, fromDevice = "Browser")

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** Robolectric layout text is unreliable; the semantics Text property is not. */
    private fun tagContains(tag: String, needle: String) {
        compose.onNodeWithTag(tag).assert(hasText(needle, substring = true))
    }

    // ---- Resolution and state mapping: where an id's truth comes from ----

    @Test
    fun theIdResolvesToWhicheverStoreActuallyHoldsIt() {
        val running = live(id = "a", status = TransferStatus.IN_PROGRESS)
        val finished = live(id = "b", status = TransferStatus.COMPLETED, done = 4096)
        val durable = hist(id = "b", status = "COMPLETED")

        assertTrue(resolveTransferDetail("a", listOf(running), emptyList(), emptyList())
            is TransferDetailModel.FromLive)
        // Terminal live rows defer to the durable log, which carries the path and the reason.
        assertTrue(resolveTransferDetail("b", listOf(finished), listOf(durable), emptyList())
            is TransferDetailModel.FromDurable)
        assertTrue(resolveTransferDetail("zz", emptyList(), emptyList(), emptyList())
            is TransferDetailModel.Missing)
        assertTrue(resolveTransferDetail(null, listOf(running), emptyList(), emptyList())
            is TransferDetailModel.Missing)
    }

    @Test
    fun completionComesFromTheEngineStateNeverFromByteCounts() {
        // Every declared byte has arrived, but the engine still says IN_PROGRESS —
        // the page must keep saying "Transferring", not infer success from the denominator.
        val fullyArrived = live(id = "f", status = TransferStatus.IN_PROGRESS, done = 4096)
        val model = resolveTransferDetail("f", listOf(fullyArrived), emptyList(), emptyList())
        assertEquals("Transferring", detailStateLabel(model))
        assertEquals(PulseStatusTone.Active, detailStateTone(model))
    }

    @Test
    fun anUnrecognisedDurableStringGetsAnExplicitFallbackLabel() {
        val model = TransferDetailModel.FromDurable(hist(id = "w", status = "FROZEN"))
        assertEquals("Unrecognised (“FROZEN”)", detailStateLabel(model))
    }

    // ---- ETA honesty ----

    @Test
    fun etaNeedsMeasuredSpeedAndKnownRemainingBytesOrItStaysQuiet() {
        assertNull(etaSecondsLeft(bytes = 10, total = 100, speedBytesPerSec = 0))
        assertNull(etaSecondsLeft(bytes = 10, total = 0, speedBytesPerSec = 5))
        assertNull(etaSecondsLeft(bytes = 100, total = 100, speedBytesPerSec = 5))
        assertEquals(18L, etaSecondsLeft(bytes = 10, total = 100, speedBytesPerSec = 5))
        assertEquals("18s", formatEta(18))
        assertEquals("2m 30s", formatEta(150))
        assertEquals("1h 2m", formatEta(3720))
    }

    @Test
    fun aRunningTransferWithZeroSpeedSaysThereIsNoStableEstimateYet() {
        val hooks = Hooks()
        screen(hooks, id = "z", active = listOf(live(id = "z", speed = 0)))
        tagContains("details_eta", "no stable estimate yet")
    }

    // ---- Toolbar and actions: only what the engine supports ----

    @Test
    fun aRunningTransferExposesDisabledCancelAndPauseWithRealReasons() {
        val hooks = Hooks()
        screen(hooks, id = "r", active = listOf(live(id = "r")))
        compose.onNodeWithTag("details_cancel_disabled").assertIsNotEnabled()
        compose.onNodeWithTag("details_pause_disabled").assertIsNotEnabled()
        // The reason lives in accessibility semantics, not a visible duplicate.
        compose.onNodeWithTag("details_cancel_disabled")
            .assert(hasContentDescription("The transfer engine has no cancel operation", substring = true))
    }

    @Test
    fun aFailedTransferOffersTheRealRecoveryAndADisabledRetry() {
        val hooks = Hooks()
        screen(
            hooks,
            id = "e",
            history = listOf(hist(id = "e", status = "FAILED", error = "Server responded with code 401"))
        )
        compose.onNodeWithTag("details_error").assertExists()
        tagContains("details_error_message", "Server responded with code 401")
        tagContains("details_error_guidance", "Recovery:")
        compose.onNodeWithTag("details_retry_disabled").assertIsNotEnabled()
        click("details_resend")
        assertEquals(1, hooks.sends)
        assertEquals("Peer rejected the upload", errorCategory("Server responded with code 401"))
    }

    @Test
    fun stagedUploadsGetTheRealDecisionAndNoDeleteChip() {
        val hooks = Hooks()
        screen(hooks, id = "p", pending = listOf(pend("p")))
        compose.onNodeWithText("Awaiting approval").assertIsDisplayed()
        compose.onNodeWithTag("details_progress_pending").assertExists()
        click("details_accept")
        click("details_decline")
        assertEquals(listOf("p"), hooks.accepted)
        assertEquals(listOf("p"), hooks.declined)
        // A staged upload has no durable record to delete and no live row to hide.
        compose.onNodeWithTag("details_remove").assertDoesNotExist()
    }

    @Test
    fun removalFromTheDurableLogDeletesTheRecordAndLeaves() {
        val hooks = Hooks()
        screen(hooks, id = "h", history = listOf(hist(id = "h")))
        click("details_remove")
        assertEquals(listOf("h"), hooks.deleted)
        assertEquals(1, hooks.backs)
    }

    @Test
    fun hidingAnUnpersistedLiveRowDismissesItAndLeaves() {
        val hooks = Hooks()
        screen(hooks, id = "l", active = listOf(live(id = "l", status = TransferStatus.FAILED, error = "boom")))
        click("details_remove")
        assertEquals(listOf("l"), hooks.dismissed)
        assertEquals(emptyList<String>(), hooks.deleted)
        assertEquals(1, hooks.backs)
    }

    @Test
    fun fileActionsAppearOnlyWhenTheFileIsActuallyOnDisk() {
        val hooks = Hooks()
        val onDisk = java.io.File.createTempFile("details-", ".bin").apply { deleteOnExit() }
        screen(hooks, id = "o", history = listOf(hist(id = "o", path = onDisk.absolutePath)))
        click("details_open")
        click("details_save")
        assertEquals(listOf(onDisk.absolutePath), hooks.opened)
        assertEquals(listOf(onDisk.absolutePath), hooks.saved)
    }

    @Test
    fun fileActionsDisappearWhenThePathPointsAtNothing() {
        val hooks = Hooks()
        screen(hooks, id = "g", history = listOf(hist(id = "g", path = "/nope/absent.bin")))
        compose.onNodeWithTag("details_open").assertDoesNotExist()
        compose.onNodeWithTag("details_save").assertDoesNotExist()
    }

    // ---- Missing / stale ids ----

    @Test
    fun anUnknownIdGetsAnExplicitMissingStateAndNothingElse() {
        val hooks = Hooks()
        screen(hooks, id = "ghost")
        compose.onNodeWithTag("details_missing").assertIsDisplayed()
        compose.onNodeWithText("Nothing lives behind this id").assertIsDisplayed()
        compose.onNodeWithTag("details_progress_bar").assertDoesNotExist()
        compose.onNodeWithTag("details_peers").assertDoesNotExist()
        click("details_back")
        assertEquals(1, hooks.backs)
    }

    @Test
    fun switchingTheIdRecomputesThePageAndNeverKeepsTheOldRecord() {
        val hooks = Hooks()
        // A real navigation swaps only the id; the page must re-resolve from the stores,
        // never keep rendering the previously selected transfer.
        val shownId = mutableStateOf("one")
        val both = listOf(hist(id = "one", name = "first.bin"), hist(id = "two", name = "second.bin"))
        compose.setContent {
            PulseDropTheme {
                TransferDetailsScreen(
                    transferId = shownId.value,
                    activeTransfers = emptyList(),
                    history = both,
                    pendingUploads = emptyList(),
                    pairedDevices = emptyList(),
                    nearbyPeers = emptyList(),
                    onBack = { hooks.backs++ },
                    onOpenFile = { hooks.opened += it },
                    onSaveToDownloads = { hooks.saved += it },
                    onAcceptPending = { hooks.accepted += it },
                    onDeclinePending = { hooks.declined += it },
                    onDeleteHistory = { hooks.deleted += it },
                    onDismissLive = { hooks.dismissed += it },
                    onOpenSend = { hooks.sends++ },
                    onCopyText = { hooks.copies += it }
                )
            }
        }
        compose.onNodeWithText("first.bin").assertIsDisplayed()
        shownId.value = "two"
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("second.bin").assertIsDisplayed()
        compose.onNodeWithText("first.bin").assertDoesNotExist()
    }

    // ---- Integrity, trust, diagnostics ----

    @Test
    fun theIntegrityCardRefusesToClaimVerificationOrSafety() {
        val hooks = Hooks()
        screen(hooks, id = "i", history = listOf(hist(id = "i")))
        compose.onNodeWithTag("details_integrity").assertExists()
        compose.onNodeWithText("scanned or safe", substring = true).assertExists()
    }

    @Test
    fun unverifiedIdentityIsNamedAsUnverifiedRatherThanPraised() {
        val hooks = Hooks()
        screen(hooks, id = "u", history = listOf(hist(id = "u", direction = "INCOMING")))
        tagContains("details_trust", "Network proximity is not authorisation")
    }

    @Test
    fun copiedDiagnosticsCarryMeasurementsAndNoLocalPathsOrSecrets() {
        val model = TransferDetailModel.FromLive(
            live(id = "d", status = TransferStatus.COMPLETED, done = 4096, path = "/storage/emulated/private/secret.txt")
        )
        val text = diagnosticsText("d", model, transport = "HTTP upload over the local network")
        assertTrue(text.contains("transferId=d"))
        assertTrue(text.contains("bytesTransferred=4096"))
        assertTrue(text.contains("integrity=transport framing only"))
        assertFalse(text.contains("/storage/emulated"))
        assertFalse(text.contains("pin"))

        val hooks = Hooks()
        screen(hooks, id = "d", active = listOf(live(id = "d", status = TransferStatus.COMPLETED, done = 4096)))
        click("details_diag_toggle")
        compose.onNodeWithTag("details_diag_block").assertExists()
        click("details_copy_diag")
        assertEquals(1, hooks.copies.size)
        assertFalse(hooks.copies[0].contains("/storage"))
    }

    @Test
    fun theCopyIdChipHandsOverTheRealTransferId() {
        val hooks = Hooks()
        screen(hooks, id = "cid", history = listOf(hist(id = "cid")))
        click("details_copy_id")
        assertEquals(listOf("cid"), hooks.copies)
    }

    // ---- Edge states ----

    @Test
    fun aZeroByteFileSaysThereIsNothingToMeasureInsteadOfPercentZeroForever() {
        val hooks = Hooks()
        screen(hooks, id = "z0", active = listOf(live(id = "z0", size = 0, done = 0)))
        compose.onNodeWithTag("details_progress_zerobyte").assertExists()
        compose.onNodeWithTag("details_progress_bar").assertDoesNotExist()
    }

    @Test
    fun aLongUnicodeNameStaysInTheTitleAndDoesNotEvaporate() {
        val tricky = "τπχαρ-файл-ファイル🚀".repeat(6) + ".bin"
        val hooks = Hooks()
        screen(hooks, id = "n", history = listOf(hist(id = "n", name = tricky)))
        compose.onNodeWithTag("details_title").assertExists()
        compose.onNodeWithText(tricky, substring = true).assertExists()
    }

    @Test
    fun theTimelineOnlyListsTimestampsTheEngineActuallyPersisted() {
        val hooks = Hooks()
        screen(hooks, id = "t", history = listOf(hist(id = "t")))
        compose.onNodeWithTag("details_timeline").assertExists()
        // Durable rows carry an end timestamp and nothing else.
        compose.onNodeWithText("Recorded complete — the durable log wrote this row").assertExists()
        compose.onNodeWithText("no fuller event log", substring = true).assertExists()
    }
}
