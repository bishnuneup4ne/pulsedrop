package com.pulsedrop.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.R
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSizes
import com.pulsedrop.app.ui.theme.PulseSpacing
import com.pulsedrop.app.update.UpdateInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Truthful facts about this build. Every version number was pinned by this build's dependency
 * catalog, every license string was read from the license the library itself ships, and every
 * link goes to a page that exists.
 */

internal data class OpenSourceDep(
    val name: String,
    val artifact: String,
    val version: String,
    val license: String
)

/** The runtime libraries this APK declares, with the license each project states for itself. */
internal val OPEN_SOURCE_DEPS: List<OpenSourceDep> = listOf(
    OpenSourceDep("AndroidX Core", "androidx.core:core-ktx", "1.18.0", "Apache-2.0"),
    OpenSourceDep("Activity Compose", "androidx.activity:activity-compose", "1.10.1", "Apache-2.0"),
    OpenSourceDep("Jetpack Compose & Material 3", "androidx.compose (BOM)", "2024.09.00", "Apache-2.0"),
    OpenSourceDep("Lifecycle", "androidx.lifecycle", "2.8.7", "Apache-2.0"),
    OpenSourceDep("Room", "androidx.room", "2.7.0", "Apache-2.0"),
    OpenSourceDep("Kotlin coroutines", "org.jetbrains.kotlinx:kotlinx-coroutines", "1.10.2", "Apache-2.0"),
    OpenSourceDep("Coil", "io.coil-kt:coil-compose", "2.7.0", "Apache-2.0"),
    OpenSourceDep("OkHttp", "com.squareup.okhttp3:okhttp", "4.10.0", "Apache-2.0"),
    OpenSourceDep("NanoHTTPD", "org.nanohttpd:nanohttpd", "2.3.1", "BSD-3-Clause"),
    OpenSourceDep("Bouncy Castle PKIX", "org.bouncycastle:bcpkix-jdk18on", "1.78.1", "Bouncy Castle Licence"),
    OpenSourceDep("ZXing core", "com.google.zxing:core", "3.5.3", "Apache-2.0"),
    OpenSourceDep("ZXing Android Embedded", "com.journeyapps:zxing-android-embedded", "4.3.0", "Apache-2.0")
)

internal data class AboutLink(val id: String, val label: String, val url: String)

/** Only destinations this project demonstrably owns or that the license files themselves name. */
internal val ABOUT_LINKS: List<AboutLink> = listOf(
    AboutLink("source", "Source code", "https://github.com/bishnuneup4ne/pulsedrop"),
    AboutLink("issues", "Report a problem", "https://github.com/bishnuneup4ne/pulsedrop/issues"),
    AboutLink("docs", "Documentation", "https://github.com/bishnuneup4ne/pulsedrop/blob/main/docs/Home.md"),
    AboutLink("security", "Security policy", "https://github.com/bishnuneup4ne/pulsedrop/blob/main/SECURITY.md"),
    AboutLink("polyform", "PolyForm Noncommercial 1.0.0 — full text",
        "https://polyformproject.org/licenses/noncommercial/1.0.0"),
    AboutLink("apache", "Apache-2.0 — full text", "https://www.apache.org/licenses/LICENSE-2.0")
)

internal fun installSpanLine(firstInstall: Long, lastUpdate: Long, fmt: SimpleDateFormat): String = when {
    firstInstall <= 0L -> "This device does not report when this app was installed."
    lastUpdate - firstInstall < 60_000L ->
        "Installed ${fmt.format(Date(firstInstall))}. Nothing has replaced this install since."
    else -> "Installed ${fmt.format(Date(firstInstall))}. Last updated ${fmt.format(Date(lastUpdate))}."
}

internal fun latestReleaseLine(updateInfo: UpdateInfo?): String =
    if (updateInfo == null)
        "No newer release has been found in this session. The updater checks GitHub releases on launch."
    else "The newest release GitHub reports is v${updateInfo.versionName} — ${updateInfo.releaseName}"

