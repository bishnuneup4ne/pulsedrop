package com.pulsedrop.app.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock

/**
 * Whether an unsolicited check is worth making a request for.
 *
 * The two timestamps are different on purpose. `lastAttempt` is what a network that flaps twice a
 * second runs into, and it keeps that from becoming two requests a second. `lastSuccess` is the only
 * thing that earns the long rest: a check that failed because there was no internet must not then sit
 * out the reconnection it was waiting for, which is the entire reason this policy exists.
 */
internal fun shouldAutoCheck(
    now: Long,
    lastAttempt: Long,
    lastSuccess: Long,
    alreadyKnows: Boolean,
    spacingMs: Long = UpdateWatch.ATTEMPT_SPACING_MS,
    refreshMs: Long = UpdateWatch.RECHECK_AFTER_SUCCESS_MS
): Boolean {
    if (alreadyKnows) return false
    if (lastAttempt >= 0 && now - lastAttempt < spacingMs) return false
    return lastSuccess < 0 || now - lastSuccess >= refreshMs
}

/**
 * Follows the network so a phone that opened with no internet still finds out about a release the
 * moment it has some, instead of waiting for the next launch. The launch check itself belongs to the
 * caller; this is the part that keeps working after the app is already open.
 */
class UpdateWatch(
    context: Context,
    private val manager: UpdateManager,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    companion object {
        /** Two changes this close together are one network event with two names, not two checks. */
        const val ATTEMPT_SPACING_MS = 10_000L

        /** How long a check that actually reached GitHub is allowed to rest before asking again. */
        const val RECHECK_AFTER_SUCCESS_MS = 15L * 60L * 1000L
    }

    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastAttempt = -1L

    fun start() {
        if (callback != null) return
        checkNow(force = true)
        val cm = connectivity ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = checkNow(force = false)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // "Available" means a link; "validated" means something answered on the other end.
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    checkNow(force = false)
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            // No default network to follow, or a ROM that refuses the callback. The check above ran
            // anyway, and the next launch registers again.
        }
    }

    fun stop() {
        val cm = connectivity
        callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        callback = null
    }

    private fun checkNow(force: Boolean) {
        val now = clock()
        if (!force && !shouldAutoCheck(now, lastAttempt, manager.lastSuccessAt, manager.update.value != null)) {
            return
        }
        lastAttempt = now
        manager.check(auto = true)
    }
}
