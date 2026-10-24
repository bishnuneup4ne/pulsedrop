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
