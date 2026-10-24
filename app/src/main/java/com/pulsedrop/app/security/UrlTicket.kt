package com.pulsedrop.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A short-lived permission to open one URL that cannot carry a header.
 *
 * The event stream is the only browser request with that problem: `EventSource` chooses its own
 * request and offers no way to attach `X-Pulse-Auth`, so the tab used to put its session token in
 * the query string. That token is a 12-hour bearer credential, and the URL it rode in is copied
 * into history, into the referrer of anything the phone's page links to, and into any screenshot of
 * a devtools network panel. A ticket is the credential a URL *should* carry: it names one purpose,
 * dies in minutes, only works from the address that asked for it, and is worthless after a restart
 * or a PIN change.
 *
 * The signature is HMAC-SHA256 over `scope`, subject address and expiry. Nothing is stored, so
 * there is no table to grow, prune, or leak, and no bookkeeping to get wrong: a ticket the phone
 * cannot verify with its current secret was never issued by this process.
 */
class UrlTicket(
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile
    private var secret = newSecret()

    /** Issues a ticket for [scope] that only [subject] may spend, until [ttlMs] has passed. */
    fun issue(scope: String, subject: String, ttlMs: Long = TTL_MS): String {
        val expiry = now() + ttlMs
        return expiry.toString(16) + "." + sign(scope, subject, expiry)
    }

    /** True only for an unexpired ticket this process issued for the same scope and subject. */
    fun allows(ticket: String, scope: String, subject: String): Boolean {
        if (ticket.isEmpty() || ticket.length > MAX_TICKET_CHARS) return false
        val dot = ticket.indexOf('.')
        if (dot <= 0 || dot == ticket.length - 1) return false
        val expiry = ticket.substring(0, dot).toLongOrNull(16) ?: return false
        if (now() > expiry) return false
        val expected = sign(scope, subject, expiry)
        return MessageDigest.isEqual(expected.toByteArray(), ticket.substring(dot + 1).toByteArray())
    }

    /**
     * Forgets every ticket in flight. Called where sessions are revoked, so "log all browsers out"
     * does not leave a minutes-long tail of stream access behind.
     */
    fun rotate() {
        secret = newSecret()
    }

    private fun sign(scope: String, subject: String, expiry: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("$scope\n$subject\n$expiry".toByteArray()).toHex()
    }

    private fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val SECRET_BYTES = 32

        /**
         * Long enough for a reconnect after a sleeping laptop, short enough that a URL lifted from
         * history is not a way back in. The tab mints a new one when the stream drops.
         */
        private const val TTL_MS = 5 * 60 * 1000L

        /** An expiry, a dot and a 64-character MAC; anything longer is not a ticket we issued. */
        private const val MAX_TICKET_CHARS = 128
    }
}
