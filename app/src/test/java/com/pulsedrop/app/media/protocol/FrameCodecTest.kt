package com.pulsedrop.app.media.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The wire contract for every phone since v1.7: `[1B type][4B BE len][payload]`.
 *
 * These vectors are written out by hand, not generated, so if a refactor ever shifts a byte
 * by one the test fails while mixed-version calls would otherwise just quietly tear down.
 * Types 1..7 are frozen; only 8+ may be added.
 */
class FrameCodecTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun typeNumbersAreFrozen() {
        assertEquals(1, TYPE_AUDIO)
        assertEquals(2, TYPE_CAMERA)
        assertEquals(3, TYPE_SCREEN)
        assertEquals(4, TYPE_SCREEN_STATE)
        assertEquals(5, TYPE_CONF_AUDIO)
        assertEquals(6, TYPE_CONF_VIDEO)
        assertEquals(7, TYPE_CONF_STATE)
    }

    @Test
    fun v2AllocatesOnlyNewNumbers() {
        // A v1.7 peer must be able to skip these without misreading the stream.
        assertEquals(8, TYPE_CONTROL)
        assertEquals(9, TYPE_H264)
        assertEquals(10, TYPE_CONF_H264)
        assertEquals(11, TYPE_SCREEN_H264)
    }

    /**
     * The whole promise the shared-screen lane is built on: a peer that has never heard of type 11
     * consumes it by length and finds the frame behind it unshifted. Anything wrong in the header
     * would not just lose the encoded share, it would tear the JPEG frames that follow it on the same
     * socket — so the bytes are written out here rather than derived from the encoder.
     */
    @Test
    fun anOldPeerReadsPastAnUnknownTypeIntact() {
        val wire = FrameCodec.encode(TYPE_SCREEN_H264, byteArrayOf(0x67, 0x01, 0x02)) +
            FrameCodec.encode(TYPE_SCREEN, byteArrayOf(0x11, 0x22))
        assertArrayEquals(hex("0B 00 00 00 03 67 01 02 03 00 00 00 02 11 22"), wire)

        val input = DataInputStream(ByteArrayInputStream(wire))
        val unknown = FrameCodec.readFrame(input)!!
        assertEquals(11, unknown.type)

        val jpeg = FrameCodec.readFrame(input)!!
        assertEquals(TYPE_SCREEN, jpeg.type)
        assertArrayEquals(byteArrayOf(0x11, 0x22), jpeg.payload)
        assertNull(FrameCodec.readFrame(input))
    }

    @Test
    fun encodeMatchesGoldenVectors() {
        assertArrayEquals(hex("01 00 00 00 03 0A 14 1E"), FrameCodec.encode(1, byteArrayOf(0x0A, 0x14, 0x1E)))
        assertArrayEquals(hex("02 00 00 00 00"), FrameCodec.encode(2, ByteArray(0)))
        assertArrayEquals(
            hex("07 00 00 01 00") + ByteArray(256) { 0xFF.toByte() },
            FrameCodec.encode(7, ByteArray(256) { 0xFF.toByte() })
        )
        // The 4-byte length is big-endian and crosses the byte-boundary where a LE bug shows.
        assertArrayEquals(
            hex("03 00 01 02 03") + ByteArray(0x10203),
            FrameCodec.encode(3, ByteArray(0x10203))
        )
    }

    @Test
    fun socketWriteReadRoundTrip() {
        val sink = ByteArrayOutputStream()
        val out = DataOutputStream(sink)
        FrameCodec.writeTo(out, TYPE_AUDIO, byteArrayOf(1, 2, 3, 4))
        FrameCodec.writeTo(out, TYPE_SCREEN_STATE, "hi".toByteArray())
        // Same bytes as hand-written goldens: header first, payload after.
        assertArrayEquals(hex("01 00 00 00 04 01 02 03 04 04 00 00 00 02 68 69"), sink.toByteArray())

        val input = DataInputStream(ByteArrayInputStream(sink.toByteArray()))
        val a = FrameCodec.readFrame(input)!!
        assertEquals(TYPE_AUDIO, a.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), a.payload)
        val b = FrameCodec.readFrame(input)!!
        assertEquals(TYPE_SCREEN_STATE, b.type)
        assertEquals("hi", String(b.payload))
        assertNull(FrameCodec.readFrame(input)) // clean EOF
    }

    @Test
    fun readFrameRejectsCorruptLength() {
        val input = DataInputStream(ByteArrayInputStream(hex("01 7F FF FF FF 00")))
        assertNull(FrameCodec.readFrame(input))
    }

    @Test
    fun batchParserReassemblesAcrossArbitrarySplits() {
        val wire = FrameCodec.encode(2, ByteArray(37) { (it % 251).toByte() }) +
            FrameCodec.encode(5, byteArrayOf(9, 8, 7))
        val parser = FrameCodec.BatchParser()
        val got = ArrayList<FrameCodec.Frame>()
        // Feed one byte at a time: every header and payload position becomes a split point.
        for (i in wire.indices) {
            got += parser.feed(byteArrayOf(wire[i]))
        }
        assertEquals(2, got.size)
        assertEquals(2, got[0].type)
        assertEquals(37, got[0].payload.size)
        assertEquals(5, got[1].type)
        assertArrayEquals(byteArrayOf(9, 8, 7), got[1].payload)
    }

    @Test
    fun batchParserDropsGarbageBeyondCap() {
        val parser = FrameCodec.BatchParser()
        // len exceeds MAX_PAYLOAD: the old loop swallowed the buffer; this must not hang or OOM.
        val frames = parser.feed(hex("01 FF FF FF FF"))
        assertEquals(0, frames.size)
        // After the drop the parser is usable again.
        val ok = parser.feed(FrameCodec.encode(1, byteArrayOf(5)))
        assertEquals(1, ok.size)
        assertArrayEquals(byteArrayOf(5), ok[0].payload)
    }

    @Test
    fun batchParserHonoursALoweredCap() {
        // 1024 bytes is legal on the wire but above the 64-byte cap this caller set. The batch is
        // dropped without waiting for the payload, so the bytes can never be accumulated 64 at a
        // time by repeated posts.
        val parser = FrameCodec.BatchParser(64)
        assertEquals(0, parser.feed(hex("02 00 00 04 00")).size)
        val exact = parser.feed(FrameCodec.encode(2, ByteArray(64)))
        assertEquals("a frame at the cap is still a frame", 1, exact.size)
        assertEquals(64, exact[0].payload.size)
    }

    @Test
    fun batchParserResetDiscardsPartial() {
        val parser = FrameCodec.BatchParser()
        parser.feed(hex("01 00 00 00 10 01 02")) // promised 16-byte payload, only 2 arrived
        parser.reset()
        val frames = parser.feed(FrameCodec.encode(1, byteArrayOf(7)))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(7), frames[0].payload)
    }
}
