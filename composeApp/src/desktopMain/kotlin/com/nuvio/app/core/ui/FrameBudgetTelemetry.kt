package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import co.touchlab.kermit.Logger

/**
 * Where a browsing frame's budget goes, split by cost type and by named subtree.
 *
 * [FrameCadenceTelemetry] answers "did we present on time", which on a fast machine is always yes:
 * vsync pins the interval at the refresh period no matter how much headroom is left, so cadence
 * cannot tell a frame that cost 2 ms from one that cost 8 ms. That headroom is what decides whether
 * a weaker PC keeps up, so this measures WORK rather than rate.
 *
 *  - `cpu`    — CPU time per frame across the UI thread AND skiko's render threads, reported as
 *               `cpu=total (ui=… render=…)`. The headroom number: what a slower CPU multiplies.
 *               Both halves are needed because which thread does the drawing depends on the
 *               renderer — see [renderThreadIds].
 *  - `period` — wall-clock interval between frames. The refresh period under vsync; the true cost
 *               of producing a frame, GPU included, in bench mode.
 *  - `probedDraw` — draw time inside the named probes. A floor on draw cost, not the total.
 *  - `alloc`  — UI-thread bytes per frame, collected later in a pause belonging to no frame.
 *
 * `cpu` well under `period` in bench mode means the frame is GPU-bound — overdraw, blur, layer
 * count — which no CPU timer here can see.
 *
 * Only scrolling frames are reported. Idle numbers are dominated by whatever animation happens to
 * be running and would bury the case being investigated.
 *
 * **Why frames are counted from the frame clock and not from a draw bracket.** The first design
 * wrapped the root in a `drawWithContent` and timed composition and draw as separate phases. It
 * reported nothing at all, twice, and `FrameBudgetRootDrawTest` says why: across 12 rendered frames
 * that each recomposed and redrew 10 cards, the ROOT draw modifier ran exactly **once**. Compose
 * re-records only the draw nodes it invalidated, so a bracket at the top of the tree sees the first
 * frame and then nothing. Per-subtree probes are unaffected — the same test counted 120 card draws
 * for 120 card recompositions — so the attribution below is sound; only the frame-level accounting
 * had to move to the one callback that genuinely fires once per frame.
 */
internal object FrameBudget {

    /** Depth beyond this stops contributing child time; only affects self/inclusive splitting. */
    private const val MaxProbeDepth = 32
    private const val ReportIntervalNanos = 5_000_000_000L

    /** 0.25 ms buckets to 32 ms, then one overflow bucket. Enough resolution for an 8.3 ms budget. */
    private const val BucketNanos = 250_000L
    private const val BucketCount = 129

    private val threadMx: com.sun.management.ThreadMXBean? = runCatching {
        java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    }.getOrNull()

    private val cpuTimeSupported =
        threadMx?.isCurrentThreadCpuTimeSupported == true && threadMx.isThreadCpuTimeEnabled
    private val otherThreadCpuSupported =
        cpuTimeSupported && threadMx?.isThreadCpuTimeSupported == true
    private val allocationSupported = threadMx?.isThreadAllocatedMemorySupported == true

    private var frameThreadId = -1L
    private var drawThreadId = -1L
    private var threadMismatchReported = false

    /**
     * Every thread whose CPU counts toward a frame, and the reason this is not just the UI thread.
     *
     * Under OpenGL, skiko draws and presents on the AWT event thread, so `cpu` sampled there covered
     * the whole frame. Under Direct3D it does not: a profile (2026-09-07) found draw, flush and
     * present on a `skiko-dispatcher-to-block-on` thread instead, with 62% of that thread's samples
     * in `Direct3DContextHandler.flush`. Sampling only the frame thread therefore made D3D look 3-7x
     * cheaper than OpenGL when the work had merely moved, and comparing the two renderers on that
     * number was meaningless. Now both are charged, and reported separately so the split is visible
     * rather than assumed.
     */
    private var renderThreadIds = LongArray(0)
    private var renderThreadsResolvedNanos = 0L
    private var renderThreadNames = ""
    private var lastRenderCpuNanos = -1L
    private var renderCpuNanos = 0L

