package com.nuvio.app.features.plugins

/**
 * Identity of the *site* a scraper targets, independent of which repository ships it.
 *
 * Repositories copy scrapers from each other file-for-file, so the script filename is the most
 * stable handle (`moviesdrive.js` in five repositories is one site); the display name is the
 * fallback when a manifest omits it. Only letters and digits survive so `Movies-Drive`,
 * `movies_drive` and `MoviesDrive` collapse together — while a real variant such as
 * `vidnest-anime` keeps its suffix and stays distinct from `vidnest`.
 */
internal val PluginScraper.duplicateKey: String
    get() {
        val base = filename.substringBeforeLast('.').ifBlank { name }
        return base.lowercase().filter { it.isLetterOrDigit() }.ifBlank { id.lowercase() }
    }

/**
 * Keeps the first scraper for each [duplicateKey] in list order (the order the repositories were
 * installed in, so the user's earlier choice wins) and returns the copies that were dropped.
 */
internal fun dedupeScrapersAcrossRepositories(
    scrapers: List<PluginScraper>,
): Pair<List<PluginScraper>, List<PluginScraper>> {
    val seen = HashSet<String>()
    val kept = ArrayList<PluginScraper>(scrapers.size)
    val skipped = ArrayList<PluginScraper>()
    for (scraper in scrapers) {
        if (seen.add(scraper.duplicateKey)) kept += scraper else skipped += scraper
    }
    return kept to skipped
}
