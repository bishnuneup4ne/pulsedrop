package com.pulsedrop.app.media.h264

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The pool is what keeps a camera buffer and an encoder buffer from being the same bytes at the same
 * time, so the property that matters is ownership: no two callers ever hold one array.
 */
class Nv21PoolTest {

    private val frameSize = 64 * 48 * 3 / 2

    @Test
    fun everyAcquireIsADistinctBuffer() {
        val pool = Nv21Pool(capacity = 3)
        val first = pool.acquire(frameSize)!!
        val second = pool.acquire(frameSize)!!
        assertNotSame(first, second)
        assertEquals(2, pool.outstanding())
    }

    @Test
    fun aReleasedBufferComesBackOutAgain() {
        val pool = Nv21Pool(capacity = 2)
        val first = pool.acquire(frameSize)!!
        first[0] = 7
        pool.release(first)
        assertEquals(0, pool.outstanding())
        val again = pool.acquire(frameSize)!!
        assertSame("the point of a pool is not allocating again", first, again)
        assertEquals(7.toByte(), again[0])
    }

    /**
     * A frame bigger than anything held must not be squeezed into a small slot: writing past the end of
     * the buffer the encoder was handed is the corruption this whole lane exists to avoid.
     */
    @Test
    fun aBiggerFrameWaitsForABiggerSlotOrIsNotClaimed() {
        val pool = Nv21Pool(capacity = 1)
        val small = pool.acquire(100)!!
        pool.release(small)
        assertNull(pool.acquire(1_000))
        // A slot sized for the big frame is then reusable by a small one.
        val pool2 = Nv21Pool(capacity = 1)
        val big = pool2.acquire(1_000)!!
        pool2.release(big)
        assertEquals(1_000, pool2.acquire(100)!!.size)
    }

    @Test
    fun capacityIsAHardCeilingOnConcurrentOwnership() {
        val pool = Nv21Pool(capacity = 2)
        val a = pool.acquire(frameSize)
        val b = pool.acquire(frameSize)
        val c = pool.acquire(frameSize)
        assertNotNull(a)
        assertNotNull(b)
        assertNull("a third frame while two are outstanding is a drop, not a fifth buffer", c)
    }

    /** A stray second release of the same array must not put those bytes in two frames at once. */
    @Test
    fun releasingTwiceDoesNotHandTheSameBufferToTwoFrames() {
        val pool = Nv21Pool(capacity = 2)
        val buf = pool.acquire(frameSize)!!
        pool.release(buf)
        pool.release(buf)
        val a = pool.acquire(frameSize)!!
        val b = pool.acquire(frameSize)!!
        assertSame(buf, a)
        assertNotSame("one array, two owners", a, b)
        assertEquals(2, pool.outstanding())
    }

    @Test
    fun clearForgetsEverythingAndLetsTheNextCallSizeUpAgain() {
        val pool = Nv21Pool(capacity = 2)
        pool.acquire(frameSize)
        pool.clear()
        assertEquals(0, pool.outstanding())
        assertEquals(frameSize, pool.acquire(frameSize)!!.size)
    }

    /** Zero and negative asks are nonsense from a camera callback, and must not hand out a buffer. */
    @Test
    fun aFrameWithNoSizeIsNotAClaim() {
        val pool = Nv21Pool(capacity = 2)
        assertNull(pool.acquire(0))
        assertNull(pool.acquire(-64))
        assertEquals(0, pool.outstanding())
    }
}
