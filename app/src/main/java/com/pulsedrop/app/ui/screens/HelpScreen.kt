package com.pulsedrop.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseDivider
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseRadius
import com.pulsedrop.app.ui.theme.PulseSpacing

// ── The answers, all true of this build ───────────────────────────────────

internal data class HelpFaq(
    val id: String,
    val question: String,
    val answer: String
)

/**
 * Every answer here was checked against the code that does the work. An answer that could not
 * be verified against real behavior does not belong on this list, however nice it would read.
 */
internal fun helpFaqs(receiveDir: String): List<HelpFaq> = listOf(
    HelpFaq(
        id = "send",
        question = "How do I send a file to a computer?",
        answer = "Start the server from Home, then open the address it shows in a browser on " +
            "the same network. If a PIN is required, the page asks for it once. Pick the files " +
            "there and they arrive in this phone's received folder."
    ),
    HelpFaq(
        id = "received",
        question = "Where do received files go?",
        answer = "Into the app's own folder, not shared storage: $receiveDir. The Receive " +
            "page shows the same path, and My files lists what is really still there."
    ),
    HelpFaq(
        id = "pair",
        question = "How do I pair with another phone?",
        answer = "Open Devices on both phones, on the same network, and send a pairing " +
            "request. Both screens then show the same six-digit code; it matches only if the " +
            "keys match, which is why confirming it on both sides is the real check."
    ),
    HelpFaq(
        id = "unreachable",
        question = "Why can't my browser reach this phone?",
        answer = "Check three things: the server says Running, the computer is on the same " +
            "network, and you typed the current address from the Network status page. If the " +
            "network blocks local traffic (guest Wi-Fi often does), the hotspot guide shows a " +
            "way around it."
    ),
    HelpFaq(
        id = "closed",
        question = "Do chats and calls work while the app is closed?",
        answer = "No. PulseDrop has no push server of its own: chats refresh while the app is " +
            "open, and a call can only ring while this phone can reach its paired peer."
    ),
    HelpFaq(
        id = "encryption",
        question = "Is any of it encrypted?",
        answer = "Chat and file traffic between paired phones is end-to-end encrypted. The " +
            "browser page is encrypted in transit with a self-signed certificate nobody pinned. " +
            "Call audio and video are not end-to-end encrypted. The Security center lists each " +
            "case instead of a score."
    ),
    HelpFaq(
        id = "updates",
        question = "How do updates arrive?",
        answer = "The app asks GitHub for the latest release and shows a banner when one " +
            "exists. Android itself verifies the package at install time. Nothing updates " +
            "silently."
    ),
    HelpFaq(
        id = "internet",
        question = "What leaves this phone over the internet?",
        answer = "Only the update check and its download, and only when you run them. File " +
            "transfers, chats, calls and screen sharing stay on the local network — that is " +
            "the whole design."
    )
)

// ── Screen ────────────────────────────────────────────────────────────────

@Composable
fun HelpScreen(
    receiveDir: String,
    onOpenHotspotGuide: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenGithubIssues: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    val c = LocalPulseColors.current
    val faqs = remember(receiveDir) { helpFaqs(receiveDir) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("help_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.lg.dp,
            end = PulseSpacing.lg.dp,
            bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = PulseSpacing.lg.dp)
                    .testTag("help_header")
            ) {
                Text(
                    "Help & support",
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.textPrimary
                )
                Text(
                    "Answers checked against what this build actually does, and the few real " +
                        "places to go when an answer is not enough.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary
                )
            }
        }

        item { PulseSectionHeader("Questions with true answers") }

        for (faq in faqs) {
            item(key = "faq-${faq.id}") {
                val open = openId == faq.id
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("help_faq_${faq.id}")) {
                    Column(Modifier.animateContentSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(PulseRadius.md.dp))
                                .clickable { openId = if (open) null else faq.id }
                                .padding(vertical = PulseSpacing.xs.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                faq.question,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (open) FontWeight.SemiBold else FontWeight.Medium,
                                color = c.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (open) "Collapse" else "Expand",
                                tint = c.textTertiary
                            )
                        }
                        if (open) {
                            Text(
                                faq.answer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSecondary,
                                modifier = Modifier
                                    .padding(top = PulseSpacing.sm.dp)
                                    .testTag("help_answer_${faq.id}")
                            )
                        }
                    }
                }
            }
        }

        item { PulseSectionHeader("Go to the page that handles it") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("help_links_card")) {
                Column {
                    HelpLinkRow("Hotspot guide", "When the network itself blocks local traffic", onOpenHotspotGuide, "help_link_hotspot")
                    PulseDivider()
                    HelpLinkRow("Notifications", "What this app can and cannot announce", onOpenNotifications, "help_link_notifications")
                }
            }
        }

        item { PulseSectionHeader("When an answer is not enough") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("help_support_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    Text(
                        "This project has one support channel, and it is public: the GitHub " +
                            "issue tracker for the same repository the updates come from. " +
                            "There is no support inbox, no live chat, and nobody can look at " +
                            "your phone from theirs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                    PulseButton(
                        label = "Report a problem on GitHub",
                        onClick = onOpenGithubIssues,
                        tone = PulseButtonTone.Primary,
                        modifier = Modifier.testTag("help_report")
                    )
                    PulseButton(
                        label = "Check for updates",
                        onClick = onCheckUpdate,
                        tone = PulseButtonTone.Neutral,
                        modifier = Modifier.testTag("help_update")
                    )
                }
            }
        }

        item { PulseSectionHeader("What help here cannot do") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("help_limits_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    Text(
                        "• No remote access: nothing in this app lets anyone reach your phone " +
                            "to fix it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "• No deleted-file recovery: a file deleted from the received folder is " +
                            "gone; the app keeps no copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "• No diagnostics a support agent can run: a crash report is written " +
                            "locally, and sharing it is your choice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpLinkRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    tag: String
) {
    val c = LocalPulseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PulseRadius.md.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = PulseSpacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = c.textTertiary
        )
    }
}
