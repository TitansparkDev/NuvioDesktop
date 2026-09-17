package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger

/**
 * Runtime switch for the two `withFrameNanos` loops that drive [FrameCadenceTelemetry] and
 * [FrameBudgetTelemetry], so their effect on an idle window can be measured rather than argued.
 *
 * **Confirmed 2026-09-07, and it is why these loops no longer run in ordinary builds.** A
 * `while (true) { withFrameNanos { … } }` loop is a permanent frame-clock awaiter, and `Recomposer`
 * schedules a frame whenever its broadcast clock has awaiters — so the app rendered continuously
 * with nothing on screen changing. Toggled live on an idle home screen:
 *
 *     loops on:  758 FPS, GPU 25%, CPU 7%
 *     loops off:  57 FPS, GPU  0%, CPU 3%
 *
 * A quarter of the GPU, permanently, for a static picture. `FrameCadenceTelemetry` shipped to every
 * user unconditionally after the flicker investigation, so this had been running for all of them —
 * telemetry meant to find performance problems being one of the larger ones. An HTPC spends most of
 * its life parked on the home screen, where that is pure heat, power and fan noise.
 *
 * Both loops are therefore gated on [FrameBenchMode.probesEnabled] now: measured only when someone
 * has asked to measure. Ctrl+Shift+F still toggles them within a diagnostic build, which is how the
 * numbers above were taken.
 */
internal object FrameClockProbes {

    var enabled by mutableStateOf(true)
        private set

    /** Returns the new state, or null when diagnostics are off and nothing changed. */
    fun toggle(): Boolean? {
        if (!FrameBenchMode.probesEnabled) return null
        enabled = !enabled
        Logger.withTag("FrameBudget").i {
            "frame-clock probes " + (if (enabled) "ON" else "OFF") +
                " — with them off, any frames still being produced while idle belong to the app"
        }
        return enabled
    }
}
