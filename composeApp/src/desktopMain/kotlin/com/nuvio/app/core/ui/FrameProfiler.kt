package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * A JFR recording, started only in bench mode, that names the methods burning the frame budget.
 *
 * [FrameBudget] can say *how much* a frame costs and *which display mode* costs more, but its draw
 * probes only see draw calls Compose actually re-records. The first real measurement run showed why
 * that is not enough: Adaptive Ambient spent **5.3-6.4 ms of UI-thread CPU per frame** while the
 * probes accounted for **0.01-0.08 ms** of it and recompositions were near zero. The cost is real,
 * it is CPU, it is on one thread — and it is somewhere no probe is placed. A sampling profiler is
 * the tool for that, and it is cheap to reach for once the search is narrowed to one thread.
 *
 * Dumped periodically rather than only on exit: the app is routinely closed by killing it, and a
 * recording that only lands on a clean shutdown would keep not being there.
 */
internal object FrameProfiler {

    private const val DumpIntervalMs = 60_000L
    // Three minutes, not ten: the profile is always read as "the mode I was just exercising", and a
    // longer window silently blends the previous one into it.
    private const val MaxAgeSeconds = 180L

    fun startIfBenching(): String {
        if (!FrameBenchMode.enabled) return "frame profiler off (bench mode not enabled)"
        return runCatching { start() }.getOrElse { "frame profiler failed: ${it.message}" }
    }

    private fun start(): String {
        val destination = logsDir().resolve("nuvio-frame-profile.jfr")
        val configuration = jdk.jfr.Configuration.getConfiguration("profile")
        val recording = jdk.jfr.Recording(configuration).apply {
            name = "nuvio-frame-budget"
            // Bounded on disk: the interesting window is always the last few minutes of scrolling,
            // and an unbounded recording on an app left running for hours is a liability.
            maxAge = java.time.Duration.ofSeconds(MaxAgeSeconds)
            isToDisk = true
            dumpOnExit = true
            setDestination(destination)
            start()
        }
        thread(isDaemon = true, name = "nuvio-frame-profiler") {
            while (true) {
                try {
                    Thread.sleep(DumpIntervalMs)
                    recording.dump(destination)
                } catch (_: InterruptedException) {
                    return@thread
                } catch (t: Throwable) {
                    Logger.withTag("FrameProfiler").i { "dump failed: ${t.message}" }
                    return@thread
                }
            }
        }
        return "frame profiler recording to $destination (rolling ${MaxAgeSeconds}s, " +
            "dumped every ${DumpIntervalMs / 1000}s)"
    }

    private fun logsDir(): Path {
        val logs = DesktopStorage.rootDir.resolve("logs")
        runCatching { java.nio.file.Files.createDirectories(logs) }
        return logs
    }
}
