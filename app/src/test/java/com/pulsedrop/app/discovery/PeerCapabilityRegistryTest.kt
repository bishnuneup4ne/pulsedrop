package com.pulsedrop.app.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCapabilityRegistryTest {

    private val key = "ip/192.168.1.9"

    @Test
    fun unknownPeerHasNothing() {
        val r = PeerCapabilityRegistry()
        assertEquals(emptySet<String>(), r.capsFor(key))
        assertEquals("", r.versionOf(key))
        assertFalse(r.has(key, PeerCapabilityRegistry.CAP_H264))
        assertFalse(r.atLeast(key, 2, 1))
    }

    @Test
    fun announcementsOnlyAddCaps() {
        val r = PeerCapabilityRegistry()
        r.record(key, "2.1.0", listOf(PeerCapabilityRegistry.CAP_H264))
        // A beacon lost mid-sweep must not downgrade a peer.
        r.record(key, "2.1.0", listOf(PeerCapabilityRegistry.CAP_CHAT_EXT1))
        assertTrue(r.has(key, PeerCapabilityRegistry.CAP_H264))
        assertTrue(r.has(key, PeerCapabilityRegistry.CAP_CHAT_EXT1))
    }

    @Test
    fun blankVersionKeepsTheOldOne() {
        val r = PeerCapabilityRegistry()
        r.record(key, "2.1.0", null)
        r.record(key, "", null)
        assertEquals("2.1.0", r.versionOf(key))
        r.record(key, "2.2.1", null)
        assertEquals("2.2.1", r.versionOf(key))
    }

    @Test
    fun atLeastMatchesPairingHandshakeSemantics() {
        val r = PeerCapabilityRegistry()
        r.record(key, "2.1.0", null)
        assertTrue(r.atLeast(key, 2, 1))
        assertTrue(r.atLeast(key, 1, 9))
        assertFalse(r.atLeast(key, 2, 2))
        r.record("pid/nonsense", "banana", null)
        assertFalse(r.atLeast("pid/nonsense", 1, 0))
    }

    @Test
    fun localDropGateStaysOnTheMajorTwoPrefix() {
        // LanScanner treats anything else as a plain web page; this gate must not move.
        assertTrue(PeerCapabilityRegistry.isLocalDropVersion("2.1.0"))
        assertTrue(PeerCapabilityRegistry.isLocalDropVersion("2.0.0"))
        assertFalse(PeerCapabilityRegistry.isLocalDropVersion("3.0.0"))
        assertFalse(PeerCapabilityRegistry.isLocalDropVersion(""))
    }

    /**
     * `/api/info` announces these, so the `h264` half of the list is a claim about hardware rather
     * than about the build: a phone that promises it without a hardware AVC encoder makes its peer
     * abandon the JPEG lane as well, and both ends then encode in software at once.
     *
     * `chat-ext1` rides along whatever else is claimable, because it is a wire format and not a
     * sensor: a phone with no encoder still answers to reactions and read receipts.
     */
    @Test
    fun h264IsOnlyClaimedWithAHardwareEncoder() {
        val saved = com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider
        try {
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = {
                listOf(com.pulsedrop.app.media.h264.MediaCodecListSnapshot.Entry("OMX.qcom.video.encoder.avc", true))
            }
            assertEquals(
                listOf(PeerCapabilityRegistry.CAP_H264, PeerCapabilityRegistry.CAP_CHAT_EXT1),
                PeerCapabilityRegistry.localCaps()
            )

            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = {
                listOf(com.pulsedrop.app.media.h264.MediaCodecListSnapshot.Entry("OMX.google.h264.encoder", true))
            }
            assertEquals(listOf(PeerCapabilityRegistry.CAP_CHAT_EXT1), PeerCapabilityRegistry.localCaps())

            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = { emptyList() }
            assertEquals(listOf(PeerCapabilityRegistry.CAP_CHAT_EXT1), PeerCapabilityRegistry.localCaps())
        } finally {
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = saved
        }
    }

    /**
     * `screen-h264` is the one cap answered off the decoder, and it is the promise the shared-screen
     * lane runs on: encoding a display is pointless unless the far end owns both a hardware AVC
     * decoder and a surface to present it onto.
     *
     * The two halves must be claimable independently. A phone with an encoder but no decoder can send
     * video and must not accept a screen; a phone with the reverse must not be talked into encoding
     * 1080p30 on a software block. The fixtures above deliberately leave `isAvcDecoder` at its default
     * of false, which is why they see no `screen-h264` in the list.
     */
    @Test
    fun screenH264IsClaimedOffTheDecoderAlone() {
        val saved = com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider
        val qcomEncoder = com.pulsedrop.app.media.h264.MediaCodecListSnapshot.Entry(
            "OMX.qcom.video.encoder.avc", true
        )
        val qcomDecoder = com.pulsedrop.app.media.h264.MediaCodecListSnapshot.Entry(
            "OMX.qcom.video.decoder.avc", false, isAvcDecoder = true
        )
        try {
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = { listOf(qcomEncoder, qcomDecoder) }
            assertEquals(
                listOf(
                    PeerCapabilityRegistry.CAP_H264,
                    PeerCapabilityRegistry.CAP_SCREEN_H264,
                    PeerCapabilityRegistry.CAP_CHAT_EXT1
                ),
                PeerCapabilityRegistry.localCaps()
            )

            // Encoder with no decoder: the h264 half survives, the screen half does not.
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = { listOf(qcomEncoder) }
            assertEquals(
                listOf(PeerCapabilityRegistry.CAP_H264, PeerCapabilityRegistry.CAP_CHAT_EXT1),
                PeerCapabilityRegistry.localCaps()
            )

            // Decoder with no encoder: a viewer that will never be asked to encode.
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = { listOf(qcomDecoder) }
            assertEquals(
                listOf(PeerCapabilityRegistry.CAP_SCREEN_H264, PeerCapabilityRegistry.CAP_CHAT_EXT1),
                PeerCapabilityRegistry.localCaps()
            )

            // A software decoder is not a promise either: it cannot hold a 1080p picture in realtime.
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = {
                listOf(
                    com.pulsedrop.app.media.h264.MediaCodecListSnapshot.Entry(
                        "c2.android.avc.decoder", false, isAvcDecoder = true
                    )
                )
            }
            assertFalse(PeerCapabilityRegistry.localCaps().contains(PeerCapabilityRegistry.CAP_SCREEN_H264))
        } finally {
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = saved
        }
    }

    /** Conference fan-out is a separate promise, and this build cannot render it yet. */
    @Test
    fun conf2IsNeverClaimed() {
        val saved = com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider
        try {
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = {
                listOf(com.pulsedrop.app.media.h264.MediaCodecListSnapshot.Entry("c2.qti.avc.encoder", true))
            }
            assertFalse(PeerCapabilityRegistry.localCaps().contains(PeerCapabilityRegistry.CAP_CONF2))
        } finally {
            com.pulsedrop.app.media.h264.MediaCodecListSnapshot.provider = saved
        }
    }

    @Test
    fun forgetRemovesEverythingForAKey() {
        val r = PeerCapabilityRegistry()
        r.record(key, "2.1.0", listOf(PeerCapabilityRegistry.CAP_H264))
        r.forget(key)
        assertEquals(emptySet<String>(), r.capsFor(key))
    }
}
