package com.pulsedrop.app

import android.app.Application
import com.pulsedrop.app.diag.CrashReporter
import com.pulsedrop.app.ui.theme.ThemePreference

class PulseDropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything else can throw, so a failure during start-up is still a report.
        CrashReporter.install(this)
        // Read the saved theme before the first Activity composes. SharedPreferences read off the
        // disk here is the cost of not painting one theme and then flipping to the other.
        ThemePreference.load(this)
    }
}
