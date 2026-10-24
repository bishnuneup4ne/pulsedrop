package com.pulsedrop.app.security

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential a browser keeps is now the thing that must be right, so it gets tested directly:
 * randomness, expiry, revocation, and the fact that a stored value is never the secret itself.
 */
class SessionStoreTest {

    private fun storeAt(startMs: Long = 1_700_000_000_000L): Pair<SessionStore, (Long) -> Unit> {
        var current = startMs
        val store = SessionStore { current }
        return store to { current = it }
    }

    @Test
    fun `an issued token is not the pin and is 256 bits of randomness`() {
        val pin = "4821"
        val (store, _) = storeAt()
        val token = store.issue()
        assertNotEquals("the PIN must never be the session credential", pin, token)
        // 32 random bytes, hex-encoded: 64 characters, URL-safe without any encoding step.
        assertEquals(64, token.length)
        assertTrue("a token must be plain hex so it needs no escaping in a header or a URL", token.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `two sessions never share a token`() {
        val (store, _) = storeAt()
        assertNotEquals(store.issue(), store.issue())
    }

    @Test
    fun `a token validates until it expires and not one second after`() {
        val (store, advance) = storeAt()
        val token = store.issue()
        assertTrue(store.isValid(token))
        assertEquals(12 * 60 * 60, store.remainingSeconds(token).toInt())
        advance(1_700_000_000_000L + 12 * 60 * 60 * 1000L - 1)
        assertTrue("still alive inside the window", store.isValid(token))
        advance(1_700_000_000_000L + 12 * 60 * 60 * 1000L)
        assertFalse("expiry is not advisory", store.isValid(token))
        assertEquals(0, store.remainingSeconds(token))
    }

    @Test
    fun `logout revokes exactly the session that logged out`() {
        val (store, _) = storeAt()
        val first = store.issue()
        val second = store.issue()
        assertTrue(store.revoke(first))
        assertFalse("a revoked token is a dead token", store.isValid(first))
        assertTrue("and it did not take the other browser with it", store.isValid(second))
        assertFalse("revoking twice reports nothing left to do", store.revoke(first))
        assertEquals(1, store.activeCount())
    }

    @Test
    fun `changing the pin revokes every browser`() {
        val (store, _) = storeAt()
        store.issue(); store.issue(); store.issue()
        assertEquals(3, store.activeCount())
        assertEquals(3, store.revokeAll())
        assertEquals(0, store.activeCount())
    }

    @Test
    fun `an unknown or forged credential is rejected`() {
        val (store, _) = storeAt()
        store.issue()
        assertFalse(store.isValid(""))
        assertFalse(store.isValid("4821"))
        assertFalse(store.isValid("not-base64-but-still-garbage!!"))
        val other = SessionStore { System.currentTimeMillis() }
        assertFalse("a token from a different phone is worthless here", other.isValid(store.issue()))
    }

    @Test
    fun `only a hash of the token is retained`() {
        val (store, _) = storeAt()
        val token = store.issue()
        // Reflection over the live map is the only honest check that nothing stores the bearer value.
        val field = SessionStore::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(store) as Map<String, Any>
        val session = map.values.single()
        assertEquals(1, map.size)
        val storedKey = map.keys.single()
        assertFalse("the bearer token must not be the lookup key", map.containsKey(token))
        assertEquals("a stored credential is a digest, not the secret", 64, storedKey.length)
        val expected = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, storedKey)
        // No reachable value carries the token either — the session holds a deadline and nothing else.
        for (f in session.javaClass.declaredFields) {
            f.isAccessible = true
            assertFalse("a stored session must not retain the bearer value", f.get(session)?.toString() == token)
        }
    }

    @Test
    fun `the store is bounded so a logged-in crowd cannot grow it without end`() {
        val (store, advance) = storeAt()
        val tokens = (1..70).map { store.issue() }
        assertTrue("capped", store.activeCount() <= 64)
        // Oldest-first eviction: the first issues are gone, the newest are alive.
        assertFalse(store.isValid(tokens.first()))
        assertTrue(store.isValid(tokens.last()))
        advance(1_700_000_000_000L + 13L * 60 * 60 * 1000)
        assertEquals("expired sessions are pruned, not merely hidden", 0, store.activeCount())
    }

    @Test
    fun `a restart starts from no sessions at all`() {
        val (before, _) = storeAt()
        val token = before.issue()
        val after = SessionStore { System.currentTimeMillis() }
        assertFalse("restarting the server logs every browser out", after.isValid(token))
    }
}
