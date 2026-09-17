package com.nuvio.app.features.plugins

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards against a scraper's result being dropped under concurrency.
 *
 * `overlapping scraper runtimes all complete` fails intermittently with
 * `NoSuchElementException: List is empty` — one scraper of forty returning nothing. **This test does
 * not reproduce it**: 400 runs across two shapes, including the CryptoJS + cheerio workload of the
 * failing test, all clean. Both real failures happened during a full-suite run, so it appears to
 * need the contention of other tests running alongside, which matches the runtime's own note that
 * the pre-confinement version dropped "roughly 1% of runs at 12-way concurrency".
 *
 * Kept anyway: it is cheap, and it would catch a regression that makes dropping common rather than
 * rare. It asserts on the count of empty results instead of `single()`, so a failure says how many
 * of how many were lost instead of just throwing.
 */
class PluginRuntimeStressTest {

    @Test
    fun `repeated concurrent batches never drop a result`() = runBlocking {
        val batches = 4
        val scraperCount = 20
        var emptyResults = 0
        var totalRuns = 0

        repeat(batches) { batch ->
            val results = (0 until scraperCount).map { index ->
                async {
                    PluginRuntime.executePlugin(
                        code = """
                            module.exports.getStreams = async function() {
                                var digest = CryptoJS.SHA256("scraper-$index").toString();
                                var ${'$'} = cheerio.load("<div class='q'>1080p</div>");
                                return [{
                                    title: "scraper-$index",
                                    url: "https://example.test/$index.mp4?d=" + digest,
                                    quality: ${'$'}(".q").text()
                                }];
                            };
                        """.trimIndent(),
                        tmdbId = "603",
                        mediaType = "movie",
                        season = null,
                        episode = null,
                        scraperId = "stress-$batch-$index",
                    )
                }
            }.awaitAll()
            totalRuns += results.size
            emptyResults += results.count { it.isEmpty() }
        }

        assertEquals(
            0,
            emptyResults,
            "dropped $emptyResults of $totalRuns results across $batches batches",
        )
    }
}
