package com.nuvio.app.features.plugins

import kotlinx.coroutines.flow.StateFlow

expect object PluginRepository {
    val uiState: StateFlow<PluginsUiState>

    fun initialize()

    fun onProfileChanged(profileId: Int)

    fun clearLocalState()

    suspend fun pullFromServer(profileId: Int)

    suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult

    fun removeRepository(manifestUrl: String)

    fun refreshAll()

    fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean = false)

    fun toggleScraper(scraperId: String, enabled: Boolean)

    fun setPluginsEnabled(enabled: Boolean)

    fun setGroupStreamsByRepository(enabled: Boolean)

    fun setSkipDuplicateScrapers(enabled: Boolean)

    /**
     * While active, no new scraper runtime starts; in-flight ones finish and queued ones wait.
     * Raised by the player from source attach until the first rendered frame, so a dozen QuickJS
     * runtimes spinning up cannot starve playback start on a weak machine. The runtime caps how
     * long a hold can last, so a start that never renders does not park scraping indefinitely.
     */
    fun setPlaybackStartupHold(active: Boolean)

    fun getEnabledScrapersForType(type: String): List<PluginScraper>

    suspend fun testScraper(scraperId: String): Result<List<PluginRuntimeResult>>

    suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Result<List<PluginRuntimeResult>>
}