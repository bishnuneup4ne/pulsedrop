package com.pulsedrop.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pulsedrop.app.update.UpdateManager
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Not now" is only real if it survives a restart. The store is the same localdrop_settings file
 * the rest of the app uses; this checks a fresh updater reads exactly what was left there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateManagerSnoozeTest {

    @Test
    fun aSnoozedVersionIsLoadedFromTheSettingsStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("localdrop_settings", Context.MODE_PRIVATE)
            .edit().putString("update_snoozed", "9.9.9").apply()
        val um = UpdateManager(context, MainScope())
        assertEquals("9.9.9", um.snoozed.value)
    }

    @Test
    fun withNothingSnoozedTheUpdaterStartsLoud() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("localdrop_settings", Context.MODE_PRIVATE)
            .edit().remove("update_snoozed").apply()
        val um = UpdateManager(context, MainScope())
        assertNull(um.snoozed.value)
    }
}
