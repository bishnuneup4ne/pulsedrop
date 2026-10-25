package com.pulsedrop.app.diag

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.security.IdentityKeyPair
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A crash on someone else's phone is worth nothing unless it arrives with that phone
 * attached to it, so the last act of a dying process writes a report and turns it into a
 * heads-up notification the user can send with one tap.
 *
 * Nothing leaves the device on its own: filing a GitHub issue needs a token, and a token
 * compiled into an APK is the same key in every copy of a program people install from a
 * link. The notification opens [CrashReportActivity] inside this app, which shows the
 * report and lets the user copy it; no report text is ever put in an intent or a URL, so
 * no other app learns the device facts by claiming a link. The crash also survives on
 * disk, and the next launch offers it again if the shade never got to show it.
 */
object CrashReporter {

    private const val DIR = "crashes"
    private const val CHANNEL_ID = "pulsedrop_crash"
    private const val MAX_REPORTS = 20
    private const val MAX_STACK = 4000

    /** Installs the handler. Anything that dies before this runs is a silent crash. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                notify(app, capture(app, thread, error))
            } catch (ignored: Exception) {
                // A reporter that crashes is worse than no reporter.
            }
            // Let the system show its own "app stopped" dialog and write the tombstone.
            previous?.uncaughtException(thread, error)
        }
        // A report the shade never got to carry (the process died mid-post) is offered again.
        try {
            pendingReports(app).firstOrNull()?.let { notify(app, it) }
        } catch (ignored: Exception) {
        }
    }

    /**
     * Every fact that separates "NullPointerException in the radar" from the one report
     * that can actually be reproduced: who, on what, running which build, doing what.
     */
    private fun capture(context: Context, thread: Thread, error: Throwable): File {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val trimmed = if (stack.length > MAX_STACK) stack.substring(0, MAX_STACK) + "\n… truncated" else stack
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val id = "%013d".format(System.currentTimeMillis())

        val body = buildString {
            appendLine("**Detected:** $stamp")
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| App | ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) |")
            appendLine("| Android | ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |")
            appendLine("| Device | ${Build.BRAND} ${Build.MODEL} |")
            appendLine("| Hardware | ${Build.DEVICE} / ${Build.HARDWARE} |")
            appendLine("| Build | ${Build.ID} ${Build.TYPE} |")
            // The state name only, never anything about the key itself. It is the difference between
            // "pairing crashed on a Galaxy" and "pairing crashed because this phone had no identity
            // to sign with", and the second one is the only half a fix can be written against.
            appendLine("| Identity | ${IdentityKeyPair.state} |")
            appendLine("| Thread | ${thread.name} |")
            appendLine()
            appendLine("```")
            append(trimmed)
            appendLine("```")
            appendLine()
            appendLine("---")
            appendLine("Filed from PulseDrop's crash reporter. The device facts above are the")
            appendLine("only data the app keeps about this crash; nothing is sent by itself.")
        }

        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        prune(dir)
        val file = File(dir, "$id.txt")
        file.writeText(body)
        return file
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        if (files.size < MAX_REPORTS) return
        files.take(files.size - MAX_REPORTS + 1).forEach { runCatching { it.delete() } }
    }

    private fun pendingReports(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles()?.filter { it.name.endsWith(".txt") }?.sortedBy { it.name }
            ?: emptyList()

    /**
     * The notification's way in: an explicit component of this app. Nothing about the crash travels
     * in the intent — the screen reads the report from `filesDir`, where only this app can write.
     */
    internal fun reportIntent(context: Context, report: File): Intent =
        Intent(context, CrashReportActivity::class.java)
            .putExtra(CrashReportActivity.EXTRA_REPORT, report.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun notify(context: Context, report: File) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Crash reports", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "PulseDrop crashes that are worth reporting" }
            )
        }
        val open = PendingIntent.getActivity(
            context, report.name.hashCode(), reportIntent(context, report),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        manager.notify(
            CHANNEL_ID, report.name.hashCode(),
            androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("PulseDrop stopped unexpectedly")
                // No stack line here: a notification is readable on a locked screen, and the report
                // is one tap away. "Nothing has been sent" is the fact the user needs.
                .setContentText("Tap to review the report. Nothing has been sent.")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        // Handing the report over to the shade means it has been said once; keep it a while
        // so a second crash is still distinguishable, but never offer the same one twice.
        runCatching { report.renameTo(File(report.parentFile, report.name + ".offered")) }
    }
}