@Composable
fun AboutScreen(
    updateInfo: UpdateInfo?,
    updateStatus: String?,
    onCheckUpdate: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val fmt = remember { SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault()) }
    val installTimes = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            Pair(info.firstInstallTime, info.lastUpdateTime)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("about_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().testTag("about_header")
            ) {
                Spacer(Modifier.height(PulseSpacing.md.dp))
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(PulseRadius.xl.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_localdrop_logo),
                        contentDescription = "PulseDrop logo",
                        modifier = Modifier.size(80.dp)
                    )
                }
                Text(
                    text = "PulseDrop",
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    modifier = Modifier.padding(top = PulseSpacing.md.dp)
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})" +
                            if (BuildConfig.DEBUG) " — debug build" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary
                )
                Text(
                    text = BuildConfig.APPLICATION_ID,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary
                )
                Text(
                    text = "Files, chats, calls and screen sharing between your own devices — over your " +
                            "network, with no account and no cloud.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = PulseSpacing.md.dp)
                        .fillMaxWidth(0.86f)
                )
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("about_device_card")) {
                AboutColumn {
                    Text(
                        installSpanLine(installTimes.first, installTimes.second, fmt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textPrimary
                    )
                    Text(
                        "Both times come from Android's own package record for this install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("about_how_card")) {
                AboutColumn {
                    Text(
                        "How it works",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    AboutFact(Icons.Default.Wifi, "One server, every device",
                        "Turn the server on and phones, tablets and browsers on the same network connect to " +
                                "this one address — there is no per-device server.")
                    AboutFact(Icons.Default.Lock, "Encrypted pairing",
                        "Pairing runs on an end-to-end encrypted channel with a spoken safety code, so you " +
                                "know the device you trusted is the one in the room.")
                    AboutFact(Icons.Default.PhoneAndroid, "No accounts, no cloud",
                        "Nothing is uploaded anywhere. Transfers go device to device; the optional browser " +
                                "server only ever talks to your own network.")
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("about_update_card")) {
                AboutColumn {
                    PulseStatus(
                        text = "Updates",
                        tone = if (updateStatus != null) PulseStatusTone.Warning else PulseStatusTone.Neutral,
                        detail = updateStatus ?: latestReleaseLine(updateInfo)
                    )
                    PulseButton(
                        "Check for updates",
                        onClick = onCheckUpdate,
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("about_check_update")
                    )
                }
            }
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("about_license_card")) {
                AboutColumn {
                    Text(
                        "License",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    Text(
                        "PulseDrop is free for noncommercial use under the PolyForm Noncommercial " +
                                "1.0.0 license, the license file committed at the root of the source " +
                                "repository. The full source is public.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        item {
            Text(
                "Built with open source",
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
                modifier = Modifier.fillMaxWidth().padding(top = PulseSpacing.xs.dp)
            )
        }
        items(OPEN_SOURCE_DEPS, key = { "dep_${it.artifact}" }) { dep ->
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("about_dep_${dep.artifact}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dep.name, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        Text(
                            "${dep.artifact} • ${dep.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary
                        )
                    }
                    Text(dep.license, style = MaterialTheme.typography.labelMedium, color = c.accent)
                }
            }
        }
        item {
            Text(
                "These are the versions this build pinned. Each license name is the one the library " +
                        "itself states; this page does not restate their text.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                modifier = Modifier.fillMaxWidth().testTag("about_deps_note")
            )
        }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("about_links_card")) {
                AboutColumn {
                    Text(
                        "Links",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    ABOUT_LINKS.forEach { link ->
                        AboutLinkRow(
                            link = link,
                            onClick = { onOpenLink(link.url) },
                            modifier = Modifier.testTag("about_link_${link.id}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutColumn(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun AboutLinkRow(
    link: AboutLink,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PulseRadius.md.dp))
            .clickable(onClick = onClick)
            .padding(vertical = PulseSpacing.xs.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(link.label, style = MaterialTheme.typography.bodyMedium, color = c.accent)
                Text(link.url, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun AboutFact(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    val c = LocalPulseColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(PulseSizes.iconLg.dp))
        Spacer(Modifier.width(PulseSpacing.md.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}
