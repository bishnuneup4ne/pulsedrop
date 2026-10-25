package com.pulsedrop.app.server

import java.io.File

/**
 * How many bytes one request body may spend on this volume.
 *
 * NanoHTTPD 2.3.1 puts a multipart body in `cacheDir` before this server sees any of it, so the
 * ceiling that protects the phone is the phone's own free space, not a number invented in a
 * constants block: a legitimate 4 GB transfer must keep working on a device with 40 GB free and
 * must be refused on one with 200 MB left, and only `StatFs` knows which device this is at request
 * time.
 *
 * `headroom` is not paranoia. `StatFs` reports what the filesystem says is free, and a write that
 * fills it to the last byte takes down more than the transfer that did it — the server, the received
 * files and the app's own database all live on the same volume.
 *
 * This fails to a ceiling rather than to "unlimited", which is deliberately the opposite of the
 * existing free-space preflight: `hasFreeSpaceFor` answers `true` when `StatFs` throws, because
 * refusing a legitimate transfer over a measurement hiccup is the worse bug there. Here the same
 * hiccup would re-open the disk-fill this exists to close.
 */
internal fun uploadBudgetBytes(dir: File, headroomBytes: Long = 64L * 1024 * 1024): Long = try {
    val stat = android.os.StatFs(dir.absolutePath)
    (stat.availableBytes - headroomBytes).coerceAtLeast(MIN_UPLOAD_BUDGET)
} catch (e: Exception) {
    DEFAULT_UPLOAD_BUDGET
}

/** Below this there is no point accepting a body at all: the transfer would fail on the spot. */
private const val MIN_UPLOAD_BUDGET = 32L * 1024 * 1024

/** Used only when the volume cannot be measured. 2 GiB is more than a phone should be asked to spare. */
private const val DEFAULT_UPLOAD_BUDGET = 2L * 1024 * 1024 * 1024

/**
 * The per-request ceiling answers one body; it cannot answer two that arrive together. Two tabs may
 * each declare less than the budget, both pass, and together fill the cache volume before either
 * finishes. This reserves each admitted request's declared length until its body has been buffered
 * and copied, so the sum of uploads alive at one moment stays under what the volume can spare.
 *
 * The reservation is the declared length, not the bytes seen so far: NanoHTTPD writes the body to
 * `cacheDir` before the route can inspect any of it, so the number that matters is the one promised,
 * and it is the same number the per-request ceiling already trusts. A request that under-declares is
 * not read past that length (`R-11`).
 */
internal class InFlightUploadBudget {

    private val reserved = java.util.concurrent.atomic.AtomicLong(0)

    /** True when [bytes] still fits under [budget]; the caller must [release] exactly once. */
    fun reserve(bytes: Long, budget: Long): Boolean {
        val ask = bytes.coerceAtLeast(0)
        while (true) {
            val current = reserved.get()
            if (ask > budget - current) return false
            if (reserved.compareAndSet(current, current + ask)) return true
        }
    }

    fun release(bytes: Long) {
        reserved.addAndGet(-bytes.coerceAtLeast(0))
    }

    /** What admitted uploads currently promise to write; a leak here would refuse later uploads. */
    fun reservedBytes(): Long = reserved.get()
}
