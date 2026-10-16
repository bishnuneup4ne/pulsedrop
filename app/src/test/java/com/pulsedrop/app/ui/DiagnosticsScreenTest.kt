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
import com.pulsedrop.app.ui.screens.CrashReportInfo
import com.pulsedrop.app.ui.screens.DiagnosticsScreen
import com.pulsedrop.app.ui.screens.crashReportsFrom
import com.pulsedrop.app.ui.screens.liveSpeedLabel
import com.pulsedrop.app.ui.screens.sessionAgeLabel
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A diagnostics page earns trust by refusing to invent. These tests pin the page to the real
 * crash folder, the real BuildConfig, and the honest statement that no application log exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagnosticsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        val opened = mutableListOf<String>()
        var network = 0
    }

    private fun screen(
        hooks: Hooks,
        crashDir: File,
        activeTransferCount: Int = 0,
        liveSpeed: Long = 0L,
        completed: Int = 0,
        failed: Int = 0,
        updateStatus: String? = null,
        lastSuccessElapsed: Long = 0L
    ) {
        compose.setContent {
            PulseDropTheme {
                DiagnosticsScreen(
                    crashDir = crashDir,
                    serverStartedAt = 0L,
                    serverRunning = false,
                    activeTransferCount = activeTransferCount,
                    liveSpeedBytesPerSec = liveSpeed,
                    historyCompleted = completed,
                    historyFailed = failed,
                    updateStatusMessage = updateStatus,
                    updateLastSuccessElapsed = lastSuccessElapsed,
                    relayHost = null,
                    relayLastSync = 0L,
                    nowElapsedRealtime = { 60_000L },
                    onOpenReport = { path, _ -> hooks.opened.add(path) },
                    onOpenNetwork = { hooks.network++ }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun crashDirWith(vararg millis: Long): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "diag_${System.nanoTime()}")
        dir.mkdirs()
        millis.forEach { File(dir, "$it.txt").writeText("stack $it") }
        File(dir, "not-a-report.offered").writeText("x")
        return dir
    }

    // ── pure functions ──────────────────────────────────────────────────────

    @Test
    fun crashReportsComeFromRealFilesNewestFirst() {
        val dir = crashDirWith(1000L, 3000L, 2000L)
        val reports = crashReportsFrom(dir)
        assertEquals(listOf(3000L, 2000L, 1000L), reports.map { it.occurredAt })
        assertEquals(10L, reports.first().sizeBytes)
    }

    @Test
    fun oddFilesInTheCrashFolderAreNotTreatedAsReports() {
        val dir = crashDirWith(5L)
        assertEquals(1, crashReportsFrom(dir).size)
    }

    @Test
    fun aMissingCrashFolderMeansNoReportsNotAnError() {
        assertEquals(emptyList<CrashReportInfo>(), crashReportsFrom(File("/no/such/dir-${System.nanoTime()}")))
    }

    @Test
    fun sessionAgeDistinguishesNeverFromARealTime() {
        assertTrue(sessionAgeLabel(0L, 60_000L).contains("Nothing has completed"))
        assertTrue(sessionAgeLabel(30_000L, 60_000L).contains("30s"))
    }

    @Test
    fun idleSpeedSaysIdleInsteadOfZeroAsAPretendMeasurement() {
        assertTrue(liveSpeedLabel(0L).contains("Idle"))
        assertTrue(liveSpeedLabel(1_048_576L).contains("MB/s"))
    }

    // ── the page ────────────────────────────────────────────────────────────

    @Test
    fun theRuntimeCardShowsTheRealBuildConfigNotAConstant() {
        screen(Hooks(), crashDirWith())
        compose.onNodeWithText("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.APPLICATION_ID, substring = true).assertIsDisplayed()
    }

    @Test
    fun crashReportsAreListedFromTheRealFolder() {
        val dir = crashDirWith(1_700_000_000_000L, 1_700_000_000_500L)
        screen(Hooks(), dir)
        scrollTo("diag_crash_card")
        compose.onNodeWithText("2 kept", substring = true).assertIsDisplayed()
        scrollTo("diag_crash_1700000000500")
        compose.onNodeWithTag("diag_crash_1700000000500").assertIsDisplayed()
        scrollTo("diag_crash_1700000000000")
        compose.onNodeWithTag("diag_crash_1700000000000").assertIsDisplayed()
    }

    @Test
    fun tappingAReportOpensTheRealFileThroughTheGivenHook() {
        val dir = crashDirWith(1_700_000_000_000L)
        val hooks = Hooks()
        screen(hooks, dir)
        scrollTo("diag_crash_1700000000000")
        compose.onNodeWithTag("diag_crash_1700000000000").performClick()
        assertEquals(listOf(File(dir, "1700000000000.txt").absolutePath), hooks.opened)
    }

    @Test
    fun noCrashReportsIsStatedAsGoodNewsNotAnEmptyTable() {
        screen(Hooks(), crashDirWith())
        scrollTo("diag_crash_card")
        compose.onNodeWithText("None kept on this device.").assertIsDisplayed()
    }

    @Test
    fun thePageStatesOutLoudThatThereIsNoApplicationLog() {
        screen(Hooks(), crashDirWith())
        scrollTo("diag_nolog_card")
        compose.onNodeWithTag("diag_nolog_card").assertIsDisplayed()
        compose.onNodeWithText("never writes a log file", substring = true).assertIsDisplayed()
    }

    @Test
    fun transferCountsAndMeasuredSpeedComeFromTheArguments() {
        screen(Hooks(), crashDirWith(), activeTransferCount = 2, liveSpeed = 512_000L, completed = 7, failed = 1)
        compose.onNodeWithText("2 transfers running", substring = true).assertIsDisplayed()
        compose.onNodeWithText("500.0 KB/s", substring = true).assertIsDisplayed()
        compose.onNodeWithText("7 completed and 1 failed", substring = true).assertIsDisplayed()
    }

    @Test
    fun theUpdaterDisclosesItsInMemoryOnlyClock() {
        screen(Hooks(), crashDirWith(), updateStatus = "Downloading v1.8…", lastSuccessElapsed = 10_000L)
        scrollTo("diag_update_card")
        compose.onNodeWithText("Downloading v1.8…").assertIsDisplayed()
        compose.onNodeWithText("in-memory only", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Last done 50s into this session.", substring = true).assertIsDisplayed()
    }

    @Test
    fun theDisclosureCardListsWhatThePageCannotSee() {
        screen(Hooks(), crashDirWith())
        scrollTo("diag_disclosure")
        compose.onNodeWithTag("diag_disclosure").assertIsDisplayed()
        compose.onNodeWithText("thermals and ANR", substring = true).assertIsDisplayed()
        compose.onNodeWithText("nothing is sampled when idle", substring = true).assertIsDisplayed()
    }

    @Test
    fun theNetworkJumpUsesTheRealNavigationHook() {
        val hooks = Hooks()
        screen(hooks, crashDirWith())
        scrollTo("diag_relay_card")
        compose.onNodeWithTag("diag_open_network").performClick()
        assertEquals(1, hooks.network)
    }
}
