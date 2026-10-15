package com.pulsedrop.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The camera used to pay for an upright frame with three JPEG passes; this is the pass that replaces
 * them, so it is checked against frames small enough to write out by hand and read back exactly.
 *
 * Two things are easy to get wrong here and impossible to notice on a call: turning the luma plane
 * and the chroma plane to different places, which shows up as colour smeared a quarter-turn around
 * the face, and forgetting that 90 and 270 swap the dimensions, which shows up as a frame stretched
 * or torn. So every case below checks the dimensions and then a specific byte at a specific index.
 */
class Nv21RotatorTest {

    /** A frame whose every byte is its own index, so a moved byte names where it came from. */
    private fun tagged(w: Int, h: Int): ByteArray {
        val total = w * h * 3 / 2
        return ByteArray(total) { it.toByte() }
    }

    private fun src4x2() = tagged(4, 2)

    /** Luma 4x2 then one row of two V,U pairs. */
    private val W = 4
    private val H = 2

    @Test
    fun `a quarter turn swaps the dimensions and carries every luma byte`() {
        val src = src4x2()
        val dst = ByteArray(src.size)
        val f = Nv21.rotate(src, W, H, 90, dst)!!
        assertEquals("90° turns a 4x2 into a 2x4", 2, f.width)
        assertEquals(4, f.height)

        // (x, y) -> (h - 1 - y, x), written at dst y' * dw + x' where dw = h = 2.
        // src index 3 is (x=3, y=0), so it lands at dst x*2 + (h-1-y) = 6 + 1 = 7.
        assertEquals("src luma 3 -> dst 7", 3.toByte(), f.data[7])
        // src (0, 1) is index 4 -> x' = 2 - 1 - 1 = 0, y' = 0 -> dst 0.
        assertEquals(4.toByte(), f.data[0])
        // src (3, 1) is index 7 -> x' = 0, y' = 3 -> dst 3 * 2 + 0 = 6.
        assertEquals(7.toByte(), f.data[6])
    }

    @Test
    fun `the chroma pair follows its own quarter of the frame`() {
        val src = src4x2()
        val dst = ByteArray(src.size)
        val f = Nv21.rotate(src, W, H, 90, dst)!!
        val plane = W * H
        // Chroma starts at src 8: V at 8, U at 9 covers luma rows 0-1, columns 0-1.
        // Its centre (1, 1) turns to dst (x'=0, y'=1); dst chroma grid is 1 wide, 2 tall.
        val vAt = plane + 0 * 2
        val uAt = vAt + 1
        assertEquals("the V of chroma pair 0", src[8], f.data[vAt])
        assertEquals("the U of chroma pair 0", src[9], f.data[uAt])
        // The second pair (src 10, 11) covers columns 2-3 and must land after it.
        assertEquals(src[10], f.data[plane + 2])
        assertEquals(src[11], f.data[plane + 3])
    }

    @Test
    fun `a half turn reverses both planes the same way`() {
        val src = src4x2()
        val dst = ByteArray(src.size)
        val f = Nv21.rotate(src, W, H, 180, dst)!!
        assertEquals(W, f.width)
        assertEquals(H, f.height)
        val plane = W * H
        // Luma (0,0) -> (3,1): src 0 lands at dst 1 * 4 + 3 = 7.
        assertEquals(src[0], f.data[7])
        // And the last luma byte comes back to the front.
        assertEquals(src[7], f.data[0])
        // Chroma pair 0 (src 8,9) reverses to the last pair of the 2x1 grid: dst 8 + 1*2 = 10.
        assertEquals(src[8], f.data[plane + 2])
        assertEquals(src[9], f.data[plane + 3])
    }

