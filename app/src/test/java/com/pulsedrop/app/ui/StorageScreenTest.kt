package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.ui.screens.FolderUsage
import com.pulsedrop.app.ui.screens.StorageScreen
import com.pulsedrop.app.ui.screens.StorageSnapshot
import com.pulsedrop.app.ui.screens.measureStorage
import com.pulsedrop.app.ui.screens.summarizeLog
import com.pulsedrop.app.ui.screens.volumeUsedFraction
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Storage & data must only ever say what a real disk walk found. These tests measure real
 * temporary directories and pin that the page never invents a byte or hides a truncation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(
        id: String,
        direction: String,
        status: String,
        size: Long
    ) = TransferHistoryEntity(
        id = id, fileName = "$id.bin", fileSize = size, direction = direction, status = status,
        remoteDevice = "peer-$id", clientIp = "10.0.0.9", localFilePath = null,
        mimeType = null, timestamp = 1000L
    )

    @Test
    fun theLogSummaryCountsOnlyCompletedBytesButEveryFailedRow() {
        val summary = summarizeLog(
            listOf(
                row("a", "INCOMING", "COMPLETED", 100),
                row("b", "OUTGOING", "COMPLETED", 250),
                row("c", "INCOMING", "FAILED", 9999),
                row("d", "OUTGOING", "FAILED", 9999)
            )
        )
        assertEquals(4, summary.rows)
        assertEquals(1, summary.receivedCount)
        assertEquals(1, summary.sentCount)
        assertEquals(2, summary.failedCount)
        // The failed rows' sizes are attempts, not landings — they must not inflate the bytes.
        assertEquals(100L, summary.receivedBytes)
        assertEquals(250L, summary.sentBytes)
    }

    @Test
    fun anUnreadableVolumeYieldsNoFractionInsteadOfAGuess() {
        assertEquals(0.75f, volumeUsedFraction(freeBytes = 256L, totalBytes = 1024L)!!, 0.0001f)
        assertNull(volumeUsedFraction(-1L, 1024L))
        assertNull(volumeUsedFraction(0L, 0L))
        assertNull(volumeUsedFraction(2000L, 1024L))
    }

    private fun tempTree(): Triple<File, File, File> {
        // Mirrors a real package root: <root>/files, <root>/databases, <root>/ext, <root>/cache.
        val root = File(System.getProperty("java.io.tmpdir")!!, "storage-test-${System.nanoTime()}")
        val ext = File(root, "ext").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        File(ext, "WebDrop_Received").mkdirs()
        File(ext, "WebDrop_Received/one.bin").writeBytes(ByteArray(1000))
        File(ext, "WebDrop_Received/two.bin").writeBytes(ByteArray(500))
        File(files, "crashes").mkdirs()
        File(files, "crashes/crash.log").writeText("x".repeat(42))
        File(cache, "junk.tmp").writeBytes(ByteArray(7))
        val dbs = File(root, "databases").apply { mkdirs() }
        File(dbs, "webdrop_database").writeBytes(ByteArray(300))
        File(dbs, "webdrop_database-wal").writeBytes(ByteArray(100))
        val prefs = File(files, "shared_prefs").apply { mkdirs() }
        File(prefs, "localdrop_settings.xml").writeText("<a/>")
        File(prefs, "notprefs.txt").writeText("ignored")
        return Triple(ext, files, cache)
    }

    @Test
    fun measureStorageWalksRealFoldersAndSumsRealFiles() {
        val (ext, files, cache) = tempTree()
        val snapshot = measureStorage(ext, files, cache, "webdrop_database")
        val byId = snapshot.folders.associateBy { it.id }
        assertEquals(1500L, byId.getValue("received").bytes)
        assertEquals(2, byId.getValue("received").fileCount)
        assertEquals(42L, byId.getValue("crashes").bytes)
        assertEquals(7L, byId.getValue("cache").bytes)
        // Never created on this phone — the page must say so rather than hide it.
        assertTrue(byId.getValue("pending").missing)
        assertEquals(0L, byId.getValue("pending").bytes)
        // The -wal file counts; the missing -shm does not; the non-xml file does not.
        assertEquals(400L, snapshot.databaseBytes)
        assertEquals("<a/>".toByteArray().size.toLong(), snapshot.prefsBytes)
    }

    private fun snapshot(complete: Boolean = true) = StorageSnapshot(
        volumeFreeBytes = if (complete) 256L else -1L,
        volumeTotalBytes = if (complete) 1024L else -1L,
        folders = listOf(
            FolderUsage("received", "Received files", "note", 1536L, 2, truncated = false, missing = false),
            FolderUsage("pending", "Waiting for a decision", "note", 0L, 0, truncated = false, missing = true)
        ),
        databaseBytes = 400L,
        prefsBytes = 12L
    )

    private class Hooks {
        var clears = 0
        var refreshes = 0
        val opened = mutableListOf<String>()
    }

    private fun screen(
        hooks: Hooks,
        snap: StorageSnapshot?,
        measuring: Boolean = false,
        history: List<TransferHistoryEntity> = listOf(
            row("a", "INCOMING", "COMPLETED", 100),
            row("b", "OUTGOING", "COMPLETED", 250)
        )
    ) {
        compose.setContent {
            PulseDropTheme {
                StorageScreen(
                    snapshot = snap,
                    measuring = measuring,
                    history = history,
                    onClearLog = { hooks.clears++ },
                    onRefresh = { hooks.refreshes++ },
                    onOpenFileManager = { hooks.opened += "files" },
                    onOpenMyFiles = { hooks.opened += "myfiles" },
                    onOpenTransfers = { hooks.opened += "transfers" }
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test
    fun whileMeasuringThePageSaysSoAndShowsNoNumbers() {
        screen(Hooks(), snap = null, measuring = true)
        scrollTo("storage_volume")
        compose.onNodeWithText("Measuring the volume…").assertIsDisplayed()
        compose.onNodeWithTag("storage_folders_pending").assertIsDisplayed()
        compose.onNodeWithTag("storage_folder_received").assertDoesNotExist()
    }

    @Test
    fun aFinishedWalkPrintsTheBytesItActuallyFound() {
        val hooks = Hooks()
        screen(hooks, snap = snapshot())
        scrollTo("storage_folder_received")
        compose.onNodeWithTag("storage_folder_received").assertIsDisplayed()
        compose.onNodeWithText("1.5 KB · 2 files").assertExists()
        scrollTo("storage_folder_pending")
        compose.onNodeWithText("never used").assertExists()
        scrollTo("storage_records")
        compose.onNodeWithText("Transfer database: 400.0 B · Settings, pairings", substring = true).assertExists()
    }

    @Test
    fun theLogCardReportsRealRowsAndRefusesToCountFailedBytes() {
        val hooks = Hooks()
        screen(
            hooks, snap = snapshot(),
            history = listOf(
                row("a", "INCOMING", "COMPLETED", 100),
                row("c", "INCOMING", "FAILED", 9999)
            )
        )
        scrollTo("storage_log")
        compose.onNodeWithText("2 rows · 1 received (100.0 B) · 0 sent (0 B) · 1 failed", substring = true)
            .assertExists()
    }

    @Test
    fun erasingTheLogAsksFirstSaysWhatItTouchesAndOnlyThenRuns() {
        val hooks = Hooks()
        screen(hooks, snap = snapshot())
        scrollTo("storage_clear_log")
        compose.onNodeWithTag("storage_clear_log").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Erase the transfer log?").assertIsDisplayed()
        compose.onNodeWithText("no recycle bin", substring = true).assertExists()
        assertEquals(0, hooks.clears)
        compose.onNodeWithTag("storage_confirm_clear").performClick()
        compose.waitForIdle()
        assertEquals(1, hooks.clears)
    }

    @Test
    fun backingOutOfTheDialogClearsNothing() {
        val hooks = Hooks()
        screen(hooks, snap = snapshot())
        scrollTo("storage_clear_log")
        compose.onNodeWithTag("storage_clear_log").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("storage_cancel_clear").performClick()
        compose.waitForIdle()
        assertEquals(0, hooks.clears)
        compose.onNodeWithText("Erase the transfer log?").assertDoesNotExist()
    }

    @Test
    fun anEmptyLogCannotBeErasedAgain() {
        screen(Hooks(), snap = snapshot(), history = emptyList())
        scrollTo("storage_clear_log")
        compose.onNodeWithTag("storage_clear_log").assertIsNotEnabled()
    }

    @Test
    fun thePageSaysPlainlyWhatItCannotSeeAndWhereTheRealEditorsAre() {
        val hooks = Hooks()
        screen(hooks, snap = snapshot())
        // The refresh button sits in the header, before the list scrolls away.
        compose.onNodeWithTag("storage_refresh").performClick()
        assertEquals(1, hooks.refreshes)
        scrollTo("storage_cannot")
        compose.onNodeWithText("· There is no recycle bin", substring = true).assertExists()
        scrollTo("storage_open_files")
        compose.onNodeWithTag("storage_open_files").performClick()
        compose.onNodeWithTag("storage_open_myfiles").performClick()
        scrollTo("storage_open_transfers")
        compose.onNodeWithTag("storage_open_transfers").performClick()
        assertEquals(listOf("files", "myfiles", "transfers"), hooks.opened)
    }
}
