package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroDiscoveryBadgeTargetTest {
    private fun fact(category: String, label: String = category, companyTmdbId: Int? = null, directorName: String? = null) =
        HeroDiscoveryFact(label = label, type = "info", category = category, companyTmdbId = companyTmdbId, directorName = directorName)

    @Test
    fun `award categories map to their list and win state`() {
        assertEquals(
            HeroDiscoveryBadgeTarget.AwardList(HeroAward.BEST_PICTURE, won = true),
            fact("award:best_picture").browseTarget(),
        )
        assertEquals(
            HeroDiscoveryBadgeTarget.AwardList(HeroAward.BEST_PICTURE, won = false),
            fact("award:best_picture_nom").browseTarget(),
        )
        assertEquals(HeroDiscoveryBadgeTarget.AwardList(HeroAward.GOLDEN_GLOBE, won = true), fact("award:globe_win").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.AwardList(HeroAward.GOLDEN_GLOBE, won = false), fact("award:globe_nom").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.AwardList(HeroAward.EMMY, won = true), fact("award:emmy_win").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.AwardList(HeroAward.EMMY, won = false), fact("award:emmy_nom").browseTarget())
    }

    @Test
    fun `keyword badges carry TMDB keyword ids and the stinger lists both keywords movie-only`() {
        val stinger = fact("stinger", label = "Mid-Credits Scene").browseTarget() as HeroDiscoveryBadgeTarget.Keyword
        assertEquals(listOf(179430, 179431), stinger.keywordIds)
        assertTrue(stinger.movieOnly)
        assertEquals(HeroDiscoveryBadgeTarget.Keyword(listOf(374649), movieOnly = false), fact("cult").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.Keyword(listOf(9672), movieOnly = false), fact("true_story").browseTarget())
    }

    @Test
    fun `context badges resolve by category and release status by label`() {
        assertEquals(HeroDiscoveryBadgeTarget.Language("ja"), fact("foreign:ja", label = "Japanese Film").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.Trending, fact("trending").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.NewAtHome, fact("new_release", label = "New").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.NewAtHome, fact("digital_release", label = "New").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.ShortFilm, fact("short_film").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.NowPlaying, fact("release_status", label = "Cinema").browseTarget())
        assertEquals(HeroDiscoveryBadgeTarget.Upcoming, fact("release_status", label = "Production").browseTarget())
        assertNull(fact("release_status", label = "Streaming").browseTarget())
    }

    @Test
    fun `studio and director badges need the id or name the fact carries`() {
        assertEquals(
            HeroDiscoveryBadgeTarget.Company(174, "Warner Bros."),
            fact("studio", label = "Warner Bros.", companyTmdbId = 174).browseTarget(),
        )
        assertNull(fact("studio", label = "Warner Bros.").browseTarget())
        assertEquals(
            HeroDiscoveryBadgeTarget.Director("Denis Villeneuve"),
            fact("director", label = "Directed by Denis Villeneuve", directorName = "Denis Villeneuve").browseTarget(),
        )
        assertNull(fact("director", label = "Directed by Denis Villeneuve").browseTarget())
    }

    @Test
    fun `badges without a source stay inert`() {
        for (category in listOf(
            "award:festival", "award:palme", "award:golden_lion", "award:golden_bear", "award:people_choice",
            "metacritic", "mini_series", "binge_ready", "noms", "foreign:",
        )) {
            assertNull(fact(category).browseTarget(), category)
        }
    }

    @Test
    fun `routable targets survive an encode decode round trip`() {
        val targets = listOf(
            HeroDiscoveryBadgeTarget.AwardList(HeroAward.GOLDEN_GLOBE, won = false),
            HeroDiscoveryBadgeTarget.Keyword(listOf(179430, 179431), movieOnly = true),
            HeroDiscoveryBadgeTarget.Keyword(listOf(9672), movieOnly = false),
            HeroDiscoveryBadgeTarget.Language("ko"),
            HeroDiscoveryBadgeTarget.Trending,
            HeroDiscoveryBadgeTarget.NewAtHome,
            HeroDiscoveryBadgeTarget.NowPlaying,
            HeroDiscoveryBadgeTarget.Upcoming,
            HeroDiscoveryBadgeTarget.ShortFilm,
        )
        for (target in targets) {
            val (kind, value) = target.encodeForRoute() ?: error("$target should encode")
            assertEquals(target, decodeHeroDiscoveryBadgeTarget(kind, value), target.toString())
        }
        assertNull(HeroDiscoveryBadgeTarget.Company(1, "x").encodeForRoute())
        assertNull(HeroDiscoveryBadgeTarget.Director("x").encodeForRoute())
        assertNull(decodeHeroDiscoveryBadgeTarget("award", "best_picture:maybe"))
        assertNull(decodeHeroDiscoveryBadgeTarget("keyword", "abc"))
        assertNull(decodeHeroDiscoveryBadgeTarget("nope", ""))
    }

    @Test
    fun `best picture nominees never include a winner and browse lists are newest first`() {
        val winners = HeroDiscoveryAwards.BEST_PICTURE_WINNER_TMDB_IDS
        val nominees = HeroDiscoveryAwards.BEST_PICTURE_NOM_TMDB_IDS
        assertTrue(winners.intersect(nominees).isEmpty(), "winners leaked into the nominee set: ${winners.intersect(nominees)}")
        assertEquals(1054867, HeroDiscoveryAwards.browseIds(HeroAward.BEST_PICTURE, won = true, tv = false).first())
        assertTrue(HeroDiscoveryAwards.browseIds(HeroAward.BEST_PICTURE, won = true, tv = true).isEmpty())
        assertTrue(HeroDiscoveryAwards.browseIds(HeroAward.EMMY, won = true, tv = false).isEmpty())
    }

    @Test
    fun `globe browse interleaves the category sets so the rail stays roughly chronological`() {
        val ids = HeroDiscoveryAwards.browseIds(HeroAward.GOLDEN_GLOBE, won = true, tv = false)
        val drama = HeroDiscoveryAwards.GOLDEN_GLOBE_DRAMA_WINNER_TMDB_IDS.toList()
        val comedy = HeroDiscoveryAwards.GOLDEN_GLOBE_COMEDY_WINNER_TMDB_IDS.toList()
        assertEquals(drama[0], ids[0])
        assertEquals(comedy[0], ids[1])
        assertEquals(drama[1], ids[2])
        assertEquals((drama + comedy).toSet(), ids.toSet())
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `browse titles rename the status badges only`() {
        assertEquals("New at Home", HeroDiscoveryBadgeTarget.NewAtHome.browseTitle("New Release"))
        assertEquals("In Cinemas", HeroDiscoveryBadgeTarget.NowPlaying.browseTitle("Cinema"))
        assertEquals("Coming Soon", HeroDiscoveryBadgeTarget.Upcoming.browseTitle("Production"))
        assertEquals("Post-Credits Scenes", HeroDiscoveryBadgeTarget.Keyword(listOf(1, 2), true).browseTitle("Mid-Credits Scene"))
        assertEquals("Best Picture", HeroDiscoveryBadgeTarget.AwardList(HeroAward.BEST_PICTURE, true).browseTitle("Best Picture"))
    }
}
