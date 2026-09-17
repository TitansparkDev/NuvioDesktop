package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which parts of the ambient wash are drawn, so each one can be priced in the running app.
 *
 * Measurement (2026-09-07) put the wash at ~3.3 ms of a browsing frame — more than the whole rest of
 * the frame in Adaptive mode — and only ~1.2 ms of that is accounted for by full-screen alpha
 * blending, which measures 0.41 ms a pass at 4K. The remaining ~1.7 ms is somewhere among the blur,
 * the saturation colour filter, the upscaled blit and the `Crossfade`'s two offscreen layers.
 *
 * Two attempts to reason about which have already been wrong — hoisting the tint out of the
 * `Crossfade` was sound on paper and measured +0.39 ms — so these exist to subtract one component at
 * a time and read the difference off the frame budget instead.
 */
internal enum class AmbientWashVariant(val label: String) {
    /** Everything, exactly as shipped. The baseline every other variant is compared against. */
    Full("full"),

    /** No accent gradient over the backdrop: prices one full-screen blend of the wash's own. */
    NoTint("noTint"),

    /** Backdrop drawn unblurred: prices the blur render effect on the 1/6-scale layer. */
    NoBlur("noBlur"),

    /** No saturation `ColorMatrix` on the backdrop: prices the colour filter. */
    NoColorFilter("noColorFilter"),

    /**
     * Backdrop swapped instantly instead of crossfading: prices the two full-screen offscreen
     * layers a transition runs in. In this mode the hero follows focus, so a transition is live
     * most of the time the user is browsing — the reason this is a suspect at all.
     */
    NoCrossfade("noCrossfade"),
}

/**
 * The selected [AmbientWashVariant], cycled with Ctrl+Shift+W.
 *
 * Only listens while frame probes are enabled. Without that guard a stray shortcut would leave an
 * ordinary user in a visually degraded mode with no indication why, which is not a trade a
 * diagnostic should ever make.
 */
internal object AmbientWashProbe {

    var variant by mutableStateOf(AmbientWashVariant.Full)
        private set

    /** Returns the newly selected variant, or null when diagnostics are off and nothing changed. */
    fun cycle(): AmbientWashVariant? {
        if (!frameBudgetProbesEnabled()) return null
        val next = AmbientWashVariant.entries[
            (variant.ordinal + 1) % AmbientWashVariant.entries.size,
        ]
        variant = next
        // The frame budget has to start a fresh window here, or the variant that just ended and the
        // one just starting would be averaged into one meaningless line.
        frameBudgetSetVariant(if (next == AmbientWashVariant.Full) null else next.label)
        return next
    }

    /** Always [AmbientWashVariant.Full] unless diagnostics are on. */
    fun effective(): AmbientWashVariant =
        if (frameBudgetProbesEnabled()) variant else AmbientWashVariant.Full
}
