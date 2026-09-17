package com.nuvio.app.features.watchprogress

import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf

/**
 * Artwork URLs that failed to load this session.
 *
 * A Continue Watching card is the one surface in the app where a dead artwork URL leaves nothing
 * at all on screen — every other card draws its title over the image. Episode stills are also the
 * most fragile artwork the app handles: providers publish them late, swap CDN hosts, and hand back
 * URLs for images that were never uploaded. Remembering the failures lets every card for the same
 * show skip straight to the fallback instead of each one re-requesting the dead URL.
 *
 * Session-scoped on purpose: a URL that failed because the network was down deserves a fresh try
 * on the next launch. That is also what makes persisting the record unnecessary for resolution —
 * a URL that is genuinely bad fails again on the next launch, and re-arms everything below.
 *
 * Lives here rather than beside the card that reports the failures because it is not only a
 * rendering concern: [needsEpisodeStillRefresh] reads it, so a still that could not be drawn is
 * re-resolved instead of being declared finished for the life of the seed.
 */
internal object ContinueWatchingArtworkFailures {
    private val failedUrls: SnapshotStateMap<String, Unit> = mutableStateMapOf()

    fun hasFailed(url: String): Boolean = url in failedUrls

    fun markFailed(url: String) {
        if (failedUrls.put(url, Unit) != null) return
        ContinueWatchingArtworkDiagnostics.logArtworkLoadFailure(url)
        // Re-queueing the card is inert on its own: `MetaDetailsRepository.fetch` without
        // forceRefresh rides an LRU with no TTL, so every retry this run would hand back the same
        // artwork-less video list. Bumping the request is what buys one real fetch — and it only
        // happens on a URL's *first* failure, so a screen full of dead artwork costs one pass.
        ContinueWatchingEnrichmentCache.requestArtworkRefresh()
    }
}
