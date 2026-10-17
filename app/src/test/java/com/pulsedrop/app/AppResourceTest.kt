package com.pulsedrop.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppResourceTest {

    @Test
    fun `launcher label is the app name with the version it is running`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The name is generated from versionName, so this fails the moment the two stop agreeing —
        // which is the point of putting the version where it cannot be forgotten.
        assertEquals("PulseDrop ${BuildConfig.VERSION_NAME}", context.getString(R.string.app_name))
    }
}
