package com.pulsedrop.app.media.h264

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.pulsedrop.app.media.ladder.QualityTier
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed by a Surface rather than by byte buffers: the shared screen's lane.
 *
 * The alternative is what the JPEG share does — take every frame out of an `ImageReader` as RGBA, scale
 * it, colour-convert it and compress it, all in this app's own process. A 1080p frame is 8 MB of RGBA
 * and 30 of them a second is not something a phone manages while also *being the app that is being
 * shared*; the existing share caps at 8 fps for exactly that reason, and spends 15 Mbps doing it.
 * Pointing the `VirtualDisplay` at this surface instead means the display server writes each frame
 * where the encoder reads it: scaled in hardware, converted in hardware, and never copied by anyone.
 *
 * What leaves here is identical in shape to [H264Encoder]'s output — one Annex-B access unit per frame,
 * SPS/PPS stamped onto every IDR — because the wire format cannot tell, and must not need to, how the
 * pixels got in.
 */
class H264SurfaceEncoder(
    private val tier: QualityTier,
    /** Called on the encoder thread with one access unit; `isIdr` marks a random-access entry point. */
    private val onAccessUnit: (au: ByteArray, isIdr: Boolean) -> Unit,
    /** Called once when the encoder dies. The caller's job is to fall back to JPEG, not to retry. */
    private val onFault: (reason: String) -> Unit
) {

    @Volatile private var running = false
    /** Why the last candidate was refused, for the one line printed when all of them were. */
    private var lastFailure: String? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    private var input: Surface? = null
    private val units = AnnexBAssembler()

    /**
     * The surface to render into, or null once the lane is down.
     *
     * Reads off [running] rather than the field, so a caller that polls this — which is what
     * `ProjectionCaptureService` does, once a tick — cannot end up pointing a live `VirtualDisplay` at
     * a codec that has already given up, which is a share that goes black without an error anywhere.
     */
    val inputSurface: Surface? get() = if (running) input else null

    val isRunning: Boolean get() = running

    /** The geometry this encoder was built for, so a rotation can be told from a repeat request. */
    fun matches(width: Int, height: Int): Boolean = running && tier.width == width && tier.height == height

    /**
     * @return false when this device has no hardware AVC encoder or configuration failed, in which case
     * the caller keeps the JPEG share. Neither outcome is something the user should be told about.
     */
    fun start(): Boolean {
        if (running) return true
        val names = HardwareCodec.avcEncoders()
        if (names.isEmpty()) {
            Log.i(TAG, "no hardware AVC encoder here; JPEG share only")
            return false
        }
        var lastFailure: String? = null
        for (name in names) {
            val created = tryCreate(name)?.also { lastFailure = null } ?: continue
            codec = created
            running = true
            worker = Thread({ pump(created) }, "h264-surf").apply { isDaemon = true; start() }
            Log.i(TAG, "screen encoder $name at ${tier.describe()}, ${tier.bitrateBps / 1000} kbps")
            return true
        }
        // Every candidate refused, and the caller silently keeps JPEG. Without this line the only
        // evidence that a share went soft is a stats chip, and a chip does not say why.
        Log.w(TAG, "no hardware AVC encoder would take ${tier.describe()}: ${lastFailure ?: "none listed"}")
        return false
    }

    /**
     * Baseline 4.0 first, for the same reasons [H264Encoder] gives — no B-frames on a lane that orders
     * frames by arrival, and a level that permits 1080p — then unconstrained, because several vendor
     * blocks refuse an explicit `KEY_PROFILE_LEVEL` while encoding perfectly well.
     */
    private fun tryCreate(name: String): MediaCodec? {
        val formats = listOf(
            buildFormat().apply {
                setInteger(AvcEncode.KEY_PROFILE_LEVEL, AvcEncode.BASELINE_LEVEL_40)
            },
            buildFormat()
        )
        return formats.firstNotNullOfOrNull { attempt(name, it) }
    }

    private fun attempt(name: String, format: MediaFormat): MediaCodec? {
        val created = try {
            MediaCodec.createByCodecName(name)
        } catch (e: Exception) {
            lastFailure = "create $name: ${e.message}"
            return null
        }
        // A block can list video/avc, be hardware, and still have no surface input — which is the
        // combination that made this lane fail with nothing printed: `createInputSurface()` throws on
        // it, the catch swallows it, and the share is JPEG for the rest of the call.
        val caps = try {
            created.codecInfo.getCapabilitiesForType(AvcEncode.MIME)
        } catch (e: Exception) {
            lastFailure = "capabilities $name: ${e.message}"
            null
        }
        if (caps == null ||
            !caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        ) {
            lastFailure = "$name has no surface input"
            try { created.release() } catch (ignored: Exception) { }
            return null
        }
        return try {
            created.configure(format, null, null, 0)
            // Between configure and start, which is where the platform wants it: the buffer queue this
            // surface fronts belongs to the codec's input pins, and start() is what connects them.
            input = created.createInputSurface()
            created.start()
            created
        } catch (e: Exception) {
            lastFailure = "configure $name: ${e.message}"
            // A surface handed out and then refused still holds the display server's buffers, and
            // releasing the codec alone does not take them back.
            try { input?.release() } catch (ignored: Exception) { }
            input = null
            try { created.release() } catch (ignored: Exception) { }
            null
        }
    }

    private fun buildFormat(): MediaFormat =
        MediaFormat.createVideoFormat(AvcEncode.MIME, tier.width, tier.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, tier.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, tier.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, AvcEncode.IDR_INTERVAL_SECONDS)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            // Bank nothing. Without these two the block is allowed to hold frames back for a better
            // rate-distortion trade, which on a share shows up as the cursor lagging behind the mouse —
            // and a screen worth 200 KB of extra sharpness is not worth a tenth of a second of lag.
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

    /**
     * Ask for an IDR on the next frame.
     *
     * Driven by a peer whose tile has just appeared, and by a unit this lane had to drop for want of
     * parameter sets — either way the peer is decoding against a reference chain it no longer holds, and
     * only a random-access frame ends that. No fallback is attempted for an encoder that refuses the
     * parameter: the older documented trick, a zero-length GOP handed to `setParameters`, stays applied
     * on several blocks and turns an otherwise healthy call into all-intra video.
     */
    fun requestKeyframe() {
        val c = codec ?: return
        if (!running) return
        try {
            // A Bundle, whatever the platform's own example appears to show: `setParameters` takes one,
            // and a MediaFormat carrying the same key is a compile error rather than a shorter way to
            // write it.
            c.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1) }
            )
        } catch (e: Exception) {
            Log.d(TAG, "sync frame refused: ${e.message}")
        }
    }

    fun stop() {
        running = false
        val c = codec
        codec = null
        val w = worker
        worker = null
        try { c?.stop() } catch (e: Exception) { }
        try { c?.release() } catch (e: Exception) { }
        try { input?.release() } catch (e: Exception) { }
        input = null
        try { w?.join(400) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun pump(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            // Output only — nothing is ever queued to this codec by hand, so there is no input half to
            // interleave a poll with. The timeout is what keeps a still screen from spinning a core:
            // a display that has not changed presents no frame, and so produces no buffer to wait on.
            val index = try {
                c.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (e: Exception) {
                fault("output dequeue failed: ${e.message}"); return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> captureParameterSets(c)
                index < 0 -> { /* buffers-changed and anything newer: nothing to do */ }
                else -> emit(c, index, info)
            }
        }
    }

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
                is SealedUnit.Stranded -> requestKeyframe()
                is SealedUnit.Ready -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) running = false
                    onAccessUnit(sealed.bytes, sealed.isIdr)
                }
            }
        } catch (e: Exception) {
            requestKeyframe()
        } finally {
            try { c.releaseOutputBuffer(index, false) } catch (ignored: Exception) { }
        }
    }

    private fun fault(reason: String) {
        if (!running) return
        running = false
        Log.w(TAG, reason)
        onFault(reason)
    }

    private fun bytesOf(buffer: ByteBuffer, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        val at = buffer.position()
        buffer.position(offset)
        buffer.get(out, 0, length)
        buffer.position(at)
        return out
    }

    companion object {
        private const val TAG = "PulseDropH264"

        /**
         * Longer than the byte-buffer lane's zero timeout, because this loop has no input to interleave
         * with: one ten-millisecond wait per turn is how a static screen costs a hundred wakeups a
         * second instead of millions, and no live frame waits on it.
         */
        private const val OUTPUT_TIMEOUT_US = 10_000L
    }
}
