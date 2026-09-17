package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginScraperDedupeTest {

    private fun scraper(repo: String, filename: String, name: String = filename.substringBeforeLast('.')) = PluginScraper(
        id = "https://$repo/manifest.json:${filename.substringBeforeLast('.')}",
        repositoryUrl = "https://$repo/manifest.json",
        name = name,
        description = "",
        version = "1.0.0",
        filename = filename,
        supportedTypes = listOf("movie"),
        enabled = true,
        manifestEnabled = true,
        code = "",
    )

    @Test
    fun `same script in several repositories runs once, first repository wins`() {
        val scrapers = listOf(
            scraper("repo-a", "moviesdrive.js"),
            scraper("repo-b", "MoviesDrive.js", name = "Movies Drive"),
            scraper("repo-c", "movies-drive.js"),
            scraper("repo-b", "uhdmovies.js"),
        )
        val (kept, skipped) = dedupeScrapersAcrossRepositories(scrapers)
        assertEquals(listOf("https://repo-a/manifest.json", "https://repo-b/manifest.json"), kept.map { it.repositoryUrl })
        assertEquals(listOf("moviesdrive", "uhdmovies"), kept.map { it.duplicateKey })
        assertEquals(2, skipped.size)
    }

    @Test
    fun `a suffixed variant is a different site`() {
        val (kept, skipped) = dedupeScrapersAcrossRepositories(
            listOf(scraper("repo-a", "vidnest.js"), scraper("repo-a", "vidnest-anime.js")),
        )
        assertEquals(2, kept.size)
        assertEquals(0, skipped.size)
    }

    @Test
    fun `name is the fallback when the manifest has no filename`() {
        assertEquals("castle", scraper("repo-a", "", name = "Castle").duplicateKey)
        // Nothing usable at all: fall back to the id so unrelated scrapers never merge.
        val anonymous = scraper("repo-a", "", name = "")
        assertEquals(anonymous.id.lowercase(), anonymous.duplicateKey)
    }
}
