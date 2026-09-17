package com.nuvio.app.core.ui

/**
 * Samples the UI thread's stack while the launch overlay is stalled, and reports where it was.
 *
 * A JFR profile cannot answer "what was the UI thread doing for that second": its sampler visits a
 * handful of threads per tick, and this app idles ~40 threads in native socket reads, so a UI
 * thread grinding through native class definition or a static initialiser gets two samples in a
 * thousand milliseconds. Asking the one thread directly does not have that problem.
 *
 * Runs only between [markFrame] calls that are further apart than [stallThresholdMs] — a frame
 * clock that is keeping up never gets sampled — and only for the overlay's lifetime.
 * `Thread.getStackTrace` on another thread costs a handshake (~0.1ms); at 40 a second during a
 * stall that is nothing next to the stall.
 */
internal class LaunchStallSampler(
    private val stallThresholdMs: Long = 150,
    private val periodMs: Long = 25,
    private val framesPerKey: Int = 7,
    private val appFramesPerKey: Int = 3,
) {
    @Volatile
    private var uiThread: Thread? = null

    @Volatile
    private var lastFrameNanos = System.nanoTime()

    @Volatile
    private var running = true

    private val byStack = HashMap<String, Int>()
    private val byAppFrame = HashMap<String, Int>()
    private var samples = 0

    init {
        Thread({ loop() }, "nuvio-launch-stall-sampler").apply { isDaemon = true }.start()
    }

    /** Call from the frame callback: names the UI thread and resets the stall clock. */
    fun markFrame() {
        uiThread = Thread.currentThread()
        lastFrameNanos = System.nanoTime()
    }

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(periodMs)
            } catch (_: InterruptedException) {
                return
            }
            val thread = uiThread ?: continue
            if ((System.nanoTime() - lastFrameNanos) / 1_000_000 < stallThresholdMs) continue
            val stack = runCatching { thread.stackTrace }.getOrNull() ?: continue
            if (stack.isEmpty()) continue
            val key = stack.take(framesPerKey).joinToString(" < ") { it.shortName() }
            // The first frames of our own code explain a stall better than the JDK leaf does. More
            // than one, because the first alone can be a shared helper (`DesktopStorage$Store
            // .ensureLoaded` said "a store file open" without naming which repository opened it).
            val app = stack.asSequence()
                .filter { it.className.startsWith("com.nuvio.") }
                .take(appFramesPerKey)
                .joinToString(" < ") { it.shortName() }
                .ifEmpty {
                    stack.firstOrNull { it.className.startsWith("androidx.compose.") }?.shortName()
                        ?: stack.first().shortName()
                }
            synchronized(this) {
                samples++
                byStack[key] = (byStack[key] ?: 0) + 1
                byAppFrame[app] = (byAppFrame[app] ?: 0) + 1
            }
        }
    }

    /** Stops sampling and returns the report lines (empty when nothing stalled). */
    fun stopAndReport(): List<String> {
        running = false
        synchronized(this) {
            if (samples == 0) return emptyList()
            val lines = ArrayList<String>()
            lines += "stall samples=$samples (every ${periodMs}ms once a gap passes ${stallThresholdMs}ms)"
            lines += "by first app frames:"
            byAppFrame.entries.sortedByDescending { it.value }.take(10).forEach { (frame, n) ->
                lines += "  %3d %s".format(n, frame)
            }
            lines += "by stack top:"
            byStack.entries.sortedByDescending { it.value }.take(8).forEach { (stack, n) ->
                lines += "  %3d %s".format(n, stack)
            }
            return lines
        }
    }

    private fun StackTraceElement.shortName(): String =
        className.substringAfterLast('.') + "." + methodName
}
