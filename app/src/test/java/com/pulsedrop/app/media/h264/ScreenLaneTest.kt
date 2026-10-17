package com.pulsedrop.app.media.h264

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each condition that sends a share back to JPEG is a thing that says no without complaining: a peer
 * that skips unknown frame types, a hardware block that encodes below realtime, a grid that has no
 * surface to decode into. So the test is not "does the good case work" — it is that each refusal
 * fires on its own, with everything else held at the good case.
 */
class ScreenLaneTest {

    private fun lane(
        decode: Boolean = true,
        encoder: Boolean = true,
        conference: Boolean = false,
        width: Int = 1920,
        height: Int = 1080
    ) = ScreenLane.choose(decode, encoder, conference, width, height)

    @Test
    fun theEncodedLaneNeedsAllFourConditionsAtOnce() {
        assertEquals(ScreenLane.Lane.ENCODED, lane())
        assertEquals(ScreenLane.Lane.ENCODED, lane(width = 1280, height = 720))
    }

    @Test
    fun aPeerThatNeverPromisedToDecodeGetsJpeg() {
        // The peer skips type 11 silently and keeps believing the share is running; nothing else on
        // either side ever notices.
        assertEquals(ScreenLane.Lane.JPEG, lane(decode = false))
    }

    @Test
    fun aSoftwareEncoderKeepsTheJpegLane() {
        assertEquals(ScreenLane.Lane.JPEG, lane(encoder = false))
    }

    @Test
    fun aConferenceIsAlwaysJpeg() {
        // The bridge relays without transcoding and the grid draws Bitmaps, so there is no surface
        // for a second chain to decode onto.
        assertEquals(ScreenLane.Lane.JPEG, lane(conference = true))
    }

    @Test
    fun missingGeometryIsRefusedRatherThanGuessedAt() {
        assertEquals(ScreenLane.Lane.JPEG, lane(width = 0, height = 1080))
        assertEquals(ScreenLane.Lane.JPEG, lane(width = 1920, height = 0))
        assertEquals(ScreenLane.Lane.JPEG, lane(width = -1920, height = -1080))
    }

    /**
     * 4:2:0 has nowhere to put a luma dimension of one odd pixel, and a codec configured with an odd
     * size either fails to start or crops silently mid-share. A display scaled to an odd width is a
     * real outcome, since the scale is a ratio of two arbitrary resolutions.
     */
    @Test
    fun oddDimensionsFallBackToJpeg() {
        assertEquals(ScreenLane.Lane.JPEG, lane(width = 1919, height = 1080))
        assertEquals(ScreenLane.Lane.JPEG, lane(width = 1920, height = 1079))
        assertEquals(ScreenLane.Lane.JPEG, lane(width = 1919, height = 1079))
    }

    @Test
    fun everyRefusalIsIndependentOfTheOthers() {
        val good = lane()
        listOf(
            lane(decode = false),
            lane(encoder = false),
            lane(conference = true),
            lane(width = 1081)
        ).forEach { refused ->
            assertEquals(ScreenLane.Lane.JPEG, refused)
        }
        assertEquals(ScreenLane.Lane.ENCODED, good)
    }
}
