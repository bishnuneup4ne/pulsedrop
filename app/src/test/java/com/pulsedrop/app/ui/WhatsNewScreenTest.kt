package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.ui.screens.WhatsNewScreen
import com.pulsedrop.app.ui.screens.formatPublished
import com.pulsedrop.app.ui.screens.releaseNotesOrEmpty
import com.pulsedrop.app.ui.screens.releaseRelation
import com.pulsedrop.app.ui.screens.releaseRelationTone
import com.pulsedrop.app.ui.components.PulseStatusTone
import com.pulsedrop.app.ui.theme.PulseDropTheme
import com.pulsedrop.app.update.ReleaseInfo
import com.pulsedrop.app.update.sortReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A changelog page is the easiest one to lie on. These tests pin it to GitHub's release payloads:
 * text arrives verbatim or the page says there was none, and while nothing has been fetched the
 * page admits that instead of showing notes it made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsNewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        var fetches = 0
    }

    private fun release(
        version: String,
        name: String = "PulseDrop $version",
        notes: String = "Real notes for $version",
        publishedAt: String = "2026-10-14T09:31:02Z",
        prerelease: Boolean = false,
        hasApk: Boolean = true,
        downloads: Int = 7
    ) = ReleaseInfo(version, name, notes, publishedAt, prerelease, hasApk, downloads)

    private fun screen(
        hooks: Hooks = Hooks(),
        installedVersion: String = "1.7.0",
        releases: List<ReleaseInfo>? = null,
        error: String? = null
    ) {
        compose.setContent {
            PulseDropTheme {
                WhatsNewScreen(
                    installedVersion = installedVersion,
                    releases = releases,
                    error = error,
                    onFetch = { hooks.fetches++ }
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
    fun aReleaseIsRelatedToTheInstalledVersionOneOfThreeTrueWays() {
        assertEquals("You are running this one", releaseRelation("1.7.0", "1.7.0"))
        assertEquals("Newer than what you have", releaseRelation("1.8.0", "1.7.0"))
        assertEquals("Older than what you have", releaseRelation("1.6.0", "1.7.0"))
    }

    @Test
    fun theSameReleaseWrittenWithDifferentPrecisionIsStillThisOne() {
        // The app reports 1.7, GitHub tags it 1.7.0; neither number is newer than the other,
        // so calling it "older" would be a lie the live page actually told once.
        assertEquals("You are running this one", releaseRelation("1.7.0", "1.7"))
        assertEquals("You are running this one", releaseRelation("1.7", "1.7.0"))
        assertEquals("Newer than what you have", releaseRelation("1.7.1", "1.7"))
        assertEquals(PulseStatusTone.Positive, releaseRelationTone("1.7.0", "1.7"))
    }

    @Test
    fun theRelationToneMatchesTheRelation() {
        assertEquals(PulseStatusTone.Positive, releaseRelationTone("1.7.0", "1.7.0"))
        assertEquals(PulseStatusTone.Active, releaseRelationTone("1.8.0", "1.7.0"))
        assertEquals(PulseStatusTone.Neutral, releaseRelationTone("1.6.0", "1.7.0"))
    }

    @Test
    fun githubTimestampsBecomeDatesAndNonsenseIsPassedThroughUnchanged() {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("Oct 14, 2026", formatPublished("2026-10-14T09:31:02Z", fmt))
        assertEquals("sometime", formatPublished("sometime", fmt))
    }

    @Test
    fun aReleaseWithoutNotesSaysSoInsteadOfGettingInventedNotes() {
        assertEquals("The publisher left no notes on this release.", releaseNotesOrEmpty("   "))
        assertEquals("Fixed a thing", releaseNotesOrEmpty("  Fixed a thing\n"))
    }

    @Test
    fun releasesSortNewestFirstEvenAcrossDoubleDigits() {
        val sorted = sortReleases(
            listOf(release("1.7.0"), release("1.10.0"), release("1.9.0"))
        ).map { it.versionName }
        assertEquals(listOf("1.10.0", "1.9.0", "1.7.0"), sorted)
    }

    // ── the page ────────────────────────────────────────────────────────────

    @Test
    fun beforeAnyAnswerThePageAdmitsItHasNothingAndAsksGitHubOnce() {
        val hooks = Hooks()
        screen(hooks)
        compose.onNodeWithTag("whatsnew_loading").assertIsDisplayed()
        // Opening the page triggers one fetch; it must not spam the API per recomposition.
        assertTrue(hooks.fetches >= 1)
        assertEquals(1, hooks.fetches)
    }

    @Test
    fun theFetchButtonAsksAgain() {
        val hooks = Hooks()
        screen(hooks)
        val before = hooks.fetches
        compose.onNodeWithTag("whatsnew_fetch").performClick()
        assertEquals(before + 1, hooks.fetches)
    }

    @Test
    fun aFailedFetchShowsTheRealReasonAndARetry() {
        val hooks = Hooks()
        val reason = "GitHub answered HTTP 403 for the release list."
        screen(hooks, error = reason)
        compose.onNodeWithTag("whatsnew_error").assertIsDisplayed()
        compose.onNodeWithText(reason).assertIsDisplayed()
        val before = hooks.fetches
        compose.onNodeWithTag("whatsnew_retry").performClick()
        assertEquals(before + 1, hooks.fetches)
    }

    @Test
    fun anEmptyReleaseListIsReportedAsEmptyNotAsSilence() {
        screen(releases = emptyList())
        scrollTo("whatsnew_empty")
        compose.onNodeWithText("GitHub lists no published releases for this project yet.")
            .assertIsDisplayed()
    }

    @Test
    fun releasesRenderWithTheirOwnWordsAndTheirOwnCounters() {
        screen(
            releases = listOf(
                release("1.8.0", notes = "Ring one screen when a call comes in"),
                release("1.7.0"),
                release("1.6.0", notes = "", hasApk = false)
            )
        )
        scrollTo("whatsnew_v1.8.0")
        compose.onNodeWithTag("whatsnew_v1.8.0").assertIsDisplayed()
        compose.onNodeWithText("Ring one screen when a call comes in").assertIsDisplayed()
        compose.onNodeWithText("Newer than what you have").assertIsDisplayed()
        compose.onNodeWithText("You are running this one").assertIsDisplayed()
        scrollTo("whatsnew_v1.6.0")
        compose.onNodeWithText("The publisher left no notes on this release.").assertIsDisplayed()
    }

    @Test
    fun aReleaseWithoutAnInstallFilePrintsNoDownloadLine() {
        screen(releases = listOf(release("1.6.0", hasApk = false, downloads = 0)))
        scrollTo("whatsnew_v1.6.0")
        compose.onNodeWithTag("whatsnew_v1.6.0").assertIsDisplayed()
        compose.onNodeWithText("install file", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun aReleaseWithAnInstallFilePrintsGitHubsOwnCount() {
        screen(releases = listOf(release("1.8.0", downloads = 42)))
        scrollTo("whatsnew_v1.8.0")
        compose.onNodeWithText("42 download(s)", substring = true).assertIsDisplayed()
    }

    @Test
    fun aPrereleaseIsMarkedAsOne() {
        screen(releases = listOf(release("1.9.0-beta", prerelease = true, publishedAt = "2026-10-01T00:00:00Z")))
        scrollTo("whatsnew_v1.9.0-beta")
        compose.onNodeWithText("Published Oct 1, 2026 • pre-release").assertIsDisplayed()
    }

    @Test
    fun theHeaderNamesTheVersionActuallyInstalled() {
        screen(installedVersion = "1.7.0", releases = listOf(release("1.8.0")))
        compose.onNodeWithText("You are on v1.7.0", substring = true).assertIsDisplayed()
    }
}