    private var context = "?"
    private var variant: String? = null
    private var scrolling = false
    private val scrollOwners = HashSet<String>()
    private var loggedTransitions = 0
    private var startupReported = false

    private var probeDepth = 0
    private val childNanos = LongArray(MaxProbeDepth)
    private val probes = LinkedHashMap<String, ProbeStat>()
    private val recompositions = LinkedHashMap<String, Int>()

    private var windowStartedNanos = 0L
    private var frames = 0
    private var lastFrameNanos = 0L
    private var lastCpuNanos = -1L
    private var lastAllocatedBytes = -1L
    private var periodNanos = 0L
    private var cpuNanos = 0L
    private var cpuFrames = 0
    private var allocatedBytes = 0L
    private var allocFrames = 0
    private var probedDrawNanos = 0L
    private var worstPeriodNanos = 0L
    private val periodHistogram = IntArray(BucketCount)

    private class ProbeStat {
        var draws = 0
        var selfNanos = 0L
        var inclusiveNanos = 0L
        var worstSelfNanos = 0L
    }

    /** Names the workload the next report describes, e.g. `home/AdaptiveAmbient`. */
    fun setContext(value: String) {
        if (context == value) return
        flush()
        context = value
    }

    /**
     * Appends an experiment name to the label, e.g. `home/AdaptiveAmbient[noBlur]`. Flushes first so
     * the window that was running under the previous variant is reported under that variant.
     */
    fun setVariant(value: String?) {
        if (variant == value) return
        flush()
        variant = value
        Logger.withTag("FrameBudget").i { "variant=" + (value ?: "full") }
    }

    /**
     * Gates reporting to frames the user is actually scrolling through. The final partial window is
     * flushed on the way out so a short flick still produces a line.
     *
     * Keyed by [owner] rather than a plain boolean because several screens share this one object:
     * Home, Search, Library and Discover are all the same composable with different rows, and each
     * live instance reports its own list's state. A bare boolean lets an idle instance's "false"
     * cancel the scrolling instance's "true" — silently, and for the whole session.
     */
    fun setScrolling(owner: String, value: Boolean) {
        val changed = if (value) scrollOwners.add(owner) else scrollOwners.remove(owner)
        if (!changed) return
        if (loggedTransitions < 20) {
            loggedTransitions++
            Logger.withTag("FrameBudget").i { "scroll $owner=$value active=${scrollOwners.size}" }
        }
        val nowScrolling = scrollOwners.isNotEmpty()
        if (scrolling == nowScrolling) return
        if (!nowScrolling) flush()
        scrolling = nowScrolling
        if (nowScrolling) resetWindow()
    }

