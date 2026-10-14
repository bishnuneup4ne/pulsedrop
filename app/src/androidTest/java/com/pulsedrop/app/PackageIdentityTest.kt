package com.pulsedrop.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the installed applicationId against the one the build was compiled with, so a package
 * or namespace change cannot ship half-applied.
 */
@RunWith(AndroidJUnit4::class)
class PackageIdentityTest {
    @Test
    fun installedPackageMatchesBuildApplicationId() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }
}
