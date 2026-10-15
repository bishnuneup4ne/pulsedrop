package com.pulsedrop.app.call

/**
 * Rotation of an NV21 preview frame, in bytes.
 *
 * The sensor hands over frames in its own orientation, which on a phone held portrait is 90 or 270
 * degrees off upright, and the peer has to receive something upright. The obvious way — decode the
 * frame, rotate a Bitmap, re-encode — costs two full JPEG passes on top of the one that was already
 * paid, and that is what makes a live feed stutter. Rotating the bytes instead costs one pass and no
 * decoder at all.
 *
 * NV21 is a full-width luma plane followed by half-width, half-height chroma in V,U order. A
 * subsampled plane cannot be rotated pixel-by-pixel: a chroma pair covers four luma pixels, so the
 * luma grid and the chroma grid map at slightly different places. The indices below are derived from
 * one center of a chroma sample, `(2ci + 1, 2cj + 1)`, landing where the rotation puts it.
 */
internal object Nv21 {

    /** A rotated frame. The buffer is reused between calls, so hold the pair, not the array. */
    class Frame(val data: ByteArray, val width: Int, val height: Int)

    /** The dimensions a `degrees` turn gives a `w` x `h` frame — 90 and 270 swap them. */
    fun rotatedSize(w: Int, h: Int, degrees: Int): Pair<Int, Int> = when (normalize(degrees)) {
        90, 270 -> h to w
        else -> w to h
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /**
     * Turn [src] by 0, 90, 180 or 270 degrees into [dst]. Any other angle is left alone: a partial
     * turn needs interpolation this byte path does not attempt, and an unrotated frame is still a
     * frame. Returns null when the geometry does not fit, which is the caller's cue to skip a frame
     * rather than send a torn one.
     */
    fun rotate(src: ByteArray, w: Int, h: Int, degrees: Int, dst: ByteArray): Frame? {
        if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0) return null
        val planeSize = w * h
        if (src.size < planeSize + planeSize / 2) return null
        val deg = normalize(degrees)
        // Anything that is not a quarter turn needs interpolation this path does not attempt, and an
        // upright frame is a better answer than a sheared one - so it keeps its own dimensions too.
        val turns = deg == 90 || deg == 270
        val dw = if (turns) h else w
        val dh = if (turns) w else h
        val total = dw * dh * 3 / 2
        if (dst.size < total) return null
        when (deg) {
            90 -> rotate90Clockwise(src, dst, w, h)
            180 -> rotate180(src, dst, w, h)
            270 -> rotate270(src, dst, w, h)
            else -> System.arraycopy(src, 0, dst, 0, total)
        }
        return Frame(dst, dw, dh)
    }

    /** 90° clockwise: `(x, y)` becomes `(h - 1 - y, x)`. */
    private fun rotate90Clockwise(src: ByteArray, dst: ByteArray, w: Int, h: Int) {
        val plane = w * h
        for (y in 0 until h) {
            val row = y * w
            val dstCol = h - 1 - y
            for (x in 0 until w) dst[x * h + dstCol] = src[row + x]
        }
        // A chroma row holds cw pairs, so it is cw * 2 bytes wide.
        val cw = w / 2
        val ch = h / 2
        val stride = cw * 2
        for (cj in 0 until ch) {
            val crow = plane + cj * stride
            for (ci in 0 until cw) {
                // Chroma centre (2ci+1, 2cj+1) lands at dst ci = dw/2 - 1 - cj, dst cj = ci, and the
                // destination grid is dw/2 = h/2 wide - not the source's width.
                val at = ci * ch + (ch - 1 - cj)
                dst[plane + at * 2] = src[crow + ci * 2]
                dst[plane + at * 2 + 1] = src[crow + ci * 2 + 1]
            }
        }
    }

    /** 180°: `(x, y)` becomes `(w - 1 - x, h - 1 - y)`, with both planes reversed the same way. */
    private fun rotate180(src: ByteArray, dst: ByteArray, w: Int, h: Int) {
        val plane = w * h
        for (y in 0 until h) {
            val row = y * w
            val drow = (h - 1 - y) * w
            for (x in 0 until w) dst[drow + (w - 1 - x)] = src[row + x]
        }
        val cw = w / 2
        val ch = h / 2
        val stride = cw * 2
        for (cj in 0 until ch) {
            val crow = plane + cj * stride
            val drow = plane + (ch - 1 - cj) * stride
            for (ci in 0 until cw) {
                val d = drow + (cw - 1 - ci) * 2
                val s = crow + ci * 2
                dst[d] = src[s]
                dst[d + 1] = src[s + 1]
            }
        }
    }

    /** 270° clockwise (90° counter-clockwise): `(x, y)` becomes `(y, w - 1 - x)`. */
    private fun rotate270(src: ByteArray, dst: ByteArray, w: Int, h: Int) {
        val plane = w * h
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) dst[(w - 1 - x) * h + y] = src[row + x]
        }
        val cw = w / 2
        val ch = h / 2
        val stride = cw * 2
        for (cj in 0 until ch) {
            val crow = plane + cj * stride
            for (ci in 0 until cw) {
                // Chroma pair (ci, cj) centre (2ci+1, 2cj+1) -> dst cj = w/2 - 1 - ci, dst ci = cj,
                // in a destination grid ch = dw/2 wide.
                val at = (cw - 1 - ci) * ch + cj
                dst[plane + at * 2] = src[crow + ci * 2]
                dst[plane + at * 2 + 1] = src[crow + ci * 2 + 1]
            }
        }
    }
}
