package com.pulsedrop.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseRow
import com.pulsedrop.app.ui.components.PulseRowDivider
import com.pulsedrop.app.ui.components.PulseRowGroup
import com.pulsedrop.app.ui.components.PulseRowLabel
import com.pulsedrop.app.ui.theme.LocalPulseColors
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
            "keys match, which is why confirming it on both sides is the real check. Pairing " +
            "lets that phone send to you without a PIN; it does not let it read your files, " +
            "message history or browser room."
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
            ) {}
        }

        // ── FAQ: one question per row, the answer folds out underneath ───────
        item { PulseRowLabel("Questions") }

        for (faq in faqs) {
            item(key = "faq-${faq.id}") {
                val open = openId == faq.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .testTag("help_faq_${faq.id}")
                ) {
                    PulseRow(
                        title = faq.question,
                        icon = Icons.Default.QuestionAnswer,
                        showChevron = false,
                        onClick = { openId = if (open) null else faq.id },
                        trailing = {
                            Icon(
                                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (open) "Collapse" else "Expand",
                                tint = c.textTertiary
                            )
                        }
                    )
                    if (open) {
                        Text(
                            faq.answer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary,
                            modifier = Modifier
                                .padding(start = 52.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
                                .testTag("help_answer_${faq.id}")
                        )
                    }
                }
            }
        }

        // ── Pages that handle it ────────────────────────────────────────────
        item { PulseRowLabel("Related pages") }

        item {
            PulseRowGroup(modifier = Modifier.fillMaxWidth().testTag("help_links_card")) {
                PulseRow(
                    title = "Hotspot guide",
                    icon = Icons.Default.Wifi,
                    subtitle = "When the network itself blocks local traffic",
                    onClick = onOpenHotspotGuide,
                    modifier = Modifier.testTag("help_link_hotspot")
                )
                PulseRowDivider(insetStart = 52)
                PulseRow(
                    title = "Notifications",
                    icon = Icons.Default.Notifications,
                    subtitle = "What this app can and cannot announce",
                    onClick = onOpenNotifications,
                    modifier = Modifier.testTag("help_link_notifications")
                )
            }
        }

        // ── Real support channels ───────────────────────────────────────────
        item { PulseRowLabel("Support") }

        item {
            Column(Modifier.fillMaxWidth().testTag("help_support_card")) {
                PulseRow(
                    title = "Report a problem on GitHub",
                    icon = Icons.Default.BugReport,
                    onClick = onOpenGithubIssues,
                    titleColor = c.accent,
                    modifier = Modifier.testTag("help_report")
                )
                PulseRowDivider(insetStart = 52)
                PulseRow(
                    title = "Check for updates",
                    icon = Icons.Default.Refresh,
                    onClick = onCheckUpdate,
                    modifier = Modifier.testTag("help_update")
                )
            }
        }

        // ── Honest limits ───────────────────────────────────────────────────
        item { PulseRowLabel("Limits") }

        item {
            PulseRowGroup(modifier = Modifier.fillMaxWidth().testTag("help_limits_card")) {
                PulseRow(
                    title = "No remote access",
                    icon = Icons.Default.Block,
                    subtitle = "No one can reach your phone to fix it.",
                    showChevron = false
                )
                PulseRowDivider(insetStart = 52)
                PulseRow(
                    title = "No deleted-file recovery",
                    icon = Icons.Default.Block,
                    subtitle = "Deleted files are gone; the app keeps no copy.",
                    showChevron = false
                )
                PulseRowDivider(insetStart = 52)
                PulseRow(
                    title = "No live diagnostics",
                    icon = Icons.Default.Block,
                    subtitle = "Crash reports stay on this phone until you share them.",
                    showChevron = false
                )
            }
        }
    }
}