    @Test
    fun `three quarter turn is the other direction`() {
        val src = src4x2()
        val dst = ByteArray(src.size)
        val f = Nv21.rotate(src, W, H, 270, dst)!!
        assertEquals(2, f.width)
        assertEquals(4, f.height)
        // (x, y) -> (y, w - 1 - x): src (0,0) index 0 -> dst (0, 3) = 3 * 2 + 0 = 6.
        assertEquals(src[0], f.data[6])
        // Chroma pair 1 (src 10, 11) sits at dst (cj, w/2 - 1 - ci) with ci = 1, cj = 0.
        assertEquals(src[10], f.data[8 + 0])
        assertEquals(src[11], f.data[8 + 1])
    }

    @Test
    fun `turning twice by ninety is a half turn`() {
        val src = src4x2()
        val first = ByteArray(src.size)
        val once = Nv21.rotate(src, W, H, 90, first)!!
        val second = ByteArray(src.size)
        val twice = Nv21.rotate(once.data, once.width, once.height, 90, second)!!
        val direct = ByteArray(src.size)
        val straight = Nv21.rotate(src, W, H, 180, direct)!!
        assertEquals(straight.width, twice.width)
        assertEquals(straight.height, twice.height)
        assertEquals(
            "composition must agree with the direct half turn",
            straight.data.toList(), twice.data.toList()
        )
    }

    @Test
    fun `a frame with more than one chroma row turns without mixing the rows`() {
        // A preview frame is never two rows tall, and a 4x2 cannot tell a chroma row stride of cw
        // from a stride of cw * 2 — which is exactly the difference between colour noise and colour
        // in the right place. So the same composition is repeated on a frame with two chroma rows.
        val src = tagged(4, 4)
        val once = Nv21.rotate(src, 4, 4, 90, ByteArray(src.size))!!
        val twice = Nv21.rotate(once.data, once.width, once.height, 90, ByteArray(src.size))!!
        val straight = Nv21.rotate(src, 4, 4, 180, ByteArray(src.size))!!
        assertEquals(straight.data.toList(), twice.data.toList())
        val thrice = Nv21.rotate(twice.data, twice.width, twice.height, 90, ByteArray(src.size))!!
        val four = Nv21.rotate(thrice.data, thrice.width, thrice.height, 90, ByteArray(src.size))!!
        assertEquals("four quarter turns are no turn", src.toList(), four.data.toList())
    }

    @Test
    fun `a full turn is the frame itself`() {
        val src = src4x2()
        val dst = ByteArray(src.size)
        val f = Nv21.rotate(src, W, H, 360, dst)!!
        assertEquals(src.toList(), f.data.toList())
        assertSame("the caller's scratch buffer is where the frame was written", dst, f.data)
    }

    @Test
    fun `an angle this byte path cannot honour sends nothing rather than something wrong`() {
        // Phone sensors report 0, 90, 180 or 270; 30 would need interpolation.
        val src = src4x2()
        val dst = ByteArray(src.size)
        val f = Nv21.rotate(src, W, H, 30, dst)
        assertEquals("an unsupported angle is left upright, not skewed", src.toList(), f?.data?.toList())
    }

    @Test
    fun `a buffer that cannot hold the frame is refused`() {
        val src = src4x2()
        assertNull(Nv21.rotate(src, W, H, 90, ByteArray(4)))
        // Odd geometry has no chroma grid to rotate.
        assertNull(Nv21.rotate(ByteArray(3 * 3 * 3 / 2), 3, 3, 90, ByteArray(27)))
        // A frame shorter than its own plane is refused rather than read past the end.
        assertNull(Nv21.rotate(ByteArray(10), W, H, 90, ByteArray(24)))
    }

    @Test
    fun `rotated size answers without touching a buffer`() {
        assertEquals(2 to 4, Nv21.rotatedSize(4, 2, 90))
        assertEquals(2 to 4, Nv21.rotatedSize(4, 2, 270))
        assertEquals(4 to 2, Nv21.rotatedSize(4, 2, 180))
        assertEquals(4 to 2, Nv21.rotatedSize(4, 2, 0))
    }
}