    /**
     * One frame boundary. This is the only callback that reliably fires once per frame, so all
     * frame-level accounting hangs off it; the draw for frame N lands between this call and the
     * next, which is why probe totals are only ever reported as window averages.
     */
    fun onFrameClock(nanos: Long) {
        val now = System.nanoTime()
        if (frameThreadId == -1L) {
            frameThreadId = Thread.currentThread().id
            reportStartup(nanos, now)
        }
        // A probe whose drawContent() threw would leave the depth counter raised; the frame
        // boundary is the safe place to put it back, since no draw is in progress here.
        probeDepth = 0

        if (!scrolling) {
            lastFrameNanos = 0L
            lastCpuNanos = -1L
            lastRenderCpuNanos = -1L
            lastAllocatedBytes = -1L
            return
        }

        if (lastFrameNanos != 0L) {
            val period = now - lastFrameNanos
            frames++
            periodNanos += period
            if (period > worstPeriodNanos) worstPeriodNanos = period
            bump(periodHistogram, period)
        }
        lastFrameNanos = now

        if (cpuTimeSupported) {
            val cpu = runCatching { threadMx!!.currentThreadCpuTime }.getOrDefault(-1L)
            if (cpu >= 0) {
                // Windows reports thread CPU time in ~15.6 ms steps, so a single frame's value is
                // meaningless — only the sum across a whole reporting window is. That is why cpu is
                // published as a window mean and never as a percentile.
                if (lastCpuNanos >= 0 && cpu >= lastCpuNanos) {
                    cpuNanos += cpu - lastCpuNanos
                    cpuFrames++
                }
                lastCpuNanos = cpu
            }
        }
        if (otherThreadCpuSupported) {
            resolveRenderThreads(now)
            var total = 0L
            var ok = renderThreadIds.isNotEmpty()
            for (id in renderThreadIds) {
                val t = runCatching { threadMx!!.getThreadCpuTime(id) }.getOrDefault(-1L)
                // A dead thread reports -1; drop the whole sample rather than book a fake decrease.
                if (t < 0) { ok = false; break }
                total += t
            }
            if (ok) {
                if (lastRenderCpuNanos >= 0 && total >= lastRenderCpuNanos) {
                    renderCpuNanos += total - lastRenderCpuNanos
                }
                lastRenderCpuNanos = total
            } else {
                lastRenderCpuNanos = -1L
                renderThreadsResolvedNanos = 0L
            }
        }
        if (allocationSupported) {
            val allocated = runCatching { threadMx!!.currentThreadAllocatedBytes }.getOrDefault(-1L)
            if (allocated >= 0) {
                if (lastAllocatedBytes >= 0 && allocated >= lastAllocatedBytes) {
                    allocatedBytes += allocated - lastAllocatedBytes
                    allocFrames++
                }
                lastAllocatedBytes = allocated
            }
        }

        if (windowStartedNanos == 0L) windowStartedNanos = now
        if (now - windowStartedNanos >= ReportIntervalNanos) flush()
    }

    /**
     * Finds skiko's render threads by name. Re-scanned until one is found because the renderer may
     * not have started a thread yet at the first frame, then left alone: enumerating threads is far
     * too expensive to do per frame.
     */
    private fun resolveRenderThreads(now: Long) {
        if (renderThreadIds.isNotEmpty()) return
        if (renderThreadsResolvedNanos != 0L && now - renderThreadsResolvedNanos < 2_000_000_000L) return
        renderThreadsResolvedNanos = now
        val mx = threadMx ?: return
        val ids = runCatching {
            mx.getThreadInfo(mx.allThreadIds, 0)
                .filterNotNull()
                .filter { it.threadName.startsWith("skiko", ignoreCase = true) }
        }.getOrNull() ?: return
        if (ids.isEmpty()) return
        renderThreadIds = ids.map { it.threadId }.toLongArray()
        renderThreadNames = ids.joinToString(",") { it.threadName }
        Logger.withTag("FrameBudget").i {
            "render threads charged to cpu: $renderThreadNames"
        }
    }

    fun onRecompose(name: String) {
        if (!scrolling) return
        recompositions[name] = (recompositions[name] ?: 0) + 1
    }

    fun probeStart(): Int {
        val depth = probeDepth
        if (depth < MaxProbeDepth) childNanos[depth] = 0L
        probeDepth = depth + 1
        return depth
    }

    fun probeEnd(name: String, depth: Int, startNanos: Long) {
        probeDepth = depth
        if (!scrolling) return
        if (drawThreadId == -1L) drawThreadId = Thread.currentThread().id
        val inclusive = System.nanoTime() - startNanos
        val self = inclusive - if (depth < MaxProbeDepth) childNanos[depth] else 0L
        if (depth > 0 && depth - 1 < MaxProbeDepth) childNanos[depth - 1] += inclusive
        // Only outermost probes contribute to the drawn total; anything nested is already inside
        // one of them and would be counted twice.
        if (depth == 0) probedDrawNanos += inclusive
        val stat = probes.getOrPut(name) { ProbeStat() }
        stat.draws++
        stat.selfNanos += self
        stat.inclusiveNanos += inclusive
        if (self > stat.worstSelfNanos) stat.worstSelfNanos = self
    }

