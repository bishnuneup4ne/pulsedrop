package com.pulsedrop.app.call

import kotlin.concurrent.withLock

/**
 * The browser leg's downlink, bounded in bytes as well as in frames.
 *
 * A frame queue capped by count alone is not a bounded queue when the frames are not the same size.
 * `FrameCodec.MAX_PAYLOAD` permits a 4 MiB payload, so 96 of them is up to about 402 MB of live
 * media held for one tab that stopped reading — on a phone whose whole heap budget is a fraction of
 * that. The tab's own stall is exactly the moment the phone should be shedding, not the moment it
 * should be allocating.
 *
 * So both limits are enforced and the oldest frame goes first, which is what live media wants: a
 * reader that is behind is shown a *newer* frame, not a bigger backlog of old ones. That is the
 * same policy the count cap already had, only now it binds at a size that means something.
 *
 * Which frame goes first is the other half, and it is a lane question, not a size question: a tab
 * that stalls behind a JPEG burst should lose *pictures*, not the voice it called for. So video is
 * shed before anything else, and audio only when there is no video left to shed — at which point the
 * backlog is made of audio and control frames alone, and the bound still has to hold, because a
 * queue that only ever shrinks someone else's frames is not a bound.
 *
 * Deliberately not here, because it needs a decision this measurement cannot make:
 *  - stopping the capture when nobody is reading. A tab that vanished mid-call leaves the camera and
 *    mic running until the leg's own timeout, and a watchdog that ends a call is a behaviour change
 *    that has to be judged on a device, not in a unit test.
 *
 * One lock rather than a lock-free counter, because the queue is tens of entries and the cost that
 * matters is the socket write downstream. An exact byte total under concurrent producers is worth
 * the uncontended lock it needs to stay exact.
 */
class WebOutbox(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES
) {

    /** What may be dropped when the queue is over either budget. */
    enum class Lane {
        /** A camera or screen frame: the next one supersedes it, so it is what a backlog gives up. */
        VIDEO,

        /** Audio, and the control frames that carry call state: shed only once nothing else can free bytes. */
        REALTIME
    }

    companion object {
        /** The frame count the leg had before this bound existed, kept so control frames still get seats. */
        const val DEFAULT_MAX_FRAMES = 96

        /**
         * Roughly a second of shared-screen frames at the sizes the shipped encoder makes, and
         * never less than the frame count can hold in the small-frame case (audio at 3.2 KB per
         * 100 ms is 96 x 3.2 KB, well under). Larger than this and the queue is storing the past.
         */
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
    }

    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val available = lock.newCondition()
    private val frames = java.util.ArrayDeque<Entry>()
    private var queued = 0

    private class Entry(val frame: ByteArray, val lane: Lane)

    /**
     * Frames waiting for the tab.
     *
     * Read under the lock, because the two numbers are one invariant: an unsynchronized reader can
     * see a frame already added and its bytes not yet shed, and would report a queue over budget
     * when it is exactly at it. That false alarm is worse than no diagnostic, since the whole point
     * of these two is to be believed.
     */
    val queuedFrames: Int get() = lock.withLock { frames.size }

    /** Bytes waiting for the tab, including each frame's 5-byte header. */
    val queuedBytes: Int get() = lock.withLock { queued }

    /**
     * Append, shedding until the queue fits both caps. Never blocks.
     *
     * False means the frame was refused outright: one frame larger than the whole budget is not a
     * backlog to shed but a caller asking this queue to hold something nothing could render, and
     * dropping it costs one frame rather than evicting every real one.
     *
     * The shed order is the lane rule — the oldest video frame first, and only when the queue holds
     * no video left, the oldest frame of any lane. So a JPEG storm starves the queue for the frames
     * it is made of, not for the voice riding underneath it.
     */
    fun offer(frame: ByteArray, lane: Lane = Lane.VIDEO): Boolean {
        if (frame.size > maxBytes) return false
        lock.lock()
        try {
            frames.addLast(Entry(frame, lane))
            queued += frame.size
            while (queued > maxBytes || frames.size > maxFrames) {
                if (!shedOldestLocked()) break
            }
            available.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Free one frame's worth of backlog, cheapest thing first. False when the queue is empty, which
     * is the only state in which a budget can be unmeetable here — every single frame is already
     * refused at the door if it does not fit alone.
     */
    private fun shedOldestLocked(): Boolean {
        val scan = frames.iterator()
        while (scan.hasNext()) {
            val entry = scan.next()
            if (entry.lane != Lane.VIDEO) continue
            scan.remove()
            queued -= entry.frame.size
            return true
        }
        val head = frames.pollFirst() ?: return false
        queued -= head.frame.size
        return true
    }

    /** The next frame for the tab, or null if [timeoutMs] passes without one. */
    fun poll(timeoutMs: Long): ByteArray? {
        lock.lock()
        try {
            var waited = 0L
            val budget = timeoutMs * 1_000_000L
            while (frames.isEmpty()) {
                if (waited >= budget) return null
                val start = System.nanoTime()
                // A spuriously long await only delays a frame that had not arrived anyway, so the
                // remaining time is the only thing worth computing precisely here.
                available.awaitNanos(budget - waited)
                waited += System.nanoTime() - start
            }
            val entry = frames.pollFirst() ?: return null
            queued -= entry.frame.size
            return entry.frame
        } finally {
            lock.unlock()
        }
    }

    /** Drop everything: a leg that ended must not deliver its last frames to whoever replaces it. */
    fun clear() {
        lock.lock()
        try {
            frames.clear()
            queued = 0
        } finally {
            lock.unlock()
        }
    }
}
