package com.nuvio.app.features.settings

import kotlin.math.roundToInt

/**
 * Bridge between this fork's custom theme (an accent → accentEnd gradient plus background,
 * elevated and card surfaces, stored as separate keys) and the official apps' `custom_theme_colors`
 * sync key: one string of exactly three `#RRGGBB` accent stops, `first,second,third`, where all
 * three equal means a flat accent.
 *
 * Without this bridge a pull of a blob written by the phone or TV app wiped every colour on the
 * desktop while leaving the theme on Custom (the official key was ignored and our keys were absent),
 * and a desktop push did the same to the phone.
 */
internal object OfficialCustomThemeColors {
    const val KEY = "custom_theme_colors"

    /** Our two-stop gradient as the official three-stop form: start, interpolated midpoint, end. */
    fun encode(accentHex: String, accentEndHex: String): String? {
        val start = parseRgb(accentHex) ?: return null
        val end = parseRgb(accentEndHex) ?: start
        val mid = IntArray(3) { i -> ((start[i] + end[i]) / 2f).roundToInt() }
        return listOf(start, mid, end).joinToString(",", transform = ::formatRgb)
    }

    /**
     * Accent and accent-end stops from the official string, or null when it is not three valid
     * colours. The midpoint is dropped: the official gradient passes through it, ours is linear
     * between the ends, which is the closest two-stop rendering.
     */
    fun decode(value: String?): Pair<String, String>? {
        val stops = value?.split(",")?.map { parseRgb(it) ?: return null } ?: return null
        if (stops.size != 3) return null
        return formatRgb(stops[0]) to formatRgb(stops[2])
    }

    private fun parseRgb(value: String): IntArray? {
        val hex = value.trim().removePrefix("#")
        if (hex.length != 6 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return IntArray(3) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }
    }

    private fun formatRgb(rgb: IntArray): String =
        "#" + rgb.joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
}
