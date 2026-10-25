package com.pulsedrop.app.media.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLimitsTest {

    @Test
    fun audioFramesAreBoundedTighterThanVideo() {
        assertTrue(MediaLimits.frameAllowed(TYPE_AUDIO, MediaLimits.MAX_AUDIO_FRAME_BYTES))
        assertFalse(MediaLimits.frameAllowed(TYPE_AUDIO, MediaLimits.MAX_AUDIO_FRAME_BYTES + 1))
        assertTrue(MediaLimits.frameAllowed(TYPE_CAMERA, MediaLimits.MAX_FRAME_BYTES))
        assertFalse(MediaLimits.frameAllowed(TYPE_CAMERA, MediaLimits.MAX_FRAME_BYTES + 1))
        assertTrue(MediaLimits.frameAllowed(TYPE_CONF_AUDIO, MediaLimits.MAX_AUDIO_FRAME_BYTES))
        assertFalse(MediaLimits.frameAllowed(TYPE_CONF_VIDEO, MediaLimits.MAX_FRAME_BYTES + 1))
    }

    @Test
    fun controlFramesAreSmall() {
        assertTrue(MediaLimits.frameAllowed(TYPE_CONTROL, MediaLimits.MAX_CONTROL_FRAME_BYTES))
        assertFalse(MediaLimits.frameAllowed(TYPE_CONTROL, MediaLimits.MAX_CONTROL_FRAME_BYTES + 1))
        assertFalse(MediaLimits.frameAllowed(TYPE_SCREEN_STATE, MediaLimits.MAX_CONTROL_FRAME_BYTES + 1))
        assertFalse(MediaLimits.frameAllowed(TYPE_CONF_STATE, MediaLimits.MAX_CONTROL_FRAME_BYTES + 1))
    }

    @Test
    fun jpegPixelsNotBytesDecideTheCap() {
        assertTrue(MediaLimits.jpegAllowed(1600, 900))
        // A square screen at the 1920 px capture edge is the largest real frame this app makes.
        assertTrue(MediaLimits.jpegAllowed(1920, 1920))
        assertFalse(MediaLimits.jpegAllowed(2000, 2001))
        assertFalse(MediaLimits.jpegAllowed(0, 100))
        assertFalse(MediaLimits.jpegAllowed(100, 0))
        assertFalse(MediaLimits.jpegAllowed(-1, 100))
    }
}
