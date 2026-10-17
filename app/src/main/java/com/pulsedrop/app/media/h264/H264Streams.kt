package com.pulsedrop.app.media.h264

import android.media.MediaCodecList
import android.os.Build
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Pure helpers over an H.264 Annex-B byte stream, kept free of `MediaCodec` so they run in a JVM
 * test. The device-only half of the pipeline is not interesting to verify; these two decisions are:
 * which bytes may go on the wire as a video frame, and which decoder is allowed to claim it is one.
 */
object H264Streams {

    /** NAL unit types that carry a decodable picture rather than parameter sets or SEI. */
    private const val NAL_IDR = 5
    private const val NAL_SEI = 6
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_AUD = 9

    /**
     * Splits Annex-B into NAL units by walking the 3- or 4-byte start codes.
     *
     * Each range is the NAL proper, start codes excluded: it begins on the `nal_unit_type` header
     * byte and stops where the next start code begins. Ending at the next *payload* instead would
     * make every unit swallow the delimiter in front of its neighbour, and `nalType` of a late unit
     * would then be read off trailing zero bytes.
     */
    fun nalUnits(annexB: ByteArray): List<IntRange> {
        val out = ArrayList<IntRange>(4)
        var current = findStart(annexB, 0) ?: return out
        while (true) {
            // Resume after the delimiter just consumed. Resuming one byte later would re-match the
            // tail of a four-byte start code as a three-byte one and invent an empty unit there.
            val next = findStart(annexB, current.second) ?: break
            if (next.first > current.second) out += current.second until next.first
            current = next
        }
        // The trailing unit is bounded by the end of the buffer, not by another start code.
        if (annexB.size > current.second) out += current.second until annexB.size
        return out
    }

    /** The nal_unit_type of a NAL, or -1 when the buffer is too short to have one. */
    fun nalType(annexB: ByteArray, nal: IntRange): Int {
        // Single-byte NAL headers only: PulseDrop's own encoder never fragments or uses the
        // multi-byte prefix forms, so the low 5 bits of the first byte is the whole answer here.
        val header = annexB.getOrNull(nal.first) ?: return -1
        return header.toInt() and 0x1F
    }

    /**
     * True when the access unit contains an IDR — a self-contained random-access frame that a peer
     * can start decoding from cold.
     *
     * This is what decides whether SPS/PPS get prepended, and a conference bridge uses it to decide
     * whether a late joiner may be fed this frame at all. Guessing from `BUFFER_FLAG_SYNC_FRAME`
     * instead is not good enough: several device encoders set that flag on ordinary P-frames.
     */
    fun containsIdr(annexB: ByteArray): Boolean =
        nalUnits(annexB).any { nalType(annexB, it) == NAL_IDR }

    /** True when the buffer carries parameter sets rather than, or alongside, picture data. */
    fun containsParameterSets(annexB: ByteArray): Boolean =
        nalUnits(annexB).any { val t = nalType(annexB, it); t == NAL_SPS || t == NAL_PPS }

    /**
     * Whether an access unit is safe to drop when the uplink falls behind.
     *
     * An IDR or anything carrying parameter sets is never droppable — dropping it strands every
     * later frame, because the peer would be decoding forward-predicted pictures against a keyframe
     * it never received and the tile would freeze rather than stutter. Only plain inter-predicted
     * frames may be thrown away.
     */
    fun isDroppable(annexB: ByteArray): Boolean =
        !containsIdr(annexB) && !containsParameterSets(annexB)

    /** Concatenates whole NAL units back into one Annex-B buffer, each re-prefixed with a start code. */
    fun toAnnexB(nals: List<ByteArray>): ByteArray {
        var size = 0
        nals.forEach { size += it.size + START_CODE.size }
        val out = ByteArray(size)
        var at = 0
        nals.forEach {
            System.arraycopy(START_CODE, 0, out, at, START_CODE.size)
            at += START_CODE.size
            System.arraycopy(it, 0, out, at, it.size)
            at += it.size
        }
        return out
    }

    /**
     * Joins buffers that may or may not already be start-code prefixed into one Annex-B run.
     *
     * Needed because `csd-0`/`csd-1` are inconsistent across vendors — some hand over a bare NAL,
     * others include the leading `00 00 00 1` — and concatenating the two kinds produces a stream
     * whose second unit has no delimiter and never gets parsed.
     */
    fun joinAnnexB(parts: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { part ->
            if (part.isEmpty()) return@forEach
            if (!beginsWithStartCode(part)) out.write(START_CODE)
            out.write(part)
        }
        return out.toByteArray()
    }

    /** True when the buffer opens with a 3- or 4-byte Annex-B start code. */
    fun beginsWithStartCode(buf: ByteArray): Boolean {
        if (buf.size < 3) return false
        if (buf[0].toInt() != 0 || buf[1].toInt() != 0) return false
        if (buf[2].toInt() == 1) return true
        return buf.size >= 4 && buf[2].toInt() == 0 && buf[3].toInt() == 1
    }

