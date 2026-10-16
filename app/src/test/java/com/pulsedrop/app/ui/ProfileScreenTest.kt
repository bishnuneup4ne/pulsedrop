package com.pulsedrop.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.pulsedrop.app.security.Crypto
import com.pulsedrop.app.ui.screens.ProfileScreen
import com.pulsedrop.app.ui.screens.groupedDeviceId
import com.pulsedrop.app.ui.screens.profileNameOrNull
import com.pulsedrop.app.ui.theme.PulseDropTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A profile page is where an app is most tempted to invent an account. These tests pin this one
 * to the only identity PulseDrop actually has: a name in the settings store and a device ID
 * derived from the real public key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Hooks {
        val renames = mutableListOf<String>()
        var devices = 0
        var security = 0
        var chats = 0
        var transfers = 0
    }

    private fun screen(
        hooks: Hooks = Hooks(),
        deviceName: String = "Test Phone",
        deviceId: String = "0a1b2c3d4e5f6789",
        serverRunning: Boolean = false,
        pairedCount: Int = 0,
        groupCount: Int = 0,
        historyCount: Int = 0
    ) {
        compose.setContent {
            PulseDropTheme {
                ProfileScreen(
                    deviceName = deviceName,
                    deviceId = deviceId,
                    modelLabel = "google sdk_gphone64_x86_64",
                    androidLabel = "17 (API 37)",
                    serverRunning = serverRunning,
                    pairedCount = pairedCount,
                    groupCount = groupCount,
                    historyCount = historyCount,
                    onRename = { hooks.renames.add(it) },
                    onOpenDevices = { hooks.devices++ },
                    onOpenSecurity = { hooks.security++ },
                    onOpenChats = { hooks.chats++ },
                    onOpenTransfers = { hooks.transfers++ }
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
    fun blankAndWhitespaceNamesAreRejectedNotSaved() {
        assertNull(profileNameOrNull(""))
        assertNull(profileNameOrNull("   "))
        assertEquals("Kitchen Pixel", profileNameOrNull("  Kitchen Pixel  "))
    }

    @Test
    fun longNamesAreCappedAtTheStatedFortyCharacters() {
        val name = "n".repeat(60)
        assertEquals(40, profileNameOrNull(name)?.length)
    }

    @Test
    fun theDeviceIdIsGroupedNotMangled() {
        assertEquals("0a1b 2c3d 4e5f 6789", groupedDeviceId("0a1b2c3d4e5f6789"))
    }

    @Test
    fun theDeviceIdShownComesFromTheRealKeyDerivation() {
        val pub = Crypto.publicKeyB64(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        val id = Crypto.deviceId(pub)
        assertEquals(16, id.length)
        screen(deviceId = id)
        compose.onNodeWithText(groupedDeviceId(id)).assertIsDisplayed()
    }

    // ── the page ────────────────────────────────────────────────────────────

    @Test
    fun theHeaderShowsTheRealSavedName() {
        screen(deviceName = "Living Room Phone")
        compose.onNodeWithText("Living Room Phone").assertIsDisplayed()
    }

    @Test
    fun renamingGoesThroughTheDialogAndHandsOverTheTrimmedName() {
        val hooks = Hooks()
        screen(hooks)
        compose.onNodeWithTag("profile_edit_name").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("profile_name_field").performTextClearance()
        compose.onNodeWithTag("profile_name_field").performTextInput("   ")
        // whitespace cannot be saved: the confirm control is disabled, not silently accepted
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("profile_name_field").performTextClearance()
        compose.onNodeWithTag("profile_name_field").performTextInput("  New Name ")
        compose.onNodeWithTag("profile_name_save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("New Name"), hooks.renames)
    }

    @Test
    fun cancellingTheDialogRenamesNothing() {
        val hooks = Hooks()
        screen(hooks)
        compose.onNodeWithTag("profile_edit_name").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("profile_name_field").performTextInput("Whatever")
        compose.onNodeWithTag("profile_name_cancel").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), hooks.renames)
    }

    @Test
    fun theServerLineStatesTheTruthAboutReachability() {
        screen(serverRunning = false)
        compose.onNodeWithText("Stopped — peers cannot reach this phone").assertIsDisplayed()
    }

    @Test
    fun countsComeFromTheArgumentsAndRouteToRealSections() {
        val hooks = Hooks()
        screen(hooks, pairedCount = 3, groupCount = 1, historyCount = 12)
        scrollTo("profile_counts_card")
        compose.onNodeWithText("Trusted devices").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithTag("profile_count_devices").performClick()
        compose.onNodeWithTag("profile_count_history").performClick()
        assertEquals(1, hooks.devices)
        assertEquals(1, hooks.transfers)
    }

    @Test
    fun aSingleGroupIsSaidInSingular() {
        screen(groupCount = 1)
        scrollTo("profile_counts_card")
        compose.onNodeWithText("Chat group").assertIsDisplayed()
    }

    @Test
    fun thePageSaysOutLoudThereIsNoAccount() {
        screen()
        scrollTo("profile_note")
        compose.onNodeWithText("no sign-in, no email and no password", substring = true).assertIsDisplayed()
        compose.onNodeWithText("uninstalling erases it all", substring = true).assertIsDisplayed()
    }

    @Test
    fun theIdentityCardCallsTheDeviceIdAnAddressNotASecret() {
        screen()
        compose.onNodeWithText("an address, not a secret", substring = true).assertIsDisplayed()
    }
}
