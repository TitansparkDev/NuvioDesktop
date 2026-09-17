package com.nuvio.app.features.simkl

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** `next_watch_info=yes` attaches the next unwatched episode, air date included, to each show. */
class SimklNextToWatchInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `next_to_watch_info is read from an all-items entry`() {
        val entry = json.decodeFromString<SimklAllItemsEntry>(
            """
            {
              "last_watched_at": "2025-12-25T21:00:00Z",
              "last_watched": "S06E07",
              "next_to_watch": "S07E01",
              "status": "watching",
              "not_aired_episodes_count": 6,
              "show": { "title": "All Creatures Great & Small", "ids": { "simkl": 1, "imdb": "tt10590066" } },
              "next_to_watch_info": { "title": "Premiere", "season": 7, "episode": 1, "date": "2026-09-17T21:00:00+01:00" }
            }
            """.trimIndent(),
        )

        assertEquals(7, entry.nextToWatchInfo?.season)
        assertEquals(1, entry.nextToWatchInfo?.episode)
        assertEquals("2026-09-17T21:00:00+01:00", entry.nextToWatchInfo?.date)
    }

    @Test
    fun `the air date honours SIMKL's offset`() {
        // 2026-09-17T21:00:00+01:00 is 20:00 UTC. The lenient timestamp parser would have read
        // 21:00 UTC — an hour is harmless, but a -05:00 offset moves a midnight premiere a day.
        assertEquals(1_789_675_200_000L, parseSimklAirDate("2026-09-17T21:00:00+01:00"))
        assertEquals(1_789_678_800_000L, parseSimklAirDate("2026-09-17T21:00:00Z"))
    }

    @Test
    fun `an entry without the field decodes with no date`() {
        val entry = json.decodeFromString<SimklAllItemsEntry>("""{"last_watched":"S01E01","show":{"title":"x","ids":{"simkl":1}}}""")
        assertEquals(null, entry.nextToWatchInfo)
    }
}
