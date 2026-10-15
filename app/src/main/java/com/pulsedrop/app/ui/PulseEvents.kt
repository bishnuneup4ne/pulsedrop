package com.pulsedrop.app.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The one transient-feedback channel in the app.
 *
 * Anything that used to fire a `Toast` posts here instead, and `MainScreen` renders the queue in
 * the snackbar host: one channel means one position, one dismissal rule, and one place a
 * screen reader announcement is made. The buffer is why it is a SharedFlow rather than a
 * nullable state — a burst of events ("3 of 5 reached", then the failure) must all be seen, in
 * order, not overwrite each other.
 */
object PulseEvents {
    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<String> = _flow.asSharedFlow()

    fun post(message: String) {
        _flow.tryEmit(message)
    }
}
