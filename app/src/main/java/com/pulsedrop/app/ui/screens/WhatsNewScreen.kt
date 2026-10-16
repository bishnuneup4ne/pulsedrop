package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.ReleaseInfo
import com.pulsedrop.app.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The changelog, if that word is allowed: every line here is text GitHub already publishes for
 * this project's releases. If GitHub has not been asked yet, the page says exactly that instead
 * of showing notes someone wrote for the page itself.
 */

// "1.7.0" vs "1.7" are the same release written two ways: neither number is newer than the
// other, so equality has to mean "no one outranks anyone", not a string match.
internal fun releaseRelation(version: String, installed: String): String = when {
    version == installed -> "You are running this one"
    UpdateManager.isNewer(version, installed) -> "Newer than what you have"
    UpdateManager.isNewer(installed, version) -> "Older than what you have"
    else -> "You are running this one"
}

internal fun releaseRelationTone(version: String, installed: String): PulseStatusTone = when {
    version == installed -> PulseStatusTone.Positive
    UpdateManager.isNewer(version, installed) -> PulseStatusTone.Active
    UpdateManager.isNewer(installed, version) -> PulseStatusTone.Neutral
    else -> PulseStatusTone.Positive
}

/** GitHub sends "2026-10-14T09:31:02Z"; anything unparseable is shown as it arrived. */
internal fun formatPublished(iso: String, fmt: SimpleDateFormat): String {
    return try {
        val parsed = synchronized(fmt) { fmt.parse(iso) }
        if (parsed == null) iso else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(parsed)
    } catch (_: Exception) {
        iso
    }
}

internal fun releaseNotesOrEmpty(notes: String): String =
    if (notes.isBlank()) "The publisher left no notes on this release." else notes.trim()

@Composable
fun WhatsNewScreen(
    installedVersion: String,
    releases: List<ReleaseInfo>?,
    error: String?,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    // GitHub publishes in UTC with no offset letter to localise against.
    val utcFmt = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    // Opening the page is the only reason to ask; the updater asks on launch anyway.
    LaunchedEffect(Unit) { onFetch() }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("whatsnew_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("whatsnew_header"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "What's new",
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary
                )
                Text(
                    "You are on v$installedVersion. Everything below is GitHub's own release text, " +
                            "in its own words — this page adds nothing to it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = PulseSpacing.xs.dp)
                )
            }
        }

        if (releases == null && error == null) {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("whatsnew_loading")) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PulseStatus(
                            text = "Not fetched yet",
                            tone = PulseStatusTone.Neutral,
                            detail = "This session has not asked GitHub for its release list. " +
                                    "Nothing here is invented while waiting."
                        )
                        PulseButton(
                            "Ask GitHub now",
                            onClick = onFetch,
                            tone = PulseButtonTone.Quiet,
                            modifier = Modifier.testTag("whatsnew_fetch")
                        )
                    }
                }
            }
        }

        if (error != null) {
            item {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("whatsnew_error")) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PulseStatus(
                            text = "The release list did not arrive",
                            tone = PulseStatusTone.Negative,
                            detail = error
                        )
                        PulseButton(
                            "Try again",
                            onClick = onFetch,
                            tone = PulseButtonTone.Quiet,
                            modifier = Modifier.testTag("whatsnew_retry")
                        )
                    }
                }
            }
        }

        if (releases != null && releases.isEmpty()) {
            item {
                Text(
                    "GitHub lists no published releases for this project yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.fillMaxWidth().testTag("whatsnew_empty")
                )
            }
        }

        items(releases.orEmpty(), key = { "whatsnew_${it.versionName}" }) { release ->
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("whatsnew_v${release.versionName}")) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "v${release.versionName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary
                        )
                        Spacer(Modifier.width(PulseSpacing.md.dp))
                        PulseStatus(
                            text = releaseRelation(release.versionName, installedVersion),
                            tone = releaseRelationTone(release.versionName, installedVersion)
                        )
                    }
                    if (release.releaseName.isNotBlank() && release.releaseName != release.versionName) {
                        Text(
                            release.releaseName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary
                        )
                    }
                    if (release.publishedAt.isNotBlank()) {
                        Text(
                            "Published ${formatPublished(release.publishedAt, utcFmt)}" +
                                    if (release.isPrerelease) " • pre-release" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                    Text(
                        releaseNotesOrEmpty(release.notes),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textPrimary
                    )
                    if (release.hasApk) {
                        Text(
                            "GitHub's counter for this release's install file: " +
                                    "${release.apkDownloads} download(s).",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                }
            }
        }

        item {
            Text(
                "The same releases API the updater reads on launch answers this page. Notes appear " +
                        "exactly as published, and a release with no notes says so rather than " +
                        "getting one from here.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                modifier = Modifier.fillMaxWidth().testTag("whatsnew_note")
            )
        }
    }
}
