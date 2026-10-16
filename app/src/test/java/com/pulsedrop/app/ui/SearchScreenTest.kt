package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.ClipMessage
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.PeerDevice
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.components.PulseSection
import com.pulsedrop.app.ui.screens.SearchCategory
import com.pulsedrop.app.ui.screens.SearchScreen
import com.pulsedrop.app.ui.screens.categoryTarget
import com.pulsedrop.app.ui.screens.containsQuery
import com.pulsedrop.app.ui.screens.searchCounts
import com.pulsedrop.app.ui.screens.searchDevices
import com.pulsedrop.app.ui.screens.searchFiles
import com.pulsedrop.app.ui.screens.searchMessages
import com.pulsedrop.app.ui.screens.searchTransfers
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search may only speak about what the app stores. These tests pin the four real sources —
 * the transfer log, the received-file records derived from it, the device lists, and this
 * session's chat — and pin that nothing else is claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun history(
        id: String = "h1",
        name: String = "photo.jpg",
        direction: String = "INCOMING",
        status: String = "COMPLETED",
        sender: String = "my-phone",
        path: String? = "/nonexistent/received/$name",
        size: Long = 2048,
        ts: Long = 1000L
    ) = TransferHistoryEntity(
        id = id, fileName = name, fileSize = size, direction = direction, status = status,
        remoteDevice = sender, clientIp = "10.0.0.5", localFilePath = path,
        mimeType = null, timestamp = ts, errorMessage = null
    )

    private fun active(
        id: String = "a1",
        name: String = "report.pdf",
        direction: TransferDirection = TransferDirection.OUTGOING
    ) = TransferItem(
        id = id, fileName = name, fileSize = 100, bytesTransferred = 40,
        speedBytesPerSec = 10, direction = direction, status = TransferStatus.IN_PROGRESS,
        progress = 0.4f, remoteDevice = "study-laptop"
    )

    private fun peer() = PeerDevice(id = "p1", name = "Kitchen Tab", ip = "192.168.1.9", port = 8080, requiresPin = false)

    private fun paired() = PairedDevice(
        pid = "pid-77", name = "Pixel Buddy", pubB64 = "AAA", originIp = "192.168.1.30", originPort = 8080
    )

    private fun msg(
        id: String = "m1",
        text: String = "hello there",
        sender: String = "me",
        deleted: Boolean = false
    ) = ClipMessage(id = id, text = text, sender = sender, deleted = deleted)

    // ── pure functions ──────────────────────────────────────────────────────

    @Test
    fun containsQueryIsCaseInsensitiveAndNullSafe() {
        assertTrue(containsQuery(listOf("Report.PDF", null), "report"))
        assertTrue(containsQuery(listOf("anything"), "  "))
        assertFalse(containsQuery(listOf("photo"), "zip"))
    }

    @Test
    fun transfersSearchTheLogAndTheLiveRowsAndSayWhichIsWhich() {
        val hits = searchTransfers(listOf(active()), listOf(history()), "report")
        assertEquals(1, hits.size)
        assertTrue(hits[0].subtitle.startsWith("Sent to study-laptop"))
        assertTrue(hits[0].subtitle.endsWith("in progress"))

        val done = searchTransfers(emptyList(), listOf(history()), "photo")
        assertEquals(1, done.size)
        assertEquals("Received from my-phone · completed", done[0].subtitle)
    }

    @Test
    fun filesSearchOnlySeesCompletedIncomingRowsThatHaveAPath() {
        val rows = listOf(
            history(id = "ok"),
            history(id = "fail", status = "FAILED"),
            history(id = "out", direction = "OUTGOING"),
            history(id = "nopath", path = null)
        )
        val hits = searchFiles(rows, "photo")
        assertEquals(listOf("f:ok"), hits.map { it.key })
        // The recorded path does not exist in this test, and the row must say so, not hide it.
        assertTrue(hits[0].subtitle.endsWith("missing from disk"))
    }

    @Test
    fun devicesSearchCoversNearbyAndTrustedWithTheirRealFields() {
        val byIp = searchDevices(listOf(peer()), listOf(paired()), "192.168.1.9")
        assertEquals(listOf("n:p1"), byIp.map { it.key })
        val byPid = searchDevices(listOf(peer()), listOf(paired()), "pid-77")
        assertEquals(listOf("p:pid-77"), byPid.map { it.key })
        assertEquals("Trusted · 192.168.1.30", byPid[0].subtitle)
    }

    @Test
    fun messagesSearchSkipsWhatTheUserDeletedOnPurpose() {
        val hits = searchMessages(listOf(msg(), msg(id = "m2", deleted = true)), "hello")
        assertEquals(listOf("m:m1"), hits.map { it.key })
        assertEquals("hello there", hits[0].subtitle)
    }

    @Test
    fun countsAreTheRealSizesOfTheFourSearches() {
        val counts = searchCounts(
            active = listOf(active()), history = listOf(history()),
            nearby = listOf(peer()), paired = listOf(paired()),
            messages = listOf(msg()), query = ""
        )
        assertEquals(2, counts[SearchCategory.TRANSFERS])
        assertEquals(1, counts[SearchCategory.FILES])
        assertEquals(2, counts[SearchCategory.DEVICES])
        assertEquals(1, counts[SearchCategory.MESSAGES])
    }

    @Test
    fun eachCategoryOpensThePageThatActuallyOwnsIt() {
        assertEquals(PulseSection.TRANSFERS, categoryTarget(SearchCategory.TRANSFERS))
        assertEquals(PulseSection.MY_FILES, categoryTarget(SearchCategory.FILES))
        assertEquals(PulseSection.DEVICES, categoryTarget(SearchCategory.DEVICES))
        assertEquals(PulseSection.MESSAGES, categoryTarget(SearchCategory.MESSAGES))
    }

    // ── the page ────────────────────────────────────────────────────────────

    private class Hooks {
        val opened = mutableListOf<PulseSection>()
    }

    private fun screen(hooks: Hooks = Hooks()) {
        compose.setContent {
            PulseDropTheme {
                SearchScreen(
                    activeTransfers = listOf(active()),
                    history = listOf(history()),
                    nearbyPeers = listOf(peer()),
                    pairedDevices = listOf(paired()),
                    messages = listOf(msg()),
                    onOpenSection = { hooks.opened += it }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun type(query: String) {
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    @Test
    fun beforeTypingThePageExplainsItsRealReachInsteadOfShowingEverything() {
        screen()
        scrollTo("search_guide")
        compose.onNodeWithTag("search_guide").assertIsDisplayed()
        // The limitations paragraph is taller than the fold on a small test window; it must
        // exist and be honest, not be fully on screen.
        compose.onNodeWithText("What it cannot: text inside a file", substring = true).assertExists()
    }

    @Test
    fun aMatchingQueryListsTheRealHitsUnderTheirGroups() {
        screen()
        type("photo")
        scrollTo("search_group_transfers")
        compose.onNodeWithTag("search_group_transfers").assertIsDisplayed()
        scrollTo("search_group_files")
        compose.onNodeWithTag("search_group_files").assertIsDisplayed()
        // The same row is legitimately one transfer and one received file — two honest hits.
        compose.onAllNodesWithText("photo.jpg").assertCountEquals(2)
    }

    @Test
    fun aNonsenseQuerySaysNothingMatchesWithoutInventingResults() {
        screen()
        type("zzz-nothing-here-zzz")
        scrollTo("search_noresults")
        compose.onNodeWithTag("search_noresults").assertIsDisplayed()
        compose.onNodeWithText("Nothing matches “zzz-nothing-here-zzz”.").assertIsDisplayed()
    }

    @Test
    fun aCategoryChipNarrowsToJustThatSource() {
        screen()
        // "e" hits transfers (report.pdf), devices (Kitchen Tab, Pixel Buddy) and messages.
        type("e")
        scrollTo("search_chip_devices")
        compose.onNodeWithTag("search_chip_devices").performClick()
        compose.waitForIdle()
        scrollTo("search_group_devices")
        compose.onNodeWithTag("search_group_devices").assertIsDisplayed()
        compose.onNodeWithTag("search_group_transfers").assertDoesNotExist()
        compose.onNodeWithTag("search_group_files").assertDoesNotExist()
    }

    @Test
    fun tappingAResultOpensThePageThatOwnsIt() {
        val hooks = Hooks()
        screen(hooks)
        type("Kitchen")
        scrollTo("search_hit_devices_0")
        compose.onNodeWithTag("search_hit_devices_0").performClick()
        assertEquals(listOf(PulseSection.DEVICES), hooks.opened)
    }
}
