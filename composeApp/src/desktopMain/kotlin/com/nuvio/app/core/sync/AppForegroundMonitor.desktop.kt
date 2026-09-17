package com.nuvio.app.core.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Desktop "the user came back to the app" signal, fed by the window's focus listener in `Main.kt`.
 *
 * This used to be `emptyFlow()`, which made every foreground-triggered refresh in the app dead
 * code on the only platform this fork ships. Nothing replaced it, so a remote Continue Watching
 * source was never re-read after startup.
 *
 * Replay is deliberately zero and the buffer one: a subscriber that starts later wants the *next*
 * time the user comes back, not a stale one, and focus events that pile up while a collector is
 * busy collapse into a single refresh rather than a queue of them.
 */
internal actual object AppForegroundMonitor {
    private val _events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    actual fun events(): Flow<Unit> = _events.asSharedFlow()

    fun onWindowGainedFocus() {
        _events.tryEmit(Unit)
    }
}
