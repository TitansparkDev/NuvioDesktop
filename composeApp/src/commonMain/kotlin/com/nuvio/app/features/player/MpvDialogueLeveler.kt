package com.nuvio.app.features.player

import kotlin.math.pow

const val DIALOGUE_LEVELER_OFF = 0
const val DIALOGUE_LEVELER_LIGHT = 1
const val DIALOGUE_LEVELER_MEDIUM = 2
const val DIALOGUE_LEVELER_STRONG = 3
const val DIALOGUE_LEVELER_MAX = 4

internal data class DialogueLevelerPreset(
    val thresholdDb: Float,
    val ratio: Float,
    val makeupDb: Float,
    val kneeDb: Float,
    val releaseMs: Float,
)

internal fun dialogueLevelerPreset(level: Int): DialogueLevelerPreset = when (
    level.coerceIn(DIALOGUE_LEVELER_OFF, DIALOGUE_LEVELER_MAX)
) {
    1 -> DialogueLevelerPreset(-20f, 2.0f, 3f, 8f, 250f)   // Light
    2 -> DialogueLevelerPreset(-24f, 2.5f, 6f, 8f, 200f)   // Medium
    3 -> DialogueLevelerPreset(-28f, 3.5f, 9f, 10f, 160f)  // Strong
    4 -> DialogueLevelerPreset(-32f, 5.0f, 12f, 12f, 120f) // Max
    else -> DialogueLevelerPreset(0f, 1f, 0f, 1f, 200f)    // Off
}

internal const val MPV_DIALOGUE_LEVELER_FILTER_LABEL = "nuvio_dialogue_leveler"

/** Builds the MPV/libavfilter equivalent of Dynamic Range Compression / Dialogue Leveler. */
fun buildMpvDialogueLevelerFilter(level: Int): String? {
    val clampedLevel = level.coerceIn(DIALOGUE_LEVELER_OFF, DIALOGUE_LEVELER_MAX)
    if (clampedLevel == DIALOGUE_LEVELER_OFF) return null

    val preset = dialogueLevelerPreset(clampedLevel)
    val threshold = dbToLinear(preset.thresholdDb)
    val makeup = dbToLinear(preset.makeupDb)
    val knee = dbToLinear(preset.kneeDb).coerceIn(1.0, 8.0)

    val thresholdStr = ((threshold * 1000000.0).toLong() / 1000000.0).toString()
    val makeupStr = ((makeup * 1000000.0).toLong() / 1000000.0).toString()
    val kneeStr = ((knee * 1000000.0).toLong() / 1000000.0).toString()
    val ratioStr = preset.ratio.toString()
    val releaseStr = preset.releaseMs.toInt().toString()

    return buildString {
        append('@').append(MPV_DIALOGUE_LEVELER_FILTER_LABEL).append(":lavfi=[")
        append("acompressor=")
        append("threshold=").append(thresholdStr)
        append(":ratio=").append(ratioStr)
        append(":attack=5")
        append(":release=").append(releaseStr)
        append(":makeup=").append(makeupStr)
        append(":knee=").append(kneeStr)
        append(":link=maximum:detection=peak,")
        append("alimiter=limit=0.98:attack=5:release=50:level=0:latency=1")
        append(']')
    }
}

private fun dbToLinear(db: Float): Double = 10.0.pow(db / 20.0)
