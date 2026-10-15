package com.pulsedrop.app

import android.app.Application
import com.pulsedrop.app.diag.CrashReporter

class PulseDropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything else can throw, so a failure during start-up is still a report.
        CrashReporter.install(this)
    }
}
