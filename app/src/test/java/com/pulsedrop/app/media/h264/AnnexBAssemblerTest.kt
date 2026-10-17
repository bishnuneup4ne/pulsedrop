package com.pulsedrop.app.media.h264

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The one job here is that a peer's decoder gets a stream it can start from: parameter sets that the
 * codec hands over exactly once, before the first frame, must still be in front of every later keyframe.
 * Every case below is a shape a real vendor's encoder has been seen to hand over, and every one of them
 * fails silently — a missing SPS is not an error, it is a grey tile.
 */
class AnnexBAssemblerTest {

    private fun nal(type: Int, vararg body: Byte): ByteArray =
        byteArrayOf((type and 0x1F).toByte()) + body

    private fun annexB(vararg nals: ByteArray): ByteArray = H264Streams.toAnnexB(nals.toList())

    private fun config(vararg parts: ByteArray): List<ByteBuffer> = parts.map { ByteBuffer.wrap(it) }

    /** The shape the assembler exists for: a hardware block emits bare SPS+PPS once, then bare IDR bodies. */
    @Test
    fun stampsBareParameterSetsInFrontOfEveryIdr() {
        val asm = AnnexBAssembler()
        asm.captureConfig(config(nal(7, 1, 2), nal(8, 3)))

        val idr = annexB(nal(5, 9, 9))
        val sealed = asm.seal(idr) as SealedUnit.Ready
        assertTrue(sealed.isIdr)
        val units = H264Streams.nalUnits(sealed.bytes)
        assertEquals(3, units.size)
        assertEquals(7, H264Streams.nalType(sealed.bytes, units[0]))
        assertEquals(8, H264Streams.nalType(sealed.bytes, units[1]))
        assertEquals(5, H264Streams.nalType(sealed.bytes, units[2]))
        // The prefix is a concatenation, so the encoder's own bytes must survive it untouched.
        assertEquals(
            sealed.bytes.size,
            H264Streams.joinAnnexB(listOf(nal(7, 1, 2), nal(8, 3))).size + idr.size
        )
    }

    /**
     * Some vendors hand over a `csd` that already opens with a start code. Re-prefixing it would put a
     * second delimiter inside the first NAL, and the decoder would read the SPS as two units — the
     * second of them type 0 — and reject the picture.
     */
    @Test
    fun normalisesAConfigThatAlreadyCarriesItsOwnDelimiters() {
        val asm = AnnexBAssembler()
        asm.captureConfig(
            config(
                byteArrayOf(0, 0, 0, 1) + nal(7, 1),
                byteArrayOf(0, 0, 1) + nal(8, 2)
            )
        )
        val sealed = asm.seal(annexB(nal(5, 9))) as SealedUnit.Ready
        val units = H264Streams.nalUnits(sealed.bytes)
        assertEquals("no empty unit invented between the two parameter sets", 3, units.size)
        assertEquals(7, H264Streams.nalType(sealed.bytes, units[0]))
        assertEquals(8, H264Streams.nalType(sealed.bytes, units[1]))
    }

    /** An encoder that puts the sets on the IDR itself must not be stamped a second, older copy. */
    @Test
    fun leavesAnIdrThatAlreadyCarriesParameterSetsAlone() {
        val asm = AnnexBAssembler()
        asm.captureConfig(config(nal(7, 1), nal(8, 2)))
        val selfContained = annexB(nal(7, 9), nal(8, 9), nal(5, 4))

        val sealed = asm.seal(selfContained) as SealedUnit.Ready
        assertTrue(sealed.isIdr)
        assertArrayEquals(selfContained, sealed.bytes)
    }

    /**
     * The failure the assembler is the guard against: a P-frame with no parameter set ever having
     * arrived is undecodable, so the lane has to be told to force a keyframe rather than ship noise.
     */
    @Test
    fun aFrameBeforeAnyParameterSetIsStrandedNotSent() {
        val asm = AnnexBAssembler()
        val sealed = asm.seal(annexB(nal(1, 4, 5)))
        assertTrue("silently forwarding this is how a tile greys out", sealed is SealedUnit.Stranded)
    }

    @Test
    fun interPredictedFramesRideOnceTheSetsExist() {
        val asm = AnnexBAssembler()
        asm.captureConfig(config(nal(7, 1), nal(8, 2)))

        val p = annexB(nal(1, 4, 5))
        val sealed = asm.seal(p) as SealedUnit.Ready
        assertFalse(sealed.isIdr)
        assertArrayEquals(p, sealed.bytes)
    }

    /**
     * A zero-length output buffer is a real thing a codec hands over between frames. Sending it would
     * put an empty payload behind the length prefix, which the peer reads as a frame and decodes as
     * nothing at all.
     */
    @Test
    fun emptyBuffersProduceNothing() {
        assertTrue(AnnexBAssembler().seal(ByteArray(0)) is SealedUnit.Stranded)

        val asm = AnnexBAssembler()
        asm.captureConfig(config(nal(7, 1)))
        assertTrue(asm.seal(ByteArray(0)) is SealedUnit.Stranded)
    }

    /**
     * An encoder that never announced config buffers is a degenerate case, not a reason to stall: a
     * self-describing stream still has to go out.
     */
    @Test
    fun missingConfigDoesNotBlockAStreamThatCarriesItsOwnSets() {
        val asm = AnnexBAssembler()
        val selfContained = annexB(nal(7, 1), nal(8, 2), nal(5, 3))
        val sealed = asm.seal(selfContained) as SealedUnit.Ready
        assertTrue(sealed.isIdr)
        assertArrayEquals(selfContained, sealed.bytes)
    }

    /**
     * `MediaFormat` hands out its `csd` ByteBuffers to whoever asks, so a read that advances the
     * position leaves the next reader — the codec itself, on a later `configure` — looking at a
     * truncated buffer.
     */
    @Test
    fun readingTheConfigLeavesTheBuffersWhereItFoundThem() {
        val sps = ByteBuffer.wrap(nal(7, 1, 2))
        val asm = AnnexBAssembler()
        asm.captureConfig(listOf(sps))
        assertEquals(0, sps.position())
        assertEquals(nal(7, 1, 2).size, sps.remaining())

        // A second capture of the same buffers must see the same bytes, not whatever the first read ate.
        asm.captureConfig(listOf(sps))
        val sealed = asm.seal(annexB(nal(5, 9))) as SealedUnit.Ready
        assertEquals(7, H264Streams.nalType(sealed.bytes, H264Streams.nalUnits(sealed.bytes)[0]))
    }

    /** A config where every slot is empty is not a config; the assembler must not invent one. */
    @Test
    fun emptyConfigSlotsAreNotTreatedAsParameterSets() {
        val asm = AnnexBAssembler()
        asm.captureConfig(config(ByteArray(0), ByteArray(0)))
        assertTrue(asm.seal(annexB(nal(1, 4))) is SealedUnit.Stranded)
    }
}
