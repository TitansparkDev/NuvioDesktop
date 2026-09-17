package com.nuvio.app.core.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decoding an animation is expensive enough that doing it twice matters, and the old code did it
 * dozens of times: the decoded-frame cache is only written when a decode FINISHES, so every request
 * arriving while one was running missed and started its own. Measured over three minutes of
 * scrolling animated rows: **2,261 decodes** of three distinct source sizes, mean 2,286 ms — about
 * 5,170 seconds of work inside a 180-second window, and the frame rate fell from 117 to 20.
 *
 * The contract is not only "decode once". It is also **who owns the codec**, and that killed the JVM
 * twice before these tests existed: an earlier version decided whether a caller had won by setting a
 * flag inside the work lambda, which is queued and may not run for seconds, while a caller whose card
 * scrolled offscreen is cancelled immediately. It concluded it had lost, closed the codec, and the
 * queued decode then read freed memory.
 *
 * Real dispatchers, not `runTest`: the callers genuinely have to overlap, and a virtual scheduler
 * plus a blocking wait simply deadlocks.
 */
class AnimatedDecodeSingleFlightTest {

    private val flights = SingleFlight<String>()

    private fun spinUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(1)
        }
        return condition()
    }

    @Test
    fun `concurrent callers decode once and the rest are told they joined`() =
        runBlocking(Dispatchers.Default) {
            val runs = AtomicInteger()
            val joined = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val callers = 24

            val waiters = (1..callers).map {
                async {
                    flights.run(
                        key = "poster.gif",
                        work = {
                            runs.incrementAndGet()
                            // Held open so every caller is in flight before the first finishes.
                            spinUntil { gate.isCompleted }
                            "frames"
                        },
                        onJoined = { joined.incrementAndGet() },
                    )
                }
            }
            spinUntil { runs.get() == 1 && joined.get() == callers - 1 }
            gate.complete(Unit)

            val results = waiters.awaitAll()
            assertEquals(1, runs.get(), "one key must decode once, not once per caller")
            assertEquals(callers - 1, joined.get(), "every caller but the starter must be told")
            assertTrue(results.all { it == "frames" }, "every caller must receive the result")
        }

    @Test
    fun `different keys decode independently`() = runBlocking(Dispatchers.Default) {
        val runs = AtomicInteger()
        val results = (1..4).map { index ->
            async {
                flights.run("poster-$index.gif", work = { runs.incrementAndGet(); "frames-$index" })
            }
        }.awaitAll()
        assertEquals(4, runs.get())
        assertEquals(listOf("frames-1", "frames-2", "frames-3", "frames-4"), results)
    }

    /**
     * The crash, as a test. A starter that is cancelled must not be treated as a joiner: its codec
     * is the one the queued decode is about to use.
     */
    @Test
    fun `a cancelled starter never reports joining, and its work still completes`() =
        runBlocking(Dispatchers.Default) {
            val runs = AtomicInteger()
            val joined = AtomicInteger()
            val workStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val starter = launch {
                flights.run(
                    key = "cancelled.gif",
                    work = {
                        runs.incrementAndGet()
                        workStarted.complete(Unit)
                        spinUntil { release.isCompleted }
                        "frames"
                    },
                    onJoined = { joined.incrementAndGet() },
                )
            }
            workStarted.await()
            starter.cancel()
            starter.join()

            assertEquals(
                0,
                joined.get(),
                "the starter owns the codec and must never be told it joined",
            )
            release.complete(Unit)
            // The work outlives the caller: it runs in the coordinator's own scope, so a card
            // scrolling offscreen cannot abandon a decode everything else is waiting on.
            assertTrue(spinUntil { runs.get() == 1 && flights.activeFlights() == 0 })
        }

    @Test
    fun `flights are retired so a later decode of the same key can run`() =
        runBlocking(Dispatchers.Default) {
            val runs = AtomicInteger()
            flights.run("repeat.gif", work = { runs.incrementAndGet(); "frames" })
            // Retirement happens on completion, so it may trail the caller by a moment; what must
            // not happen is an entry lingering forever, holding decoded frames the cache can no
            // longer release.
            assertTrue(spinUntil { flights.activeFlights() == 0 }, "flights must drain")
            flights.run("repeat.gif", work = { runs.incrementAndGet(); "frames" })
            assertEquals(2, runs.get(), "a retired key must be able to decode again")
        }
}