    internal fun debugProbeSnapshot(): Map<String, Triple<Int, Long, Long>> =
        probes.mapValues { (_, stat) -> Triple(stat.draws, stat.selfNanos, stat.inclusiveNanos) }

    internal fun debugRecompositionSnapshot(): Map<String, Int> = recompositions.toMap()

    internal fun debugFrameCount(): Int = frames

    internal fun debugReset() {
        scrolling = false
        scrollOwners.clear()
        loggedTransitions = 0
        frameThreadId = -1L
        drawThreadId = -1L
        probeDepth = 0
        startupReported = true
        resetWindow()
    }

    private fun reportStartup(clockNanos: Long, now: Long) {
        if (startupReported) return
        startupReported = true
        val metrics = com.nuvio.app.desktopDisplayMetrics()
        val refreshHz = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.displayMode.refreshRate
        }.getOrDefault(0)
        Logger.withTag("FrameBudget").i {
            "display=" + (metrics?.let { "${it.sizePx.width}x${it.sizePx.height}" } ?: "?") +
                " density=" + (metrics?.let { "%.2f".format(it.density) } ?: "?") +
                " refresh=" + (if (refreshHz > 0) "${refreshHz}Hz" else "?") +
                " bench=" + FrameBenchMode.enabled + " probes=" + FrameBenchMode.probesEnabled +
                " cpuTime=" + cpuTimeSupported + " alloc=" + allocationSupported +
                // Reported because the frame clock's timestamps are NOT System.nanoTime values —
                // measured at ~3.5e6 ms offset — so nobody tries to use them as durations again.
                " frameClockOffset=" + "%.0f".format((now - clockNanos) / 1_000_000.0) + "ms"
        }
    }

    private fun bump(histogram: IntArray, nanos: Long) {
        val bucket = (nanos / BucketNanos).toInt().coerceIn(0, BucketCount - 1)
        histogram[bucket]++
    }

    private fun percentileMs(histogram: IntArray, total: Int, fraction: Double): Double {
        if (total == 0) return 0.0
        var remaining = (total * fraction).toInt().coerceAtMost(total - 1)
        for (i in histogram.indices) {
            remaining -= histogram[i]
            if (remaining < 0) return (i + 1) * BucketNanos / 1_000_000.0
        }
        return BucketCount * BucketNanos / 1_000_000.0
    }

    private fun flush() {
        if (frames == 0) {
            resetWindow()
            return
        }
        val n = frames
        val meanPeriodMs = periodNanos / 1_000_000.0 / n
        val meanUiCpuMs = if (cpuFrames > 0) cpuNanos / 1_000_000.0 / cpuFrames else -1.0
        val meanRenderCpuMs = if (cpuFrames > 0) renderCpuNanos / 1_000_000.0 / cpuFrames else 0.0
        val meanCpuMs = if (meanUiCpuMs < 0) -1.0 else meanUiCpuMs + meanRenderCpuMs
        // The draw thread is only known once a probe has run, so this can only be checked here.
        if (!threadMismatchReported && drawThreadId != -1L && drawThreadId != frameThreadId) {
            threadMismatchReported = true
            Logger.withTag("FrameBudget").i {
                "draw runs on thread $drawThreadId but the frame clock on $frameThreadId — `cpu` " +
                    "covers the frame thread only, so draw CPU is missing from it"
            }
        }
        Logger.withTag("FrameBudget").i {
            (if (FrameBenchMode.enabled) "[bench] " else "") +
                label() + " frames=$n period=" + "%.2f".format(meanPeriodMs) + "ms" +
                " periodP50=" + "%.2f".format(percentileMs(periodHistogram, n, 0.50)) + "ms" +
                " periodP95=" + "%.2f".format(percentileMs(periodHistogram, n, 0.95)) + "ms" +
                " periodMax=" + "%.2f".format(worstPeriodNanos / 1_000_000.0) + "ms" +
                (
                    if (meanCpuMs >= 0) {
                        " cpu=" + "%.2f".format(meanCpuMs) + "ms" +
                            " (ui=" + "%.2f".format(meanUiCpuMs) +
                            " render=" + "%.2f".format(meanRenderCpuMs) + ")"
                    } else {
                        " cpu=n/a"
                    }
                    ) +
                " probedDraw=" + "%.2f".format(probedDrawNanos / 1_000_000.0 / n) + "ms" +
                (
                    if (allocFrames > 0) {
                        " alloc=" + "%.0f".format(allocatedBytes / 1024.0 / allocFrames) + "KB/f"
                    } else {
                        ""
                    }
                    )
        }
        if (probes.isNotEmpty()) {
            val ranked = probes.entries
                .sortedByDescending { it.value.selfNanos }
                .take(12)
                .joinToString(" ") { entry ->
                    val stat = entry.value
                    entry.key + "=" + "%.2f".format(stat.selfNanos / 1_000_000.0 / n) + "ms" +
                        "(x" + "%.1f".format(stat.draws.toDouble() / n) +
                        " incl=" + "%.2f".format(stat.inclusiveNanos / 1_000_000.0 / n) +
                        " worst=" + "%.2f".format(stat.worstSelfNanos / 1_000_000.0) + ")"
                }
            Logger.withTag("FrameBudget").i { label() + " self-draw/frame: $ranked" }
        }
        if (recompositions.isNotEmpty()) {
            val ranked = recompositions.entries
                .sortedByDescending { it.value }
                .take(12)
                .joinToString(" ") { entry ->
                    entry.key + "=" + "%.1f".format(entry.value.toDouble() / n)
                }
            Logger.withTag("FrameBudget").i { label() + " recompositions/frame: $ranked" }
        }
        resetWindow()
    }

    private fun label(): String = if (variant == null) context else "$context[$variant]"

    private fun resetWindow() {
        windowStartedNanos = 0L
        frames = 0
        lastFrameNanos = 0L
        lastCpuNanos = -1L
        lastRenderCpuNanos = -1L
        lastAllocatedBytes = -1L
        periodNanos = 0L
        cpuNanos = 0L
        renderCpuNanos = 0L
        cpuFrames = 0
        allocatedBytes = 0L
        allocFrames = 0
        probedDrawNanos = 0L
        worstPeriodNanos = 0L
        periodHistogram.fill(0)
        probes.clear()
        recompositions.clear()
    }
}

