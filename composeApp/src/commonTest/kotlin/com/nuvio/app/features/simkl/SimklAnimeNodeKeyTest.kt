package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * `/sync/all-items` puts anime under a `show` key inside its `anime` array — never under `anime`.
 *
 * The payload below is the real shape, taken verbatim (ids and all) from a live account on
 * 2026-09-09. Reading `entry.anime` for these entries returned null and discarded every one of
 * them, so imported watched history contained no anime at all: an account with ~14k watched rows
 * had zero. Parsing the wire format is the only thing that catches this — hand-built
 * `SimklAllItemsEntry(anime = ...)` fixtures set the key the API never sends and pass either way.
 */
class SimklAnimeNodeKeyTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val liveAnimePayload = """
        {
          "shows": [],
          "movies": [],
          "anime": [
            {
              "last_watched_at": "2026-09-09T22:16:13Z",
              "last_watched": "E2",
              "status": "watching",
              "watched_episodes_count": 2,
              "total_episodes_count": 12,
              "show": {
                "title": "Super no Ura de Yani Suu Futari",
                "year": 2026,
                "ids": {
                  "simkl": 2838478,
                  "mal": "62076",
                  "kitsu": "50040",
                  "imdb": "tt37614297",
                  "tmdb": "296286",
                  "tvdb": 465973
                }
              },
              "anime_type": "tv",
              "seasons": [
                {
                  "number": 1,
                  "episodes": [
                    { "number": 1, "watched_at": "2026-09-09T21:31:56Z" },
                    { "number": 2, "watched_at": "2026-09-09T22:16:13Z" }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an anime array entry exposes its media through showMedia`() {
        val parsed = json.decodeFromString<SimklAllItemsResponse>(liveAnimePayload)
        val entry = parsed.anime.single()

        // The shape this whole bug rests on: the `anime` key is absent on the wire.
        assertEquals(null, entry.anime)
        assertNotNull(entry.show)
        assertEquals("Super no Ura de Yani Suu Futari", entry.showMedia?.title)
        assertEquals("50040", entry.showMedia?.ids?.kitsu)
    }

    @Test
    fun `anime episodes reach imported watched history`() {
        val parsed = json.decodeFromString<SimklAllItemsResponse>(liveAnimePayload)

        val watched = parsed.toWatchedItems()

        assertEquals(2, watched.size, "both watched episodes should import; this returned zero before")
        assertTrue(watched.all { it.type == "series" })
        assertEquals(listOf(1, 2), watched.mapNotNull { it.episode }.sorted())
        assertTrue(watched.all { it.id.isNotBlank() })
    }

    @Test
    fun `a season-less anime entry is still offered for episode backfill`() {
        // The shape SIMKL sends once a show leaves "watching": a bare count and no seasons at all.
        val seasonLess = """
            {
              "shows": [],
              "movies": [],
              "anime": [
                {
                  "last_watched_at": "2026-09-09T22:16:13Z",
                  "last_watched": "E2",
                  "status": "completed",
                  "watched_episodes_count": 2,
                  "total_episodes_count": 12,
                  "show": {
                    "title": "Super no Ura de Yani Suu Futari",
                    "ids": { "simkl": 2838478, "kitsu": "50040", "imdb": "tt37614297" }
                  },
                  "anime_type": "tv"
                }
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString<SimklAllItemsResponse>(seasonLess)
        assertTrue(parsed.anime.single().seasons.isEmpty())

        val targets = parsed.episodeBackfillTargets()

        assertEquals(1, targets.size)
        assertEquals(2838478, targets.single().simklId)
        assertTrue(targets.single().isAnime)
    }
}
