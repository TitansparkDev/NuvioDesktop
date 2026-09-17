package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import com.nuvio.app.features.discord.DiscordActivityName
import com.nuvio.app.features.discord.DiscordActivityStyle
import com.nuvio.app.features.discord.DiscordEpisodeArtwork
import com.nuvio.app.features.discord.DiscordPresenceMode
import com.nuvio.app.features.discord.DiscordPresenceSettings
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_discord_activity_name
import nuvio.composeapp.generated.resources.settings_discord_activity_name_app
import nuvio.composeapp.generated.resources.settings_discord_activity_name_description
import nuvio.composeapp.generated.resources.settings_discord_activity_name_title
import nuvio.composeapp.generated.resources.settings_discord_activity_style
import nuvio.composeapp.generated.resources.settings_discord_activity_style_description
import nuvio.composeapp.generated.resources.settings_discord_activity_style_listening
import nuvio.composeapp.generated.resources.settings_discord_activity_style_watching
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork_description
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork_poster
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork_still
import nuvio.composeapp.generated.resources.settings_discord_presence
import nuvio.composeapp.generated.resources.settings_discord_presence_description
import nuvio.composeapp.generated.resources.settings_discord_presence_disabled
import nuvio.composeapp.generated.resources.settings_discord_presence_full
import nuvio.composeapp.generated.resources.settings_discord_presence_watching
import nuvio.composeapp.generated.resources.settings_discord_section_activity
import nuvio.composeapp.generated.resources.settings_discord_section_presence
import org.jetbrains.compose.resources.stringResource

/**
 * Discord Rich Presence sub-page under Integrations. The mode row is always shown; the three
 * rows that shape the published activity only exist once presence is on, because a greyed row
 * invites a click that cannot do anything while there is no activity to shape.
 */
internal fun LazyListScope.discordPresenceSettingsContent(
    isTablet: Boolean,
    settings: DiscordPresenceSettings,
    onModeChange: (DiscordPresenceMode) -> Unit,
    onEpisodeArtworkChange: (DiscordEpisodeArtwork) -> Unit,
    onActivityStyleChange: (DiscordActivityStyle) -> Unit,
    onActivityNameChange: (DiscordActivityName) -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_discord_section_presence),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_discord_presence),
                    description = stringResource(Res.string.settings_discord_presence_description),
                    options = listOf(
                        SettingsChoiceOption(DiscordPresenceMode.Disabled, stringResource(Res.string.settings_discord_presence_disabled)),
                        SettingsChoiceOption(DiscordPresenceMode.Watching, stringResource(Res.string.settings_discord_presence_watching)),
                        SettingsChoiceOption(DiscordPresenceMode.Full, stringResource(Res.string.settings_discord_presence_full)),
                    ),
                    selectedValue = settings.mode,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DiscordPresence),
                    onSelected = onModeChange,
                )
            }
        }
    }

    if (settings.mode == DiscordPresenceMode.Disabled) return

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_discord_section_activity),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_discord_episode_artwork),
                    description = stringResource(Res.string.settings_discord_episode_artwork_description),
                    options = listOf(
                        SettingsChoiceOption(
                            DiscordEpisodeArtwork.Poster,
                            stringResource(Res.string.settings_discord_episode_artwork_poster),
                        ),
                        SettingsChoiceOption(
                            DiscordEpisodeArtwork.EpisodeThumbnail,
                            stringResource(Res.string.settings_discord_episode_artwork_still),
                        ),
                    ),
                    selectedValue = settings.episodeArtwork,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DiscordEpisodeArtwork),
                    onSelected = onEpisodeArtworkChange,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_discord_activity_style),
                    description = stringResource(Res.string.settings_discord_activity_style_description),
                    options = listOf(
                        SettingsChoiceOption(
                            DiscordActivityStyle.Watching,
                            stringResource(Res.string.settings_discord_activity_style_watching),
                        ),
                        SettingsChoiceOption(
                            DiscordActivityStyle.Listening,
                            stringResource(Res.string.settings_discord_activity_style_listening),
                        ),
                    ),
                    selectedValue = settings.activityStyle,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DiscordActivityStyle),
                    onSelected = onActivityStyleChange,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_discord_activity_name),
                    description = stringResource(Res.string.settings_discord_activity_name_description),
                    options = listOf(
                        SettingsChoiceOption(
                            DiscordActivityName.AppName,
                            stringResource(Res.string.settings_discord_activity_name_app),
                        ),
                        SettingsChoiceOption(
                            DiscordActivityName.Title,
                            stringResource(Res.string.settings_discord_activity_name_title),
                        ),
                    ),
                    selectedValue = settings.activityName,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DiscordActivityName),
                    onSelected = onActivityNameChange,
                )
            }
        }
    }
}
