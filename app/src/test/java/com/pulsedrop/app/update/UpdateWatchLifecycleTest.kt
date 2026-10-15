package com.pulsedrop.app.update

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The policy is tested elsewhere; this is the part that only shows up on a device: a network listener
 * that is registered, registered once, and given back. A ViewModel that is recreated on every cold
 * start and leaves a callback behind is a slow leak of binder handles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateWatchLifecycleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** A scope whose job is already cancelled: anything launched into it is dropped, so no test reaches the network. */
    private val deadScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })

    private fun watch(): UpdateWatch =
        UpdateWatch(context, UpdateManager(context, deadScope), clock = { 0L })

    private fun registered(): Int = shadowOf(cm).networkCallbacks.size

    @Test
    fun `starting listens, and listening twice is still listening once`() {
        val before = registered()
        val w = watch()
        w.start()
        assertEquals("one event source, however many times start is called", before + 1, registered())
        w.start()
        assertEquals(before + 1, registered())
        w.stop()
    }

    @Test
    fun `stopping gives the callback back`() {
        val before = registered()
        val w = watch()
        w.start()
        w.stop()
        assertEquals(before, registered())
        // Stopping something already stopped must not unregister anything else's listener.
        w.stop()
        assertEquals(before, registered())
    }
}
