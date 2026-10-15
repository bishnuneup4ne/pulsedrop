package com.pulsedrop.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind "check for an update without being asked". A callback that fires on every network
 * event is a battery and data plan problem; a callback that never fires again is the reason the
 * feature exists. These are the lines between those two failures, and they are the only part of the
 * watcher that can be proven without a network.
 */
class UpdateWatchTest {

    private val spacing = UpdateWatch.ATTEMPT_SPACING_MS
    private val refresh = UpdateWatch.RECHECK_AFTER_SUCCESS_MS

    private fun allowed(
        now: Long,
        lastAttempt: Long = -1,
        lastSuccess: Long = -1,
        knows: Boolean = false
    ) = shouldAutoCheck(now, lastAttempt, lastSuccess, knows)

    @Test
    fun `the very first event is never blocked by a timestamp it does not have yet`() {
        // A phone rebooted and opened inside the spacing window still has to be able to check.
        assertTrue(allowed(now = 1_000L))
    }

    @Test
    fun `a flapping network produces one check, not one per flap`() {
        val t = 60_000L
        assertTrue(allowed(now = t))
        assertFalse("two events a second apart are one network coming up", allowed(now = t + 1_000L, lastAttempt = t))
        assertTrue(allowed(now = t + spacing, lastAttempt = t))
    }

    @Test
    fun `a check that never reached github does not earn a rest`() {
        // This is the whole point: open with no internet, the failure must not mute the reconnect.
        val t = 60_000L
        assertTrue(allowed(now = t, lastSuccess = -1L))
        assertTrue(allowed(now = t + spacing + 1L, lastAttempt = t, lastSuccess = -1L))
    }

    @Test
    fun `a check that did reach github rests until it is worth another`() {
        val t = 60_000L
        assertFalse(allowed(now = t + refresh - 1L, lastAttempt = t, lastSuccess = t))
        assertTrue(allowed(now = t + refresh, lastAttempt = t, lastSuccess = t))
    }

    @Test
    fun `an announcement waits the same spacing, and nothing more`() {
        // The watcher's other job: telling the install counter about this device. That work settles
        // itself once it succeeds, so the only rule it needs is not to fire once per flap.
        val t = 60_000L
        assertTrue("a rebooted phone has no lastConnected to wait for", shouldAnnounce(t, -1L))
        assertFalse(shouldAnnounce(t + 1_000L, t))
        assertTrue(shouldAnnounce(t + spacing, t))
    }

    @Test
    fun `a known update is not re-announced on every network change`() {
        val t = 60_000L
        assertFalse(allowed(now = t + refresh, lastAttempt = t, lastSuccess = t, knows = true))
        // The user dismissed it for this version, so the watcher's own answer says "already know".
        assertTrue(allowed(now = t + refresh, lastAttempt = t, lastSuccess = t, knows = false))
    }

    @Test
    fun `spacing is checked before the rest so a burst cannot queue up`() {
        val t = 60_000L
        assertFalse(allowed(now = t + 2_000L, lastAttempt = t, lastSuccess = -1L))
    }
}
