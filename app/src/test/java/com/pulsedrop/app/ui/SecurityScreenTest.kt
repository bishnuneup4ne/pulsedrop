package com.pulsedrop.app.ui

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pulsedrop.app.model.PairedDevice
import com.pulsedrop.app.server.WebClientInfo
import com.pulsedrop.app.ui.screens.SecurityScreen
import com.pulsedrop.app.ui.screens.browserAgeLabel
import com.pulsedrop.app.ui.screens.encryptionFacts
import com.pulsedrop.app.ui.screens.securityRecommendations
import com.pulsedrop.app.ui.theme.PulseDropTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Security Center is only worth opening if it cannot lie. These tests pin the three ways it
 * could: advice that was never triggered, encryption wording that overstates the transport, and
 * destructive buttons that mutate without confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        val pinRequired = mutableListOf<Boolean>()
        val autoAccept = mutableListOf<Boolean>()
        val unpaired = mutableListOf<String>()
        var regen = 0
        var devices = 0
    }

    private fun screen(
        hooks: Hooks,
        serverRunning: Boolean = false,
        pinRequired: Boolean = false,
        autoAccept: Boolean = true,
        paired: List<PairedDevice> = emptyList(),
        clients: List<WebClientInfo> = emptyList(),
        code: (String) -> String? = { null }
    ) {
        compose.setContent {
            PulseDropTheme {
                SecurityScreen(
                    serverRunning = serverRunning,
                    pinRequired = pinRequired,
                    autoAccept = autoAccept,
                    pairedDevices = paired,
                    webClients = clients,
                    onSetPinRequired = { hooks.pinRequired += it },
                    onRegeneratePin = { hooks.regen++ },
                    onSetAutoAccept = { hooks.autoAccept += it },
                    onUnpair = { hooks.unpaired += it },
                    safetyCode = code,
                    onOpenDevices = { hooks.devices++ }
                )
            }
        }
    }

    private fun device(
        pid: String = "p1",
        name: String = "Nova",
        verified: Boolean = false,
        appVersion: String = ""
    ) = PairedDevice(
        pid = pid,
        name = name,
        pubB64 = "AAA",
        originIp = "10.0.2.99",
        originPort = 8080,
        createdAt = 1_730_000_000_000L,
        verified = verified,
        appVersion = appVersion
    )

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(tag))
    }

    // ── Pure mappings ──────────────────────────────────────────────────────

    @Test
    fun aPhoneAlreadyInTheSafeStateGetsNoAdvice() {
        val advice = securityRecommendations(
            serverRunning = true,
            pinRequired = true,
            autoAccept = false,
            unverifiedPairs = 0,
            micGranted = true
        )
        assertTrue(advice.isEmpty())
    }

    @Test
    fun adviceOnlyNamesConditionsThatAreReallyTrue() {
        val off = securityRecommendations(
            serverRunning = false, pinRequired = false, autoAccept = false,
            unverifiedPairs = 0, micGranted = true
        )
        assertTrue(
            "a stopped server is not an open door",
            off.none { it.id == "pin" }
        )
        val on = securityRecommendations(
            serverRunning = true, pinRequired = false, autoAccept = true,
            unverifiedPairs = 2, micGranted = false
        )
        assertEquals(listOf("pin", "autoaccept", "verify", "mic"), on.map { it.id })
    }

    @Test
    fun encryptionWordingNeverCallsBrowserOrCallDataEndToEnd() {
        val facts = encryptionFacts().associateBy { it.key }
        assertTrue(facts.getValue("pair_chat").state.contains("End-to-end"))
        assertEquals("Not end-to-end encrypted", facts.getValue("browser_data").state)
        assertEquals("Not encrypted", facts.getValue("call_media").state)
        assertTrue(
            "TLS is stated as transport, never as end-to-end",
            facts.getValue("browser_tls").state.contains("in transit") &&
                !facts.getValue("browser_tls").state.contains("end-to-end")
        )
    }

    @Test
    fun browserAgeNeverGoesNegative() {
        val now = 1_730_000_000_000L
        assertEquals("seen 45s ago", browserAgeLabel(now - 45_000, now))
        assertEquals("seen 3m ago", browserAgeLabel(now - 180_000, now))
        assertEquals("seen 0s ago", browserAgeLabel(now + 5_000, now))
    }

    // ── UI ─────────────────────────────────────────────────────────────────

    @Test
    fun noPairingsSaysSoWithoutInventingDevices() {
        screen(Hooks())
        scrollTo("security_devices_empty")
        compose.onNodeWithTag("security_devices_empty").assertExists()
        compose.onNodeWithText("Nova").assertDoesNotExist()
    }

    @Test
    fun unpairRequiresConfirmationAndCancelChangesNothing() {
        val hooks = Hooks()
        screen(hooks, paired = listOf(device()))
        scrollTo("security_unpair_p1")
        compose.onNodeWithTag("security_unpair_p1").performClick()
        compose.onNodeWithTag("security_unpair_dialog").assertExists()

        compose.onNodeWithTag("security_unpair_cancel").performClick()
        assertEquals(hooks.unpaired, emptyList<String>())

        compose.onNodeWithTag("security_unpair_p1").performClick()
        compose.onNodeWithTag("security_unpair_confirm").performClick()
        assertEquals(listOf("p1"), hooks.unpaired)
    }

    @Test
    fun unpairCopyPromisesNoPeerNotificationAndNoUndo() {
        screen(Hooks(), paired = listOf(device()))
        scrollTo("security_unpair_p1")
        compose.onNodeWithTag("security_unpair_p1").performClick()
        compose.onNodeWithText("The other device is not notified", substring = true).assertExists()
        compose.onNodeWithText("no undo", substring = true).assertExists()
    }

    @Test
    fun pinToggleAndRegenerateAreWiredToTheRealServiceCalls() {
        val hooks = Hooks()
        screen(hooks)
        scrollTo("security_pin_toggle")
        compose.onNodeWithTag("security_pin_toggle").performClick()
        assertEquals(listOf(true), hooks.pinRequired)

        compose.onNodeWithTag("security_pin_regen").performClick()
        compose.onNodeWithTag("security_pin_regen_cancel").performClick()
        assertEquals(0, hooks.regen)
        compose.onNodeWithTag("security_pin_regen").performClick()
        compose.onNodeWithTag("security_pin_regen_confirm").performClick()
        assertEquals(1, hooks.regen)
    }

    @Test
    fun adviceCardsAppearOnlyForTrueConditionsAndTheirButtonsAct() {
        val hooks = Hooks()
        screen(hooks, serverRunning = true, pinRequired = false, autoAccept = true)
        scrollTo("security_rec_pin")
        compose.onNodeWithTag("security_rec_pin").assertExists()
        compose.onNodeWithTag("security_rec_verify").assertDoesNotExist()
        compose.onNodeWithTag("security_rec_action_pin").performClick()
        assertEquals(listOf(true), hooks.pinRequired)

        scrollTo("security_rec_action_autoaccept")
        compose.onNodeWithTag("security_rec_action_autoaccept").performClick()
        assertTrue(hooks.autoAccept.contains(false))
    }

    @Test
    fun stoppedServerAndSeenBrowsersGetSeparateHonestSections() {
        screen(Hooks(), serverRunning = false)
        scrollTo("security_browsers_stopped")
        compose.onNodeWithText("The server is stopped", substring = true).assertExists()
    }

    @Test
    fun seenBrowsersAreListedAsLastSeenRecordsNotSessions() {
        val now = System.currentTimeMillis()
        screen(
            Hooks(),
            serverRunning = true,
            clients = listOf(
                WebClientInfo(ip = "10.0.2.5", name = "Laptop", userAgent = "", lastSeen = now - 20_000)
            )
        )
        scrollTo("security_browser_10.0.2.5")
        compose.onNodeWithTag("security_browser_10.0.2.5").assertExists()
        scrollTo("security_browsers_note")
        compose.onNodeWithText("cannot disconnect", substring = true).assertExists()
    }

    @Test
    fun permissionsReadFromTheOsAndCheckAgainStampsTheRealCheck() {
        screen(Hooks())
        scrollTo("security_perm_${Manifest.permission.RECORD_AUDIO}")
        compose.onNodeWithTag("security_perm_${Manifest.permission.RECORD_AUDIO}").assertExists()
        compose.onNodeWithText("Not granted").assertExists()

        scrollTo("security_header")
        compose.onNodeWithTag("security_check").performClick()
        compose.onNodeWithTag("security_lastchecked").assertExists()
    }

    @Test
    fun aGrantedPermissionShowsAsGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        screen(Hooks())
        scrollTo("security_perm_${Manifest.permission.RECORD_AUDIO}")
        compose.onNodeWithText("Granted").assertExists()
    }

    @Test
    fun anUngantedPermissionNextToItStillShowsAsNotGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        screen(Hooks())
        scrollTo("security_perm_${Manifest.permission.CAMERA}")
        compose.onNodeWithTag("security_perm_${Manifest.permission.CAMERA}").assertExists()
        compose.onNodeWithText("Not granted").assertExists()
    }

    @Test
    fun safetyCodeDialogShowsTheRealCodeOrSaysThereIsNone() {
        val hooks = Hooks()
        screen(hooks, paired = listOf(device()), code = { "482910" })
        scrollTo("security_code_p1")
        compose.onNodeWithTag("security_code_p1").performClick()
        compose.onNodeWithText("482910").assertExists()
        compose.onNodeWithText("Verify on Devices").performClick()
        assertEquals(1, hooks.devices)
    }

    @Test
    fun aMissingPeerKeyIsNamedInsteadOfShowingAnEmptyCode() {
        screen(Hooks(), paired = listOf(device()), code = { null })
        scrollTo("security_code_p1")
        compose.onNodeWithTag("security_code_p1").performClick()
        compose.onNodeWithText("No code is available", substring = true).assertExists()
    }

    @Test
    fun thePageStatesWhatItCannotDo() {
        screen(Hooks())
        scrollTo("security_disclosure")
        compose.onNodeWithTag("security_disclosure").assertExists()
        compose.onNodeWithText("No malware scanning", substring = true).assertExists()
        compose.onNodeWithText("no security", substring = true).assertExists()
    }

    @Test
    fun updateIntegrityIsStatedAsSizeOnlyVerification() {
        screen(Hooks())
        scrollTo("security_update")
        compose.onNodeWithText("Checked by Android, not by the app").assertExists()
        compose.onNodeWithText("only the downloaded file's size", substring = true).assertExists()
    }
}
