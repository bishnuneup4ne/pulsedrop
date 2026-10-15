package com.pulsedrop.app.call

/**
 * The audio a screen share carries.
 *
 * Sharing a game or a video means the peer wants to hear it, not just see it. The system hands that
 * over as 48 kHz stereo PCM while a call runs on 16 kHz mono, so it is downsampled and mixed under
 * the voice rather than sent as a second stream the peer would have to align.
 */

/** Samples one call frame carries: 100 ms at the 16 kHz the voice already runs at. */
internal const val CALL_FRAME_SAMPLES = 1600

/**
 * 48 kHz interleaved stereo bytes to 16 kHz mono samples: three frames average into one, and the two
 * channels into one. Returns how many samples were written; the trailing frames that do not complete
 * a group of three are left for the next chunk.
 */
internal fun downmixToCallRate(src: ByteArray, srcLen: Int, out: ShortArray): Int {
    val frames = srcLen / 4
    val n = minOf(frames / 3, out.size)
    for (i in 0 until n) {
        var acc = 0
        val base = i * 12
        for (k in 0 until 3) {
            val o = base + k * 4
            val l = ((src[o + 1].toInt() shl 8) or (src[o].toInt() and 0xFF)).toShort().toInt()
            val r = ((src[o + 3].toInt() shl 8) or (src[o + 2].toInt() and 0xFF)).toShort().toInt()
            acc += (l + r) / 2
        }
        out[i] = (acc / 3).toShort()
    }
    return n
}

/**
 * A window of playback audio between two threads running at slightly different speeds. The writer is
 * the capture; the reader is the frame pacer. When they drift the oldest samples go — a share whose
 * sound lags half a second behind the picture is worse than one that loses a consonant.
 */
internal class SampleRing(private val capacity: Int) {

    private val data = ShortArray(capacity)
    private var start = 0
    private var size = 0

    /** Samples actually kept. More than a full window arrives as its own most recent tail. */
    @Synchronized
    fun write(src: ShortArray, len: Int): Int {
        if (len <= 0 || capacity <= 0) return 0
        if (len >= capacity) {
            System.arraycopy(src, len - capacity, data, 0, capacity)
            start = 0
            size = capacity
            return capacity
        }
        val free = capacity - size
        if (len > free) {
            val drop = len - free
            start = (start + drop) % capacity
            size -= drop
        }
        val end = (start + size) % capacity
        val first = minOf(len, capacity - end)
        System.arraycopy(src, 0, data, end, first)
        if (len > first) System.arraycopy(src, first, data, 0, len - first)
        size += len
        return len
    }

    /** The next [n] samples, oldest first, zero-filled where the capture has fallen behind. */
    @Synchronized
    fun read(out: ShortArray, n: Int) {
        val want = minOf(n, out.size)
        val avail = minOf(want, size)
        val first = minOf(avail, capacity - start)
        System.arraycopy(data, start, out, 0, first)
        if (avail > first) System.arraycopy(data, 0, out, first, avail - first)
        for (i in avail until want) out[i] = 0
        start = (start + avail) % capacity
        size -= avail
    }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }
}

/**
 * Mic bytes plus the playback window for the same frame, back into signed 16-bit little-endian.
 * A voice over a loud game saturates rather than wrapping into crackle.
 */
internal fun mixDown(mic: ByteArray, micLen: Int, playback: ShortArray): ByteArray {
    val samples = micLen / 2
    val out = ByteArray(micLen)
    for (i in 0 until samples) {
        val o = i * 2
        val m = ((mic[o + 1].toInt() shl 8) or (mic[o].toInt() and 0xFF)).toShort().toInt()
        val v = m + playback[i].toInt()
        val clamped = if (v > 32767) 32767 else if (v < -32768) -32768 else v
        out[o] = (clamped and 0xFF).toByte()
        out[o + 1] = ((clamped shr 8) and 0xFF).toByte()
    }
    return out
}
