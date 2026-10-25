package com.pulsedrop.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser leg's downlink, which until now was bounded only by frame count — and a frame is a
 * JPEG the size of whatever was captured, so 96 of them is a budget in name only. These tests hold
 * the two caps to what they say, because the failure mode is invisible until a phone runs out of
 * heap in the middle of a call.
 *
 * A stalled tab is the whole reason the queue exists, so every case is written the way the leg
 * behaves: the phone keeps producing while nothing reads.
 */
class WebOutboxTest {

    private fun frame(size: Int, tag: Int = 0): ByteArray =
        ByteArray(size) { if (it == 0) tag.toByte() else 0 }

    /** The headline claim: bytes bind, not just frames, and the total is exact rather than implied. */
    @Test
    fun `the backlog is bounded in bytes and not only in frames`() {
        val box = WebOutbox(maxFrames = 96, maxBytes = 8 * 1024 * 1024)
        repeat(96) { box.offer(frame(1024 * 1024)) }
        assertTrue(
            "96 one-megabyte frames left " + box.queuedBytes + " bytes queued",
            box.queuedBytes <= 8 * 1024 * 1024
        )
        // Count alone would have allowed all of them: the byte bound had to be what refused.
        assertTrue(box.queuedFrames < 96)
    }

    /** Two caps means two binding limits; the small-frame case must still be the count, not bytes. */
    @Test
    fun `a queue of small frames is cut by the count and not by the byte budget`() {
        val box = WebOutbox(maxFrames = 4, maxBytes = 8 * 1024 * 1024)
        // 100 ms of 16 kHz mono PCM16, the shipped audio frame.
        repeat(6) { box.offer(frame(3200)) }
        assertEquals(4, box.queuedFrames)
        assertEquals(4 * 3200, box.queuedBytes)
    }

    /** What a tab that is behind actually gets: a newer frame, not a longer wait. */
    @Test
    fun `the oldest frame goes first and the newest survives`() {
        val box = WebOutbox(maxFrames = 2, maxBytes = 1024 * 1024)
        box.offer(frame(16, tag = 1))
        box.offer(frame(16, tag = 2))
        box.offer(frame(16, tag = 3))
        assertEquals(2, box.queuedFrames)
        assertEquals(2, box.poll(0)?.get(0)?.toInt())
        assertEquals(3, box.poll(0)?.get(0)?.toInt())
    }

    /** The same, driven by size: one huge frame must not push the whole recent history out. */
    @Test
    fun `a large frame sheds only as many old frames as its size costs`() {
        val box = WebOutbox(maxFrames = 96, maxBytes = 100)
        box.offer(frame(40, tag = 1))
        box.offer(frame(40, tag = 2))
        box.offer(frame(50, tag = 3))
        // 130 bytes over a 100-byte budget costs exactly the one oldest frame, not both older ones.
        assertEquals(90, box.queuedBytes)
        assertEquals(2, box.queuedFrames)
        assertEquals(2, box.poll(0)?.get(0)?.toInt())
        assertEquals(3, box.poll(0)?.get(0)?.toInt())
    }

    /**
     * A single frame bigger than the entire budget is refused rather than allowed to evict every
     * real frame to make room for something nobody could render anyway.
     */
    @Test
    fun `one frame larger than the whole budget is refused and changes nothing`() {
        val box = WebOutbox(maxFrames = 96, maxBytes = 1024)
        box.offer(frame(500, tag = 7))
        assertFalse(box.offer(frame(2000)))
        assertEquals(1, box.queuedFrames)
        assertEquals(500, box.queuedBytes)
        assertEquals(7, box.poll(0)?.get(0)?.toInt())
    }

    @Test
    fun `the byte total follows the frames in and out exactly`() {
        val box = WebOutbox(maxFrames = 8, maxBytes = 1 shl 20)
        val sizes = listOf(1, 4096, 65536, 3200, 3200, 120000)
        sizes.forEach { box.offer(frame(it)) }
        assertEquals(sizes.sum(), box.queuedBytes)
        sizes.forEach { assertEquals(it, box.poll(0)?.size) }
        assertEquals(0, box.queuedBytes)
        assertEquals(0, box.queuedFrames)
    }

    @Test
    fun `clear leaves no frames for whoever replaces the leg`() {
        val box = WebOutbox(maxFrames = 8, maxBytes = 1 shl 20)
        repeat(5) { box.offer(frame(1000)) }
        box.clear()
        assertEquals(0, box.queuedBytes)
        assertEquals(0, box.queuedFrames)
        assertNull(box.poll(0))
    }

