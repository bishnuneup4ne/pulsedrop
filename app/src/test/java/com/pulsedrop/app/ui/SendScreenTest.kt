package com.pulsedrop.app.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.ui.screens.SendChannel
import com.pulsedrop.app.ui.screens.SendFileItem
import com.pulsedrop.app.ui.screens.SendScreen
import com.pulsedrop.app.ui.screens.addPicksToSelection
import com.pulsedrop.app.ui.screens.buildRecipients
import com.pulsedrop.app.ui.screens.sendPreflight
import com.pulsedrop.app.ui.screens.totalSizeOf
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
 * The Send page promises one thing above all: bytes leave only after the last tap, and only the
 * way the engine can actually carry them. These tests hold both halves — the pure decisions
 * (dedupe, totals, preflight, recipient truth) and the click path from selection to callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SendScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(name: String, size: Long = 1000, readable: Boolean = true) =
        SendFileItem(Uri.parse("content://x/$name"), name, size, null, readable)

    private fun peer(id: String, name: String, ip: String, kind: String? = "LocalDrop") = PeerDevice(
        id = id, name = name, ip = ip, port = 8080, requiresPin = false, appKind = kind,
        hasServer = kind != "Browser"
    )

    // ── Pure decisions ─────────────────────────────────────────────────────

    @Test
    fun pickingTheSameFileTwiceIsOneJobNotTwo() {
        val uri = Uri.parse("content://docs/a.txt")
        val once = addPicksToSelection(emptyList(), listOf(uri))
        val twice = addPicksToSelection(once, listOf(uri))
        assertEquals(1, twice.size)
    }

    @Test
    fun totalsUseSafeMathAndSaySoAboutUnknownSizes() {
        val huge = listOf(item("big", Long.MAX_VALUE - 1), item("alsoBig", Long.MAX_VALUE - 1))
        assertEquals(Long.MAX_VALUE, totalSizeOf(huge))
        val withUnknown = listOf(item("known", 500), item("mystery", -1))
        assertEquals(500L, totalSizeOf(withUnknown))
    }

    @Test
    fun preflightBlocksWhatCannotSendAndWarnsAboutWhatCan() {
        val unpaired = peer("p1", "Alpha", "10.0.0.5")
        val online = buildRecipients(listOf(unpaired), null, emptyList()).first()
        val blockers = sendPreflight(emptyList(), null).filter { it.blocking }
        assertEquals(2, blockers.size) // no files + no recipient

        val plainWarn = sendPreflight(listOf(item("a")), online)
            .first { it.text.contains("unencrypted") }
        assertFalse(plainWarn.blocking) // unpaired sends really do go through

        val offline = buildRecipients(emptyList(), null, listOf(
            PairedDevice("pid1", "Laptop", "cHVi", "10.0.0.9", 8080)
        )).first()
        assertTrue(sendPreflight(listOf(item("a")), offline).any { it.blocking })

        val unreadable = sendPreflight(listOf(item("gone", readable = false)), online)
        assertTrue(unreadable.any { it.blocking && it.text.contains("gone") })
    }

    @Test
    fun recipientsTellTrustInWordsAndNeverDuplicate() {
        val seen = peer("p1", "Alpha", "10.0.0.5")
        val tab = peer("w1", "Tab", "10.0.0.6", kind = "Browser")
        val paired = PairedDevice("pid1", "Alpha", "cHVi", "10.0.0.5", 8080)
        val list = buildRecipients(listOf(seen, tab), null, listOf(paired))
        assertEquals(SendChannel.ENCRYPTED, list.first { it.id == "p1" }.channel)
        assertEquals(SendChannel.WEB_HOSTED, list.first { it.id == "w1" }.channel)
        assertEquals(2, list.size) // the offline Alpha behind the online Alpha is the same machine
    }

    // ── The page itself ────────────────────────────────────────────────────

    private class Hooks {
        val peerSends = mutableListOf<Pair<String, List<Uri>>>()
        var transfersOpens = 0
    }

    private fun tempFile(name: String, bytes: Int = 2048): Uri {
        val dir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "pdsend")
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(ByteArray(bytes) { ('a' + (it % 26)).code.toByte() })
        return Uri.fromFile(f)
    }

    private fun show(
        initialFiles: List<Uri> = emptyList(),
        peers: List<PeerDevice> = emptyList(),
        paired: List<PairedDevice> = emptyList(),
        hooks: Hooks = Hooks()
    ) {
        var selection by mutableStateOf(initialFiles)
        compose.setContent {
            PulseDropTheme {
                SendScreen(
                    selectedFiles = selection,
                    onSelectedFilesChange = { selection = it },
                    nearbyPeers = peers,
                    connectedPeer = null,
                    pairedDevices = paired,
                    scanning = false,
                    onSendToPeer = { p, uris, _ -> hooks.peerSends.add(p.name to uris) },
                    onSendToBrowser = { _, _ -> },
                    onOpenDevices = {},
                    onOpenTransfers = { hooks.transfersOpens++ }
                )
            }
        }
    }

    private fun scrollTo(tag: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))

    private fun scrollToText(text: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    @Test
    fun emptyPageAsksForFilesAndRefusesToSend() {
        show()
        scrollTo("send_browse_btn")
        compose.onNodeWithTag("send_browse_btn").assertIsDisplayed()
        scrollTo("send_review_btn")
        compose.onNodeWithTag("send_review_btn").assertIsNotEnabled()
        compose.onNodeWithText("Choose at least one file to send.", substring = true).assertExists()
    }

    @Test
    fun selectedFilesShowNameAndRealSizeAndCanBeRemoved() {
        val a = tempFile("notes.txt", 2048)
        val b = tempFile("photo.jpg", 4096)
        show(initialFiles = listOf(a, b))
        compose.waitForIdle()
        scrollTo("send_file_row_notes.txt")
        compose.onNodeWithText("2.0 KB").assertIsDisplayed()
        scrollTo("send_file_row_photo.jpg")
        compose.onNodeWithText("4.0 KB").assertIsDisplayed()
        compose.onNodeWithTag("send_remove_photo.jpg").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("send_file_row_photo.jpg").assertDoesNotExist()
    }

    @Test
    fun reviewThenSendReachesTheEngineOnceAndHandsOffToTransfers() {
        val hooks = Hooks()
        val file = tempFile("report.pdf", 1024)
        val alpha = peer("p1", "Alpha", "10.0.0.5")
        show(initialFiles = listOf(file), peers = listOf(alpha), hooks = hooks)
        compose.waitForIdle()
        scrollTo("send_recipient_p1")
        compose.onNodeWithTag("send_recipient_p1").performClick()
        scrollTo("send_review_btn")
        compose.onNodeWithTag("send_review_btn").assertIsEnabled().performClick()
        compose.onNodeWithText("Send 1 file(s) to Alpha?").assertIsDisplayed()
        // The unpaired truth is in the review, not hidden behind the button.
        compose.onNodeWithTag("send_review_channel")
            .onChildren().onFirst()
            .assert(hasText("Not paired — unencrypted", substring = true))
        compose.onNodeWithTag("send_confirm_btn").performClick()
        assertEquals(listOf("Alpha" to listOf(file)), hooks.peerSends)
        assertEquals(1, hooks.transfersOpens)
        compose.waitForIdle()
        compose.onNodeWithTag("send_file_row_report.pdf").assertDoesNotExist()
    }

    @Test
    fun clearingTheSelectionAsksFirst() {
        show(initialFiles = listOf(tempFile("keepme.txt")))
        compose.waitForIdle()
        scrollTo("send_clear_btn")
        compose.onNodeWithTag("send_clear_btn").performClick()
        compose.onNodeWithText("Clear the whole selection?").assertIsDisplayed()
        compose.onNodeWithText("Keep them").performClick()
        scrollTo("send_file_row_keepme.txt")
        compose.onNodeWithTag("send_file_row_keepme.txt").assertIsDisplayed()
        scrollTo("send_clear_btn")
        compose.onNodeWithTag("send_clear_btn").performClick()
        compose.onNodeWithTag("send_clear_confirm_btn").performClick()
        compose.waitForIdle()
        scrollToText("Choose the files to send")
        compose.onNodeWithTag("send_file_row_keepme.txt").assertDoesNotExist()
    }

    @Test
    fun offlinePairedDeviceIsShownButCannotBeChosen() {
        val hooks = Hooks()
        val paired = PairedDevice("pid1", "Laptop", "cHVi", "10.0.0.9", 8080)
        show(paired = listOf(paired), hooks = hooks)
        scrollTo("send_recipient_offline_pid1")
        compose.onNodeWithText("Offline — not found this scan").assertIsDisplayed()
        // A row you cannot choose announces itself as such — the contract, not a thrown click.
        compose.onNodeWithTag("send_recipient_offline_pid1").assertIsNotEnabled()
        scrollTo("send_review_btn")
        compose.onNodeWithTag("send_review_btn").assertIsNotEnabled()
        assertTrue(hooks.peerSends.isEmpty())
    }

    @Test
    fun aDiscoveryBlipDoesNotUndoTheChoiceOrSnatchTheReviewDialog() {
        val hooks = Hooks()
        val file = tempFile("blip.txt", 512)
        var selection by mutableStateOf(listOf(file))
        var peers by mutableStateOf(listOf(peer("p1", "Alpha", "10.0.0.5")))
        compose.setContent {
            PulseDropTheme {
                SendScreen(
                    selectedFiles = selection,
                    onSelectedFilesChange = { selection = it },
                    nearbyPeers = peers,
                    connectedPeer = null,
                    pairedDevices = emptyList(),
                    scanning = false,
                    onSendToPeer = { p, uris, _ -> hooks.peerSends.add(p.name to uris) },
                    onSendToBrowser = { _, _ -> },
                    onOpenDevices = {},
                    onOpenTransfers = { hooks.transfersOpens++ }
                )
            }
        }
        compose.waitForIdle()
        scrollTo("send_recipient_p1")
        compose.onNodeWithTag("send_recipient_p1").performClick()
        scrollTo("send_review_btn")
        compose.onNodeWithTag("send_review_btn").assertIsEnabled().performClick()
        compose.onNodeWithText("Send 1 file(s) to Alpha?").assertIsDisplayed()
        // The radar loses Alpha for a cycle — the choice and the open dialog must survive it.
        peers = emptyList()
        compose.waitForIdle()
        compose.onNodeWithText("Send 1 file(s) to Alpha?").assertIsDisplayed()
        compose.onNodeWithTag("send_confirm_btn").performClick()
        assertEquals(listOf("Alpha" to listOf(file)), hooks.peerSends)
        assertEquals(1, hooks.transfersOpens)
    }
}
