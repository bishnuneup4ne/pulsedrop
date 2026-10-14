package com.pulsedrop.app.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bridge re-tags every relayed frame. A single off-by-one here does not crash anything -
 * it just plays everyone's audio as noise and every tile as a broken image, so the codec is
 * worth pinning down away from a device.
 */
class ConfFrameTest {

    @Test
    fun `audio round trips the origin and the payload`() {
        val pcm = ByteArray(3200) { (it % 251).toByte() }
        val encoded = ConfFrame.audio("a1b2c3d4e5", pcm)
        val decoded = ConfFrame.readAudio(encoded!!)!!
        assertEquals("a1b2c3d4e5", decoded.first)
        assertArrayEquals(pcm, decoded.second)
    }

    @Test
    fun `video round trips the origin and the jpeg`() {
        val jpeg = ByteArray(1024) { (it % 97).toByte() }
        val encoded = ConfFrame.video("deadbeef01", jpeg, ConfFrame.KIND_SCREEN)
        val decoded = ConfFrame.readVideo(encoded!!)!!
        assertEquals("deadbeef01", decoded.first)
        assertArrayEquals(jpeg, decoded.second)
        assertEquals(ConfFrame.KIND_SCREEN, ConfFrame.kind(encoded))
    }

    @Test
    fun `camera and screen kinds stay distinct`() {
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        val cam = ConfFrame.video("peer", jpeg, ConfFrame.KIND_CAMERA)!!
        val scr = ConfFrame.video("peer", jpeg, ConfFrame.KIND_SCREEN)!!
        assertEquals(ConfFrame.KIND_CAMERA, ConfFrame.kind(cam))
        assertEquals(ConfFrame.KIND_SCREEN, ConfFrame.kind(scr))
        // The payload must survive the kind byte exactly.
        assertArrayEquals(jpeg, ConfFrame.readVideo(cam)!!.second)
        assertArrayEquals(jpeg, ConfFrame.readVideo(scr)!!.second)
    }

    @Test
    fun `a non-ascii origin id keeps its byte length and offset`() {
        // Ids are hex in practice, but the length prefix counts bytes, not chars. A name that
        // encodes longer than it reads must not shift the payload by the wrong amount.
        val pcm = byteArrayOf(9, 9, 9, 9)
        val encoded = ConfFrame.audio("schön", pcm)!!
        val decoded = ConfFrame.readAudio(encoded)!!
        assertEquals("schön", decoded.first)
        assertArrayEquals(pcm, decoded.second)
    }

    @Test
    fun `an origin id too long to fit the length prefix is refused`() {
        assertNull(ConfFrame.audio("x".repeat(256), ByteArray(4)))
        assertNull(ConfFrame.video("x".repeat(256), ByteArray(4), ConfFrame.KIND_CAMERA))
        // 255 is the largest value the single length byte can hold.
        assertEquals(257, ConfFrame.audio("x".repeat(255), ByteArray(1))!!.size)
    }

    @Test
    fun `an empty origin id is refused rather than relaying an anonymous frame`() {
        assertNull(ConfFrame.audio("", ByteArray(8)))
        assertNull(ConfFrame.video("", ByteArray(8), ConfFrame.KIND_SCREEN))
    }

    @Test
    fun `truncated frames decode to null instead of throwing`() {
        val good = ConfFrame.audio("abcdef", ByteArray(64))!!
        assertNull(ConfFrame.readAudio(ByteArray(0)))
        assertNull(ConfFrame.readAudio(good.copyOfRange(0, 1)))
        // Claims a 6-byte id but only carries 3 bytes of it.
        assertNull(ConfFrame.readAudio(byteArrayOf(6, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())))
        // A video frame missing its kind byte.
        val video = ConfFrame.video("abcd", ByteArray(16), ConfFrame.KIND_CAMERA)!!
        assertNull(ConfFrame.readVideo(video.copyOfRange(0, 1 + 4)))
    }

    @Test
    fun `zero length frames decode to empty payloads and not a loss of the origin`() {
        val encoded = ConfFrame.audio("peer", ByteArray(0))!!
        val decoded = ConfFrame.readAudio(encoded)!!
        assertEquals("peer", decoded.first)
        assertEquals(0, decoded.second.size)
    }

    @Test
    fun `kind of a malformed frame falls back to camera`() {
        assertEquals(ConfFrame.KIND_CAMERA, ConfFrame.kind(ByteArray(0)))
    }
}
