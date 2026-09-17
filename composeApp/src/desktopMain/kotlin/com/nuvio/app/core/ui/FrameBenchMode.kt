package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerSettingsStorage

/**
 * Diagnostic launch mode that unpins the frame rate, so a browsing frame's TOTAL cost can be read
 * off the clock.
 *
 * With vsync on — the normal case — the frame period is the refresh period whether a frame cost
 * 2 ms or 15 ms, and only starts moving once the app has already missed. That makes the interesting
 * question ("how much of the budget is left?") unanswerable on a fast machine, which is exactly the
 * machine this gets developed on. It also hides GPU cost completely: [FrameBudget] can time the
 * app's own composition and draw, but blur, overdraw and layer blending happen after the last
 * Kotlin frame on that path returns.
 *
 * Turned off, the frame period becomes the wall-clock cost of producing one frame, CPU and GPU
 * together. That number is comparable between display modes and scales predictably to weaker
 * hardware, which FPS does not.
 *
 * Two ways in, matching the stream-payload diagnostics above it: the `NUVIO_FRAME_BENCH` env var,
 * or a file named `frame-bench` in the Nuvio data directory. The marker file is the one that works
 * when the app is launched from Explorer or a shortcut, which is how it is actually launched.
 *
 * Not for normal use: with no vsync the GPU renders flat out, which on a laptop is loud and hot.
 */
internal object FrameBenchMode {

    /** Set by [applyIfRequested]; reported in the frame-budget lines so a log is never ambiguous. */
    var enabled: Boolean = false
        private set

    /**
     * Whether named subtree probes are installed.
     *
     * Separate from [enabled] because the two have different costs. The phase split is one pair of
     * `nanoTime` calls for the whole window and runs for everyone; a probe is an extra draw node on
     * every poster card, which is small but is structure added to the hot path for a measurement
     * nobody asked for. So the summary is always on and the breakdown is opt-in — and running
     * without vsync implies wanting the breakdown.
     */
    var probesEnabled: Boolean by mutableStateOf(false)
        private set

    /**
     * Flips diagnostics at runtime, from the Advanced settings toggle.
     *
     * Snapshot state, not a plain flag: the frame-clock loops and every `frameBudgetProbe` read it
     * during composition, so making it observable is what lets the switch take effect without a
     * restart. Never turns bench mode on — that disables vsync and belongs to the marker file.
     */
    fun applyProbesEnabled(value: Boolean) {
        if (probesEnabled == value || enabled) return
        probesEnabled = value
        Logger.withTag("FrameBudget").i { "performance logging " + (if (value) "ON" else "OFF") }
    }

    /**
     * Must run before the first Skia layer is created — the properties are read when the layer is
     * constructed, and setting them afterwards silently does nothing.
     */
    fun applyIfRequested(): String {
        val benchEnv = !System.getenv("NUVIO_FRAME_BENCH").isNullOrBlank()
        val benchMarker = markerExists("frame-bench")
        val probeEnv = !System.getenv("NUVIO_FRAME_PROBES").isNullOrBlank()
        val probeMarker = markerExists("frame-probes")
        enabled = benchEnv || benchMarker
        // The stored Advanced-settings toggle is the ordinary way in; the markers stay for
        // developer use and for a user who cannot reach settings.
        val stored = runCatching { PlayerSettingsStorage.loadDesktopPerformanceLogging() }
            .getOrNull() == true
        probesEnabled = enabled || probeEnv || probeMarker || stored
        if (enabled) {
            System.setProperty("skiko.vsync.enabled", "false")
            // Without this skiko substitutes its own frame limiter for the missing vsync, which
            // re-pins the period to the display's refresh rate and undoes the whole point.
            System.setProperty("skiko.vsync.framelimit.fallback.enabled", "false")
            // Skiko's own counter, which is driven by presents rather than by anything this app
            // schedules — the only frame rate here that is not measured by the code under suspicion
            // of inflating it.
            System.setProperty("skiko.fps.enabled", "true")
            System.setProperty("skiko.fps.periodSeconds", "5.0")
        }
        return "frame bench enabled=$enabled (env=$benchEnv, marker=$benchMarker) " +
            "probes=$probesEnabled (env=$probeEnv, marker=$probeMarker, setting=$stored)"
    }

    /** Test seam. Production code sets both flags once, from [applyIfRequested]. */
    internal fun debugSetProbesEnabled(value: Boolean) {
        probesEnabled = value
    }

    private fun markerExists(name: String): Boolean = runCatching {
        DesktopStorage.rootDir.resolve(name).toFile().exists()
    }.getOrDefault(false)
}
