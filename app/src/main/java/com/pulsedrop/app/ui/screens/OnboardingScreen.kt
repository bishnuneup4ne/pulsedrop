package com.pulsedrop.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseButtonTone
import com.pulsedrop.app.ui.components.PulseCard
import com.pulsedrop.app.ui.theme.LocalPulseColors
import com.pulsedrop.app.ui.theme.PulseSpacing

/**
 * First-run setup, stated as facts. Every tick on this page is derived from live app state —
 * the server, the PIN setting, the OS permission, the pairing store — and nothing is marked
 * done by tapping through slides. Returning users are never blocked by it.
 */

object OnboardingPrefs {
    private const val STORE = "localdrop_settings"
    private const val KEY_SEEN = "onboarding_seen"

    fun isSeen(context: Context): Boolean =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEN, true).apply()
    }
}

internal fun notificationStepDone(granted: Boolean, osAsks: Boolean): Boolean =
    granted || !osAsks

internal fun onboardingProgress(doneCount: Int, total: Int): String =
    "$doneCount of $total steps done. Each one reflects what the app is actually doing right now — " +
            "nothing here completes by tapping past it."

@Composable
fun OnboardingScreen(
    serverRunning: Boolean,
    serverUrl: String,
    pinRequired: Boolean,
    notificationsGranted: Boolean,
    osAsksAboutNotifications: Boolean,
    pairedCount: Int,
    onToggleServer: () -> Unit,
    onSetPinRequired: () -> Unit,
    onAskNotifications: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalPulseColors.current
    val stepsDone = listOf(
        serverRunning,
        pinRequired,
        notificationStepDone(notificationsGranted, osAsksAboutNotifications),
        pairedCount > 0
    ).count { it }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("onboarding_list"),
        contentPadding = PaddingValues(
            start = PulseSpacing.gutter.dp, end = PulseSpacing.gutter.dp,
            top = PulseSpacing.md.dp, bottom = PulseSpacing.xl.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth().testTag("onboarding_header")) {
                Text("Getting started", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text(
                    onboardingProgress(stepsDone, 4),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            OnboardingStep(
                tag = "onboarding_step_server",
                done = serverRunning,
                title = "Turn the server on",
                doneDetail = if (serverUrl.isNotBlank()) "Running at $serverUrl" else "Running now.",
                todoDetail = "Phones, tablets and browsers on your network connect to this phone " +
                        "once the server is on.",
                actionLabel = if (serverRunning) "Stop the server" else "Start the server",
                onAction = onToggleServer
            )
        }

        item {
            OnboardingStep(
                tag = "onboarding_step_pin",
                done = pinRequired,
                title = "Require a PIN from browsers",
                doneDetail = "A PIN is required before a web client can do anything.",
                todoDetail = "Without a PIN, anyone who can reach this phone's address can use it. " +
                        "Turn the requirement on and pick a code in Security center.",
                actionLabel = if (pinRequired) "Change PIN — open Settings" else "Require a PIN",
                onAction = if (pinRequired) onOpenSettings else onSetPinRequired
            )
        }

        item {
            val notifDone = notificationStepDone(notificationsGranted, osAsksAboutNotifications)
            OnboardingStep(
                tag = "onboarding_step_notifications",
                done = notifDone,
                title = "Allow notifications",
                doneDetail = if (!osAsksAboutNotifications)
                    "This Android version shows notifications without asking."
                else "Notifications are allowed.",
                todoDetail = "Android will not deliver transfer and pairing alerts until you allow " +
                        "them. The system asks once; if you already denied it, open the app's " +
                        "notification settings instead.",
                actionLabel = "Allow notifications",
                onAction = onAskNotifications,
                hideAction = notifDone
            )
        }

        item {
            OnboardingStep(
                tag = "onboarding_step_pair",
                done = pairedCount > 0,
                title = "Pair your first device",
                doneDetail = if (pairedCount == 1) "One device is trusted."
                else "$pairedCount devices are trusted.",
                todoDetail = "Pairing runs over an encrypted channel with a safety code you can " +
                        "read aloud. Start a scan on the Devices page.",
                actionLabel = "Open devices",
                onAction = onOpenDevices
            )
        }

        item {
            Text(
                "These settings live on this phone only — there is no account to sync them to, " +
                        "and this page never closes itself: it just reports what is true.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_note")
            )
        }
    }
}

@Composable
private fun OnboardingStep(
    tag: String,
    done: Boolean,
    title: String,
    doneDetail: String,
    todoDetail: String,
    actionLabel: String,
    onAction: () -> Unit,
    hideAction: Boolean = false
) {
    val c = LocalPulseColors.current
    PulseCard(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (done) "Done" else "Not done",
                tint = if (done) c.accent else c.textTertiary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(PulseSpacing.md.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(
                    if (done) doneDetail else todoDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary
                )
                if (!hideAction) {
                    PulseButton(
                        actionLabel,
                        onClick = onAction,
                        tone = PulseButtonTone.Quiet,
                        modifier = Modifier.padding(top = PulseSpacing.sm.dp).testTag("${tag}_action")
                    )
                }
            }
        }
    }
}
