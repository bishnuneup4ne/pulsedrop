package com.pulsedrop.app.security

/**
 * A per-address flood guard, not a quota.
 *
 * The body ceilings answered "how much may one request cost"; nothing answered "how often". A tab
 * inside every ceiling could still ask as fast as the socket allowed, and the cheap routes — the
 * ones that parse nothing — were the cheapest to spam. This is a token bucket per normalized
 * address: [capacity] at rest, refilled at [refillPerSecond], so an ordinary tab that polls and
 * streams a call never notices, while a loop is answered 429 until it slows down.
 *
 * It is deliberately small and in memory. A restart forgets it, which is the same posture as the
 * PIN lockout: this protects the phone for the length of a session, not an account.
 */
class RequestRateLimiter(
    private val capacity: Double = 120.0,
    private val refillPerSecond: Double = 30.0,
    private val maxKeys: Int = 1024,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private class Bucket(var tokens: Double, var lastRefill: Long)

    private val buckets = HashMap<String, Bucket>()

    fun allow(key: String): Boolean = synchronized(buckets) {
        val nowMs = now()
        if (buckets.size >= maxKeys) pruneLocked(nowMs)
        val bucket = buckets.getOrPut(key) { Bucket(capacity, nowMs) }
        val elapsed = (nowMs - bucket.lastRefill).coerceAtLeast(0L)
        bucket.tokens = (bucket.tokens + elapsed * refillPerSecond / 1000.0).coerceAtMost(capacity)
        bucket.lastRefill = nowMs
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        true
    }

    /** Exposed so a test can prove the bookkeeping itself is bounded, not just the refusals. */
    internal fun trackedAddresses(): Int = synchronized(buckets) { buckets.size }

    /** Forget addresses that are not currently limited. A flood cannot grow the map without bound. */
    private fun pruneLocked(nowMs: Long) {
        buckets.entries.removeAll { (_, b) ->
            b.tokens >= capacity && nowMs - b.lastRefill > IDLE_MS
        }
        if (buckets.size >= maxKeys) {
            buckets.entries
                .sortedBy { it.value.lastRefill }
                .take(buckets.size - maxKeys + 1)
                .forEach { buckets.remove(it.key) }
        }
    }

    private companion object {
        const val IDLE_MS = 60_000L
    }
}
