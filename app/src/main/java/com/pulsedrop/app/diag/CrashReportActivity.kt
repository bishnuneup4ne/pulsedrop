package com.pulsedrop.app.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.pulsedrop.app.ui.theme.PulseDropTheme
import java.io.File

/**
 * The screen a crash notification opens. It reads the report from this app's private storage and
 * shows it; the report is never carried in the intent that opened the screen and never put in a URL.
 *
 * Sending is deliberately two steps: "Copy report" puts the markdown on the clipboard, and "Open
 * GitHub" opens the *empty* new-issue form. That is the difference between a crash report and a link
 * any app claiming `github.com` can read.
 */
class CrashReportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REPORT = "com.pulsedrop.app.extra.CRASH_REPORT"
        private const val ISSUE_URL = "https://github.com/bishnuneup4ne/pulsedrop/issues/new"
        private const val DIR = "crashes"

        /**
         * Resolves a report name to a file inside `filesDir/crashes`, or null. The name comes from a
         * `PendingIntent` this app built, but resolving it with a path-separator check keeps a bad
         * extra from reaching `filesDir/shared_prefs` — cheap, and the failure mode is silent exit.
         * `notify()` renames a report to `.offered` before the user can tap, so both names are tried.
         */
        internal fun reportFile(context: Context, name: String): File? {
            if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
            val dir = File(context.filesDir, DIR).canonicalFile
            return listOf(File(dir, name), File(dir, "$name.offered"))
                .firstOrNull { it.isFile && it.canonicalFile.parentFile == dir }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT)?.let { reportFile(this, it) }
        val markdown = report?.let { runCatching { it.readText() }.getOrNull() }
        if (report == null || markdown == null) {
            finish()
            return
        }
        setContent {
            PulseDropTheme {
                CrashReportScreen(
                    markdown = markdown,
                    onCopy = {
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("PulseDrop crash report", markdown))
                        Toast.makeText(this, "Report copied. Paste it into the issue.", Toast.LENGTH_LONG).show()
                    },
                    onOpenGitHub = { startActivity(Intent(Intent.ACTION_VIEW, ISSUE_URL.toUri())) },
                    onDelete = {
                        report.delete()
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun CrashReportScreen(
    markdown: String,
    onCopy: () -> Unit,
    onOpenGitHub: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("PulseDrop stopped unexpectedly", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "This report is stored only on this phone. Copy it and paste it into the issue on " +
                "GitHub if you want to send it — nothing is sent by itself.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                markdown,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete) { Text("Delete") }
            TextButton(onClick = onCopy) { Text("Copy report") }
            Button(onClick = onOpenGitHub) { Text("Open GitHub") }
        }
    }
}
