package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the matcher paths that don't depend on anime-list-mini.json being present: an item
 * with no native kitsu/mal id never consults the mapping, so these results are deterministic.
 */
class LocalAnimeEpisodeMatcherTest {

    private val s1e1 = LocalMediaFile(path = "/anime/SAO/SAO S01E01.mkv", season = 1, episode = 1)
    private val s2e1 = LocalMediaFile(path = "/anime/SAO/SAO S02E01.mkv", season = 2, episode = 1)
    private val abs12 = LocalMediaFile(path = "/anime/Frieren/Frieren - 12.mkv", season = null, episode = 12)

    private val item = LocalMediaItem(
        key = "folder:sao",
        folderId = "folder",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Sword Art Online",
        imdbId = "tt2250192",
        tmdbId = 45782,
        files = listOf(s1e1, s2e1, abs12),
    )

    @Test
    fun foreignVideoIdMatchesNothing() {
        // Another show's episode id (e.g. reaching us through a stale meta) must not match,
        // even though its season/episode coordinates line up with a file on disk.
        assertTrue(LocalAnimeEpisodeMatcher.matchFiles(item, "tt0108778:1:1")!!.isEmpty())
    }

    @Test
    fun seasonedFilesMatchFranchiseCoordinates() {
        assertEquals(listOf(s1e1), LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:1:1"))
        assertEquals(listOf(s2e1), LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:2:1"))
    }

    @Test
    fun missingEpisodeMatchesNothing() {
        // S03E01 is not on disk — must return an empty list, not a wrong-season file.
        assertTrue(LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:3:1")!!.isEmpty())
    }

    @Test
    fun absoluteFileMatchesSeasonOneCoordinatesWhenUnmapped() {
        // With no mapping entry the item is treated as a plain season-1 entry.
        assertEquals(listOf(abs12), LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:1:12"))
    }

    @Test
    fun idWithoutEpisodeCoordinatesIsUnparsed() {
        assertNull(LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192"))
    }

    @Test
    fun tmdbStyleFourPartIdsParse() {
        assertEquals(listOf(s2e1), LocalAnimeEpisodeMatcher.matchFiles(item, "tmdb:45782:2:1"))
    }

    @Test
    fun `a later season is not claimed by earlier season folders`() {
        // Real anime-list entries for one franchise (seasons 1, 2 and 3), since the check depends
        // on the mapping. Each season sits in its own folder.
        val file = LocalMediaFile(path = "/anime/Show/Show - 01.mkv", episode = 1)
        val seasonOne = splitSeasonItem(kitsuId = 42323, malId = 39535, file = file)
        val seasonTwo = splitSeasonItem(kitsuId = 45950, malId = 51179, file = file)
        val seasonThree = splitSeasonItem(kitsuId = 49002, malId = 59193, file = file)

        // Entry-relative, PVR-shaped and franchise ids for the same season 3 episode.
        for (request in listOf("kitsu:49002:12", "kitsu:49002:1:12", "tt13293588:3:12")) {
            assertFalse(LocalAnimeEpisodeMatcher.coversSeasonOf(seasonOne, request), request)
            assertFalse(LocalAnimeEpisodeMatcher.coversSeasonOf(seasonTwo, request), request)
            assertTrue(LocalAnimeEpisodeMatcher.coversSeasonOf(seasonThree, request), request)
        }
    }

    @Test
    fun `split cour halves are separate titles`() {
        // Real entries for a season 2 split into two cours, which TMDB also files under season 1.
        val file = LocalMediaFile(path = "/anime/Show/Show - 01.mkv", episode = 1)
        val seasonOne = splitSeasonItem(kitsuId = 11209, malId = 31240, file = file)
        val firstHalf = splitSeasonItem(kitsuId = 42198, malId = 39587, file = file)
        val secondHalf = splitSeasonItem(kitsuId = 43247, malId = 42203, file = file)

        assertFalse(LocalAnimeEpisodeMatcher.coversSeasonOf(seasonOne, "kitsu:42198:5"))
        assertFalse(LocalAnimeEpisodeMatcher.coversSeasonOf(firstHalf, "kitsu:43247:3"))
        assertFalse(LocalAnimeEpisodeMatcher.coversSeasonOf(secondHalf, "kitsu:42198:5"))
        assertTrue(LocalAnimeEpisodeMatcher.coversSeasonOf(secondHalf, "kitsu:43247:3"))
    }

    @Test
    fun `whole-show season subfolder layout takes every season`() {
        // `Show/Season 01/…`, matched to the first season's entry, with only Season 1 on disk so far.
        val showLayout = splitSeasonItem(
            kitsuId = 42323,
            malId = 39535,
            file = LocalMediaFile(path = "D:\\Anime\\Show\\Season 01\\Show - S01E01.mkv", season = 1, episode = 1),
        )
        assertTrue(LocalAnimeEpisodeMatcher.coversSeasonOf(showLayout, "kitsu:49002:12"))
    }

    @Test
    fun undecidableFoldersAreKept() {
        // No mapping entry for the folder, no episode coordinates, or an entry the mapping lacks.
        assertTrue(LocalAnimeEpisodeMatcher.coversSeasonOf(item, "kitsu:49002:12"))
        val mapped = splitSeasonItem(kitsuId = 42323, malId = 39535, file = s1e1)
        assertTrue(LocalAnimeEpisodeMatcher.coversSeasonOf(mapped, "kitsu:49002"))
        assertTrue(LocalAnimeEpisodeMatcher.coversSeasonOf(mapped, "kitsu:999999999:1"))
        assertTrue(LocalAnimeEpisodeMatcher.coversSeasonOf(mapped.copy(isAnime = false), "kitsu:49002:12"))
    }

    @Test
    fun myAnimeListAliasMatchesAbsoluteFile() {
        val malItem = item.copy(malId = 123, files = listOf(abs12))

        assertEquals(
            listOf(abs12),
            LocalAnimeEpisodeMatcher.matchFiles(malItem, "myanimelist:123:12"),
        )
    }

    @Test
    fun `pokemon sibling kitsu entries do not share absolute local files`() {
        val xyFile = LocalMediaFile(path = "/anime/Pokemon XY - 12.mkv", episode = 12)
        val xyzFile = LocalMediaFile(path = "/anime/Pokemon XYZ - 12.mkv", episode = 12)
        val xy = pokemonItem(kitsuId = 7850, malId = 19291, file = xyFile)
        val xyz = pokemonItem(kitsuId = 11367, malId = 31592, file = xyzFile)

        assertTrue(LocalAnimeEpisodeMatcher.matchFiles(xy, "kitsu:11367:12").orEmpty().isEmpty())
        assertEquals(listOf(xyzFile), LocalAnimeEpisodeMatcher.matchFiles(xyz, "kitsu:11367:12"))
        assertTrue(LocalAnimeEpisodeMatcher.matchFiles(xyz, "kitsu:7850:12").orEmpty().isEmpty())
        assertEquals(listOf(xyFile), LocalAnimeEpisodeMatcher.matchFiles(xy, "kitsu:7850:12"))
    }

    @Test
    fun `internal mapping overrides franchise-looking source coordinates`() {
        val mapped = LocalMediaFile(
            path = "/anime/Pokemon XYZ S17E12.mkv",
            season = 17,
            episode = 12,
            mappedEpisode = 13,
        )
        val xyz = pokemonItem(kitsuId = 11367, malId = 31592, file = mapped)

        assertTrue(LocalAnimeEpisodeMatcher.matchFiles(xyz, "kitsu:11367:12").orEmpty().isEmpty())
        assertEquals(listOf(mapped), LocalAnimeEpisodeMatcher.matchFiles(xyz, "kitsu:11367:13"))
    }

    private fun splitSeasonItem(
        kitsuId: Int,
        malId: Int,
        file: LocalMediaFile,
    ) = LocalMediaItem(
        key = "folder:show-$kitsuId",
        folderId = "folder",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Some Show",
        imdbId = "tt13293588",
        kitsuId = kitsuId,
        malId = malId,
        files = listOf(file),
    )

    private fun pokemonItem(
        kitsuId: Int,
        malId: Int,
        file: LocalMediaFile,
    ) = LocalMediaItem(
        key = "folder:pokemon-$kitsuId",
        folderId = "folder",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Pokemon",
        imdbId = "tt0168366",
        tmdbId = 60572,
        kitsuId = kitsuId,
        malId = malId,
        files = listOf(file),
    )
}
