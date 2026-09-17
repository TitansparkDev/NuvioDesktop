package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier

/**
 * Frame-budget instrumentation, declared here so the shared browsing screens can mark up their own
 * hot spots. Only the desktop target measures anything; see `FrameBudgetTelemetry.kt`.
 */

/** Attributes this subtree's draw time to [name] in the frame-budget report. */
internal expect fun Modifier.frameBudgetProbe(name: String): Modifier

/** Names the workload being measured, e.g. `home/AdaptiveAmbient`. */
internal expect fun frameBudgetSetContext(value: String)

/**
 * Reporting is gated on this: only frames the user is scrolling through are interesting. [owner]
 * identifies the reporting screen, so an idle instance cannot cancel a scrolling one.
 */
internal expect fun frameBudgetSetScrolling(owner: String, value: Boolean)

/** Whether the opt-in diagnostics are on. Gates anything that changes what the user sees. */
internal expect fun frameBudgetProbesEnabled(): Boolean

/** Turns the diagnostics on or off at runtime, from the settings toggle. */
internal expect fun frameBudgetSetProbesEnabled(enabled: Boolean)

/**
 * Tags subsequent reports with an experiment name, flushing the window in progress so the two are
 * never averaged together. Null clears it.
 */
internal expect fun frameBudgetSetVariant(value: String?)

/** Counts one recomposition of [name]. Called by [RecompositionProbe]; not useful on its own. */
internal expect fun frameBudgetRecompose(name: String)

/**
 * Counts how often the enclosing composable recomposes, reported per frame alongside the draw
 * table.
 *
 * `@NonRestartableComposable` is the whole mechanism: without a restart group of its own, this
 * body runs exactly when its caller's group runs, so the count is the caller's recomposition count
 * and not this function's. An ordinary composable would be skipped — [name] is stable — and count
 * nothing at all.
 *
 * Draw time alone cannot find over-recomposition: a screen recomposing wholesale every frame can
 * still redraw the same picture, so it shows up as `pre` cost with nothing in the draw table to
 * explain it. This is what names the culprit.
 */
@Composable
@NonRestartableComposable
internal fun RecompositionProbe(name: String) {
    frameBudgetRecompose(name)
}
