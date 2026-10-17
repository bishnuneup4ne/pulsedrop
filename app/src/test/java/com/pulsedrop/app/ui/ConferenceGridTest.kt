package com.pulsedrop.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.pulsedrop.app.call.CallParticipant
import com.pulsedrop.app.call.CallState
import com.pulsedrop.app.ui.screens.CallOverlay
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A group call used to draw its grid as a full-screen layer and then paint the header over it, so
 * "Group call" landed on the first row of tiles and a five-device roster wrapped down the middle of
 * the picture. The grid also stopped at a height picked in advance, which left the bottom half of
 * the phone empty. These tests measure the layout rather than the pixels, because every one of those
 * three failures is a bounds problem that reads as "the UI is broken" long before it reads as one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConferenceGridTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: CallState) {
        compose.setContent {
            PulseDropTheme {
                CallOverlay(state = state, remoteFrame = null, remoteFrames = emptyMap(),
                    onToggleMute = {}, onToggleCamera = {}, onToggleScreenShare = {},
                    onHangUp = {}, modifier = Modifier)
            }
        }
    }

    private fun conference(seats: List<String>, selfPid: String) = CallState(
        active = true, connected = true, video = true, cameraOn = false,
        conference = true, isHost = true, status = "Group call",
        participants = seats.map { CallParticipant(it, "Device $it", true) },
        selfPid = selfPid
    )

    @Test
    fun `the header stays above the grid instead of on top of it`() {
        show(conference(listOf("host", "p1", "p2"), selfPid = "host"))
        val header = compose.onNodeWithText("Group call").getBoundsInRoot()
        val grid = compose.onNodeWithTag("conference_grid").getBoundsInRoot()
        compose.onNodeWithTag("conference_grid").assertIsDisplayed()
        assertTrue(
            "the header sits over the tiles: header bottom ${header.bottom}, grid top ${grid.top}",
            header.bottom.value <= grid.top.value
        )
    }

    @Test
    fun `the grid fills the room between the header and the dock`() {
        show(conference(listOf("host", "p1", "p2"), selfPid = "host"))
        val grid = compose.onNodeWithTag("conference_grid").getBoundsInRoot()
        val dock = compose.onNodeWithTag("call_end_btn").getBoundsInRoot()
        assertTrue(
            "the grid stops short of the dock instead of filling it: grid bottom ${grid.bottom}, " +
                "dock top ${dock.top}",
            dock.top.value - grid.bottom.value < 20f
        )
    }

    @Test
    fun `this device's own seat is labelled as itself`() {
        show(conference(listOf("host", "p1", "p2"), selfPid = "host"))
        compose.onNodeWithTag("conf_tile_host").assertIsDisplayed()
        // The camera never comes back over the wire, so the local tile is the one seat that cannot
        // be identified by an arriving frame.
        compose.onNodeWithText("You").assertIsDisplayed()
    }

    @Test
    fun `the roster counts seats instead of spelling out every name`() {
        show(conference(listOf("host", "p1", "p2", "p3", "p4", "p5"), selfPid = "host"))
        compose.onNodeWithText("6 of 6 joined").assertIsDisplayed()
    }
}
