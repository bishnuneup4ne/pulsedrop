package com.pulsedrop.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallLengthTest {

    /** The clock a call screen and its end-of-call banner both show, so both are pinned here. */
    @Test
    fun underAnHourIsMinutesAndSeconds() {
        assertEquals("0:00", formatCallLength(0))
        assertEquals("0:07", formatCallLength(7))
        assertEquals("1:05", formatCallLength(65))
        assertEquals("59:59", formatCallLength(3599))
    }

    @Test
    fun pastAnHourGainsAnHourField() {
        assertEquals("1:00:00", formatCallLength(3600))
        assertEquals("1:07:04", formatCallLength(4024))
        assertEquals("2:00:09", formatCallLength(7209))
    }
}