/**
 * Attributes draw time to [name]. Reports both self time (this subtree minus nested probes) and
 * inclusive time, because the two answer different questions: a row whose self time is trivial but
 * whose inclusive time is large is paying for its cards, not for itself.
 */
internal actual fun Modifier.frameBudgetProbe(name: String): Modifier =
    if (!FrameBenchMode.probesEnabled) this else drawWithContent {
        val depth = FrameBudget.probeStart()
        val start = System.nanoTime()
        drawContent()
        FrameBudget.probeEnd(name, depth, start)
    }

internal actual fun frameBudgetRecompose(name: String) {
    if (FrameBenchMode.probesEnabled) FrameBudget.onRecompose(name)
}

internal actual fun frameBudgetProbesEnabled(): Boolean = FrameBenchMode.probesEnabled

internal actual fun frameBudgetSetProbesEnabled(enabled: Boolean) =
    FrameBenchMode.applyProbesEnabled(enabled)

internal actual fun frameBudgetSetVariant(value: String?) = FrameBudget.setVariant(value)

internal actual fun frameBudgetSetContext(value: String) = FrameBudget.setContext(value)

internal actual fun frameBudgetSetScrolling(owner: String, value: Boolean) =
    FrameBudget.setScrolling(owner, value)

/** Drives every frame boundary in [FrameBudget]. Pair with [FrameCadenceTelemetry]. */
@Composable
internal fun FrameBudgetTelemetry() {
    // Gated, not merely toggleable: an always-on frame-clock awaiter costs 25% of the GPU on an
    // idle window. See FrameClockProbes.
    val enabled = FrameBenchMode.probesEnabled && FrameClockProbes.enabled
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            withFrameNanos { FrameBudget.onFrameClock(it) }
        }
    }
}
