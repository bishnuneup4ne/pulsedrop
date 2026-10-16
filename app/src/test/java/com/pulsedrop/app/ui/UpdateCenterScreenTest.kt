package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.screens.UpdateCenterScreen
import com.pulsedrop.app.ui.screens.lastCheckLine
import com.pulsedrop.app.ui.screens.sizeLine
import com.pulsedrop.app.ui.screens.updateCenterStatus
import com.pulsedrop.app.ui.theme.PulseDropTheme
import com.pulsedrop.app.update.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update page is where an app is most tempted to fake a progress bar or claim a version it
 * has not seen. These tests pin every state to the updater's real signals and pin the words that
 * describe what it cannot do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateCenterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var checks = 0
        var downloads = 0
        var installs = 0
        var permissions = 0
        var snoozes = 0
        var whatsNew = 0
    }

    private val info = UpdateInfo(
        versionName = "1.8.0",
        releaseName = "PulseDrop 1.8.0",
        notes = "Ring one screen when a call comes in",
        downloadUrl = "https://example.invalid/a.apk",
        sizeBytes = 5_242_880L
    )

    private fun screen(
        hooks: Hooks = Hooks(),
        info: UpdateInfo? = null,
        statusMessage: String? = null,
        progress: Float? = null,
        apkReady: Boolean = false,
        checking: Boolean = false,
        canInstallPermission: Boolean = true,
        snoozedVersion: String? = null
    ) {
        compose.setContent {
            PulseDropTheme {
                UpdateCenterScreen(
                    installedVersion = "1.7.0",
                    buildCode = 9,
                    isDebugBuild = false,
                    info = info,
                    statusMessage = statusMessage,
                    progress = progress,
                    apkReady = apkReady,
                    checking = checking,
                    canInstallPermission = canInstallPermission,
                    snoozedVersion = snoozedVersion,
                    lastCheckLine = lastCheckLine(0L, 0L),
                    lastAnswered = false,
                    onCheck = { hooks.checks++ },
                    onDownload = { hooks.downloads++ },
                    onInstall = { hooks.installs++ },
                    onAskInstallPermission = { hooks.permissions++ },
                    onSnooze = { hooks.snoozes++ },
                    onOpenWhatsNew = { hooks.whatsNew++ }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction())
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
        compose.waitForIdle()
    }

    // ── pure functions ──────────────────────────────────────────────────────

    @Test
    fun theStatusReflectsTheRealOperationAndNothingElse() {
        assertEquals("No check has answered yet" to PulseStatusTone.Neutral,
            updateCenterStatus(false, null, false, null, null, lastAnswered = false))
        assertEquals("Asking GitHub…" to PulseStatusTone.Active,
            updateCenterStatus(true, null, false, null, null, lastAnswered = false))
        assertEquals("Update available: v1.8.0" to PulseStatusTone.Warning,
            updateCenterStatus(false, null, false, info, null, lastAnswered = true))
        assertEquals("Downloading v1.8.0 — 40%" to PulseStatusTone.Active,
            updateCenterStatus(true, 0.4f, false, info, null, lastAnswered = true))
        assertEquals("Downloaded and ready — Android's installer takes it from here" to PulseStatusTone.Positive,
            updateCenterStatus(false, 1f, true, info, null, lastAnswered = true))
        // a plain message only speaks when no operation is in flight
        assertEquals("You are on the latest version (1.7.0)" to PulseStatusTone.Neutral,
            updateCenterStatus(false, null, false, null, "You are on the latest version (1.7.0)", lastAnswered = true))
        // An answered "nothing to install" is not the same as never having asked.
        assertEquals("GitHub's last answer: no update pending for this device" to PulseStatusTone.Positive,
            updateCenterStatus(false, null, false, null, null, lastAnswered = true))
    }

    @Test
    fun theLastCheckLineTellsNeverApartFromLongAgo() {
        assertEquals("A check has not reached GitHub in this session.", lastCheckLine(0L, 600_000L))
        assertEquals("Last check reached GitHub under a minute ago.", lastCheckLine(60_000L, 60_000L + 30_000L))
        assertEquals("Last check reached GitHub 5 minute(s) ago.", lastCheckLine(60_000L, 60_000L + 5 * 60_000L))
        assertEquals("Last check reached GitHub 2 hour(s) ago.",
            lastCheckLine(60_000L, 60_000L + 120 * 60_000L))
    }

    @Test
    fun aMissingSizeIsAdmittedNotGuessed() {
        assertEquals("GitHub says the install file is 5.0 MB.", sizeLine(5_242_880L))
        assertEquals("GitHub did not report a size for the install file.", sizeLine(0L))
    }

    // ── the page ────────────────────────────────────────────────────────────

    @Test
    fun theVersionCardQuotesTheBuildRecordAndTheHonestLastCheck() {
        screen()
        compose.onNodeWithText("Installed: v1.7.0 (build 9)").assertIsDisplayed()
        compose.onNodeWithText("A check has not reached GitHub in this session.").assertIsDisplayed()
    }

    @Test
    fun checkingWiresToTheRealCheckAndDisablesItselfWhileOneRuns() {
        val hooks = Hooks()
        screen(hooks)
        compose.onNodeWithTag("updatecenter_check").performClick()
        assertEquals(1, hooks.checks)
    }

    @Test
    fun aRunningCheckCannotBeStartedAgain() {
        screen(checking = true)
        compose.onNodeWithTag("updatecenter_check").assertIsNotEnabled()
        compose.onNodeWithText("Checking…").assertIsDisplayed()
    }

    @Test
    fun anAvailableUpdateShowsGitHubsOwnWordsAndNothingInvented() {
        val hooks = Hooks()
        screen(hooks, info = info)
        scrollTo("updatecenter_target_card")
        compose.onNodeWithText("v1.8.0 is waiting").assertIsDisplayed()
        compose.onNodeWithText("Ring one screen when a call comes in").assertIsDisplayed()
        compose.onNodeWithText("GitHub says the install file is 5.0 MB.").assertIsDisplayed()
        compose.onNodeWithTag("updatecenter_download").performClick()
        assertEquals(1, hooks.downloads)
        compose.onNodeWithTag("updatecenter_snooze").performClick()
        assertEquals(1, hooks.snoozes)
    }

    @Test
    fun aReleaseWithoutNotesSaysThereWereNone() {
        screen(info = info.copy(notes = "  "))
        scrollTo("updatecenter_target_card")
        compose.onNodeWithText("Nothing was invented to fill it", substring = true).assertIsDisplayed()
    }

    @Test
    fun aReadyApkOffersInstallThroughAndroidNotSilentSuccess() {
        val hooks = Hooks()
        screen(hooks, info = info, apkReady = true)
        // assert the status line while it is still in view, before scrolling to the action card
        compose.onNodeWithText("Downloaded and ready — Android's installer takes it from here")
            .assertIsDisplayed()
        scrollTo("updatecenter_target_card")
        compose.onNodeWithTag("updatecenter_install").performClick()
        assertEquals(1, hooks.installs)
        assertEquals(0, hooks.permissions)
    }

    @Test
    fun installWithoutThePermissionAsksAndroidForItFirst() {
        val hooks = Hooks()
        screen(hooks, info = info, apkReady = true, canInstallPermission = false)
        scrollTo("updatecenter_target_card")
        compose.onNodeWithTag("updatecenter_install").performClick()
        assertEquals(0, hooks.installs)
        assertEquals(1, hooks.permissions)
    }

    @Test
    fun aSnoozedVersionIsAnnouncedWhileItStaysQuiet() {
        screen(snoozedVersion = "1.8.0")
        scrollTo("updatecenter_snoozed_card")
        compose.onNodeWithText("v1.8.0 is snoozed", substring = true).assertIsDisplayed()
    }

    @Test
    fun theTrustCardRefusesToClaimVerificationTheAppDoesNotDo() {
        screen()
        scrollTo("updatecenter_trust_card")
        compose.onNodeWithText("There is no separate checksum or signature step", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theLimitsCardNamesWhatTheUpdaterCannotDo() {
        screen()
        scrollTo("updatecenter_limits_card")
        compose.onNodeWithText("No rollback", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No silent install", substring = true).assertIsDisplayed()
    }

    @Test
    fun thePageRoutesToTheRealReleaseList() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("updatecenter_whatsnew")
        compose.onNodeWithTag("updatecenter_whatsnew").performClick()
        assertEquals(1, hooks.whatsNew)
    }
}
