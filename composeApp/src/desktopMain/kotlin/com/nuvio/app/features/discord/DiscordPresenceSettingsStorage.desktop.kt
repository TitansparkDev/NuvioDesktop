package com.nuvio.app.features.discord

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DiscordPresenceSettingsStorage {
    // Legacy boolean toggle; retained only to migrate existing installs to a mode.
    private const val legacyEnabledKey = "enabled"
    private const val modeKey = "mode"
    private const val episodeArtworkKey = "episode_artwork"
    private const val activityStyleKey = "activity_style"
    private const val activityNameKey = "activity_name"
    private val store = DesktopStorage.store("nuvio_discord_presence")

    actual fun loadMode(): DiscordPresenceMode {
        store.getString(modeKey)?.let { stored ->
            runCatching { DiscordPresenceMode.valueOf(stored) }.getOrNull()?.let { return it }
        }
        // Migration: the previous boolean toggle showed the full integration when enabled,
        // so a previously-enabled install maps to Full; anything else stays Disabled.
        return if (store.getBoolean(legacyEnabledKey) == true) {
            DiscordPresenceMode.Full
        } else {
            DiscordPresenceMode.Disabled
        }
    }

    actual fun saveMode(mode: DiscordPresenceMode) {
        store.putString(modeKey, mode.name)
    }

    // Poster is the default because it is what the presence already showed: the artwork order was
    // poster-first before this setting existed, so an install that never touches it sees no change.
    actual fun loadEpisodeArtwork(): DiscordEpisodeArtwork =
        store.getString(episodeArtworkKey)
            ?.let { stored -> runCatching { DiscordEpisodeArtwork.valueOf(stored) }.getOrNull() }
            ?: DiscordEpisodeArtwork.Poster

    actual fun saveEpisodeArtwork(value: DiscordEpisodeArtwork) {
        store.putString(episodeArtworkKey, value.name)
    }

    // Watching is what every install published before this setting existed.
    actual fun loadActivityStyle(): DiscordActivityStyle =
        store.getString(activityStyleKey)
            ?.let { stored -> runCatching { DiscordActivityStyle.valueOf(stored) }.getOrNull() }
            ?: DiscordActivityStyle.Watching

    actual fun saveActivityStyle(value: DiscordActivityStyle) {
        store.putString(activityStyleKey, value.name)
    }

    actual fun loadActivityName(): DiscordActivityName =
        store.getString(activityNameKey)
            ?.let { stored -> runCatching { DiscordActivityName.valueOf(stored) }.getOrNull() }
            ?: DiscordActivityName.AppName

    actual fun saveActivityName(value: DiscordActivityName) {
        store.putString(activityNameKey, value.name)
    }
}
