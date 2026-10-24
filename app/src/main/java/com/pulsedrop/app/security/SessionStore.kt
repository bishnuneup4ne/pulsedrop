package com.pulsedrop.app.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A browser that types the PIN gets a *session*, not the PIN.
 *
 * Before this, `/api/auth` handed back `pinCode` itself and `isAuthorized` compared incoming
 * credentials to it, so the long-lived shared secret sat in every tab's `localStorage`, in the
 * `/api/events` URL, and in browser history — unexpiring, unrevocable per tab, and unaffected by a
 * server restart. A session is a bearer random that dies on its own: it expires, one can be pulled,
 * and changing the PIN or restarting the server takes all of them with it.
 *
 * Only the SHA-256 of an issued token is kept, so a memory dump or a borrowed phone cannot replay a
 * live session. The token itself never comes back out — issue it or lose it, and re-authenticate.
 *
 * Deliberately *not* bound to the client IP: every browser here is behind the same NAT or hotspot
 * that hands out addresses by DHCP, so an IP binding would log out a laptop that walked to the other
 * side of the house while telling an attacker on the same subnet nothing they could not spoof.
 *
 * In-memory is the policy, not an oversight: a restart invalidating sessions is the documented
 * behaviour, and there is nothing to persist and therefore nothing to protect at rest here.
 */
class SessionStore(private val now: () -> Long = { System.currentTimeMillis() }) {

    private class Session(val expiresAt: Long)

    private val sessions = LinkedHashMap<String, Session>()
    private val random = SecureRandom()

    /** Issues a fresh session and returns the bearer token. The caller shows this exactly once. */
    fun issue(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        // Hex rather than base64: `java.util.Base64` is API 26 and this app serves Android 7 phones,
        // and `android.util.Base64` would make the store untestable off a device. Hex also keeps a
        // token unambiguous anywhere it is compared or logged, which base64's `+/=` do not.
        val token = bytes.toHex()
        synchronized(sessions) {
            pruneLocked()
            while (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
            sessions[hash(token)] = Session(now() + SESSION_TTL_MS)
        }
        return token
    }

    /** True only for a token that was issued by this store, has not expired, and was not revoked. */
    fun isValid(token: String): Boolean = synchronized(sessions) {
        pruneLocked()
        (sessions[hash(token)]?.expiresAt ?: 0L) > now()
    }

    fun revoke(token: String): Boolean = synchronized(sessions) { sessions.remove(hash(token)) != null }

    /** Every session dies when the PIN changes, or when the owner says "log all browsers out". */
    fun revokeAll(): Int = synchronized(sessions) { sessions.size.also { sessions.clear() } }

    fun activeCount(): Int = synchronized(sessions) { pruneLocked(); sessions.size }

    /** Expiry of a token this store issued, or 0 when unknown. Lets the UI count down a session. */
    fun remainingSeconds(token: String): Long = synchronized(sessions) {
        val deadline = sessions[hash(token)]?.expiresAt ?: 0L
        ((deadline - now()) / 1000L).coerceAtLeast(0L)
    }

    private fun pruneLocked() {
        val current = now()
        sessions.entries.filter { it.value.expiresAt <= current }.map { it.key }.forEach { sessions.remove(it) }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        /** 256 bits, the same width as the pairing keys, so no token is guessable by exhaustion. */
        private const val TOKEN_BYTES = 32
        private const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L

        /**
         * Caps what an authenticated host can allocate. Sessions are only minted on a correct PIN,
         * so this bounds a shared phone left logged in from many tabs, not an attacker's guesses.
         */
        private const val MAX_SESSIONS = 64
    }
}
