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

    @Test
    fun forgetRemovesEverythingForAKey() {
        val r = PeerCapabilityRegistry()
        r.record(key, "2.1.0", listOf(PeerCapabilityRegistry.CAP_H264))
        r.forget(key)
        assertEquals(emptySet<String>(), r.capsFor(key))
    }
}
