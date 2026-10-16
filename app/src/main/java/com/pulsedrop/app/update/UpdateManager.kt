package com.pulsedrop.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.pulsedrop.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One newer GitHub release, with the APK this app can fetch without leaving it. */
data class UpdateInfo(
    val versionName: String,
    val releaseName: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/** One published release exactly as GitHub describes it — the raw material of a changelog. */
data class ReleaseInfo(
    val versionName: String,
    val releaseName: String,
    val notes: String,
    val publishedAt: String,
    val isPrerelease: Boolean,
    val hasApk: Boolean,
    val apkDownloads: Int
)

/** Newest first, by the same version comparison the updater itself uses. */
internal fun sortReleases(list: List<ReleaseInfo>): List<ReleaseInfo> = list.sortedWith { a, b ->
    when {
        UpdateManager.isNewer(a.versionName, b.versionName) -> -1
        UpdateManager.isNewer(b.versionName, a.versionName) -> 1
        else -> 0
    }
}

/**
 * Checks the project's GitHub releases on every launch and downloads the update in-app, so
 * users never have to open a browser and hunt for the APK. Installs go through the standard
 * "unknown sources" flow, which Android requires for any app not installed from Play.
 */
class UpdateManager(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val REPO = "bishnuneup4ne/pulsedrop"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases"

        /** True when `a` is a strictly newer dotted version than `b`. */
        fun isNewer(a: String, b: String): Boolean {
            val pa = a.trim().removePrefix("v").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.trim().removePrefix("v").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    /**
     * The last update a check actually confirmed, kept even when the popup was snoozed:
     * closing a dialog is not amnesia, and the Update Center would otherwise claim
     * "no check has answered" right under "last check reached GitHub 3 minutes ago".
     */
    private val _lastKnown = MutableStateFlow<UpdateInfo?>(null)
    val lastKnown: StateFlow<UpdateInfo?> = _lastKnown.asStateFlow()

    /** Null when idle; 0f..1f while downloading; 1f with [installedApk] set when done. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _downloadedApk = MutableStateFlow<File?>(null)
    val downloadedApk: StateFlow<File?> = _downloadedApk.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Lifetime APK downloads across every published release, from GitHub's own counters. */
    private val _downloads = MutableStateFlow<Int?>(null)
    val downloads: StateFlow<Int?> = _downloads.asStateFlow()

    /** Every published release GitHub lists, or null until the first fetch answers. */
    private val _releases = MutableStateFlow<List<ReleaseInfo>?>(null)
    val releases: StateFlow<List<ReleaseInfo>?> = _releases.asStateFlow()

    /** Why the last release-list fetch failed, in GitHub's terms — never a swallowed error. */
    private val _releasesError = MutableStateFlow<String?>(null)
    val releasesError: StateFlow<String?> = _releasesError.asStateFlow()

    private var releasesFetching = false

    private var updateVersion = ""

    /** True while a check request is actually in flight, so the UI can disable its own button. */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val settingsPrefs =
        context.getSharedPreferences("localdrop_settings", Context.MODE_PRIVATE)

    /** When a check last actually reached GitHub and read an answer, on the monotonic clock. */
    private var _lastSuccessAt = 0L
    val lastSuccessAt: Long get() = _lastSuccessAt

    /**
     * When a banner the user closed stays closed for that version. The version lives in the
     * settings store, so "not now" survives a restart — but a deliberate manual check always
     * overrides it.
     */
    private var dismissedVersion = settingsPrefs.getString("update_snoozed", "").orEmpty()
    private val _snoozed = MutableStateFlow(dismissedVersion.ifBlank { null })
    val snoozed: StateFlow<String?> = _snoozed.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** The launched lookup, so a caller that cares can wait for it instead of guessing. */
    fun check(auto: Boolean): Job {
        if (_downloads.value == null) fetchReleases()
        return scope.launch(Dispatchers.IO) {
            _checking.value = true
            try {
                val req = Request.Builder()
                    .url(LATEST_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (!auto) _statusMessage.value = "Could not reach GitHub (try again later)"
                        return@use
                    }
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    // Reached the answer, not just the socket: this is the stamp that earns a rest.
                    _lastSuccessAt = SystemClock.elapsedRealtime()
                    val tag = json.optString("tag_name")
                    val version = tag.removePrefix("v")
                    if (!isNewer(version, BuildConfig.VERSION_NAME)) {
                        updateVersion = ""
                        _update.value = null
                        _lastKnown.value = null
                        _downloadedApk.value = null
                        if (!auto) _statusMessage.value = "You are on the latest version (${BuildConfig.VERSION_NAME})"
                        return@use
                    }
                    val assets = json.optJSONArray("assets") ?: org.json.JSONArray()
                    var url = ""
                    var size = 0L
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size", 0L)
                            break
                        }
                    }
                    if (url.isBlank()) {
                        if (!auto) _statusMessage.value = "Release $tag has no installable file yet"
                        return@use
                    }
                    val info = UpdateInfo(
                        versionName = version,
                        releaseName = json.optString("name", "PulseDrop $version"),
                        notes = json.optString("body").take(1200),
                        downloadUrl = url,
                        sizeBytes = size
                    )
                    _lastKnown.value = info
                    if (version == dismissedVersion) {
                        // A banner the user closed is not a bug to re-report on the next reconnect;
                        // asking on purpose overrides having closed it earlier.
                        if (auto) {
                            updateVersion = ""
                            _update.value = null
                            return@use
                        }
                        dismissedVersion = ""
                        settingsPrefs.edit().remove("update_snoozed").apply()
                        _snoozed.value = null
                    }
                    updateVersion = version
                    // A previously downloaded file for this exact version can be reused.
                    val prior = apkFile(version)
                    if (prior != null && prior.exists() && (size == 0L || prior.length() == size)) {
                        _downloadedApk.value = prior
                    } else {
                        prior?.delete()
                        _downloadedApk.value = null
                    }
                    _update.value = info
                    _statusMessage.value = null
                }
            } catch (e: Exception) {
                if (!auto) _statusMessage.value = "Update check failed: ${e.message}"
            } finally {
                _checking.value = false
            }
        }
    }

    /**
     * Fetch every published release once and read two things out of the same answer: the
     * lifetime APK download total the About page shows, and the release list the What's-new
     * page renders verbatim. A forced refresh is the page's retry button, nothing more.
     */
    fun fetchReleases(force: Boolean = false) {
        if (releasesFetching) return
        if (!force && _releases.value != null) return
        releasesFetching = true
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        _releasesError.value = "GitHub answered HTTP ${resp.code} for the release list."
                        return@use
                    }
                    val arr = org.json.JSONArray(resp.body?.string() ?: return@use)
                    var total = 0
                    val list = ArrayList<ReleaseInfo>()
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        if (r.optBoolean("draft")) continue
                        val assets = r.optJSONArray("assets") ?: org.json.JSONArray()
                        var hasApk = false
                        var apkPulls = 0
                        for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                hasApk = true
                                apkPulls += a.optInt("download_count", 0)
                                total += a.optInt("download_count", 0)
                            }
                        }
                        list += ReleaseInfo(
                            versionName = r.optString("tag_name").removePrefix("v"),
                            releaseName = r.optString("name"),
                            notes = r.optString("body"),
                            publishedAt = r.optString("published_at"),
                            isPrerelease = r.optBoolean("prerelease"),
                            hasApk = hasApk,
                            apkDownloads = apkPulls
                        )
                    }
                    _downloads.value = total
                    _releases.value = sortReleases(list)
                    _releasesError.value = null
                }
            } catch (e: Exception) {
                _releasesError.value = "Could not reach GitHub for the release list: ${e.message}"
            }
            releasesFetching = false
        }
    }

    private fun apkFile(version: String): File? = try {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        File(dir, "pulsedrop-$version.apk")
    } catch (e: Exception) {
        null
    }

    fun download() {
        val info = _update.value ?: _lastKnown.value ?: return
        if (_progress.value != null) return
        scope.launch(Dispatchers.IO) {
            val target = apkFile(info.versionName) ?: return@launch
            try {
                val req = Request.Builder().url(info.downloadUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        _statusMessage.value = "Download failed (${resp.code})"
                        return@use
                    }
                    val stream = resp.body?.byteStream() ?: return@use
                    val total = if (info.sizeBytes > 0) info.sizeBytes else resp.body?.contentLength() ?: -1L
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                        out.flush()
                    }
                    if (total > 0 && target.length() != total) {
                        target.delete()
                        _statusMessage.value = "Download was cut short — try again"
                    } else {
                        _progress.value = 1f
                        _downloadedApk.value = target
                    }
                    _progress.value = null
                }
            } catch (e: Exception) {
                _progress.value = null
                target.delete()
                _statusMessage.value = "Download failed: ${e.message}"
            }
        }
    }

    fun dismiss() {
        (_update.value ?: _lastKnown.value)?.versionName?.let {
            dismissedVersion = it
            settingsPrefs.edit().putString("update_snoozed", it).apply()
            _snoozed.value = it
        }
        _progress.value = null
        _update.value = null
    }

    fun consumeStatus() {
        _statusMessage.value = null
    }

    /** Android 8+ only: has the user allowed this app to install apps at all? */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        // The Settings constant exists only on API 26+; the action string works everywhere.
        Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:${context.packageName}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun install() {
        val apk = _downloadedApk.value ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            _statusMessage.value = "No installer available on this device"
        }
    }
}