    /**
     * The four-byte start code every unit of a PulseDrop access unit begins with.
     *
     * The v2 spec (docs/Protocol-V2.md, type 9) reads "annex-B, no start codes needed". Sending
     * start codes anyway is the safer half of that ambiguity: it is what `MediaCodec` emits
     * natively, and a start-code-delimited unit can carry SPS + PPS + IDR as one self-describing
     * frame. Stripping them would need a second length scheme in front of each NAL, which is a
     * format the receiving side has to be written for rather than one it already speaks.
     */
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    private fun findStart(buf: ByteArray, from: Int): Pair<Int, Int>? {
        var i = from
        while (i < buf.size - 2) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                if (i + 2 < buf.size && buf[i + 2].toInt() == 1) return Pair(i, i + 3)
                if (i + 3 < buf.size && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1) {
                    return Pair(i, i + 4)
                }
            }
            i++
        }
        return null
    }

}

/**
 * What one encoder output buffer becomes on the wire.
 *
 * A unit is either sendable, or it is not sendable *yet* and the codec has to be pushed into making a
 * keyframe. Those are different problems for the caller — one takes the bytes, the other reaches for
 * `PARAMETER_KEY_REQUEST_SYNC_FRAME` or a `BUFFER_FLAG_KEY_FRAME` on the next input — so they are
 * different answers rather than a byte array and a guess.
 */
internal sealed interface SealedUnit {

    /** One complete access unit, with its parameter sets already on it where it needed them. */
    class Ready(val bytes: ByteArray, val isIdr: Boolean) : SealedUnit

    /** Nothing to send, and nothing will be decodable until the encoder makes an IDR. */
    object Stranded : SealedUnit
}

/**
 * Turns encoder output buffers into access units a peer can start decoding from.
 *
 * Both encoded lanes have to make the same three decisions, and they have to make them the same way:
 * parameter sets arrive once, as a config buffer, and are then never seen again; `csd` buffers are
 * start-code prefixed on some vendors and bare on others; and an inter-predicted frame that has never
 * had a parameter set in front of it is noise, not a frame. Duplicating that across a byte-buffer
 * encoder and a surface encoder is how one lane ends up shipping a stream the other lane recovered from
 * — and the wire format is frozen, so the drift is permanent.
 */
internal class AnnexBAssembler {

    private var parameterSets: ByteArray? = null

    /**
     * Held back from the codec's config callback, where the vendor's padding varies, and normalised to
     * start-code form once so every IDR can be stamped with a plain concatenation.
     */
    fun captureConfig(csdBuffers: List<ByteBuffer>) {
        val parts = csdBuffers.filter { it.remaining() > 0 }.map { bytesOf(it) }
        if (parts.isEmpty()) return
        parameterSets = H264Streams.joinAnnexB(parts)
    }

    /**
     * @param body one output buffer's worth of Annex-B, codec-config buffers excluded by the caller.
     */
    fun seal(body: ByteArray): SealedUnit {
        if (body.isEmpty()) return SealedUnit.Stranded
        val sets = parameterSets
        if (!H264Streams.containsIdr(body)) {
            // A P-frame before any parameter set ever arrived would only reach the peer as noise, so
            // the lane has to be told to make a keyframe rather than quietly feeding it onward.
            return if (sets == null) SealedUnit.Stranded
            else SealedUnit.Ready(body, false)
        }
        if (sets == null || H264Streams.containsParameterSets(body)) return SealedUnit.Ready(body, true)
        // Annex-B is self-delimiting, so a prefix is a concatenation — no NAL surgery needed.
        val stamped = ByteArray(sets.size + body.size)
        System.arraycopy(sets, 0, stamped, 0, sets.size)
        System.arraycopy(body, 0, stamped, sets.size, body.size)
        return SealedUnit.Ready(stamped, true)
    }

    private fun bytesOf(buffer: ByteBuffer): ByteArray {
        val out = ByteArray(buffer.remaining())
        val at = buffer.position()
        buffer.get(out, 0, out.size)
        buffer.position(at)
        return out
    }
}

/**
 * The two config buffers every AVC encoder hands over, in the order the wire wants them.
 *
 * Named by the SDK's own `csd-0`/`csd-1` keys rather than read out of the format wholesale: a vendor
 * that also drops an `esds` in there is not this lane's problem, and taking the two known slots keeps
 * the SPS ahead of the PPS, which is the order a decoder expects them in.
 */
