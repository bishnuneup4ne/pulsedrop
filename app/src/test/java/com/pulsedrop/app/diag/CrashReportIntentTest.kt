package com.pulsedrop.app.diag

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A crash report names the phone, the build, the thread and a stack trace. The old notification
 * wrapped all of it in an implicit `ACTION_VIEW` to `github.com`, so every app claiming that host
 * was offered the report in the query string, and the browser kept it in history.
 *
 * The invariant now: the notification's intent is an explicit component of this app, carries no
 * URL and no report text, and the screen it opens reads only from `filesDir/crashes`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrashReportIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notification opens this app, not whatever claims github`() {
        val report = File(context.filesDir, "crashes").apply { mkdirs() }
            .resolve("0000000000001.txt").apply { writeText("java.lang.NullPointerException at Radar.kt:12") }

        val intent = CrashReporter.reportIntent(context, report)

        assertEquals(CrashReportActivity::class.java.name, intent.component?.className)
        assertNull("the report must not travel as a data URI", intent.data)
        assertEquals(report.name, intent.getStringExtra(CrashReportActivity.EXTRA_REPORT))
        assertFalse(
            "no extra may smuggle the report or a URL: ${intent.extras}",
            intent.extras?.keySet()?.any { key ->
                val value = intent.extras?.get(key)?.toString().orEmpty()
                value.contains("github.com") || value.contains("NullPointerException")
            } ?: false
        )
    }

    @Test
    fun `the screen cannot be launched by another app`() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, CrashReportActivity::class.java), 0
        )
        assertFalse("an exported report screen is a way to read crashes", info.exported)
    }

    @Test
    fun `only files inside the crash directory are read`() {
        File(context.filesDir, "crashes").mkdirs()
        val outside = File(context.filesDir, "secret.txt").apply { writeText("not a crash") }

        assertNull(CrashReportActivity.reportFile(context, "../secret.txt"))
        assertNull("a sibling in filesDir is still outside crashes/", CrashReportActivity.reportFile(context, "secret.txt"))
        assertTrue(outside.exists())
    }

    @Test
    fun `a report the shade already saw is still found after the rename`() {
        val dir = File(context.filesDir, "crashes").apply { mkdirs() }
        val offered = File(dir, "0000000000002.txt.offered").apply { writeText("offered") }

        assertEquals(offered.canonicalFile, CrashReportActivity.reportFile(context, "0000000000002.txt")?.canonicalFile)
    }

    @Test
    fun `the screen shows a report that exists and stays open`() {
        val report = File(context.filesDir, "crashes").apply { mkdirs() }
            .resolve("0000000000003.txt").apply { writeText("boom") }

        val activity = Robolectric.buildActivity(
            CrashReportActivity::class.java, CrashReporter.reportIntent(context, report)
        ).setup().get()

        assertFalse("a real report must not fall through to finish()", activity.isFinishing)
        assertNotNull("and it must show something", activity.window.decorView)
    }

    @Test
    fun `a missing report closes the screen instead of showing an empty page`() {
        val activity = Robolectric.buildActivity(
            CrashReportActivity::class.java,
            CrashReporter.reportIntent(context, File(context.filesDir, "crashes/never-written.txt"))
        ).setup().get()

        assertTrue(activity.isFinishing)
    }
}
