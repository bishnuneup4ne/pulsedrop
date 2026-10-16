package com.pulsedrop.app.ui.screens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.components.PulseSectionHeader
import com.pulsedrop.app.ui.components.PulseStatus
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing

// ── What this app actually posts ──────────────────────────────────────────

internal data class NotificationFact(
    val id: String,
    val label: String,
    val whenItAppears: String,
    val defaultImportance: Int
)

/**
 * Every notification channel the code creates, and only these. A sixth row would be a
 * capability the app does not have; the server, share, call, ended-call and crash channels
 * are all of it.
 */
internal val NOTIFICATION_FACTS = listOf(
    NotificationFact(
        id = "webdrop_server_channel",
        label = "Server running",
        whenItAppears = "Sits in the shade the whole time the server is on, with its address.",
        defaultImportance = NotificationManager.IMPORTANCE_LOW
    ),
    NotificationFact(
        id = "localdrop_screen_share",
        label = "Screen sharing",
        whenItAppears = "Stays while your screen is being shared, with a stop control.",
        defaultImportance = NotificationManager.IMPORTANCE_LOW
    ),
    NotificationFact(
        id = "pulsedrop_call",
        label = "Calls",
        whenItAppears = "Rings when a call arrives and stays while it runs.",
        defaultImportance = NotificationManager.IMPORTANCE_HIGH
    ),
    NotificationFact(
        id = "pulsedrop_call_ended",
        label = "Ended calls",
        whenItAppears = "Posted once when a call finishes, saying how long it lasted.",
        defaultImportance = NotificationManager.IMPORTANCE_DEFAULT
    ),
    NotificationFact(
        id = "pulsedrop_crash",
        label = "Crash reports",
        whenItAppears = "Only after the app stops unexpectedly, pointing at the report it saved.",
        defaultImportance = NotificationManager.IMPORTANCE_HIGH
    )
)

internal fun importanceLabel(importance: Int): String = when (importance) {
    NotificationManager.IMPORTANCE_NONE -> "Turned off"
    NotificationManager.IMPORTANCE_MIN -> "Minimal"
    NotificationManager.IMPORTANCE_LOW -> "Silent"
    NotificationManager.IMPORTANCE_DEFAULT -> "Default"
    NotificationManager.IMPORTANCE_HIGH -> "Alerting"
    else -> "Unknown to this app"
}

/**
 * A channel that has never been created is not "off" — it just has not existed yet, because
 * its event never happened. Saying otherwise would invent a state.
 */
internal fun channelStateLine(fact: NotificationFact, channel: NotificationChannel?): String =
    if (channel == null) {
        "Not created yet — it appears the first time this happens."
    } else {
        "${channel.name}: ${importanceLabel(channel.importance)}"
    }

// ── Screen ────────────────────────────────────────────────────────────────

@Composable
fun NotificationScreen(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenActivity: () -> Unit
) {
    val c = LocalPulseColors.current
    val context = LocalContext.current

    // Channel state is read live: the user can change any of it in system settings without
    // the app knowing, so the screen re-reads when asked rather than caching at startup.
    var readTick by remember { mutableIntStateOf(0) }
    val channels = remember(readTick, context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        NOTIFICATION_FACTS.associate { fact ->
            fact.id to runCatching { manager?.getNotificationChannel(fact.id) }.getOrNull()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("notif_list"),
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
                    .testTag("notif_header")
            ) {
                Text(
                    "Notifications",
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.textPrimary
                )
                Text(
                    "What this app can notify you about, what each one is for, and what it " +
                        "never notifies — read from the channels it actually creates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary
                )
                PulseButton(
                    label = "Read channel states again",
                    onClick = { readTick++ },
                    tone = PulseButtonTone.Neutral,
                    icon = Icons.Filled.Notifications,
                    modifier = Modifier
                        .padding(top = PulseSpacing.md.dp)
                        .testTag("notif_recheck")
                )
            }
        }

        // ── Permission ─────────────────────────────────────────────────────
        item { PulseSectionHeader("Permission") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("notif_permission_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    PulseStatus(
                        text = if (permissionGranted) "Notifications allowed" else "Notifications blocked",
                        tone = if (permissionGranted) PulseStatusTone.Positive else PulseStatusTone.Negative,
                        modifier = Modifier.testTag("notif_permission_status")
                    )
                    Text(
                        if (permissionGranted) {
                            "Android shows this app's notifications. Each channel below can " +
                                "still be turned off individually in system settings."
                        } else {
                            "Android 13+ hides every notification this app posts — including " +
                                "the persistent one that says the server is running. The app " +
                                "still works inside its own windows."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    PulseButton(
                        label = "Ask Android again",
                        onClick = onRequestPermission,
                        enabled = !permissionGranted,
                        disabledReason = "Already allowed — change it in system settings instead.",
                        tone = PulseButtonTone.Neutral,
                        modifier = Modifier.testTag("notif_ask")
                    )
                    PulseButton(
                        label = "System notification settings",
                        onClick = onOpenSystemSettings,
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.testTag("notif_system")
                    )
                }
            }
        }

        // ── Channels ───────────────────────────────────────────────────────
        item { PulseSectionHeader("What this app notifies about") }

        for (fact in NOTIFICATION_FACTS) {
            item(key = "channel-${fact.id}") {
                PulseCard(modifier = Modifier.fillMaxWidth().testTag("notif_channel_${fact.id}")) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(fact.label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                        Text(
                            fact.whenItAppears,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
                        )
                        Text(
                            channelStateLine(fact, channels[fact.id]),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                channels[fact.id]?.importance == NotificationManager.IMPORTANCE_NONE
                            ) c.danger else c.textTertiary
                        )
                    }
                }
            }
        }

        // ── What never notifies ────────────────────────────────────────────
        item { PulseSectionHeader("What never sends a notification") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("notif_silence_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs.dp)) {
                    Text(
                        "• Chat messages: the app only refreshes chats while it is open; a " +
                            "message that arrives later is not pushed to this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "• Finished transfers: progress lives in the server notification and " +
                            "in the app; a completion is not announced separately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                    Text(
                        "• Updates and pairing requests: shown as banners inside the app only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary
                    )
                }
            }
        }

        // ── No history ─────────────────────────────────────────────────────
        item { PulseSectionHeader("History") }

        item {
            PulseCard(modifier = Modifier.fillMaxWidth().testTag("notif_history_card")) {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm.dp)) {
                    Text(
                        "PulseDrop keeps no log of the notifications it posted — they belong " +
                            "to Android, and Android does not hand that history back to apps. " +
                            "The events this app does record are on the Activity page.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary
                    )
                    PulseButton(
                        label = "Open activity",
                        onClick = onOpenActivity,
                        tone = PulseButtonTone.Neutral,
                        modifier = Modifier.testTag("notif_open_activity")
                    )
                }
            }
        }
    }
}
