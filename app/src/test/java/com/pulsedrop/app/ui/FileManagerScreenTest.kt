package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pulsedrop.app.ui.screens.FileEntry
import com.pulsedrop.app.ui.screens.FileManagerScreen
import com.pulsedrop.app.ui.screens.ManagedRoot
import com.pulsedrop.app.ui.screens.folderTotal
import com.pulsedrop.app.ui.screens.listFolder
import com.pulsedrop.app.ui.screens.volumeLabel
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileManagerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var opened: String? = null
        var shared: String? = null
        var saved: String? = null
        var renamed: Pair<String, String>? = null
        var deleted: Pair<String, String?>? = null
        val messages = mutableListOf<String>()
    }

    private fun screen(
        hooks: Hooks,
        roots: List<ManagedRoot>,
        records: Map<String, String> = emptyMap()
    ) {
        compose.setContent {
            PulseDropTheme {
                FileManagerScreen(
                    roots = roots,
                    recordIdsByPath = records,
                    volumeFreeBytes = 5_000_000_000L,
                    volumeTotalBytes = 64_000_000_000L,
                    onOpen = { path, result -> hooks.opened = path; result?.invoke(false, "No app opens this type.") },
                    onShare = { hooks.shared = it },
                    onSaveToDownloads = { hooks.saved = it },
                    onRename = { id, name, result -> hooks.renamed = id to name; result(true, "Renamed.") },
                    onDelete = { path, recordId, result ->
                        hooks.deleted = path to recordId
                        result(true, "Deleted ${File(path).name} — no copy is kept.")
                    },
                    onMessage = { hooks.messages.add(it) }
                )
            }
        }
    }

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "fmtest_$name${System.nanoTime()}").apply { mkdirs() }

    private fun File.write(name: String, bytes: Int): File =
        File(this, name).apply { writeBytes(ByteArray(bytes) { 'a'.code.toByte() }) }

    // ── pure listing ────────────────────────────────────────────────────────

    @Test
    fun foldersComeFirstAndFilesSortByName() {
        val dir = tempDir("sort")
        dir.write("zebra.txt", 3)
        dir.write("apple.txt", 5)
        File(dir, "sub").mkdirs()
        val listed = listFolder(dir)
        assertEquals(listOf("sub", "apple.txt", "zebra.txt"), listed.map { it.name })
        assertTrue(listed.first().isDirectory)
    }

    @Test
    fun sizesAndDatesAreReadFromTheRealFile() {
        val dir = tempDir("size")
        val f = dir.write("payload.bin", 1234)
        val entry = listFolder(dir).single()
        assertEquals(1234L, entry.sizeBytes)
        assertEquals(f.lastModified(), entry.modifiedAt)
    }

    @Test
    fun recordIdsAttachOnlyToFilesThatHaveOne() {
        val dir = tempDir("rec")
        dir.write("known.txt", 1)
        dir.write("unknown.txt", 1)
        val records = mapOf(File(dir, "known.txt").absolutePath to "id-1")
        val listed = listFolder(dir, records)
        assertEquals("id-1", listed.first { it.name == "known.txt" }.recordId)
        assertEquals(null, listed.first { it.name == "unknown.txt" }.recordId)
    }

    @Test
    fun aMissingDirectoryListsAsEmptyInsteadOfCrashing() {
        assertEquals(emptyList<FileEntry>(), listFolder(File("/no/such/place-${System.nanoTime()}")))
    }

    @Test
    fun theListingIsCappedAndTheCapIsHonest() {
        val dir = tempDir("cap")
        repeat(5) { dir.write("f$it.txt", 1) }
        assertEquals(3, listFolder(dir, limit = 3).size)
    }

    @Test
    fun folderTotalsSumRealBytesAcrossSubfolders() {
        val dir = tempDir("total")
        dir.write("a", 100)
        val sub = File(dir, "sub").apply { mkdirs() }
        sub.write("b", 50)
        val scan = folderTotal(dir)
        assertEquals(150L, scan.totalBytes)
        assertEquals(2, scan.fileCount)
        assertEquals(false, scan.truncated)
    }

    @Test
    fun aFolderBiggerThanTheCapReportsItWasOnlyPartlyScanned() {
        val dir = tempDir("bigcap")
        repeat(4) { dir.write("x$it", 10) }
        val scan = folderTotal(dir, maxEntries = 2)
        assertTrue(scan.truncated)
    }

    @Test
    fun volumeLabelsSayUnavailableInsteadOfInventingNumbers() {
        assertTrue(volumeLabel(-1, -1).contains("unavailable"))
        val ok = volumeLabel(5_000_000_000L, 64_000_000_000L)
        assertTrue(ok, ok.contains("free of"))
    }

    // ── the screen over real folders ───────────────────────────────────────

    @Test
    fun rootOverviewShowsRealCountsFromARealScan() {
        val dir = tempDir("roots")
        dir.write("one.txt", 10)
        dir.write("two.txt", 20)
        val hooks = Hooks()
        screen(hooks, listOf(ManagedRoot("received", "Received files", "note", dir, false)))
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_received").assertIsDisplayed()
        compose.onNodeWithText("2 files • 30.0 B").assertIsDisplayed()
    }

    @Test
    fun aRootThatDoesNotExistYetSaysSo() {
        val missing = File("/no/such/root-${System.nanoTime()}")
        val hooks = Hooks()
        screen(hooks, listOf(ManagedRoot("pending", "Waiting for a decision", "note", missing, false)))
        compose.waitForIdle()
        compose.onNodeWithText("Does not exist yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingARootListsTheRealFilesAndTappingUpGoesBack() {
        val dir = tempDir("browse")
        dir.write("report.pdf", 7)
        val hooks = Hooks()
        screen(hooks, listOf(ManagedRoot("received", "Received files", "note", dir, false)))
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_received").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("files_row_report.pdf").assertIsDisplayed()
        compose.onNodeWithTag("files_up").assertIsDisplayed()
        compose.onNodeWithTag("files_up").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_received").assertIsDisplayed()
    }

    @Test
    fun anEmptyFolderSaysNothingIsThere() {
        val dir = tempDir("empty")
        val hooks = Hooks()
        screen(hooks, listOf(ManagedRoot("received", "Received files", "note", dir, false)))
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_received").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("files_empty").assertIsDisplayed()
    }

    @Test
    fun deleteAsksFirstAndTheDialogNamesTheRealFile() {
        val dir = tempDir("del")
        dir.write("gone.txt", 5)
        val hooks = Hooks()
        screen(hooks, listOf(ManagedRoot("received", "Received files", "note", dir, false)))
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_received").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("files_menu_gone.txt").performClick()
        compose.onNodeWithTag("files_act_delete_gone.txt").performClick()
        compose.onNodeWithText("Delete gone.txt?", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("files_delete_cancel").performClick()
        hooks.deleted = null
        compose.onNodeWithTag("files_menu_gone.txt").performClick()
        compose.onNodeWithTag("files_act_delete_gone.txt").performClick()
        compose.onNodeWithTag("files_delete_confirm").performClick()
        compose.waitForIdle()
        assertEquals(File(dir, "gone.txt").absolutePath to (null as String?), hooks.deleted)
        assertTrue(hooks.messages.any { it.contains("no copy is kept") })
    }

    @Test
    fun renameIsOfferedOnlyForFilesWithATransferRecord() {
        val dir = tempDir("ren")
        dir.write("recorded.txt", 5)
        dir.write("stranger.txt", 5)
        val hooks = Hooks()
        screen(
            hooks,
            listOf(ManagedRoot("received", "Received files", "note", dir, false)),
            records = mapOf(File(dir, "recorded.txt").absolutePath to "rec-9")
        )
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_received").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("files_menu_recorded.txt").performClick()
        compose.onNodeWithTag("files_act_rename_recorded.txt").assertIsEnabled()
        compose.onNodeWithTag("files_act_rename_recorded.txt").performClick()
        compose.onNodeWithTag("files_rename_save").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("rec-9" to "recorded.txt", hooks.renamed)

        compose.onNodeWithTag("files_menu_stranger.txt").performClick()
        compose.onNodeWithTag("files_act_rename_stranger.txt").assertIsNotEnabled()
    }

    @Test
    fun readOnlyRootsHideTheDestructiveActionsButStillListFiles() {
        val dir = tempDir("ro")
        dir.write("payload.apk", 9)
        val hooks = Hooks()
        screen(hooks, listOf(ManagedRoot("updates", "Update downloads", "note", dir, true)))
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_updates").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("files_row_payload.apk").assertIsDisplayed()
        compose.onNodeWithTag("files_readonly_note").assertIsDisplayed()
        compose.onNodeWithTag("files_menu_payload.apk").performClick()
        compose.onNodeWithTag("files_act_share_payload.apk").assertDoesNotExist()
        compose.onNodeWithTag("files_act_delete_payload.apk").assertDoesNotExist()
    }

    @Test
    fun thePageDisclosesWhatItCannotDo() {
        val hooks = Hooks()
        screen(hooks, emptyList())
        compose.waitForIdle()
        compose.onNodeWithTag("files_disclosure").assertIsDisplayed()
        compose.onNodeWithText("cannot browse shared storage", substring = true).assertIsDisplayed()
        compose.onNodeWithText("no recycle bin", substring = true).assertIsDisplayed()
    }

    @Test
    fun openingAFileRoutesToTheRealOpenerAndReportsItsFailureTruthfully() {
        val dir = tempDir("open")
        dir.write("doc.txt", 2)
        val hooks = Hooks()
        screen(hooks, listOf(ManagedRoot("received", "Received files", "note", dir, false)))
        compose.waitForIdle()
        compose.onNodeWithTag("files_root_received").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("files_menu_doc.txt").performClick()
        compose.onNodeWithTag("files_act_open_doc.txt").performClick()
        compose.waitForIdle()
        assertEquals(File(dir, "doc.txt").absolutePath, hooks.opened)
        assertTrue(hooks.messages.any { it.contains("No app opens this type") })
    }
}
