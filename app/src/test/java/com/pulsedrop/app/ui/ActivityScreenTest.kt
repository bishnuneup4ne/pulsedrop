package com.pulsedrop.app.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.pulsedrop.app.data.TransferHistoryEntity
import com.pulsedrop.app.model.ChatGroup
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.model.TransferDirection
import com.pulsedrop.app.model.TransferItem
import com.pulsedrop.app.model.TransferStatus
import com.pulsedrop.app.ui.screens.ActivityCategory
import com.pulsedrop.app.ui.screens.ActivityOutcome
import com.pulsedrop.app.ui.screens.ActivityScreen
import com.pulsedrop.app.ui.screens.activityPeerLabel
import com.pulsedrop.app.ui.screens.buildActivityItems
import com.pulsedrop.app.ui.screens.groupByDay
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Activity is only trustworthy if every row is traceable to a real store. These tests pin the
 * mapping from those stores, the dedupe between live and durable rows, and the page's promise
 * that it never shows more than what this phone keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private class Hooks {
        val details = mutableListOf<String>()
        var devices = 0
    }

    private fun screen(
        hooks: Hooks,
        history: List<TransferHistoryEntity> = emptyList(),
        live: List<TransferItem> = emptyList(),
        paired: List<PairedDevice> = emptyList(),
        groups: List<ChatGroup> = emptyList()
    ) {
        compose.setContent {
            PulseDropTheme {
                ActivityScreen(
                    history = history,
                    activeTransfers = live,
                    pairedDevices = paired,
                    groups = groups,
                    onOpenTransferDetails = { hooks.details += it },
                    onOpenDevices = { hooks.devices++ }
                )
            }
        }
    }

    private fun row(
        id: String,
        name: String = "photo.jpg",
        status: String = "COMPLETED",
        direction: String = "INCOMING",
        sender: String = "Client (10.0.0.5)",
        error: String? = null,
        ts: Long = now
    ) = TransferHistoryEntity(
        id = id, fileName = name, fileSize = 2048, direction = direction, status = status,
        remoteDevice = sender, clientIp = "10.0.0.5", localFilePath = null,
        mimeType = null, timestamp = ts, errorMessage = error
    )

    private fun live(
        id: String,
        status: TransferStatus = TransferStatus.IN_PROGRESS,
        direction: TransferDirection = TransferDirection.OUTGOING
    ) = TransferItem(
        id = id, fileName = "clip.mp4", fileSize = 1000, bytesTransferred = 400,
        speedBytesPerSec = 100, direction = direction, status = status, progress = 0.4f,
        remoteDevice = "Paired • Ana", clientIp = "", timestamp = now
    )

    private fun paired(pid: String = "p1", name: String = "Ana", verified: Boolean = true) =
        PairedDevice(
            pid = pid, name = name, pubB64 = "AA", originIp = "10.0.0.9", originPort = 8080,
            createdAt = now - 3 * day, verified = verified
        )

    private fun scrollTo(tag: String) {
        compose.onNode(hasTestTag("activity_list") and hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    // ---- Pure mapping: every row must trace to a real store ----

    @Test
    fun durableRowsBecomeSuccessAndFailureWithTheirRealReason() {
        val items = buildActivityItems(
            history = listOf(
                row("a", name = "notes.txt"),
                row("b", status = "FAILED", direction = "OUTGOING", error = "Peer hung up")
            ),
            activeTransfers = emptyList(), pairedDevices = emptyList(), groups = emptyList()
        )
        val received = items.first { it.id == "transfer:a" }
        assertEquals(ActivityOutcome.SUCCESS, received.outcome)
        assertEquals("Received notes.txt", received.title)
        assertEquals("Browser • 2.0 KB", received.detail)

        val failed = items.first { it.id == "transfer:b" }
        assertEquals(ActivityOutcome.FAILURE, failed.outcome)
        assertEquals("Transfer failed", failed.title)
        assertTrue(failed.detail.contains("Peer hung up"))
    }

    @Test
    fun aLiveRowAlreadyInTheDurableLogIsNeverShownTwice() {
        val items = buildActivityItems(
            history = listOf(row("dup", name = "x.txt")),
            activeTransfers = listOf(live("dup", status = TransferStatus.COMPLETED)),
            pairedDevices = emptyList(), groups = emptyList()
        )
        assertEquals(listOf("transfer:dup"), items.map { it.id })
    }

    @Test
    fun aLiveInProgressRowIsMarkedSessionOnlyAndCancelledStaysCancelled() {
        val items = buildActivityItems(
            history = emptyList(),
            activeTransfers = listOf(
                live("l1", status = TransferStatus.IN_PROGRESS),
                live("l2", status = TransferStatus.CANCELLED)
            ),
            pairedDevices = emptyList(), groups = emptyList()
        )
        val inProgress = items.first { it.id == "live:l1" }
        assertEquals(ActivityOutcome.LIVE, inProgress.outcome)
        assertTrue(inProgress.sessionOnly)
        assertTrue(inProgress.detail.contains("of "))
        assertEquals(ActivityOutcome.CANCELLED, items.first { it.id == "live:l2" }.outcome)
    }

    @Test
    fun unverifiedPairingIsNamedAsUnverifiedRatherThanTrusted() {
        val items = buildActivityItems(
            history = emptyList(), activeTransfers = emptyList(),
            pairedDevices = listOf(paired(verified = false), paired(pid = "p2", verified = true)),
            groups = emptyList()
        )
        val unverified = items.first { it.id == "pair:p1" }
        assertEquals(ActivityCategory.DEVICE, unverified.category)
        assertTrue(unverified.detail.contains("unverified"))
        assertFalse(unverified.detail.contains("confirmed"))
        assertTrue(items.first { it.id == "pair:p2" }.detail.contains("confirmed"))
    }

    @Test
    fun groupCreationLandsInCommunicationAndEverythingSortsNewestFirst() {
        val items = buildActivityItems(
            history = listOf(row("old", ts = now - 5 * day)),
            activeTransfers = emptyList(),
            pairedDevices = listOf(paired()),
            groups = listOf(
                ChatGroup(id = "g1", name = "Family", memberPids = listOf("p1", "p2"), createdAt = now)
            )
        )
        val group = items.first { it.id == "group:g1" }
        assertEquals(ActivityCategory.COMMUNICATION, group.category)
        assertEquals("Group “Family” created", group.title)
        assertEquals("2 devices", group.detail)
        assertEquals(items.sortedByDescending { it.timestamp }, items)
    }

    @Test
    fun peerLabelsAreCleanedButNeverInvented() {
        assertEquals("Browser", activityPeerLabel("Web Client", null))
        assertEquals("Browser", activityPeerLabel("Client (1.2.3.4)", null))
        assertEquals("Ana", activityPeerLabel("Paired • Ana", null))
        assertEquals("10.0.0.7", activityPeerLabel("", "10.0.0.7"))
        assertEquals("Unknown device", activityPeerLabel("", ""))
        assertEquals("Laptop", activityPeerLabel("Laptop", null))
    }

    @Test
    fun dayGroupingUsesTheLocalCalendarNotRawUtc() {
        fun at(daysAgo: Int) = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }.timeInMillis
        val items = listOf(
            com.pulsedrop.app.ui.screens.ActivityItem(
                id = "t", category = ActivityCategory.TRANSFER, outcome = ActivityOutcome.SUCCESS,
                timestamp = at(0), title = "t", detail = ""
            ),
            com.pulsedrop.app.ui.screens.ActivityItem(
                id = "y", category = ActivityCategory.TRANSFER, outcome = ActivityOutcome.SUCCESS,
                timestamp = at(1), title = "y", detail = ""
            ),
            com.pulsedrop.app.ui.screens.ActivityItem(
                id = "e", category = ActivityCategory.TRANSFER, outcome = ActivityOutcome.SUCCESS,
                timestamp = at(9), title = "e", detail = ""
            )
        )
        val groups = groupByDay(items)
        assertEquals(listOf("Today", "Yesterday", groups[2].first), groups.map { it.first })
        assertFalse(groups[2].first == "Today" || groups[2].first == "Yesterday")
        assertEquals(listOf("e"), groups[2].second.map { it.id })
    }

    // ---- The page itself ----

    @Test
    fun anEmptyStoreSaysNothingIsRecordedWithoutFabricatingRows() {
        screen(Hooks())
        compose.onNodeWithTag("activity_empty").assertExists()
        compose.onNodeWithText("Nothing recorded yet").assertExists()
        compose.onAllNodes(hasTestTag("activity_row_transfer:a")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun rowsRenderWithOutcomeTextNotColorAlone() {
        val hooks = Hooks()
        screen(hooks, history = listOf(row("a"), row("b", status = "FAILED", error = "Peer hung up")))
        scrollTo("activity_row_transfer:b")
        compose.onNodeWithTag("activity_row_transfer:b").assertExists()
        // The icon carries an accessible outcome name.
        compose.onAllNodes(hasContentDescription("Failed")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodes(hasContentDescription("Success")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun categoryAndOutcomeFiltersCombineAndTheSessionMarkerShows() {
        val hooks = Hooks()
        screen(
            hooks,
            history = listOf(row("a"), row("b", status = "FAILED")),
            live = listOf(live("l1")),
            paired = listOf(paired()),
            groups = listOf(ChatGroup("g1", "Family", listOf("p1"), createdAt = now))
        )
        compose.onNodeWithTag("activity_cat_Devices").performClick()
        scrollTo("activity_row_pair:p1")
        compose.onNodeWithTag("activity_row_pair:p1").assertExists()
        compose.onAllNodes(hasTestTag("activity_row_transfer:a")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

        compose.onNodeWithTag("activity_cat_All").performClick()
        compose.onNodeWithTag("activity_out_In progress").performClick()
        scrollTo("activity_row_live:l1")
        compose.onNodeWithTag("activity_session_live:l1", useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasTestTag("activity_row_transfer:b")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun searchMatchesTitleAndDetailFieldsAndNoResultsOffersAReset() {
        val hooks = Hooks()
        screen(hooks, history = listOf(row("a", name = "report.pdf"), row("b", name = "song.mp3")))
        compose.onNode(hasSetTextAction()).performTextInput("report")
        compose.onAllNodes(hasText("Sent report.pdf")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onAllNodes(hasText("Received report.pdf")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("nothing-matches-this")
        compose.onNodeWithTag("activity_no_results").assertExists()
        compose.onNodeWithText("2 recorded events are hidden by the current search and filters.")
            .assertExists()
        // Reset restores both rows.
        compose.onNode(hasText("Reset filters") and hasClickAction()).performClick()
        scrollTo("activity_row_transfer:b")
        compose.onNodeWithTag("activity_row_transfer:b").assertExists()
    }

    @Test
    fun openingARowShowsRealMetadataAndTheTransferLinkCarriesTheDurableId() {
        val hooks = Hooks()
        screen(hooks, history = listOf(row("a", name = "photo.jpg")))
        scrollTo("activity_row_transfer:a")
        compose.onNodeWithTag("activity_row_transfer:a").performClick()
        compose.onNodeWithTag("activity_detail").assertExists()
        compose.onNodeWithText("File transfer").assertExists()
        compose.onNodeWithText("Completed successfully").assertExists()
        compose.onNodeWithTag("activity_detail_open").performClick()
        assertEquals(listOf("a"), hooks.details)
    }

    @Test
    fun aDeviceRowLinksToTheRealDevicesPageNotAnInventedDetail() {
        val hooks = Hooks()
        screen(hooks, paired = listOf(paired()))
        compose.onNodeWithTag("activity_row_pair:p1").performClick()
        compose.onNodeWithTag("activity_detail").assertExists()
        compose.onNodeWithTag("activity_detail_devices").performClick()
        assertEquals(1, hooks.devices)
    }

    @Test
    fun thePageNamesWhatThisPhoneDoesNotKeep() {
        val hooks = Hooks()
        screen(hooks, history = listOf(row("a")))
        scrollTo("activity_disclosure")
        compose.onNodeWithTag("activity_disclosure").assertExists()
        compose.onNodeWithText("What this phone does not keep").assertExists()
        compose.onAllNodes(hasText(
            "Chat messages, call history, pairing attempts and server start/stop " +
                "leave no record after the app exits — they cannot appear here. " +
                "Rows marked “this session” end with it. Transfer records stay until " +
                "you clear them under Transfers; history lives on this phone only " +
                "and is never synced.",
            substring = true
        )).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun aLongUntrustedFileNameStillRendersAndSearchStaysUsable() {
        val hooks = Hooks()
        val nasty = "你好 •".repeat(40) + "report.pdf"
        screen(hooks, history = listOf(row("a", name = nasty)))
        scrollTo("activity_row_transfer:a")
        compose.onNodeWithTag("activity_row_transfer:a").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("你好")
        compose.onAllNodes(hasTestTag("activity_row_transfer:a")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
}
