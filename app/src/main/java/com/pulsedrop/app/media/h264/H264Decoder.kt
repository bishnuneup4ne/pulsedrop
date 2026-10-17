package com.pulsedrop.app.media.h264

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware H.264 decoder presenting a peer's camera onto the call's existing SurfaceView.
 *
 * One instance per remote source: in a conference every tile is a different sender's stream, and a
 * shared decoder would interleave two unrelated reference chains into garbage.
 *
 * Frames render as they are produced rather than on their presentation timestamps. These are live
 * pictures, not a file — the timestamps were minted on the sender's clock a network hop away, and
 * honouring them would add a delay the call cannot spend and occasionally skip a frame for being
 * "late" when it is in fact current.
 */
class H264Decoder(
    private val onFault: (reason: String) -> Unit
) {

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    private var surface: Surface? = null
    private var surfaceWidth = 640
    private var surfaceHeight = 480

    /**
     * An access unit that arrived before any IDR is unusable, not merely delayed: it is predicted
     * from a reference frame this decoder has never seen. Held here only long enough to be counted
     * and dropped — a decoder that buffers inter-predicted frames hoping a keyframe turns up later
     * builds a backlog and then paints all of it at once, seconds behind.
     */
    private val inbox = ArrayBlockingQueue<ByteArray>(4)

    private var awaitingKeyframe = true
    private var unitsFed = 0
    private var unitsDropped = 0

    val isRunning: Boolean get() = running

    /**
     * @param surface the tile this source is drawn into. A surface that arrives after this call is
     * picked up by [setSurface] on the next frame instead of failing configuration; [width] and
     * [height] are that surface's size, which is all a decoder configured for surface output needs —
     * the stream's own SPS sets the real picture geometry.
     */
    fun start(surface: Surface?, width: Int, height: Int): Boolean {
        this.surface = surface
        surfaceWidth = width.coerceAtLeast(2)
        surfaceHeight = height.coerceAtLeast(2)
        running = true
        awaitingKeyframe = true
        worker = Thread({ pump() }, "h264-dec").apply { isDaemon = true; start() }
        return true
    }

    /** Swaps the presentation surface, which is what a rotating or re-attached call view needs. */
    fun setSurface(next: Surface?, width: Int, height: Int) {
        surface = next
        surfaceWidth = width.coerceAtLeast(2)
        surfaceHeight = height.coerceAtLeast(2)
        val c = codec ?: return
        if (next == null) return
        try {
            c.setOutputSurface(next)
        } catch (e: Exception) {
            Log.d(TAG, "output surface swap deferred: ${e.message}")
        }
    }

    /**
     * @return false when the unit was not queued — either it is the frame the decoder cannot start
     * from, or the decoder is already behind. Feeding a late unit is never better than dropping it.
     */
    fun submit(accessUnit: ByteArray): Boolean {
        if (!running) return false
        if (awaitingKeyframe && !H264Streams.containsIdr(accessUnit)) {
            unitsDropped++
            return false
        }
        if (inbox.offer(accessUnit)) return true
        // Full: the oldest queued unit is now the stale one. Drop it and keep the newest.
        inbox.poll()
        unitsDropped++
        return inbox.offer(accessUnit)
    }

    /** Access units actually handed to the codec. With [droppedUnits], this is the peer's stutter. */
    val fedUnits: Int get() = unitsFed

    /** Units thrown away: pre-keyframe, or overtaken by a newer one while the decoder was behind. */
    val droppedUnits: Int get() = unitsDropped

    fun stop() {
        running = false
        val w = worker
        worker = null
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }
        codec = null
        inbox.clear()
        awaitingKeyframe = true
        try { w?.join(400) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun pump() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = ensureCodec()
            if (c == null) {
                // Nowhere to draw yet, so the newest unit is put back rather than spent on a decoder
                // that does not exist. Polling first is also what makes this a wait instead of a spin.
                val held = inbox.poll(30, TimeUnit.MILLISECONDS)
                if (held != null) inbox.offer(held)
                continue
            }
            inbox.poll(30, TimeUnit.MILLISECONDS)?.let { feed(c, it) }
            drain(c, info)
        }
    }

    /**
     * The decoder is created lazily, on the first unit that actually reaches it, and only once a
     * surface exists to attach. A call can open its media socket before the overlay has laid out its
     * SurfaceView, and configuring against no surface at all fails outright on several devices.
     */
    private fun ensureCodec(): MediaCodec? {
        codec?.let { return it }
        // No surface, or one the view has not sized yet: hold the units on the queue rather than
        // configure a decoder for a 2x2 picture that the first real frame would only have to undo.
        val target = surface ?: return null
        if (surfaceWidth < 16 || surfaceHeight < 16) return null
        val created = try {
            MediaCodec.createDecoderByType(AVC_MIME).apply {
                configure(lazyFormat(), target, null, 0)
                start()
            }
        } catch (e: Exception) {
            fault("decoder unavailable: ${e.message}")
            null
        }
        codec = created
        return created
    }

    /**
     * A format carrying only the mime type and the size of the surface it will draw into.
     *
     * The stream declares its real resolution in the SPS, and a decoder with a surface output takes
     * that as soon as the first IDR arrives. Passing the *sender's* geometry instead is what makes a
     * mid-call resolution change — a peer stepping down a ladder rung — land as a cropped or stretched
     * picture, because the format and the stream then disagree about what size a frame is.
     */
    private fun lazyFormat(): MediaFormat =
        MediaFormat.createVideoFormat(AVC_MIME, surfaceWidth, surfaceHeight)

    private fun feed(c: MediaCodec, au: ByteArray) {
        val index = try {
            c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (e: Exception) {
            fault("input dequeue failed: ${e.message}"); return
        }
        if (index < 0) {
            unitsDropped++
            return
        }
        val buffer = c.getInputBuffer(index)
        if (buffer == null || buffer.capacity() < au.size) {
            queueEmpty(c, index)
            if (buffer != null) fault("input buffer ${buffer.capacity()}B below unit ${au.size}B")
            return
        }
        buffer.position(0)
        buffer.put(au)
        unitsFed++
        // Cleared here rather than at submit: a unit only stops being a keyframe-waiting decoder once
        // the codec actually holds it. While there is no surface to decode onto the latch stays up, so
        // the first frame after the tile appears is an IDR and not a mid-chain prediction of nothing.
        awaitingKeyframe = false
        try {
            c.queueInputBuffer(index, 0, au.size, System.nanoTime() / 1000, 0)
        } catch (e: Exception) {
            fault("queueInput failed: ${e.message}")
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (running) {
            val index = try {
                c.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (e: Exception) {
                fault("output dequeue failed: ${e.message}"); return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Normal on the first frame and again on any resolution change; the surface is
                    // already attached, so there is nothing to reconfigure and nothing to drop.
                    Log.d(TAG, "output format: ${try { c.outputFormat } catch (e: Exception) { "?" }}")
                }
                index < 0 -> { /* buffers-changed and anything newer */ }
                else -> {
                    // true = render to the surface now. Frame pacing is the sender's problem.
                    try { c.releaseOutputBuffer(index, true) } catch (ignored: Exception) { }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        awaitingKeyframe = true
                    }
                }
            }
        }
    }

    private fun queueEmpty(c: MediaCodec, index: Int) {
        try { c.queueInputBuffer(index, 0, 0, 0L, 0) } catch (ignored: Exception) { }
    }

    private fun fault(reason: String) {
        if (!running) return
        running = false
        Log.w(TAG, reason)
        onFault(reason)
    }

    companion object {
        private const val TAG = "PulseDropH264"
        private const val AVC_MIME = "video/avc"
        private const val INPUT_TIMEOUT_US = 4_000L
        private const val OUTPUT_TIMEOUT_US = 0L

        /** Exposed for the call's stats readout: units dropped is the peer's stutter, in one number. */
        fun ByteArray.toHexPrefix(limit: Int = 8): String =
            take(limit).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
