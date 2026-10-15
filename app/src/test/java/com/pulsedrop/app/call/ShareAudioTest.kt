package com.pulsedrop.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind "they can hear what I'm sharing". Frame sizes, sample rates and a saturating
 * add are exactly the parts that are easy to get wrong and painful to find by ear on a call, so they
 * are checked here instead; the capture that feeds them needs a real screen to share.
 */
class ShareAudioTest {

    /** Little-endian stereo frame, the shape playback capture hands over. */
    private fun stereo(l: Short, r: Short) = byteArrayOf(
        (l.toInt() and 0xFF).toByte(), ((l.toInt() shr 8) and 0xFF).toByte(),
        (r.toInt() and 0xFF).toByte(), ((r.toInt() shr 8) and 0xFF).toByte()
    )

    @Test
    fun three_stereo_frames_become_one_mono_sample_at_the_call_rate() {
        val src = ByteArray(12)
        var at = 0
        repeat(3) {
            val f = stereo(1500, 2500)
            f.copyInto(src, at)
            at += 4
        }
        val out = ShortArray(1)
        assertEquals(1, downmixToCallRate(src, src.size, out))
        assertEquals("the two channels average, then the three frames do", 2000.toShort(), out[0])
    }

    @Test
    fun a_partial_group_of_three_waits_for_the_rest_of_the_next_chunk() {
        val src = ByteArray(4 * 5)
        val out = ShortArray(8)
        assertEquals("five frames hold one complete group", 1, downmixToCallRate(src, src.size, out))
        assertEquals("two leftover frames are not invented into a third", 1, downmixToCallRate(src, 12, out))
    }

    @Test
    fun the_ring_carries_exactly_what_was_written_and_zero_when_it_runs_dry() {
        val ring = SampleRing(1600 * 4)
        val wrote = ShortArray(1600) { it.toShort() }
        assertEquals(1600, ring.write(wrote, 1600))
        val out = ShortArray(1600)
        ring.read(out, 1600)
        assertEquals(wrote.toList(), out.toList())
        ring.read(out, 1600)
        assertEquals("an underflow is silence, not last frame's audio twice", 0, out[0].toInt())
    }

    @Test
    fun the_ring_wraps_and_drops_the_oldest_when_the_capture_runs_ahead() {
        val ring = SampleRing(8)
        val first = ShortArray(6) { (it + 1).toShort() }
        ring.write(first, 6)
        val second = ShortArray(5) { (it + 100).toShort() }
        // 6 in, 5 more, room for 8: the three oldest go, the five newest stay.
        assertEquals(5, ring.write(second, 5))
        val out = ShortArray(8)
        ring.read(out, 8)
        assertEquals(listOf<Short>(4, 5, 6, 100, 101, 102, 103, 104), out.toList())
        // And a burst longer than the window keeps only its own tail.
        ring.write(ShortArray(20) { it.toShort() }, 20)
        ring.read(out, 8)
        assertEquals(listOf<Short>(12, 13, 14, 15, 16, 17, 18, 19), out.toList())
    }

    @Test
    fun a_voice_over_a_loud_share_saturates_instead_of_wrapping() {
        fun pcm(samples: ShortArray) = ByteArray(samples.size * 2).also { b ->
            samples.forEachIndexed { i, v ->
                b[i * 2] = (v.toInt() and 0xFF).toByte()
                b[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
            }
        }
        fun sample(bytes: ByteArray, i: Int): Int =
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()

        val loud = shortArrayOf(30000, -30000, 12000, -27000)
        val mixed = mixDown(pcm(shortArrayOf(10000, -10000, 3000, -3000)), 8, loud)
        assertEquals("past the top of the scale stays at the top", 32767, sample(mixed, 0))
        assertEquals("past the bottom stays at the bottom", -32768, sample(mixed, 1))
        assertEquals(15000, sample(mixed, 2))
        assertEquals(-30000, sample(mixed, 3))
    }

    @Test
    fun clearing_drops_whatever_a_finished_share_left_behind() {
        val ring = SampleRing(1600)
        ring.write(ShortArray(1600) { 7 }, 1600)
        ring.clear()
        val out = ShortArray(4)
        ring.read(out, 4)
        assertEquals(listOf<Short>(0, 0, 0, 0), out.toList())
    }
}
