package com.nuvio.app.features.recap

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiError
import com.nuvio.app.features.discover.DiscoverAiException
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Produces one recap: cache, or a single request to the user's own provider.
 *
 * This is the only place the two halves meet — the pure builders in this package decide *what* is
 * asked, and [DiscoverAiClient] handles *how*. Reusing that client rather than writing a second one
 * is deliberate: it already knows the two things a fresh implementation would get wrong, namely
 * that an Anthropic safety decline arrives as HTTP 200 with `stop_reason: "refusal"`, and how to
 * read a rate-limit hint out of either provider's headers.
 *
 * The `DiscoverAi*` names are wrong now that a second feature uses them. Renaming them to something
 * neutral touches eleven files and none of this one's behaviour, so it is left as its own
 * mechanical change rather than smuggled in here.
 */
object RecapRepository {
    private val log = Logger.withTag("Recap")

    /**
     * Where a generation actually runs.
     *
     * **Deliberately not the caller's scope.** The caller is a `LaunchedEffect` inside a dialog, so
     * dismissing the panel mid-request would cancel it — after the provider had already been asked,
     * and therefore after the user had already been charged for an answer nobody keeps. Run here
     * instead: closing the panel abandons the wait, the request finishes, the answer lands in the
     * cache, and re-opening is free.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * One request per key at a time.
     *
     * [DiscoverAiClient] already serialises requests globally, but serialising is not deduplicating:
     * two clicks on the same season would queue and then both bill. Callers that arrive while a
     * recap is in flight await the same answer instead.
     */
    private val inFlight = mutableMapOf<String, Deferred<Result<RecapResult>>>()
    private val inFlightLock = Mutex()

    /**
     * @param forceRefresh skip a cached answer and buy a new one. The sheet's "regenerate", and the
     *   only path in this feature that can bill twice for the same inputs — so it is never a
     *   default and never automatic.
     */
    suspend fun recap(
        meta: MetaDetails,
        boundary: RecapBoundary,
        todayIsoDate: String,
        forceRefresh: Boolean = false,
    ): Result<RecapResult> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        if (!settings.isRecapReady) {
            return Result.failure(RecapUnavailableException(RecapUnavailable.NotEnabled))
        }

        val source = buildRecapSource(
            episodes = meta.releasedMainSeasonEpisodes(todayIsoDate),
            boundary = boundary,
            seriesDescription = meta.description,
        )
        if (source.isEmpty) {
            return Result.failure(RecapUnavailableException(RecapUnavailable.NothingToRecap))
        }

        val languageTag = ThemeSettingsRepository.selectedAppLanguage.value.code
        val mode = if (settings.recapUseModelKnowledge) {
            RecapKnowledgeMode.ModelKnowledge
        } else {
            RecapKnowledgeMode.SynopsesOnly
        }
        val key = recapCacheKey(
            seriesId = meta.id,
            boundary = boundary,
            model = settings.effectiveModel,
            languageTag = languageTag,
            fingerprint = source.fingerprint(),
            mode = mode,
        )

        RecapCache.get(key)?.takeUnless { forceRefresh }?.let { cached ->
            return Result.success(
                RecapResult(
                    text = cached.text,
                    fromCache = true,
                    model = cached.model,
                    builtAtEpochMs = cached.builtAtEpochMs,
                    condensedSeasons = cached.condensedSeasons,
                    omittedSeasons = cached.omittedSeasons,
                    usedModelKnowledge =
                        cached.knowledgeMode == RecapKnowledgeMode.ModelKnowledge.name,
                ),
            )
        }

        val deferred = inFlightLock.withLock {
            inFlight[key]?.takeIf { it.isActive } ?: scope.async {
                try {
                    runCatching { generate(meta, boundary, source, languageTag, key, mode) }
                        .getOrElse { Result.failure(it) }
                } finally {
                    // Only clears the entry if it is still this coroutine's. Without the identity
                    // check a fast generation could remove the key before the registration below
                    // ran, leaving a *completed* deferred parked in the map — and every later
                    // request for that key, "Write it again" included, would be handed the old
                    // answer instead of a new one.
                    inFlightLock.withLock { if (inFlight[key] === coroutineContext[Job]) inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    private suspend fun generate(
        meta: MetaDetails,
        boundary: RecapBoundary,
        source: RecapSource,
        languageTag: String,
        /** Computed by the caller, which already needed it to check the cache. */
        cacheKey: String,
        mode: RecapKnowledgeMode,
    ): Result<RecapResult> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        log.i {
            "recap ${meta.name} to ${boundary.cacheToken()}: " +
                "sending ${source.lines.size} episodes, mode=$mode"
        }
        val prompt = buildRecapPrompt(
            seriesTitle = meta.name,
            boundary = boundary,
            source = source,
            languageTag = languageTag,
            mode = mode,
        )

        val text = DiscoverAiClient.complete(settings, prompt)
            .getOrElse {
                log.w { "recap ${meta.name} failed: $it" }
                return Result.failure(it)
            }
            .trim()

        if (text.isEmpty()) return Result.failure(DiscoverAiException(DiscoverAiError.Empty))

        log.i {
            "recap ${meta.name} to ${boundary.cacheToken()}: " +
                "${source.lines.size} episodes in, ${text.length} chars out" +
                if (source.wasTrimmed) " (trimmed)" else ""
        }

        val entry = RecapCacheEntry(
            text = text,
            builtAtEpochMs = System.currentTimeMillis(),
            model = settings.effectiveModel,
            knowledgeMode = mode.name,
            omittedSeasons = source.omittedSeasons,
            condensedSeasons = source.condensedSeasons,
        )
        // Cached before it is shown, not after: the failure this guards against is the sheet being
        // dismissed mid-render, which would otherwise throw away an answer already paid for.
        RecapCache.put(cacheKey, entry)

        return Result.success(
            RecapResult(
                text = text,
                fromCache = false,
                model = entry.model,
                builtAtEpochMs = entry.builtAtEpochMs,
                condensedSeasons = source.condensedSeasons,
                omittedSeasons = source.omittedSeasons,
                usedModelKnowledge = mode == RecapKnowledgeMode.ModelKnowledge,
            ),
        )
    }
}

/** A finished recap, however it was obtained. */
data class RecapResult(
    val text: String,
    /** True when no request was made. Drives whether the sheet offers "regenerate". */
    val fromCache: Boolean,
    val model: String,
    val builtAtEpochMs: Long,
    val condensedSeasons: List<Int> = emptyList(),
    val omittedSeasons: List<Int> = emptyList(),
    /**
     * Written with the model's own knowledge allowed.
     *
     * Surfaced in the sheet rather than kept internal: this ships to nightly to be judged, and a
     * report of "it spoiled season 7" is only actionable if the reader could see which mode wrote
     * the thing they are reporting.
     */
    val usedModelKnowledge: Boolean = false,
)

/**
 * A recap was not attempted, and why.
 *
 * Separate from `DiscoverAiException` because neither case is a provider failure: nothing was sent,
 * nothing was billed, and the fix is in the app rather than at the other end.
 */
class RecapUnavailableException(val reason: RecapUnavailable) : Exception(reason.toString())
