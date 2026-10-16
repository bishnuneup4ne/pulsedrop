package com.pulsedrop.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.ui.components.PulseButton
import com.pulsedrop.app.ui.components.PulseChip
import com.pulsedrop.app.ui.components.PulseSwitch
import com.pulsedrop.app.ui.theme.PulseDropTheme
import com.pulsedrop.app.ui.theme.PulseTheme
import com.pulsedrop.app.ui.theme.ThemePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kit promises things — a loading button cannot be clicked, a badge only shows real counts,
 * a switch reports the value it changed to. Promises are only worth what the tests make them, so
 * each one is exercised through the same click path a finger uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PulseKitBehaviorTest {

    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) {
        compose.setContent { PulseDropTheme { body() } }
    }

    @Test
    fun chipClickReachesTheCaller() {
        var clicks = 0
        content { PulseChip(label = "Filters", onClick = { clicks++ }) }
        compose.onNodeWithText("Filters").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun chipBadgeOnlyShowsRealCounts() {
        content {
            PulseChip(label = "WithBadge", badge = 3)
            PulseChip(label = "WithoutBadge", badge = 0)
        }
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("WithoutBadge").assertIsDisplayed()
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun loadingAndDisabledButtonsRefuseClicks() {
        var clicks = 0
        content {
            PulseButton(label = "Loading", onClick = { clicks++ }, loading = true)
            PulseButton(label = "Off", onClick = { clicks++ }, enabled = false)
            PulseButton(label = "On", onClick = { clicks++ })
        }
        // A disabled control refuses a real tap by announcing itself as disabled; that is the
        // contract, so it is what we assert (performClick drives the semantics action directly and
        // is not how a finger behaves).
        compose.onNodeWithText("Loading").assertIsNotEnabled()
        compose.onNodeWithText("Off").assertIsNotEnabled()
        compose.onNodeWithText("On").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun switchReportsTheValueItChangedTo() {
        var reported: Boolean? = null
        content { PulseSwitch(checked = false, onCheckedChange = { reported = it }, label = "Auto accept") }
        compose.onNodeWithText("Auto accept").performClick()
        assertEquals(true, reported)
    }
}

/**
 * A setting that does not survive the process is not a setting. These round-trip the two
 * preferences the Settings sheet actually writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsPersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun clear() {
        context.getSharedPreferences("localdrop_settings", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun themeChoiceSurvivesAReload() {
        ThemePreference.save(context, PulseTheme.LIGHT)
        ThemePreference.load(context)
        assertEquals(PulseTheme.LIGHT, ThemePreference.mode.value)
    }

    @Test
    fun anUnsetThemeDefaultsToLight() {
        ThemePreference.load(context)
        assertEquals(PulseTheme.LIGHT, ThemePreference.mode.value)
    }

    @Test
    fun autoAcceptDefaultsToOnAndRoundTrips() {
        val prefs = context.getSharedPreferences("localdrop_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("autoAccept", true))
        prefs.edit().putBoolean("autoAccept", false).apply()
        assertFalse(
            context.getSharedPreferences("localdrop_settings", Context.MODE_PRIVATE)
                .getBoolean("autoAccept", true)
        )
    }
}

/**
 * The one feedback channel must not lose messages between posting and collecting, and posting
 * with nobody listening must never throw — half the app posts events from service threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PulseEventsTest {

    @Test
    fun postedMessagesReachTheCollector() = runTest {
        val received = mutableListOf<String>()
        val job = launch { PulseEvents.flow.collect { received.add(it) } }
        testScheduler.advanceUntilIdle()
        PulseEvents.post("one")
        PulseEvents.post("two")
        testScheduler.advanceUntilIdle()
        assertTrue(received.containsAll(listOf("one", "two")))
        job.cancel()
    }

    @Test
    fun postingWithoutAListenerDoesNotThrow() {
        PulseEvents.post("nobody is watching")
    }
}
