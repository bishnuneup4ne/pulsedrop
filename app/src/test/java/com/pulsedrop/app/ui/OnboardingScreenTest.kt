package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.ui.screens.OnboardingPrefs
import com.pulsedrop.app.ui.screens.OnboardingScreen
import com.pulsedrop.app.ui.screens.notificationStepDone
import com.pulsedrop.app.ui.screens.onboardingProgress
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Onboarding earns trust the same way every other page here does: a step is only checked when
 * the underlying setting is actually on. These tests flip the real inputs and demand the page
 * follow, and they pin that the first-run flag persists exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var server = 0
        var pin = 0
        var notif = 0
        var devices = 0
        var settings = 0
    }

    private fun screen(
        hooks: Hooks = Hooks(),
        serverRunning: Boolean = false,
        serverUrl: String = "http://10.0.2.16:8080",
        pinRequired: Boolean = false,
        notificationsGranted: Boolean = false,
        osAsks: Boolean = true,
        pairedCount: Int = 0
    ) {
        compose.setContent {
            PulseDropTheme {
                OnboardingScreen(
                    serverRunning = serverRunning,
                    serverUrl = serverUrl,
                    pinRequired = pinRequired,
                    notificationsGranted = notificationsGranted,
                    osAsksAboutNotifications = osAsks,
                    pairedCount = pairedCount,
                    onToggleServer = { hooks.server++ },
                    onSetPinRequired = { hooks.pin++ },
                    onAskNotifications = { hooks.notif++ },
                    onOpenDevices = { hooks.devices++ },
                    onOpenSettings = { hooks.settings++ }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    // ── pure functions ──────────────────────────────────────────────────────

    @Test
    fun theNotificationStepIsDoneWhenAllowedOrWhenTheOsNeverAsks() {
        assertTrue(notificationStepDone(granted = true, osAsks = true))
        assertFalse(notificationStepDone(granted = false, osAsks = true))
        assertTrue(notificationStepDone(granted = false, osAsks = false))
    }

    @Test
    fun theProgressLineCountsStepsAndSaysTheyReflectReality() {
        assertTrue(onboardingProgress(2, 4).startsWith("2 of 4 steps done"))
        assertTrue(onboardingProgress(0, 4).contains("reflects what the app is actually doing"))
    }

    @Test
    fun theFirstRunFlagPersistsThroughTheRealPreferencesStore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(OnboardingPrefs.isSeen(context))
        OnboardingPrefs.markSeen(context)
        assertTrue(OnboardingPrefs.isSeen(context))
    }

    // ── the page ────────────────────────────────────────────────────────────

    @Test
    fun aPristineSetupSaysZeroOfFourInsteadOfAPretendWelcome() {
        screen()
        compose.onNodeWithText("0 of 4 steps done", substring = true).assertIsDisplayed()
    }

    @Test
    fun theServerStepStartsTheEngineThroughTheRealHook() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("onboarding_step_server")
        compose.onNodeWithTag("onboarding_step_server_action").performClick()
        assertEquals(1, hooks.server)
    }

    @Test
    fun aRunningServerIsReportedWithItsRealUrl() {
        screen(serverRunning = true)
        scrollTo("onboarding_step_server")
        compose.onNodeWithText("Running at http://10.0.2.16:8080", substring = true).assertIsDisplayed()
    }

    @Test
    fun thePinStepEnablesTheRealSettingAndNamesTheRealConsequence() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("onboarding_step_pin")
        compose.onNodeWithText("anyone who can reach this phone's address", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_step_pin_action").performClick()
        assertEquals(1, hooks.pin)
    }

    @Test
    fun aRequiredPinIsReportedAsDoneAndItsControlRoutesToSettings() {
        val hooks = Hooks()
        screen(hooks, pinRequired = true)
        scrollTo("onboarding_step_pin")
        compose.onNodeWithText("A PIN is required before a web client", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_step_pin_action").performClick()
        // When already on, the same control routes to Settings; it does not re-toggle blindly.
        assertEquals(1, hooks.settings)
        assertEquals(0, hooks.pin)
    }

    @Test
    fun theNotificationStepAsksThroughTheRealPermissionPath() {
        val hooks = Hooks()
        screen(hooks, notificationsGranted = false, osAsks = true)
        scrollTo("onboarding_step_notifications")
        compose.onNodeWithTag("onboarding_step_notifications_action").performClick()
        assertEquals(1, hooks.notif)
    }

    @Test
    fun theNotificationStepHidesItsButtonOnceThePermissionIsGranted() {
        screen(notificationsGranted = true)
        scrollTo("onboarding_step_notifications")
        compose.onNodeWithTag("onboarding_step_notifications_action").assertDoesNotExist()
        compose.onNodeWithText("Notifications are allowed.").assertIsDisplayed()
    }

    @Test
    fun anAndroidThatNeverAsksIsNotPretendedToBeAwaitingPermission() {
        screen(osAsks = false)
        scrollTo("onboarding_step_notifications")
        compose.onNodeWithText("without asking", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_step_notifications_action").assertDoesNotExist()
    }

    @Test
    fun thePairingStepCountsTheRealTrustedDevices() {
        screen(pairedCount = 2)
        scrollTo("onboarding_step_pair")
        compose.onNodeWithText("2 devices are trusted.").assertIsDisplayed()
    }

    @Test
    fun thePairingStepOpensTheRealDevicesPage() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("onboarding_step_pair")
        compose.onNodeWithTag("onboarding_step_pair_action").performClick()
        assertEquals(1, hooks.devices)
    }

    @Test
    fun thePageStatesThatNothingSyncsAndNothingIntercepts() {
        screen()
        scrollTo("onboarding_note")
        compose.onNodeWithText("no account to sync them to", substring = true).assertIsDisplayed()
        compose.onNodeWithText("never closes itself", substring = true).assertIsDisplayed()
    }
}
