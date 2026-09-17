package com.nuvio.app.features.player

/**
 * The languages an external player launch should be handed subtitles for, in priority order.
 * Empty means "forward nothing": no language preference resolves at all.
 */
fun externalPlayerSubtitleTargets(
    settings: PlayerSettingsUiState,
    originalLanguage: String? = OriginalLanguageCache.current,
): List<String> = preferredSubtitleTargetsForSettings(
    settings = settings,
    originalLanguage = originalLanguage,
)

/**
 * Orchestrates the full external player launch flow:
 * fetches subtitles if forwarding is enabled, downloads them to local cache,
 * then returns an enriched request for the caller to dispatch.
 */
suspend fun prepareExternalPlayerLaunch(
    request: ExternalPlayerPlaybackRequest,
    type: String,
    videoId: String,
    forwardSubtitles: Boolean,
    settings: PlayerSettingsUiState,
    originalLanguage: String? = OriginalLanguageCache.current,
    onOverlayMessage: (String?) -> Unit,
): ExternalPlayerPlaybackRequest {
    if (!forwardSubtitles) return request
    val targets = externalPlayerSubtitleTargets(settings, originalLanguage)
    if (targets.isEmpty()) return request

    onOverlayMessage("Loading subtitles from addons...")

    val subtitles = SubtitleForwarder.fetchForExternalPlayer(
        type = type,
        videoId = videoId,
        targets = targets,
        // The keyword rejections name a track kind, not a language, so they apply here exactly as
        // they do to the internal player's own lists.
        isRejected = { subtitle -> settings.rejectsAddonSubtitle(subtitle) },
        trackKind = settings.preferredSubtitleTrackKind,
    )

    if (subtitles != null) {
        onOverlayMessage("Downloading subtitles...")
        val cachedSubtitles = SubtitleCacheProvider.cacheForExternalPlayer(subtitles)
        if (cachedSubtitles != null) {
            return request.copy(subtitles = cachedSubtitles)
        }
        // Fallback: use original URLs if caching fails
        return request.copy(subtitles = subtitles)
    }

    return request
}
