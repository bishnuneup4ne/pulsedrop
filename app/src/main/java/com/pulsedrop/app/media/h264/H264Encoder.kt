package com.pulsedrop.app.media.h264

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.pulsedrop.app.media.ladder.QualityTier
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One raw camera frame handed to the encoder: already rotated, packed NV21, at the tier's size. */
internal class RawFrame(val data: ByteArray, val width: Int, val height: Int)

/**
 * Hardware H.264 encoder feeding the v2 media lane (`TYPE_H264`, type 9).
 *
 * This replaces the per-frame JPEG encode for the camera only. At 1080p the JPEG path costs 30-60 ms
 * of CPU per frame before a byte is sent, which caps it near 15-20 fps regardless of the network, and
 * a 1080p JPEG is 150-250 KB — 30 fps of those is 36-60 Mbps, past what a hotspot carries. A hardware
 * block encodes 1080p30 into the ladder's 6 Mbps in single-digit milliseconds, off the capture thread.
 *
 * Everything leaving here is one complete access unit in Annex-B, with SPS/PPS prepended on every
 * IDR, so a peer that joins late or recovers a lost frame can start on the next frame it sees with no
 * out-of-band setup.
 */
class H264Encoder(
    private val tier: QualityTier,
    /** Called on the encoder thread with one access unit; `isIdr` marks a random-access entry point. */
    private val onAccessUnit: (au: ByteArray, isIdr: Boolean) -> Unit,
    /** Called once when the encoder dies. The caller's job is to fall back to JPEG, not to retry. */
    private val onFault: (reason: String) -> Unit,
    /**
     * Called for every frame submitted, once the encoder is finished with its bytes — copied, dropped
     * as late, or discarded when the lane came down. A caller reusing camera memory can only recycle a
     * buffer after this; see [Nv21Pool].
     */
    private val onFrameDone: (data: ByteArray) -> Unit = { }
) {

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var worker: Thread? = null

    /**
     * Depth 2, deliberately. The encoder consumes at about real time; a deeper queue is not absorbing
     * jitter, it is banking latency the peer pays for in a stale picture. When it is full the newest
     * frame is the one a viewer wants, so the oldest is dropped and the next frame is forced to an
     * IDR — keeping the P-frames behind a dropped keyframe would strand the decoder on pictures
     * predicted from a reference it never received.
     */
    private val inbox = ArrayBlockingQueue<RawFrame>(2)

    @Volatile private var forceIdr = true
    private val units = AnnexBAssembler()
    private var frameIndex = 0L

    val isRunning: Boolean get() = running

    /**
     * @return false when this device has no hardware AVC encoder or configuration failed, in which
     * case the caller keeps the JPEG lane. Neither outcome is something the user should be told about.
     */
    fun start(): Boolean {
        if (running) return true
        val names = HardwareCodec.avcEncoders()
        if (names.isEmpty()) {
            Log.i(TAG, "no hardware AVC encoder here; JPEG lane only")
            return false
        }
        for (name in names) {
            val created = tryCreate(name) ?: continue
            codec = created
            running = true
            frameIndex = 0
            forceIdr = true
            worker = Thread({ pump(created) }, "h264-enc").apply { isDaemon = true; start() }
            Log.i(TAG, "H.264 encoder $name at ${tier.describe()}, ${tier.bitrateBps / 1000} kbps")
            return true
        }
        return false
    }

    /**
     * Baseline 4.0 first — Level 4.0 because 1920x1080 is beyond what Level 3.1 frames allow, and
     * Baseline because it forbids B-frames, whose decode-order/display-order split a length-prefixed
     * live lane has no way to express. Then unconstrained, because several vendor blocks reject an
     * explicit `KEY_PROFILE_LEVEL` for Baseline while encoding it perfectly well, and letting the
     * codec pick its own default beats dropping the whole call to JPEG over that disagreement.
     */
    private fun tryCreate(name: String): MediaCodec? {
        val withProfile = buildFormat().apply {
            setInteger(AvcEncode.KEY_PROFILE_LEVEL, AvcEncode.BASELINE_LEVEL_40)
        }
        for (format in listOf(withProfile, buildFormat())) {
            val instance = try {
                MediaCodec.createByCodecName(name).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                continue
            }
            return instance
        }
        return null
    }

    private fun buildFormat(): MediaFormat =
        MediaFormat.createVideoFormat(AvcEncode.MIME, tier.width, tier.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, tier.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, tier.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, AvcEncode.IDR_INTERVAL_SECONDS)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
        }

    /**
     * @return false when the frame was dropped as late, or the lane is down — in which case the caller
     * still owns the bytes and [onFrameDone] will not fire for them. True hands ownership over: the
     * buffer comes back through [onFrameDone] exactly once.
     */
    fun submit(nv21: ByteArray, width: Int, height: Int): Boolean {
        if (!running) return false
        val frame = RawFrame(nv21, width, height)
        if (inbox.offer(frame)) return true
        // Full: the oldest frame is the stale one, so it goes, and its owner gets its buffer back.
        inbox.poll()?.let { onFrameDone(it.data) }
        forceIdr = true
        return inbox.offer(frame)
    }

    /**
     * Emit an IDR on the next frame. Driven by a peer's `keyframe-request` control frame and by the
     * first frame after any dropped or failed output, since both leave the peer decoding against a
     * reference it no longer holds.
     */
    fun requestKeyframe() {
        forceIdr = true
    }

    fun stop() {
        running = false
        val w = worker
        worker = null
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }
        codec = null
        releaseHeldFrames()
        try { w?.join(400) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /**
     * Give back every frame the lane was holding.
     *
     * Ownership passed to the encoder when [submit] accepted the frame, so a lane that dies — faulted or
     * stopped — owes those buffers back. Anything left behind is a slot the caller's pool can never
     * hand out again, and four lost slots is a lane that quietly stops encoding mid-call.
     */
    private fun releaseHeldFrames() {
        var held = inbox.poll()
        while (held != null) {
            onFrameDone(held.data)
            held = inbox.poll()
        }
    }

    private fun pump(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            // A short poll on input and none on output: the loop must stay eager to drain, or output
            // banks up while the thread waits on a frame nobody offered.
            val frame = inbox.poll(30, TimeUnit.MILLISECONDS)
            if (frame != null) {
                // Every path through feedInput ends the encoder's interest in these bytes.
                try {
                    feedInput(c, frame)
                } catch (e: Exception) {
                    fault("input feed failed: ${e.message}")
                } finally {
                    onFrameDone(frame.data)
                }
            }
            drainOutput(c, info)
        }
    }

    private fun feedInput(c: MediaCodec, frame: RawFrame) {
        val index = try {
            c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (e: Exception) {
            fault("input dequeue failed: ${e.message}"); return
        }
        if (index < 0) {
            // The encoder is behind. Drop this frame rather than block the capture thread.
            forceIdr = true
            return
        }
        val buffer = c.getInputBuffer(index)
        if (buffer == null) {
            queueEmpty(c, index)
            return
        }
        val format = try { c.outputFormat } catch (e: Exception) { null }
        val stride = positiveOr(format?.getInteger(MediaFormat.KEY_STRIDE), frame.width)
        val sliceHeight = positiveOr(format?.getInteger(MediaFormat.KEY_SLICE_HEIGHT), frame.height)
        var written = Nv21Packing.copy(
            src = frame.data,
            dst = buffer,
            width = frame.width,
            height = frame.height,
            stride = stride,
            sliceHeight = sliceHeight,
            semiplanar = true
        )
        if (written <= 0 && (stride != frame.width || sliceHeight != frame.height)) {
            // The padding the codec advertised does not fit the allocation it handed over. Packing
            // tight is what an encoder asked for before it ever reported a stride, so it is worth one
            // retry before declaring the lane unusable.
            written = Nv21Packing.copy(
                src = frame.data,
                dst = buffer,
                width = frame.width,
                height = frame.height,
                stride = frame.width,
                sliceHeight = frame.height,
                semiplanar = true
            )
        }
        if (written <= 0) {
            queueEmpty(c, index)
            fault("input buffer of ${buffer.capacity()}B cannot hold ${frame.width}x${frame.height}")
            return
        }
        val flags = if (forceIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        val ptsUs = frameIndex * (1_000_000L / tier.fps)
        frameIndex++
        forceIdr = false
        try {
            c.queueInputBuffer(index, 0, written, ptsUs, flags)
        } catch (e: Exception) {
            fault("queueInput failed: ${e.message}")
        }
    }

    private fun drainOutput(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (running) {
            val index = try {
                c.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (e: Exception) {
                fault("output dequeue failed: ${e.message}"); return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> captureParameterSets(c)
                index < 0 -> { /* buffers-changed and anything newer: nothing to do */ }
                else -> emit(c, index, info)
            }
        }
    }

    /**
     * Parameter sets arrive once, as a config buffer, and never again. Held back instead of sent as a
     * frame of their own so the wire only ever carries something decodable, then prepended to IDR
     * output. Normalised to start-code form because csd buffers are inconsistent across vendors —
     * some already carry the leading `00 00 00 1`, some hand over a bare NAL.
     */
    private fun captureParameterSets(c: MediaCodec) {
        val format = try { c.outputFormat } catch (e: Exception) { return }
        units.captureConfig(csdBuffers(format))
    }

    private fun emit(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val buffer = c.getOutputBuffer(index)
        try {
            if (buffer == null || info.size <= 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            when (val sealed = units.seal(bytesOf(buffer, info.offset, info.size))) {
                is SealedUnit.Stranded -> forceIdr = true
                is SealedUnit.Ready -> deliver(sealed.bytes, sealed.isIdr, info)
            }
        } catch (e: Exception) {
            forceIdr = true
        } finally {
            try { c.releaseOutputBuffer(index, false) } catch (ignored: Exception) { }
        }
    }

    private fun deliver(au: ByteArray, isIdr: Boolean, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) running = false
        onAccessUnit(au, isIdr)
    }

    private fun fault(reason: String) {
        if (!running) return
        running = false
        // The pump is about to leave its loop, so nothing will drain the inbox itself.
        releaseHeldFrames()
        Log.w(TAG, reason)
        onFault(reason)
    }

    /**
     * Returns an input index with no data in it.
     *
     * `MediaCodec` has no `releaseInputBuffer` and no `discardBuffer` — queueing zero bytes is the
     * only way to hand back an index that will not be filled. Anything else strands the buffer in
     * the pool, and the pool is small.
     */
    private fun queueEmpty(c: MediaCodec, index: Int) {
        try { c.queueInputBuffer(index, 0, 0, 0L, 0) } catch (ignored: Exception) { }
    }

    private fun bytesOf(buffer: ByteBuffer, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        val at = buffer.position()
        buffer.position(offset)
        buffer.get(out, 0, length)
        buffer.position(at)
        return out
    }

    private fun positiveOr(value: Int?, fallback: Int): Int =
        if (value != null && value > 0) value else fallback

    companion object {
        private const val TAG = "PulseDropH264"
        private const val INPUT_TIMEOUT_US = 4_000L
        private const val OUTPUT_TIMEOUT_US = 0L
    }
}

/**
 * Copies a tightly packed NV21 frame into an encoder input buffer, honouring the codec's row padding.
 *
 * Hardware blocks pad the luma stride and slice height past the picture size, and writing the packed
 * frame as though the buffer were tight shears the image on exactly the devices worth encoding for.
 * Pad columns are skipped rather than filled — they fall outside the displayed frame either way, and
 * advancing the position costs less than writing zeroes.
 */
object Nv21Packing {

    /**
     * @param semiplanar true for interleaved chroma (`COLOR_FormatYUV420SemiPlanar`, the NV21 order),
     * false for separate U and V planes (I420).
     * @return bytes written, or 0 when the buffer cannot hold the frame at this padding.
     */
    fun copy(
        src: ByteArray,
        dst: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        semiplanar: Boolean
    ): Int {
        if (width <= 0 || height <= 0 || src.size < width * height * 3 / 2) return 0
        val rows = height.coerceAtMost(sliceHeight)
        val chromaRows = rows / 2
        val needed = stride.toLong() * (rows + chromaRows)
        if (dst.capacity() < needed) return 0

        dst.position(0)
        // Luma: one row at a time, since the source is tight and the destination is not.
        repeat(rows) { row ->
            dst.put(src, row * width, width)
            if (stride > width) dst.position(dst.position() + (stride - width))
        }
        // Chroma: NV21 keeps it interleaved, so a row is width bytes either way; I420 splits the
        // two planes and needs each half-row placed separately.
        val chromaStart = stride * rows
        if (semiplanar) {
            repeat(chromaRows) { row ->
                dst.position(chromaStart + row * stride)
                dst.put(src, width * height + row * width, width)
            }
        } else {
            repeat(chromaRows) { row ->
                dst.position(chromaStart + row * (stride / 2))
                dst.put(src, width * height + row * (width / 2), width / 2)
            }
            repeat(chromaRows) { row ->
                dst.position(chromaStart + (stride / 2) * chromaRows + row * (stride / 2))
                dst.put(src, width * height + (width / 2) * chromaRows + row * (width / 2), width / 2)
            }
        }
        return needed.toInt()
    }
}
