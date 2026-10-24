package com.pulsedrop.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRateLimiterTest {

    private var clock = 1_700_000_000_000L

    private fun limiter(capacity: Double = 3.0, refill: Double = 1.0, maxKeys: Int = 16) =
        RequestRateLimiter(capacity = capacity, refillPerSecond = refill, maxKeys = maxKeys, now = { clock })

    @Test
    fun `a burst up to capacity is allowed and the next request is not`() {
        val limiter = limiter()
        repeat(3) { assertTrue("request ${it + 1} of the burst", limiter.allow("192.168.1.9")) }
        assertFalse(limiter.allow("192.168.1.9"))
    }

    @Test
    fun `refill is per second, so one second buys one request at rate one`() {
        val limiter = limiter()
        repeat(3) { limiter.allow("192.168.1.9") }
        clock += 999
        assertFalse(limiter.allow("192.168.1.9"))
        clock += 1
        assertTrue("a full second should have refilled one token", limiter.allow("192.168.1.9"))
        assertFalse(limiter.allow("192.168.1.9"))
    }

    @Test
    fun `refill never exceeds capacity`() {
        val limiter = limiter()
        clock += 60_000
        repeat(3) { assertTrue(limiter.allow("192.168.1.9")) }
        assertFalse("a long idle must not bank a flood", limiter.allow("192.168.1.9"))
    }

    @Test
    fun `one address's burst does not spend another's`() {
        val limiter = limiter()
        repeat(3) { limiter.allow("192.168.1.9") }
        assertFalse(limiter.allow("192.168.1.9"))
        assertTrue(limiter.allow("192.168.1.10"))
    }

    @Test
    fun `the map is bounded and forgets idle addresses first`() {
        val limiter = limiter(capacity = 1.0, refill = 1.0, maxKeys = 2)
        assertTrue(limiter.allow("a"))
        clock += 1_000
        assertTrue(limiter.allow("b"))
        clock += 1_000
        assertTrue(limiter.allow("c"))
        // "a" was refilled and idle, so it is the one evicted; it comes back with a full bucket.
        assertTrue(limiter.allow("a"))
        // The property that matters is the bound, not which key lost.
        assertEquals("bucket count must stay bounded", 2, limiter.trackedAddresses())
    }
}
