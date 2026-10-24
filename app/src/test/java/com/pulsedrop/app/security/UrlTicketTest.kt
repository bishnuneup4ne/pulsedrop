package com.pulsedrop.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTicketTest {

    private var clock = 1_700_000_000_000L
    private val tickets = UrlTicket(now = { clock })

    @Test
    fun `a ticket opens the scope it was issued for from the address that asked`() {
        val t = tickets.issue("events", "192.168.1.9")
        assertTrue(tickets.allows(t, "events", "192.168.1.9"))
    }

    @Test
    fun `a ticket expires`() {
        val t = tickets.issue("events", "192.168.1.9", ttlMs = 1_000)
        clock += 999
        assertTrue(tickets.allows(t, "events", "192.168.1.9"))
        clock += 2
        assertFalse(tickets.allows(t, "events", "192.168.1.9"))
    }

    @Test
    fun `a ticket for one scope does not open another`() {
        val t = tickets.issue("events", "192.168.1.9")
        assertFalse(tickets.allows(t, "download:abc", "192.168.1.9"))
    }

    @Test
    fun `a ticket from another address is refused`() {
        val t = tickets.issue("events", "192.168.1.9")
        assertFalse(tickets.allows(t, "events", "192.168.1.10"))
    }

    @Test
    fun `moving the expiry forward invalidates the signature`() {
        val t = tickets.issue("events", "192.168.1.9", ttlMs = 1_000)
        val parts = t.split(".")
        val stretched = (parts[0].toLong(16) + 3_600_000).toString(16) + "." + parts[1]
        assertFalse(tickets.allows(stretched, "events", "192.168.1.9"))
    }

    @Test
    fun `a single flipped character invalidates the ticket`() {
        val t = tickets.issue("events", "192.168.1.9")
        val flipped = t.dropLast(1) + if (t.last() == '0') '1' else '0'
        assertFalse(tickets.allows(flipped, "events", "192.168.1.9"))
    }

    @Test
    fun `rotation invalidates every ticket in flight`() {
        val t = tickets.issue("events", "192.168.1.9")
        tickets.rotate()
        assertFalse(tickets.allows(t, "events", "192.168.1.9"))
    }

    @Test
    fun `a ticket minted after rotation works`() {
        tickets.rotate()
        val t = tickets.issue("events", "192.168.1.9")
        assertTrue(tickets.allows(t, "events", "192.168.1.9"))
    }

    @Test
    fun `malformed tickets are refused rather than parsed loosely`() {
        val cases = listOf("", ".", "abc", ".abc", "abc.", "1.abc", "-1." + "0".repeat(64),
            "gg.1234", "1", "1..2", "f".repeat(200) + ".0")
        cases.forEach { assertFalse("accepted <$it>", tickets.allows(it, "events", "192.168.1.9")) }
    }

    @Test
    fun `a refused ticket cannot be retried with a different subject after rotation`() {
        val t = tickets.issue("events", "192.168.1.9")
        tickets.rotate()
        assertFalse(tickets.allows(t, "events", "192.168.1.10"))
    }

    @Test
    fun `rotation changes the signature for the same scope, subject and instant`() {
        clock = 1_700_000_000_000L
        val before = tickets.issue("events", "192.168.1.9")
        tickets.rotate()
        val after = tickets.issue("events", "192.168.1.9")
        assertNotEquals(before, after)
        assertTrue(tickets.allows(after, "events", "192.168.1.9"))
    }
}