    /**
     * The reader blocks with a 2 s timeout in the server's chunked response, so both halves of that
     * have to hold: it must not spin, and it must wake when the frame arrives.
     */
    @Test
    fun `a wait times out and a wait is woken by the frame it waits for`() {
        val box = WebOutbox(maxFrames = 8, maxBytes = 1 shl 20)
        val started = System.nanoTime()
        assertNull(box.poll(120))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("poll(120) returned after $elapsedMs ms", elapsedMs in 100..1500)

        val producer = Thread {
            Thread.sleep(60)
            box.offer(frame(64, tag = 9))
        }
        producer.start()
        val woken = box.poll(2000)
        producer.join(2000)
        assertEquals(9, woken?.get(0)?.toInt())
    }

    /**
     * Producers are the capture and mix threads, the reader is the HTTP one, so the invariant is
     * what a phone needs it to be under real contention: never over budget, never a corrupt total,
     * and never a deadlock that leaves a call hanging.
     */
    @Test
    fun `concurrent producers never leave the queue over its budget`() {
        val box = WebOutbox(maxFrames = 32, maxBytes = 64 * 1024)
        var breached = false
        var read = 0
        val producers = (1..4).map { p ->
            Thread {
                repeat(500) { i -> box.offer(frame(512 + (i % 8) * 512, tag = p)) }
            }
        }
        val reader = Thread {
            while (read < 1200) {
                if (box.queuedBytes > 64 * 1024 || box.queuedFrames > 32) breached = true
                box.poll(1)
                read++
            }
        }
        val all = (producers + reader).onEach { it.start() }
        all.forEach { it.join(20_000) }
        assertTrue("a producer or the reader was still alive", all.none { it.isAlive })
        assertFalse("byte or frame budget was exceeded: ${box.queuedBytes} / ${box.queuedFrames}", breached)
    }

    /** The lane rule itself: a backlog that must give bytes gives up its oldest picture, not its voice. */
    @Test
    fun `video is shed before audio and before call state`() {
        val box = WebOutbox(maxFrames = 96, maxBytes = 180)
        box.offer(frame(40, tag = 1), WebOutbox.Lane.REALTIME)   // a roster snapshot
        box.offer(frame(40, tag = 2), WebOutbox.Lane.VIDEO)
        box.offer(frame(40, tag = 3), WebOutbox.Lane.VIDEO)
        box.offer(frame(40, tag = 4), WebOutbox.Lane.VIDEO)
        box.offer(frame(40, tag = 5), WebOutbox.Lane.REALTIME)   // 100 ms of mixed audio
        assertEquals("four frames, not five, fit the 180-byte budget", 4, box.queuedFrames)
        assertEquals(160, box.queuedBytes)
        val kept = generateSequence { box.poll(0) }.map { it[0].toInt() }.toList()
        // Both realtime frames are still here and exactly one byte's worth of video paid for them.
        assertEquals(listOf(1, 3, 4, 5), kept)
    }

    /**
     * Priority is not a licence to exceed the bound. Once a stalled tab's backlog is only audio —
     * which is what a long silence with a live mic looks like — the oldest audio chunk goes, because
     * a queue that only ever shrinks other people's frames is not a bound.
     */
    @Test
    fun `audio is shed too once there is no video left to pay with`() {
        val box = WebOutbox(maxFrames = 96, maxBytes = 120)
        repeat(4) { i -> box.offer(frame(40, tag = i + 1), WebOutbox.Lane.REALTIME) }
        assertEquals(120, box.queuedBytes)
        assertEquals(3, box.queuedFrames)
        assertEquals(2, box.poll(0)?.get(0)?.toInt())
    }

    /**
     * The case the shipped encoder produces: video at frame rate, audio every 100 ms, one tab that
     * stopped reading. Every audio frame the storm generated is still queued, and the backlog was
     * paid for entirely in pictures — which is the PRD's §8.6, stated as an assertion.
     */
    @Test
    fun `a jpeg storm costs pictures and not the voice`() {
        val box = WebOutbox()
        val audioFrames = (0 until 30).count { it % 3 == 0 }
        repeat(30) { i ->
            box.offer(frame(1024 * 1024, tag = 7), WebOutbox.Lane.VIDEO)
            if (i % 3 == 0) box.offer(frame(3200, tag = 9), WebOutbox.Lane.REALTIME)
        }
        assertTrue(box.queuedBytes <= WebOutbox.DEFAULT_MAX_BYTES)
        val kept = generateSequence { box.poll(0) }.toList()
        assertEquals("every audio frame survives the backlog", audioFrames, kept.count { it[0].toInt() == 9 })
        assertTrue("the backlog was paid for in video", kept.count { it[0].toInt() == 7 } < 30)
    }
}
