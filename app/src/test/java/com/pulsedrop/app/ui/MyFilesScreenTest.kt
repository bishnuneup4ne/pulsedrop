package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.ui.screens.FileCategory
import com.pulsedrop.app.ui.screens.MyFileSort
import com.pulsedrop.app.ui.screens.MyFilesScreen
import com.pulsedrop.app.ui.screens.fileCategoryOf
import com.pulsedrop.app.ui.screens.myFileMatchesQuery
import com.pulsedrop.app.ui.screens.myFileRecords
import com.pulsedrop.app.ui.screens.relativeLocationLabel
import com.pulsedrop.app.ui.screens.renameProblem
import com.pulsedrop.app.ui.screens.sortMyFiles
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * My Files is a mirror of two real things: the transfer log and the app-owned received folder.
 * These tests pin that it never claims more than that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MyFilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(System.getProperty("java.io.tmpdir")!!, "myfiles-test-${System.nanoTime()}").apply { mkdirs() }

    private class Hooks {
        val opened = mutableListOf<Pair<String, Boolean>>()
        val saved = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val fileDeleted = mutableListOf<String>()
        val recordsRemoved = mutableListOf<String>()
        val detailsOpened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var receives = 0
        var transfers = 0
    }

    private fun screen(
        hooks: Hooks,
        history: List<TransferHistoryEntity>,
        openFails: Boolean = false,
        renameFails: Boolean = false,
        deleteFails: Boolean = false
    ) {
        compose.setContent {
            PulseDropTheme {
                MyFilesScreen(
                    history = history,
                    receiveDir = dir.absolutePath,
                    volumeFreeBytes = 5L * 1024 * 1024 * 1024,
                    volumeTotalBytes = 64L * 1024 * 1024 * 1024,
                    onOpenFile = { path, result ->
                        hooks.opened += path to !openFails
                        result(!openFails, if (openFails) "No app on this phone opens this type of file." else null)
                    },
                    onSaveToDownloads = { hooks.saved += it },
                    onShareFile = { hooks.shared += it },
                    onRename = { id, name, result ->
                        hooks.renamed += id to name
                        result(!renameFails, if (renameFails) "Android refused the rename" else "Renamed")
                    },
                    onDeleteFile = { id, result ->
                        hooks.fileDeleted += id
                        result(!deleteFails, if (deleteFails) "Delete refused" else "Deleted")
                    },
                    onRemoveRecord = { hooks.recordsRemoved += it },
                    onOpenTransferDetails = { hooks.detailsOpened += it },
                    onCopyText = { hooks.copied += it },
                    onMessage = { hooks.messages += it },
                    onOpenReceive = { hooks.receives++ },
                    onOpenTransfers = { hooks.transfers++ }
                )
            }
        }
    }

    private fun file(name: String): File = File(dir, name).apply { writeText("x".repeat(10)) }

    private fun recv(
        id: String = "r1",
        name: String = "photo.jpg",
        status: String = "COMPLETED",
        direction: String = "INCOMING",
        path: String? = null,
        size: Long = 2048,
        mime: String? = null,
        sender: String = "Client (10.0.0.5)",
        ts: Long = 1000L
    ) = TransferHistoryEntity(
        id = id, fileName = name, fileSize = size, direction = direction, status = status,
        remoteDevice = sender, clientIp = "10.0.0.5",
        localFilePath = path ?: File(dir, name).absolutePath,
        mimeType = mime, timestamp = ts, errorMessage = null
    )

    private fun scrollTo(tag: String) {
        compose.onNode(hasTestTag("myfiles_list") and hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    private fun tap(tag: String) {
        if (tag.startsWith("myfiles_row") || tag.startsWith("myfiles_actions")) scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun tapText(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
    }

    // ---- Pure record/model rules ----

    @Test
    fun onlyCompletedIncomingRecordsWithAPathAreReceivedFiles() {
        val rows = listOf(
            recv(id = "a"),
            recv(id = "b", direction = "OUTGOING"),
            recv(id = "c", status = "FAILED"),
            recv(id = "d").copy(localFilePath = null)
        )
        val records = myFileRecords(rows, scan = false)
        assertEquals(listOf("a"), records.map { it.id })
    }

    @Test
    fun categoriesComeFromMimeOrExtensionAndUnknownStaysUnknown() {
        assertEquals(FileCategory.IMAGE, fileCategoryOf("image/png", "whatever"))
        assertEquals(FileCategory.IMAGE, fileCategoryOf(null, "holiday.HEIC"))
        assertEquals(FileCategory.ARCHIVE, fileCategoryOf(null, "stuff.zip"))
        assertEquals(FileCategory.DOCUMENT, fileCategoryOf(null, "notes.md"))
        assertEquals(FileCategory.OTHER, fileCategoryOf(null, "mystery.weirdext"))
    }

    @Test
    fun sortingUsesRawNumbersAndAbsentMeasurementsStayAtTheEnd() {
        File(dir, "big.bin").writeText("x".repeat(300))
        File(dir, "small.bin").writeText("x".repeat(100))
        val records = myFileRecords(
            listOf(
                recv(id = "big", name = "big.bin", size = 100_000),
                recv(id = "small", name = "small.bin", size = 100),
                recv(id = "unmeasured", name = "gone.bin", size = 5_000, path = File(dir, "gone.bin").absolutePath)
            ),
            scan = true
        )
        val asc = sortMyFiles(records, MyFileSort.SIZE, ascending = true)
        assertEquals(listOf("small", "big"), asc.take(2).map { it.id })
        assertEquals("unmeasured", asc.last().id)
        val desc = sortMyFiles(records, MyFileSort.SIZE, ascending = false)
        assertEquals(listOf("big", "small"), desc.take(2).map { it.id })
        assertEquals("unmeasured", desc.last().id)
    }

    @Test
    fun searchReachesNameExtensionCategorySenderAndRecordId() {
        val r = myFileRecords(
            listOf(recv(id = "rec42", name = "Vacation.PNG", sender = "Kitchen TV")),
            scan = false
        ).first()
        assertTrue(myFileMatchesQuery(r, "vacation"))
        assertTrue(myFileMatchesQuery(r, "png"))
        assertTrue(myFileMatchesQuery(r, "image"))
        assertTrue(myFileMatchesQuery(r, "kitchen"))
        assertTrue(myFileMatchesQuery(r, "rec42"))
        assertTrue(!myFileMatchesQuery(r, "nothing-matches-this"))
    }

    @Test
    fun renameRejectsTraversalDotNamesAndSeparatorsBeforeAndroidEverSeesThem() {
        assertNotNull(renameProblem("../../etc/passwd"))
        assertNotNull(renameProblem(".."))
        assertNotNull(renameProblem("a/b"))
        assertNotNull(renameProblem("   "))
        assertNull(renameProblem("holiday photo.png"))
    }

    @Test
    fun locationLabelsStayInsideTheAppFolderAndNeverLeakTheAbsolutePrefix() {
        val label = relativeLocationLabel(File(dir, "a.txt").absolutePath, dir.absolutePath)
        assertTrue(label.startsWith("received folder • "))
        assertTrue(!label.contains(dir.absolutePath))
    }

    // ---- UI: scope and states ----

    @Test
    fun anEmptyHistorySaysNothingArrivedAndPointsAtReceive() {
        val hooks = Hooks()
        screen(hooks, history = emptyList())
        scrollTo("myfiles_empty")
        compose.onNodeWithTag("myfiles_empty").assertExists()
        tapText("Open Receive")
        assertEquals(1, hooks.receives)
    }

    @Test
    fun trackedButMissingFilesStayListedWithAnExplicitMissingMarker() {
        val hooks = Hooks()
        file("present.jpg")
        screen(hooks, history = listOf(
            recv(id = "p", name = "present.jpg"),
            recv(id = "g", name = "gone.jpg", path = File(dir, "gone.jpg").absolutePath)
        ))
        compose.onNodeWithText("2 tracked records • 1 verified present right now").assertExists()
        scrollTo("myfiles_row_g")
        compose.onNodeWithTag("myfiles_missing_g", useUnmergedTree = true).assertExists()
    }

    @Test
    fun whenNothingIsOnDiskThePageSaysSoAndStillKeepsTheRecords() {
        val hooks = Hooks()
        screen(hooks, history = listOf(recv(id = "g1", name = "gone1.jpg", path = File(dir, "gone1.jpg").absolutePath)))
        compose.onNodeWithTag("myfiles_all_missing").assertExists()
        compose.onNodeWithText("moved or deleted with another app", substring = true).assertExists()
    }

    @Test
    fun aSearchThatHidesEverythingNamesTheHiddenScopeAndOffersClear() {
        val hooks = Hooks()
        file("photo.jpg")
        screen(hooks, history = listOf(recv(id = "a", name = "photo.jpg")))
        compose.onNodeWithTag("myfiles_search").performTextInput("zzzz")
        scrollTo("myfiles_no_results")
        compose.onNodeWithTag("myfiles_no_results").assertExists()
        compose.onNodeWithText("1 tracked file still exist in the full scope", substring = true).assertExists()
        tapText("Clear filters")
        scrollTo("myfiles_row_a")
        compose.onNodeWithTag("myfiles_row_a").assertExists()
    }

    // ---- Actions: real callbacks, real distinctions ----

    @Test
    fun openingDelegatesTheRealPathAndAFailedOpenBecomesAHonestMessage() {
        val hooks = Hooks()
        file("photo.jpg")
        screen(hooks, history = listOf(recv(id = "a", name = "photo.jpg")), openFails = true)
        tap("myfiles_actions_a")
        compose.onNodeWithTag("myfiles_open_a").performClick()
        assertEquals(1, hooks.opened.size)
        assertEquals(File(dir, "photo.jpg").absolutePath, hooks.opened[0].first)
        assertTrue(hooks.messages.any { it.contains("No app on this phone") })
    }

    @Test
    fun deletingAFileIsNeverRecordRemovalAndEachConfirmsItsOwnMeaning() {
        val hooks = Hooks()
        file("photo.jpg")
        screen(hooks, history = listOf(recv(id = "a", name = "photo.jpg")))
        tap("myfiles_actions_a")
        compose.onNodeWithTag("myfiles_deletefile_a").performClick()
        compose.onNodeWithText("no trash", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("myfiles_delete_confirm").performClick()
        assertEquals(listOf("a"), hooks.fileDeleted)
        assertEquals(emptyList<String>(), hooks.recordsRemoved)

        tap("myfiles_actions_a")
        compose.onNodeWithTag("myfiles_removerecord_a").performClick()
        compose.onNodeWithText("removes only PulseDrop's history row", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("myfiles_remove_record_confirm").performClick()
        assertEquals(listOf("a"), hooks.recordsRemoved)
    }

    @Test
    fun renameValidatesInTheDialogAndOnlyHandsUsableNamesToTheEngine() {
        val hooks = Hooks()
        file("photo.jpg")
        screen(hooks, history = listOf(recv(id = "a", name = "photo.jpg")))
        tap("myfiles_actions_a")
        compose.onNodeWithTag("myfiles_rename_a").performClick()
        compose.onNodeWithTag("myfiles_rename_field").performTextInput("../evil")
        compose.onNodeWithTag("myfiles_rename_problem").assertExists()
        compose.onNodeWithTag("myfiles_rename_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("myfiles_rename_field").performTextClearance()
        compose.onNodeWithTag("myfiles_rename_field").performTextInput("holiday.jpg")
        compose.onNodeWithTag("myfiles_rename_confirm").performClick()
        assertEquals(listOf("a" to "holiday.jpg"), hooks.renamed)
    }

    @Test
    fun detailsLinkJumpsToTheRealTransferRecordAndCopyStaysInTheAppFolderScope() {
        val hooks = Hooks()
        file("photo.jpg")
        screen(hooks, history = listOf(recv(id = "a", name = "photo.jpg")))
        scrollTo("myfiles_row_a")
        tap("myfiles_row_a")
        compose.onNodeWithText("Record id").assertIsDisplayed()
        compose.onNodeWithTag("myfiles_details_transfer").performClick()
        assertEquals(listOf("a"), hooks.detailsOpened)

        scrollTo("myfiles_row_a")
        tap("myfiles_row_a")
        compose.onNodeWithTag("myfiles_details_copy").performClick()
        assertEquals(File(dir, "photo.jpg").absolutePath, hooks.copied.last())
        assertTrue(hooks.messages.any { it.contains("Location copied") })
    }

    @Test
    fun missingFilesLoseTheirFileActionsButKeepRecordRemovalAndDetails() {
        val hooks = Hooks()
        screen(hooks, history = listOf(recv(id = "g", name = "gone.jpg", path = File(dir, "gone.jpg").absolutePath)))
        tap("myfiles_actions_g")
        compose.onNodeWithTag("myfiles_open_g").assertIsNotEnabled()
        compose.onNodeWithTag("myfiles_rename_g").assertIsNotEnabled()
        compose.onNodeWithTag("myfiles_deletefile_g").assertIsNotEnabled()
        compose.onNodeWithTag("myfiles_removerecord_g").performClick()
        compose.onNodeWithTag("myfiles_remove_record_confirm").performClick()
        assertEquals(listOf("g"), hooks.recordsRemoved)
    }

    @Test
    fun bulkDeleteEnqueuesOnlyPresentFilesAndReportsTheHonestPerFileTally() {
        val hooks = Hooks()
        file("one.jpg")
        file("two.jpg")
        screen(
            hooks,
            history = listOf(
                recv(id = "one", name = "one.jpg"),
                recv(id = "two", name = "two.jpg"),
                recv(id = "gone", name = "gone.jpg", path = File(dir, "gone.jpg").absolutePath)
            )
        )
        scrollTo("myfiles_select_all")
        compose.onNode(hasTestTag("myfiles_select_all") and hasClickAction()).performClick()
        scrollTo("myfiles_bulk_delete")
        compose.onNodeWithTag("myfiles_bulk_delete").performClick()
        compose.onNodeWithText("2 of 3 are actually", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("myfiles_bulk_delete_confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf("one", "two"), hooks.fileDeleted)
        assertTrue(!hooks.fileDeleted.contains("gone"))
        val dialogStillOpen =
            compose.onAllNodesWithTag("myfiles_bulk_delete_confirm").fetchSemanticsNodes().isNotEmpty()
        assertTrue(
            "messages=${hooks.messages} dialogStillOpen=$dialogStillOpen deleted=${hooks.fileDeleted}",
            hooks.messages.joinToString(" | ").contains("Deleted 2 files.")
        )
    }

    @Test
    fun longUnicodeNamesStayInRowAndDetailsWithoutCrash() {
        val hooks = Hooks()
        val tricky = "файл-ファイル-🚀".repeat(5) + ".bin"
        file(tricky)
        screen(hooks, history = listOf(recv(id = "u", name = tricky)))
        scrollTo("myfiles_row_u")
        compose.onNodeWithTag("myfiles_row_u").assertExists()
    }
}
