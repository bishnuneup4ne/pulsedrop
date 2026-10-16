package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.BuildConfig
import com.pulsedrop.app.ui.screens.ABOUT_LINKS
import com.pulsedrop.app.ui.screens.AboutScreen
import com.pulsedrop.app.ui.screens.OPEN_SOURCE_DEPS
import com.pulsedrop.app.ui.screens.installSpanLine
import com.pulsedrop.app.ui.screens.latestReleaseLine
import com.pulsedrop.app.ui.theme.PulseDropTheme
import com.pulsedrop.app.update.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The About page is where fabricated claims get caught, so these tests pin it to real things:
 * the actual BuildConfig, Android's own install record, the licenses the libraries state for
 * themselves, and link URLs that belong to this project or to those license texts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        val links = mutableListOf<String>()
        var checks = 0
    }

    private fun screen(
        hooks: Hooks = Hooks(),
        updateInfo: UpdateInfo? = null,
        updateStatus: String? = null
    ) {
        compose.setContent {
            PulseDropTheme {
                AboutScreen(
                    updateInfo = updateInfo,
                    updateStatus = updateStatus,
                    onCheckUpdate = { hooks.checks++ },
                    onOpenLink = { url -> hooks.links.add(url) }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun fmt() = SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault())

    // ── pure functions ──────────────────────────────────────────────────────

    @Test
    fun anUnreportedInstallTimeIsStatedAsUnreportedNotAsEpochZero() {
        assertTrue(installSpanLine(0L, 0L, fmt()).contains("does not report"))
    }

    @Test
    fun aFreshInstallSaysNothingHasReplacedIt() {
        val t = 1_700_000_000_000L
        val line = installSpanLine(t, t + 30_000L, fmt())
        assertTrue(line.contains("Nothing has replaced this install"))
        assertTrue(line.contains(fmt().format(Date(t))))
    }

    @Test
    fun anUpdatedInstallNamesBothRealTimes() {
        val first = 1_700_000_000_000L
        val last = first + 86_400_000L * 3
        val line = installSpanLine(first, last, fmt())
        assertTrue(line.contains("Installed"))
        assertTrue(line.contains("Last updated"))
        assertTrue(line.contains(fmt().format(Date(last))))
    }

    @Test
    fun theUpdateLineDistinguishesNeverFoundFromARealRelease() {
        assertTrue(latestReleaseLine(null).contains("No newer release has been found"))
        val info = UpdateInfo("1.8", "PulseDrop 1.8", "notes", "https://example.invalid/a.apk", 1L)
        val line = latestReleaseLine(info)
        assertTrue(line.contains("v1.8"))
        assertTrue(line.contains("PulseDrop 1.8"))
    }

    @Test
    fun everyListedDependencyCarriesALicenseName() {
        assertTrue(OPEN_SOURCE_DEPS.all { it.license.isNotBlank() && it.version.isNotBlank() })
        assertEquals(
            "BSD-3-Clause",
            OPEN_SOURCE_DEPS.first { it.artifact == "org.nanohttpd:nanohttpd" }.license
        )
        assertEquals(
            "Bouncy Castle Licence",
            OPEN_SOURCE_DEPS.first { it.artifact == "org.bouncycastle:bcpkix-jdk18on" }.license
        )
    }

    // ── the page ────────────────────────────────────────────────────────────

    @Test
    fun theHeaderShowsTheRealBuildNotAHardcodedVersion() {
        screen()
        compose.onNodeWithText(
            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", substring = true
        ).assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.APPLICATION_ID).assertIsDisplayed()
    }

    @Test
    fun theDeviceCardReportsAndroidsOwnInstallRecordOrSaysItCannot() {
        screen()
        scrollTo("about_device_card")
        compose.onNodeWithText("does not report", substring = true).assertIsDisplayed()
    }

    @Test
    fun theUpdateCardShowsTheRealUpdaterState() {
        screen(updateStatus = "Checking GitHub…")
        scrollTo("about_update_card")
        compose.onNodeWithText("Checking GitHub…").assertIsDisplayed()
    }

    @Test
    fun withNoCheckRunThePageSaysNoNewerReleaseWasFound() {
        screen()
        scrollTo("about_update_card")
        compose.onNodeWithText("No newer release has been found", substring = true).assertIsDisplayed()
    }

    @Test
    fun theCheckButtonDrivesTheRealUpdater() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("about_update_card")
        compose.onNodeWithTag("about_check_update").performClick()
        assertEquals(1, hooks.checks)
    }

    @Test
    fun aKnownNewerReleaseIsNamedWithItsRealVersion() {
        val hooks = Hooks()
        screen(hooks, updateInfo = UpdateInfo("1.8", "PulseDrop 1.8", "notes", "https://example.invalid/a.apk", 1L))
        scrollTo("about_update_card")
        compose.onNodeWithText("v1.8", substring = true).assertIsDisplayed()
    }

    @Test
    fun theLicenseCardNamesTheLicenseTheRepoActuallyCarries() {
        screen()
        scrollTo("about_license_card")
        compose.onNodeWithTag("about_license_card").assertIsDisplayed()
        compose.onNodeWithText("PolyForm Noncommercial 1.0.0").assertIsDisplayed()
    }

    @Test
    fun dependencyRowsRenderWithTheirPinnedVersionsAndLicenses() {
        screen()
        scrollTo("about_dep_org.nanohttpd:nanohttpd")
        compose.onNodeWithTag("about_dep_org.nanohttpd:nanohttpd").assertIsDisplayed()
        compose.onNodeWithText("2.3.1", substring = true).assertIsDisplayed()
        compose.onNodeWithText("BSD-3-Clause").assertIsDisplayed()
        scrollTo("about_dep_com.squareup.okhttp3:okhttp")
        compose.onNodeWithText("4.10.0", substring = true).assertIsDisplayed()
    }

    @Test
    fun theDependencyListDisclosesThatVersionsAreBuildTimePins() {
        screen()
        scrollTo("about_deps_note")
        compose.onNodeWithText("this build pinned", substring = true).assertIsDisplayed()
    }

    @Test
    fun linkRowsHandOverTheExactUrlTheyDisplay() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("about_links_card")
        val source = ABOUT_LINKS.first { it.id == "source" }
        compose.onNodeWithTag("about_link_source").performClick()
        assertEquals(listOf(source.url), hooks.links)
        val polyform = ABOUT_LINKS.first { it.id == "polyform" }
        scrollTo("about_link_polyform")
        compose.onNodeWithTag("about_link_polyform").performClick()
        assertEquals(listOf(source.url, polyform.url), hooks.links)
    }

    @Test
    fun everyLinkUrlIsOneThisProjectOrItsLicensesActuallyName() {
        assertTrue(ABOUT_LINKS.all {
            it.url.startsWith("https://github.com/bishnuneup4ne/pulsedrop") ||
                    it.url.startsWith("https://polyformproject.org/licenses/noncommercial") ||
                    it.url == "https://www.apache.org/licenses/LICENSE-2.0"
        })
    }

    @Test
    fun theHowItWorksFactsSurviveTheRewrite() {
        screen()
        scrollTo("about_how_card")
        compose.onNodeWithText("One server, every device").assertIsDisplayed()
        compose.onNodeWithText("Encrypted pairing").assertIsDisplayed()
        compose.onNodeWithText("No accounts, no cloud").assertIsDisplayed()
    }
}
