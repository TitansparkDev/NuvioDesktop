package com.nuvio.app.features.discord

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much of the Discord Rich Presence integration is shared.
 *
 * - [Disabled]: no presence at all (default when nothing has been configured).
 * - [Watching]: presence only while actively playing something.
 * - [Full]: the complete integration — browsing, library, viewing, and playback.
 */
enum class DiscordPresenceMode {
    Disabled,
    Watching,
    Full,
}

/**
 * Which artwork an episode's presence should show.
 *
 * Only meaningful for episodic playback — a film has no episode still, so this changes nothing for
 * one. The candidate list is a *preference*, not a requirement: whichever is picked, the other is
 * still the fallback, because Discord fetches artwork from its own servers and a poster that only
 * resolves on the user's LAN is unusable there (see [isExternallyFetchableArtworkUrl]). A user who
 * asks for posters and gets stills anyway is looking at that, not at this setting.
 */
enum class DiscordEpisodeArtwork {
    /** The series poster — portrait, letterboxed into Discord's square. */
    Poster,

    /** The episode still — landscape, and it fills the square without bars. */
    EpisodeThumbnail,
}

/**
 * Which Discord activity type playback is published as. Discord's own layouts make this a
 * trade-off with no clean answer, so it is the user's call:
 *
 * - [Watching]: the honest verb ("Watching on Nuvio HTPC") with the media progress bar on the
 *   profile card — but the friends-list "Active Now" panel only ever shows the header line for
 *   this type; the title, episode and poster never appear there.
 * - [Listening]: the same layout Spotify gets, and the *only* type that expands in "Active Now"
 *   with artwork, title, episode and bar. Reads "Listening to Nuvio HTPC", which is the price.
 *
 * Playing (type 0) is not offered: it expands in "Active Now" too, but loses the progress bar.
 * (Findings from codeine's Active Now report, 2026-09.)
 */
enum class DiscordActivityStyle {
    Watching,
    Listening,
}

/**
 * What the activity's `name` carries, i.e. the header line after Discord's verb.
 *
 * - [AppName]: "Watching on Nuvio HTPC" / "Listening to Nuvio HTPC", with the title on the
 *   details line beneath it. The default since codeine's layout change.
 * - [Title]: "Watching Friends", the original output, where the title is also the details line.
 */
enum class DiscordActivityName {
    AppName,
    Title,
}

data class DiscordPresenceSettings(
    val mode: DiscordPresenceMode = DiscordPresenceMode.Disabled,
    val episodeArtwork: DiscordEpisodeArtwork = DiscordEpisodeArtwork.Poster,
    val activityStyle: DiscordActivityStyle = DiscordActivityStyle.Watching,
    val activityName: DiscordActivityName = DiscordActivityName.AppName,
) {
    /** Playback presence is shared in both Watching and Full. */
    val showPlaybackPresence: Boolean get() = mode != DiscordPresenceMode.Disabled

    /** Browsing/library presence is shared only in Full. */
    val showBrowsingPresence: Boolean get() = mode == DiscordPresenceMode.Full
}

internal expect object DiscordPresenceSettingsStorage {
    fun loadMode(): DiscordPresenceMode
    fun saveMode(mode: DiscordPresenceMode)
    fun loadEpisodeArtwork(): DiscordEpisodeArtwork
    fun saveEpisodeArtwork(value: DiscordEpisodeArtwork)
    fun loadActivityStyle(): DiscordActivityStyle
    fun saveActivityStyle(value: DiscordActivityStyle)
    fun loadActivityName(): DiscordActivityName
    fun saveActivityName(value: DiscordActivityName)
}

object DiscordPresenceSettingsRepository {
    private val _uiState = MutableStateFlow(DiscordPresenceSettings())
    val uiState: StateFlow<DiscordPresenceSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var mode = DiscordPresenceMode.Disabled
    private var episodeArtwork = DiscordEpisodeArtwork.Poster
    private var activityStyle = DiscordActivityStyle.Watching
    private var activityName = DiscordActivityName.AppName

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        mode = DiscordPresenceSettingsStorage.loadMode()
        episodeArtwork = DiscordPresenceSettingsStorage.loadEpisodeArtwork()
        activityStyle = DiscordPresenceSettingsStorage.loadActivityStyle()
        activityName = DiscordPresenceSettingsStorage.loadActivityName()
        publish()
    }

    fun setMode(value: DiscordPresenceMode) {
        ensureLoaded()
        if (mode == value) return
        mode = value
        publish()
        DiscordPresenceSettingsStorage.saveMode(value)
    }

    fun setEpisodeArtwork(value: DiscordEpisodeArtwork) {
        ensureLoaded()
        if (episodeArtwork == value) return
        episodeArtwork = value
        publish()
        DiscordPresenceSettingsStorage.saveEpisodeArtwork(value)
        // Note the presence already on screen is rebuilt by the player's own effect, which reads
        // this value as one of its keys. Republishing the stored activity here would not help:
        // it was assembled under the old preference and still carries the old URL.
    }

    fun setActivityStyle(value: DiscordActivityStyle) {
        ensureLoaded()
        if (activityStyle == value) return
        activityStyle = value
        publish()
        DiscordPresenceSettingsStorage.saveActivityStyle(value)
        // As with the artwork preference: the player's effect keys on this and republishes.
    }

    fun setActivityName(value: DiscordActivityName) {
        ensureLoaded()
        if (activityName == value) return
        activityName = value
        publish()
        DiscordPresenceSettingsStorage.saveActivityName(value)
    }

    private fun publish() {
        _uiState.value = DiscordPresenceSettings(
            mode = mode,
            episodeArtwork = episodeArtwork,
            activityStyle = activityStyle,
            activityName = activityName,
        )
    }
}
