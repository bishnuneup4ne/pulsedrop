package com.pulsedrop.app.media.h264

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The two questions these helpers answer are the two that corrupt a call silently: is this frame a
 * keyframe (so may it be dropped, and does it need parameter sets in front of it), and is this codec
 * a hardware block (so will 1080p30 be realtime or a slideshow). Both are decided from bytes and
 * names, so both are testable without a camera, a codec, or a device.
 */
class H264StreamsTest {

    private fun annexB(vararg nals: ByteArray): ByteArray = H264Streams.toAnnexB(nals.toList())

    /** nal_unit_type lives in the low 5 bits of the header byte; ref_idc stays zero for these. */
    private fun nal(type: Int, vararg body: Byte): ByteArray =
        byteArrayOf((type and 0x1F).toByte()) + body

    @Test
    fun splitsUnitsAndReadsTheirTypes() {
        val sps = nal(7, 1, 2)
        val pps = nal(8, 3)
        val idr = nal(5, 9, 9)
        val stream = annexB(sps, pps, idr)

        val units = H264Streams.nalUnits(stream)
        assertEquals(3, units.size)
        assertEquals(7, H264Streams.nalType(stream, units[0]))
        assertEquals(8, H264Streams.nalType(stream, units[1]))
        assertEquals(5, H264Streams.nalType(stream, units[2]))
    }

    /**
     * A unit must stop where the next start code begins, not where the next payload does. Getting
     * this wrong leaves a trailing `00 00 00 1` inside every unit, which reads back as a zero-type
     * NAL — and a frame that looks like it has no IDR is a frame the sender happily drops.
     */
    @Test
    fun unitsDoNotSwallowTheNextStartCode() {
        val a = nal(5, 1, 2, 3)
        val b = nal(1, 4, 5)
        val stream = annexB(a, b)
        val units = H264Streams.nalUnits(stream)

        units.forEachIndexed { i, range ->
            val last = stream[range.last]
            assertTrue(
                "unit $i ends on $last, which belongs to a delimiter",
                last.toInt() != 0 && last.toInt() != 1
            )
        }
        assertEquals(2, units.size)
        assertEquals(a.size, units[0].last - units[0].first + 1)
        assertEquals(b.size, units[1].last - units[1].first + 1)
    }

    @Test
    fun recognisesThreeAndFourByteStartCodesTogether() {
        val withShort = byteArrayOf(0, 0, 1) + nal(7, 1)
        val withLong = byteArrayOf(0, 0, 0, 1) + nal(5, 2)
        val stream = withShort + withLong
        val units = H264Streams.nalUnits(stream)
        assertEquals(2, units.size)
        assertEquals(7, H264Streams.nalType(stream, units[0]))
        assertEquals(5, H264Streams.nalType(stream, units[1]))
    }

    @Test
    fun idrDetectionDrivesBothPrependingAndDropPolicy() {
        val idrAu = annexB(nal(7, 1), nal(8, 2), nal(5, 3))
        val interAu = annexB(nal(1, 4))
        assertTrue(H264Streams.containsIdr(idrAu))
        assertFalse(H264Streams.containsIdr(interAu))
        assertTrue(H264Streams.containsParameterSets(idrAu))
        assertFalse(H264Streams.containsParameterSets(interAu))
    }

    /**
     * The rule that keeps a picture from freezing solid: a parameter set or an IDR is never droppable,
     * because every frame behind it is predicted from something the peer then never receives.
     */
    @Test
    fun onlyInterPredictedFramesMayBeDropped() {
        assertTrue(H264Streams.isDroppable(annexB(nal(1, 1, 2))))
        assertFalse("an IDR is an entry point", H264Streams.isDroppable(annexB(nal(5, 1))))
        assertFalse("a bare SPS is not a drop", H264Streams.isDroppable(annexB(nal(7, 1))))
        assertFalse(H264Streams.isDroppable(annexB(nal(8, 1))))
    }

    @Test
    fun emptyAndDelimiterOnlyBuffersYieldNoUnits() {
        assertEquals(emptyList<IntRange>(), H264Streams.nalUnits(ByteArray(0)))
        assertEquals(emptyList<IntRange>(), H264Streams.nalUnits(byteArrayOf(0, 0, 0, 1)))
        assertEquals(-1, H264Streams.nalType(ByteArray(0), 0 until 0))
    }

