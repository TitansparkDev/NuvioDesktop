package com.nuvio.app.features.recap

import com.nuvio.app.features.discover.DiscoverAiPromptText

/**
 * The prompt a recap sends.
 *
 * Pure, like `buildDiscoverAiPrompt`, and for the same reason: what the model is actually asked is
 * the hardest thing to eyeball and the easiest thing to get subtly wrong.
 *
 * **The instruction text is not localised.** It is sent to a provider, not shown to a person, and
 * translating it would change what is asked. The language the *answer* comes back in is stated
 * inside the prompt instead — see [buildRecapPrompt]'s `languageTag`.
 */

/** Roughly how long the answer should be. Words rather than tokens: the model is being told, not capped. */
private const val TARGET_WORDS_MIN = 250
private const val TARGET_WORDS_MAX = 600

/**
 * Whether the model may draw on what it already knows about the series.
 *
 * Worth stating as a choice rather than a flag, because the two trade different risks:
 * [SynopsesOnly] can only be thin, [ModelKnowledge] can be wrong. Neither is strictly better, which
 * is why this ships as a setting to be judged in the field rather than as a silent default change.
 */
enum class RecapKnowledgeMode {
    /**
     * Only the supplied synopses. Cannot leak and cannot invent, and is thin exactly where the
     * synopses are — which is finales, because episode overviews are written before broadcast to
     * sell the episode rather than to describe it.
     */
    SynopsesOnly,

    /**
     * The model may also recall the series itself, for events strictly before the cut-off.
     *
     * The safety argument is that events before the cut-off are not spoilers by definition — the
     * viewer has watched them. The risk is not that the model blurts out an ending; it is
     * **misattribution**: a true event placed in the wrong season is a spoiler delivered with
     * complete confidence, and season boundaries are what a model's memory is vaguest about. The
     * prompt for this mode is written around that one failure, not around secrecy in general.
     */
    ModelKnowledge,
}

/** Shared by both modes: everything about shape, tense and what a recap is for. */
private const val COMMON_RULES =
    "- Some episodes may be listed by title only. Treat those as time that passed, not as " +
        "an invitation to fill in what happened.\n" +
        "- Do not editorialise about quality, and do not address the reader's viewing habits.\n\n" +
        "Write flowing prose in past tense, organised by what matters rather than episode by " +
        "episode: the through-lines, where the main characters ended up, and the questions left " +
        "open. Plain paragraphs separated by blank lines. No headings, no bullet points, no " +
        "markdown, no preamble, and no closing line about what to expect next."

private const val SYSTEM_PROMPT =
    "You write \"story so far\" recaps for someone returning to a television series they have " +
        "partly watched.\n\n" +
        "You will be given a list of episodes with their synopses, and a cut-off point. The list " +
        "is your only source. You may recognise the series from elsewhere; that knowledge is off " +
        "limits here, because the person reading has not seen past the cut-off and anything you " +
        "add from memory is a spoiler they cannot un-read.\n\n" +
        "Rules:\n" +
        "- Never mention an event, death, reveal, betrayal, relationship or arrival that is not " +
        "in the supplied synopses.\n" +
        "- Never refer to anything at or after the cut-off, including what happens next.\n" +
        COMMON_RULES

/**
 * [RecapKnowledgeMode.ModelKnowledge].
 *
 * Built around misattribution rather than secrecy. The instruction carrying the most weight is
 * the third: **when unsure which season something belongs to, leave it out.** A recap that omits
 * a real event is mildly disappointing; one that reports a later season as already having
 * happened has spoiled the thing the reader came back for. Those costs are not symmetrical, and
 * the model is told so outright — left to itself it optimises for an answer that sounds complete.
 */
