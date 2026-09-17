package com.nuvio.app.features.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.discover.DiscoverAiError
import com.nuvio.app.features.discover.DiscoverAiException
import com.nuvio.app.features.settings.discoverAiErrorMessage
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.recap_error_empty
import nuvio.composeapp.generated.resources.recap_error_not_enabled
import nuvio.composeapp.generated.resources.recap_error_truncated
import nuvio.composeapp.generated.resources.recap_error_nothing
import nuvio.composeapp.generated.resources.recap_footer_model
import nuvio.composeapp.generated.resources.recap_loading
import nuvio.composeapp.generated.resources.recap_note_model_knowledge
import nuvio.composeapp.generated.resources.recap_regenerate
import nuvio.composeapp.generated.resources.recap_spoiler_note
import nuvio.composeapp.generated.resources.recap_subtitle_before_episode
import nuvio.composeapp.generated.resources.recap_subtitle_before_season
import nuvio.composeapp.generated.resources.recap_title
import nuvio.composeapp.generated.resources.recap_trimmed_condensed
import nuvio.composeapp.generated.resources.recap_trimmed_omitted
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The recap panel.
 *
 * Owns its own request rather than taking a state object from the details screen. `MetaDetailsScreen`
 * is already three thousand lines and every feature that hands it another `var` makes the next one
 * harder to place; the fetch here is one call with one boundary and nothing else on that screen
 * needs to observe it.
 */
@Composable
fun RecapDialog(
    meta: MetaDetails,
    boundary: RecapBoundary,
    todayIsoDate: String,
    onDismiss: () -> Unit,
) {
    var state by remember(meta.id, boundary) { mutableStateOf<RecapUiState>(RecapUiState.Loading) }
    var refreshToken by remember(meta.id, boundary) { mutableStateOf(0) }

    LaunchedEffect(meta.id, boundary, refreshToken) {
        state = RecapUiState.Loading
        val outcome = RecapRepository.recap(
            meta = meta,
            boundary = boundary,
            todayIsoDate = todayIsoDate,
            // The first pass may serve the cache; only the explicit button buys a second answer.
            forceRefresh = refreshToken > 0,
        )
        state = outcome.fold(
            onSuccess = { RecapUiState.Ready(it) },
            onFailure = { error ->
                when ((error as? RecapUnavailableException)?.reason) {
                    RecapUnavailable.NotEnabled ->
                        RecapUiState.Failed(getString(Res.string.recap_error_not_enabled), canRetry = false)

                    RecapUnavailable.NothingToRecap ->
                        RecapUiState.Failed(getString(Res.string.recap_error_nothing), canRetry = false)

                    null -> RecapUiState.Failed(recapProviderErrorMessage(error), canRetry = true)
                }
            },
        )
    }

    val colors = MaterialTheme.nuvio.colors

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.recap_title),
        subtitle = recapSubtitle(seriesTitle = meta.name, boundary = boundary),
        actions = {
            val ready = state as? RecapUiState.Ready
            if (ready != null) {
                TextButton(onClick = { refreshToken += 1 }) {
                    Text(stringResource(Res.string.recap_regenerate))
                }
            }
            (state as? RecapUiState.Failed)?.takeIf { it.canRetry }?.let {
                TextButton(onClick = { refreshToken += 1 }) {
                    Text(stringResource(Res.string.action_retry))
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
        },
    ) {
        when (val current = state) {
            RecapUiState.Loading -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(Res.string.recap_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            }

            is RecapUiState.Failed -> Text(
                text = current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )

            is RecapUiState.Ready -> Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Split on blank lines rather than rendered as one block: the prompt asks for
                // paragraphs, and a single Text collapses them into a wall.
                current.result.text.split(Regex("\n\\s*\n")).forEach { paragraph ->
                    val trimmed = paragraph.trim()
                    if (trimmed.isEmpty()) return@forEach
                    Text(
                        text = trimmed,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                    )
                }

                current.result.omittedSeasons.takeIf { it.isNotEmpty() }?.let { seasons ->
                    RecapNote(stringResource(Res.string.recap_trimmed_omitted, seasons.joinToString(", ")))
                }
                current.result.condensedSeasons.takeIf { it.isNotEmpty() }?.let { seasons ->
                    RecapNote(stringResource(Res.string.recap_trimmed_condensed, seasons.joinToString(", ")))
                }
                if (current.result.usedModelKnowledge) {
                    RecapNote(stringResource(Res.string.recap_note_model_knowledge))
                }
                RecapNote(stringResource(Res.string.recap_spoiler_note))
                current.result.model.takeIf { it.isNotBlank() }?.let { model ->
                    RecapNote(stringResource(Res.string.recap_footer_model, model))
                }
            }
        }
    }
}

/**
 * A provider failure, said in this feature's own words where the shared ones do not fit.
 *
 * Most of `discoverAiErrorMessage` is about the key, the host and the quota, and is the same story
 * whichever feature hit it — reusing it keeps one account of those. But two of its cases are
 * written about *rows*: an empty answer there is "replied, but not with suggestions", which is what
 * a recap dialog was caught saying. Those two are answered here instead.
 */
private suspend fun recapProviderErrorMessage(error: Throwable): String =
    when ((error as? DiscoverAiException)?.error) {
        DiscoverAiError.Empty -> getString(Res.string.recap_error_empty)
        DiscoverAiError.Truncated -> getString(Res.string.recap_error_truncated)
        else -> discoverAiErrorMessage(error)
    }

@Composable
private fun RecapNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

/** "Breaking Bad - everything before season 3", or the same with an episode code. */
@Composable
private fun recapSubtitle(seriesTitle: String, boundary: RecapBoundary): String =
    if (boundary.episode == null) {
        stringResource(Res.string.recap_subtitle_before_season, seriesTitle, boundary.season)
    } else {
        val code = localizedSeasonEpisodeCode(boundary.season, boundary.episode)
            ?: "S${boundary.season}E${boundary.episode}"
        stringResource(Res.string.recap_subtitle_before_episode, seriesTitle, code)
    }

internal sealed interface RecapUiState {
    data object Loading : RecapUiState

    data class Ready(val result: RecapResult) : RecapUiState

    /** [canRetry] is false for the two cases a second attempt cannot change. */
    data class Failed(val message: String, val canRetry: Boolean) : RecapUiState
}