    @Test
    fun joinAddsDelimitersOnlyWhereTheyAreMissing() {
        val bareSps = nal(7, 1, 2)
        val barePps = nal(8, 3)
        val alreadyFramed = byteArrayOf(0, 0, 0, 1) + nal(5, 9)

        val joined = H264Streams.joinAnnexB(listOf(bareSps, barePps, alreadyFramed))
        assertEquals(3, H264Streams.nalUnits(joined).size)
        assertEquals(7, H264Streams.nalType(joined, H264Streams.nalUnits(joined)[0]))
        assertEquals(5, H264Streams.nalType(joined, H264Streams.nalUnits(joined)[2]))

        // One delimiter per unit: doubling a csd that already carried its own would make the second
        // unit begin inside the padding of the first.
        assertEquals(START_CODE_TOTAL * 2 + bareSps.size + barePps.size + alreadyFramed.size, joined.size)
    }

    @Test
    fun beginsWithStartCodeCoversBothLengths() {
        assertTrue(H264Streams.beginsWithStartCode(byteArrayOf(0, 0, 1, 5)))
        assertTrue(H264Streams.beginsWithStartCode(byteArrayOf(0, 0, 0, 1, 5)))
        assertFalse(H264Streams.beginsWithStartCode(byteArrayOf(0, 1, 65)))
        assertFalse(H264Streams.beginsWithStartCode(byteArrayOf(0, 0)))
    }

    /**
     * Refusing a software encoder is the point of this list. `c2.android.avc.encoder` and
     * `OMX.google.h264.encoder` are the two names that appear as AOSP defaults; accepting either
     * would let a build advertise `h264` and then deliver fewer frames than the JPEG lane it
     * replaced, which is worse than never trying.
     */
    @Test
    fun softwareEncodersAreRefusedByName() {
        assertFalse(HardwareCodec.isProbablyHardware("OMX.google.h264.encoder"))
        assertFalse(HardwareCodec.isProbablyHardware("c2.android.avc.encoder"))
        assertFalse(HardwareCodec.isProbablyHardware(""))
        assertTrue(HardwareCodec.isProbablyHardware("OMX.qcom.video.encoder.avc"))
        assertTrue(HardwareCodec.isProbablyHardware("c2.mtk.avc.encoder"))
        assertTrue(HardwareCodec.isProbablyHardware("OMX.Exynos.avc.enc"))
    }

    @Test
    fun selectionKeepsHardwareAvcAndIgnoresEverythingElse() {
        val saved = MediaCodecListSnapshot.provider
        try {
            MediaCodecListSnapshot.provider = {
                listOf(
                    MediaCodecListSnapshot.Entry("OMX.google.h264.encoder", true),
                    MediaCodecListSnapshot.Entry("OMX.qcom.video.encoder.avc", true),
                    MediaCodecListSnapshot.Entry("OMX.qcom.video.decoder.avc", false),
                    MediaCodecListSnapshot.Entry("OMX.qcom.video.encoder.hevc", false)
                )
            }
            assertEquals(listOf("OMX.qcom.video.encoder.avc"), HardwareCodec.avcEncoders())
            assertTrue(HardwareCodec.hasHardwareAvcEncoder())

            MediaCodecListSnapshot.provider = { listOf(MediaCodecListSnapshot.Entry("OMX.google.h264.encoder", true)) }
            assertFalse("a software-only device must not advertise the lane", HardwareCodec.hasHardwareAvcEncoder())

            MediaCodecListSnapshot.provider = { emptyList() }
            assertFalse(HardwareCodec.hasHardwareAvcEncoder())
        } finally {
            MediaCodecListSnapshot.provider = saved
        }
    }

