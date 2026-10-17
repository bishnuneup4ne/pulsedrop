package com.pulsedrop.app.media.h264

/**
 * A fixed set of reusable NV21 buffers, handed to the encoder and taken back when it is done.
 *
 * The camera delivers preview frames on its own thread into buffers it expects to recycle, and the
 * encoder reads them on a different thread a few milliseconds later. Returning a camera buffer
 * immediately therefore lets the sensor overwrite pixels the encoder is still reading: one torn frame,
 * and the peer sees corruption until the next keyframe two seconds on. Allocating a fresh array per
 * frame would be correct and cost ~90 MB/s of garbage at 1080p30, which is a GC pause in the middle of
 * a call. A small ring of long-lived buffers is both: whoever holds a frame owns its bytes until the
 * encoder reports back.
 *
 * Depth 4 is the whole budget — 12 MB at 1080p. When all four are outstanding the encoder is already
 * more than a frame behind, so the new frame is dropped rather than allocating.
 */
class Nv21Pool(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()

    /** Buffers nobody holds. A slot the pool has never allocated is absent, not null-valued. */
    private val free = ArrayList<ByteArray>(capacity)
    private var allocated = 0

    /**
     * @return a buffer of at least [minSize] bytes that no one else holds, or null when every slot is
     * outstanding. Slots size up on first use, so a call that never runs at 1080p never reserves
     * 1080p memory.
     */
    fun acquire(minSize: Int): ByteArray? = synchronized(lock) {
        if (minSize <= 0) return null
        val i = free.indexOfFirst { it.size >= minSize }
        if (i >= 0) return free.removeAt(i)
        if (allocated >= capacity) return null
        allocated++
        ByteArray(minSize)
    }

    /** Put a buffer back. Beyond capacity the extra is discarded, so a double release cannot grow
     * the pool or hand the same array to two frames. */
    fun release(buf: ByteArray) = synchronized(lock) {
        if (buf.isNotEmpty() && free.size < allocated) free.add(buf)
    }

    /** Buffers handed out and not yet returned. */
    fun outstanding(): Int = synchronized(lock) { allocated - free.size }

    /** Forget everything — the sensor is about to be reconfigured at a different size. */
    fun clear() = synchronized(lock) {
        free.clear()
        allocated = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 4
    }
}
