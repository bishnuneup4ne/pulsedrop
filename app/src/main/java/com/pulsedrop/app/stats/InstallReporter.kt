package com.pulsedrop.app.stats

import android.content.Context
import android.content.pm.PackageManager
import com.pulsedrop.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The stamps Android keeps about the package itself, which is what tells an install from an upgrade. */
internal data class InstallStamps(val firstInstall: Long, val lastUpdate: Long)

/** Where an attempt to announce an install got to. */
internal enum class InstallOutcome { NOT_CONFIGURED, SETTLED, UPGRADE, ANNOUNCED, DEFERRED, NO_PACKAGE }

/**
 * Announces this device to the public install-count page once, and only for a real install.
 *
 * The count the landing page shows is devices, not downloads, so two things matter here. First,
 * an in-app update must never add to it: the page counts an install the first time PulseDrop runs
 * on a device that has never run it before, which Android records for us — a package whose
 * install stamp and update stamp differ has been replaced at least once. Second, nothing about
 * the device or its owner travels: the body is a random UUID this app generated for the install
 * plus the version being installed, so the number is a count of installs with no way back to a
 * phone. The server accepts a given id exactly once anyway, so a retried request cannot double up.
 *
 * While [BuildConfig.INSTALL_PING_URL] is blank there is nowhere to tell, and this class does
 * nothing at all.
 */
class InstallReporter(
    context: Context,
    private val scope: CoroutineScope,
    private val endpoint: String = BuildConfig.INSTALL_PING_URL,
    private val version: String = BuildConfig.VERSION_NAME,
    private val post: (String, String) -> Boolean = Companion::sendAnnouncement
) {

    companion object {
        internal const val PREFS = "pulsedrop_install"
        internal const val KEY_ID = "install_id"
        internal const val KEY_VERDICT = "verdict"
        internal const val ANNOUNCED = "announced"
        internal const val UPGRADE = "upgrade"

        /** A package that has never been replaced on this device: same stamp for both events. */
        internal fun isFreshInstall(stamps: InstallStamps): Boolean =
            stamps.lastUpdate <= stamps.firstInstall

        /** The exact body, so the shape the page validates can be checked without a network. */
        internal fun announcement(id: String, version: String): String =
            "{\"install_id\":\"$id\",\"version\":\"$version\"}"

        private val jsonType = "application/json; charset=utf-8".toMediaType()

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /** True when the page accepted the announcement; anything else leaves it for another time. */
        private fun sendAnnouncement(url: String, body: String): Boolean = try {
            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    /** Fire-and-forget, from whoever already knows the network is worth trying. */
    fun report() {
        scope.launch(Dispatchers.IO) { announceOnce(currentStamps()) }
    }

    private fun currentStamps(): InstallStamps? = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        InstallStamps(info.firstInstallTime, info.lastUpdateTime)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * The whole decision, in one pass: settle it from stamps the caller supplies, so a test can
     * hand over a package that has been updated as easily as one that has not.
     */
    internal fun announceOnce(stamps: InstallStamps?): InstallOutcome {
        if (endpoint.isBlank()) return InstallOutcome.NOT_CONFIGURED
        val verdict = prefs.getString(KEY_VERDICT, "")
        if (verdict == ANNOUNCED || verdict == UPGRADE) return InstallOutcome.SETTLED
        if (stamps == null) return InstallOutcome.NO_PACKAGE
        if (!isFreshInstall(stamps)) {
            // An upgrade of an install predating this feature must not be counted now, either,
            // and there is no reason to look again: the stamps cannot go backwards.
            prefs.edit().putString(KEY_VERDICT, UPGRADE).apply()
            return InstallOutcome.UPGRADE
        }
        val id = prefs.getString(KEY_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }
        if (!post(endpoint, announcement(id, version))) return InstallOutcome.DEFERRED
        prefs.edit().putString(KEY_VERDICT, ANNOUNCED).apply()
        return InstallOutcome.ANNOUNCED
    }
}