    /**
     * The screen-share lane asks this question of the *peer*, so it must be answerable on a device that
     * has no encoder at all — and the two lists must not bleed into each other. An encoder name in the
     * decoder list would let a phone promise to present a shared screen it cannot decode, which shows
     * up as a black tile rather than an error.
     */
    @Test
    fun decoderSelectionIsIndependentOfEncoderSelection() {
        val saved = MediaCodecListSnapshot.provider
        try {
            MediaCodecListSnapshot.provider = {
                listOf(
                    MediaCodecListSnapshot.Entry("OMX.qcom.video.encoder.avc", true),
                    MediaCodecListSnapshot.Entry("OMX.qcom.video.decoder.avc", false, isAvcDecoder = true),
                    MediaCodecListSnapshot.Entry("c2.mtk.avc.decoder", false, isAvcDecoder = true),
                    MediaCodecListSnapshot.Entry("c2.android.avc.decoder", false, isAvcDecoder = true),
                    MediaCodecListSnapshot.Entry("OMX.google.h264.decoder", false, isAvcDecoder = true)
                )
            }
            assertEquals(
                listOf("OMX.qcom.video.decoder.avc", "c2.mtk.avc.decoder"),
                HardwareCodec.avcDecoders()
            )
            assertTrue(HardwareCodec.hasHardwareAvcDecoder())
            assertEquals(listOf("OMX.qcom.video.encoder.avc"), HardwareCodec.avcEncoders())

            // A device whose only AVC block is a decoder can watch a share but must not claim the
            // encoder cap; the reverse holds too, and both come from the same list.
            MediaCodecListSnapshot.provider = {
                listOf(MediaCodecListSnapshot.Entry("OMX.qcom.video.decoder.avc", false, isAvcDecoder = true))
            }
            assertTrue(HardwareCodec.hasHardwareAvcDecoder())
            assertFalse(HardwareCodec.hasHardwareAvcEncoder())

            MediaCodecListSnapshot.provider = {
                listOf(MediaCodecListSnapshot.Entry("c2.android.avc.decoder", false, isAvcDecoder = true))
            }
            assertFalse("a software decoder cannot hold 1080p", HardwareCodec.hasHardwareAvcDecoder())

            MediaCodecListSnapshot.provider = {
                listOf(MediaCodecListSnapshot.Entry("OMX.qcom.video.encoder.avc", true))
            }
            assertFalse("an encoder is not a decoder", HardwareCodec.hasHardwareAvcDecoder())
        } finally {
            MediaCodecListSnapshot.provider = saved
        }
    }

    @Test
    fun packingWritesTightRowsIntoAPaddedBuffer() {
        val w = 4
        val h = 4
        val stride = 8
        val nv21 = ByteArray(w * h + w * h / 2) { (it % 251).toByte() }
        val dst = ByteBuffer.allocate(stride * (h + h / 2))

        val written = Nv21Packing.copy(nv21, dst, w, h, stride, h, semiplanar = true)
        assertEquals(stride * (h + h / 2), written)

        // Row 0 luma must sit at offset 0..3, and row 1 must start at the stride, not at 4.
        for (x in 0 until w) {
            assertEquals(nv21[x], dst.get(x))
            assertEquals(nv21[w + x], dst.get(stride + x))
        }
        // Chroma begins after the padded luma plane, not after the packed one.
        assertEquals(nv21[w * h], dst.get(stride * h))
    }

    @Test
    fun packingRefusesABufferThatCannotHoldTheFrame() {
        val w = 16
        val h = 16
        val nv21 = ByteArray(w * h * 3 / 2)
        val tiny = ByteBuffer.allocate(64)
        assertEquals(0, Nv21Packing.copy(nv21, tiny, w, h, w, h, semiplanar = true))
    }

    @Test
    fun packingRefusesATruncatedSource() {
        val nv21 = ByteArray(100)
        val dst = ByteBuffer.allocate(4096)
        assertEquals(0, Nv21Packing.copy(nv21, dst, 64, 64, 64, 64, semiplanar = true))
    }

    /**
     * What `copy` reports is the padded footprint it actually wrote, not the packed frame it was
     * given. Reporting the smaller number would tell the encoder fewer bytes were there than the
     * codec will read past, and the first rows of the next plane would decode as noise.
     */
    @Test
    fun packingReportsThePaddedFootprintItWrote() {
        val w = 4
        val h = 4
        val stride = 8
        val nv21 = ByteArray(w * h + w * h / 2)
        val dst = ByteBuffer.allocate(stride * (h + h / 2))
        assertEquals(stride * (h + h / 2), Nv21Packing.copy(nv21, dst, w, h, stride, h, semiplanar = true))

        val tight = ByteBuffer.allocate(w * (h + h / 2))
        assertEquals(w * (h + h / 2), Nv21Packing.copy(nv21, tight, w, h, w, h, semiplanar = true))
    }

    /** A slice height shorter than the picture must clip rows rather than write past the buffer. */
    @Test
    fun packingStopsAtTheCodecsOwnSliceHeight() {
        val w = 4
        val h = 8
        val nv21 = ByteArray(w * h + w * h / 2)
        val dst = ByteBuffer.allocate(w * (h + h / 2))
        val written = Nv21Packing.copy(nv21, dst, w, h, w, sliceHeight = 4, semiplanar = true)
        assertEquals(w * (4 + 2), written)
    }

    @Test
    fun planarChromaSplitsIntoTwoPlanes() {
        val w = 4
        val h = 4
        val nv21 = ByteArray(w * h * 3 / 2) { (it % 7).toByte() }
        val dst = ByteBuffer.allocate(w * h + w * h / 2)
        val written = Nv21Packing.copy(nv21, dst, w, h, w, h, semiplanar = false)
        assertEquals(w * h + w * (h / 2), written)
    }

    private val START_CODE_TOTAL = H264Streams.START_CODE.size
}