internal fun csdBuffers(format: MediaFormat): List<ByteBuffer> =
    listOfNotNull(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"))

/**
 * The encoder settings that are a property of the *wire*, not of the thing being captured.
 *
 * Both H.264 lanes — the camera's byte-buffer encoder and the screen's surface encoder — have to agree
 * on these, because a peer's decoder is told which stream it is getting by a frame type, not by a
 * profile. One lane drifting to a profile with B-frames would produce access units the length-prefixed
 * lane has no way to order, and the wire format is frozen, so the mistake would ship forever.
 */
internal object AvcEncode {

    const val MIME = "video/avc"

    /**
     * The `profile-level` key by its wire name. `MediaFormat.KEY_PROFILE_LEVEL` is the API 34
     * constant for this same key, but the key itself has been read by encoders since 21, and the
     * app's floor is 24 — so the string is used directly rather than gated on a newer constant.
     */
    const val KEY_PROFILE_LEVEL = "profile-level"

    /**
     * Baseline profile with Level 4.0, the two OR-ed as the codec expects. Spelled as raw
     * numbers because `MediaCodecInfo.CodecProfileLevel.PROFILE_BASELINE` and `LEVEL_40` are
     * declared `BigInteger` in the SDK, which cannot be OR-ed into the `Int` this key wants.
     * Level 4.0, not 3.1, because a 1920x1080 frame exceeds what 3.1 permits at any frame rate.
     */
    const val BASELINE_LEVEL_40 = 0x01 or 0x2000

    /**
     * One random-access entry point every two seconds, so a peer that joins mid-call — or recovers from
     * a lost frame — waits at most two seconds for a picture rather than needing out-of-band setup.
     */
    const val IDR_INTERVAL_SECONDS = 2
}

/**
 * Picking an encoder instance.
 *
 * The distinction is not cosmetic. A software `video/avc` encoder on a mid-range phone encodes
 * 1080p at a fraction of realtime, so asking it for 30 fps produces a stream that queues latency
 * until the call is a slideshow — the exact failure the JPEG lane was already suffering, only
 * louder. A hardware block does it in a few milliseconds. So the hardware question is answered
 * before the codec is created, and answered "no" rather than falling back silently.
 */
object HardwareCodec {

    /**
     * Name heuristics for the software encoders worth refusing.
     *
     * `MediaCodecInfo.isSoftwareOnly` is API 29 and the app's floor is 24, so on older devices this
     * reads the component name, which is stable in practice: Google's own software build is
     * `OMX.google.*` or the `c2.android.*` soft codec, and anything with `google`/`android` in that
     * slot is not the vendor's hardware block.
     */
    fun isProbablyHardware(name: String): Boolean {
        val n = name.lowercase()
        if (n.isEmpty()) return false
        if (n.contains("omx.google") || n.contains("c2.android")) return false
        return true
    }

    /**
     * The platform's own answer where it has one (`isHardwareAccelerated`), the name guess where it
     * does not.
     *
     * This matters because `screen-h264` is a claim about the *peer*: a vendor block whose component
     * name reads like software makes this device refuse to announce the capability, and the sharer
     * then spends the whole call pushing JPEG to a phone that could have decoded H.264 natively. The
     * name heuristic is a floor for old releases, never an override of a real answer.
     */
    fun isHardware(entry: MediaCodecListSnapshot.Entry): Boolean =
        entry.isHardware ?: isProbablyHardware(entry.name)

    /** Hardware `video/avc` encoders, in the order they were enumerated. */
    fun avcEncoders(): List<String> {
        return try {
            MediaCodecListSnapshot.list().filter { it.isAvcEncoder && isHardware(it) }
                .map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** True when this device can plausibly do the H.264 lane at all. */
    fun hasHardwareAvcEncoder(): Boolean = avcEncoders().isNotEmpty()

    /**
     * True when this device can *watch* a 1080p stream, which is the promise the screen-share lane runs
     * on: the sharer needs a peer that owns a hardware decoder and a surface to present onto, and
     * neither of those is implied by having an encoder itself.
     */
    fun hasHardwareAvcDecoder(): Boolean = avcDecoders().isNotEmpty()

    /** Hardware `video/avc` decoders, in the order they were enumerated. */
    fun avcDecoders(): List<String> = try {
        MediaCodecListSnapshot.list().filter { it.isAvcDecoder && isHardware(it) }
            .map { it.name }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Indirection over `MediaCodecList`, which cannot be constructed or queried meaningfully on a JVM.
 * Tests substitute a fake list; on a device this reads the platform.
 */
object MediaCodecListSnapshot {

    data class Entry(
        val name: String,
        val isAvcEncoder: Boolean,
        /** Defaulted because most fixtures only ever cared about the encoder half. */
        val isAvcDecoder: Boolean = false,
        /** null means "the platform did not say", which is when the name heuristic is used. */
        val isHardware: Boolean? = null
    )

    /** Overridable so codec selection is testable without a device. */
    @Volatile
    var provider: () -> List<Entry> = { readPlatform() }

    fun list(): List<Entry> = provider()

    private fun readPlatform(): List<Entry> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.supportedTypes.any { t -> t == AVC_MIME } }
            .map { Entry(it.name, it.isEncoder, !it.isEncoder, hardwareFlag(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * `MediaCodecInfo.isHardwareAccelerated` is API 29; below it there is no answer, which is not the
     * same thing as "no" — so the name heuristic stays as the fallback rather than as the rule.
     */
    private fun hardwareFlag(info: android.media.MediaCodecInfo): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else null

    private const val AVC_MIME = "video/avc"
}