private const val SYSTEM_PROMPT_WITH_MODEL_KNOWLEDGE =
    "You write \"story so far\" recaps for someone returning to a television series they have " +
        "partly watched.\n\n" +
        "You will be given a list of episodes with their synopses, and a cut-off point. The " +
        "synopses are brief and are often written before broadcast, so they under-describe what " +
        "actually happened, especially in finales. You may therefore also use what you already " +
        "know about this series, but ONLY for events that happened strictly before the " +
        "cut-off, which the reader has already watched and cannot be spoiled by.\n\n" +
        "Rules:\n" +
        "- The supplied synopses are authoritative. Where your memory disagrees with them, " +
        "they win.\n" +
        "- Never refer to anything at or after the cut-off, including what happens next.\n" +
        "- If you are not certain which season an event belongs to, LEAVE IT OUT. Placing a " +
        "later event earlier is the worst thing you can do here, and it is the mistake you are " +
        "most likely to make. Omitting a real event costs the reader very little; revealing one " +
        "they have not reached ruins what they came back for. When those trade off, omit.\n" +
        "- If you do not actually know this series, say nothing beyond the synopses. Do not " +
        "reconstruct plausible events; an invented plot is as useless as a spoiled one.\n" +
        COMMON_RULES

/**
 * Builds the recap request for one series.
 *
 * @param languageTag IETF tag of the app language, e.g. `en`, `de`. Passed as a tag rather than a
 *   language name so there is no table to keep in step with `AppLanguage`, and so the instruction
 *   does not itself need translating.
 */
fun buildRecapPrompt(
    seriesTitle: String,
    boundary: RecapBoundary,
    source: RecapSource,
    languageTag: String = "en",
    mode: RecapKnowledgeMode = RecapKnowledgeMode.SynopsesOnly,
): DiscoverAiPromptText {
    val user = buildString {
        append("Series: ").append(seriesTitle.trim()).append('\n')
        append("Cut-off: ").append(describeBoundary(boundary)).append('\n')
        append(
            "Recap everything before that point. Aim for $TARGET_WORDS_MIN-$TARGET_WORDS_MAX words.",
        )
        if (languageTag.isNotBlank() && !languageTag.equals("en", ignoreCase = true)) {
            append(" Write the recap in the language with IETF tag \"")
            append(languageTag.trim())
            append("\".")
        }
        append('\n')

        if (source.omittedSeasons.isNotEmpty()) {
            // Told to the model as well as to the user. Without it, a recap that starts at season
            // 12 reads as though the series does, and the model writes an origin story for a
            // midpoint.
            append("\nEarlier seasons not supplied: ")
            append(source.omittedSeasons.joinToString(", "))
            append(". Do not describe them; the recap starts after them.\n")
        }

        append("\nEpisodes:\n")
        var lastSeason: Int? = null
        source.lines.forEach { line ->
            if (line.season != lastSeason) {
                append("\nSeason ").append(line.season)
                if (line.season in source.condensedSeasons) append(" (titles only)")
                append('\n')
                lastSeason = line.season
            }
            append(episodeCode(line.season, line.episode))
            append(" - ")
            append(line.title.ifBlank { "Untitled" })
            line.overview?.let { append(": ").append(it) }
            append('\n')
        }
    }

    val system = when (mode) {
        RecapKnowledgeMode.SynopsesOnly -> SYSTEM_PROMPT
        RecapKnowledgeMode.ModelKnowledge -> SYSTEM_PROMPT_WITH_MODEL_KNOWLEDGE
    }
    return DiscoverAiPromptText(system = system, user = user)
}

/**
 * The cut-off in the model's own terms.
 *
 * Both forms name a *specific episode that must not appear*, rather than the last one that may.
 * "Everything up to season 3" is ambiguous about whether season 3 is included; "the start of
 * season 3, which has not been watched" is not.
 */
private fun describeBoundary(boundary: RecapBoundary): String =
    if (boundary.episode == null) {
        "the start of season ${boundary.season}. Nothing from season ${boundary.season} onwards " +
            "has been watched."
    } else {
        "${episodeCode(boundary.season, boundary.episode)}, which has not been watched. " +
            "Everything before it has been."
    }

/** `S03E04`. Two digits minimum, no locale involvement — this string is for a model, not a screen. */
private fun episodeCode(season: Int, episode: Int): String =
    "S" + season.toString().padStart(2, '0') + "E" + episode.toString().padStart(2, '0')
