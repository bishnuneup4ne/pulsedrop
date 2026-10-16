package com.pulsedrop.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.ui.screens.NOTIFICATION_FACTS
import com.pulsedrop.app.ui.screens.NotificationScreen
import com.pulsedrop.app.ui.screens.channelStateLine
import com.pulsedrop.app.ui.screens.importanceLabel
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Notifications page is a promise about honesty, not features: it may only describe the
 * channels this app really creates, must read their state from Android rather than assume it,
 * and must never claim a history the app does not keep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var ask = 0
        var settings = 0
        var activity = 0
    }

    private fun screen(
        hooks: Hooks,
        permissionGranted: Boolean = true
    ) {
        compose.setContent {
            PulseDropTheme {
                NotificationScreen(
                    permissionGranted = permissionGranted,
                    onRequestPermission = { hooks.ask++ },
                    onOpenSystemSettings = { hooks.settings++ },
                    onOpenActivity = { hooks.activity++ }
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    // ── Pure mappings ──────────────────────────────────────────────────────

    @Test
    fun importanceWordsNeverInventAnUnknownState() {
        assertEquals("Turned off", importanceLabel(NotificationManager.IMPORTANCE_NONE))
        assertEquals("Silent", importanceLabel(NotificationManager.IMPORTANCE_LOW))
        assertEquals("Alerting", importanceLabel(NotificationManager.IMPORTANCE_HIGH))
        assertEquals("Unknown to this app", importanceLabel(4242))
    }

    @Test
    fun anUncreatedChannelIsNotReportedAsOff() {
        val fact = NOTIFICATION_FACTS.first()
        assertTrue(
            channelStateLine(fact, null).startsWith("Not created yet")
        )
    }

    @Test
    fun aCreatedChannelReportsTheNameAndImportanceAndroidGives() {
        val fact = NOTIFICATION_FACTS.first()
        val channel = NotificationChannel(fact.id, "Server running", NotificationManager.IMPORTANCE_LOW)
        assertEquals("Server running: Silent", channelStateLine(fact, channel))
    }

    // ── Permission card ────────────────────────────────────────────────────

    @Test
    fun aGrantedPermissionDisablesTheAskButtonButKeepsTheReason() {
        val hooks = Hooks()
        screen(hooks, permissionGranted = true)
        scrollTo("notif_permission_status")
        compose.onNodeWithText("Notifications allowed").assertExists()
        compose.onNodeWithTag("notif_ask").assertIsNotEnabled()
    }

    @Test
    fun aBlockedPermissionSaysWhatAndroidHidesAndLetsYouAskAgain() {
        val hooks = Hooks()
        screen(hooks, permissionGranted = false)
        scrollTo("notif_permission_status")
        compose.onNodeWithText("Notifications blocked").assertExists()
        compose.onNodeWithText("hides every notification this app posts", substring = true)
            .assertExists()
        compose.onNodeWithTag("notif_ask").performClick()
        assertEquals(1, hooks.ask)
    }

    @Test
    fun systemSettingsButtonOpensTheRealPanel() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("notif_system")
        compose.onNodeWithTag("notif_system").performClick()
        assertEquals(1, hooks.settings)
    }

    // ── Channels ───────────────────────────────────────────────────────────

    @Test
    fun everyChannelRowExistsForEveryChannelTheCodeCreatesAndNoMore() {
        assertEquals(5, NOTIFICATION_FACTS.size)
        val hooks = Hooks()
        screen(hooks)
        for (fact in NOTIFICATION_FACTS) {
            scrollTo("notif_channel_${fact.id}")
            compose.onNodeWithTag("notif_channel_${fact.id}").assertExists()
        }
    }

    @Test
    fun aLiveChannelReadsItsRealStateFromAndroidNotFromAConstant() {
        val manager = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("pulsedrop_call", "Calls", NotificationManager.IMPORTANCE_NONE)
        )
        try {
            val hooks = Hooks()
            screen(hooks)
            scrollTo("notif_channel_pulsedrop_call")
            compose.onNodeWithTag("notif_channel_pulsedrop_call")
                .assertExists()
            compose.onNodeWithText("Calls: Turned off").assertExists()
        } finally {
            manager.deleteNotificationChannel("pulsedrop_call")
        }
    }

    // ── Honest limits ──────────────────────────────────────────────────────

    @Test
    fun theSilenceCardNamesWhatNeverNotifies() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("notif_silence_card")
        compose.onNodeWithText("Chat messages: the app only refreshes chats while it is open", substring = true)
            .assertExists()
        compose.onNodeWithText("Finished transfers", substring = true).assertExists()
    }

    @Test
    fun theHistoryCardDisclaimsALogAndPointsAtTheRealOne() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("notif_history_card")
        compose.onNodeWithText("keeps no log of the notifications it posted", substring = true)
            .assertExists()
        compose.onNodeWithTag("notif_open_activity").performClick()
        assertEquals(1, hooks.activity)
    }
}
