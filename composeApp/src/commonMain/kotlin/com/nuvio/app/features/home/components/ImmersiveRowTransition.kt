package com.nuvio.app.features.home.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.nuvio.app.features.home.HomeTvRowTransition

/**
 * Shelf-level transition for a TV Mode row change, per the user's [HomeTvRowTransition]: a
 * cross-fade of the whole row (title, dots, posters), or a hard cut. Off returns
 * `EnterTransition.None` so `AnimatedContent` swaps in the same frame it would have without the
 * wrapper. Both fades are gradual, so the swap never presents a one-frame luminance step to a VRR
 * panel.
 *
 * The nudge is deliberately *not* here. It is applied to the row body alone via
 * [immersiveRowBodyEnter] / [immersiveRowBodyExit] and `Modifier.animateEnterExit`, so the posters
 * move and the title stays put — a title that shifts while you are reading it is the one thing the
 * motion must not do.
 */
internal fun immersiveRowTransition(mode: HomeTvRowTransition): ContentTransform {
    if (mode == HomeTvRowTransition.Off) {
        return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    }
    // No size transform at all: the shelf is a fixed-height box, so there is no size to animate,
    // and a size modifier would clip a focused poster's scale-up at the row bounds mid-transition.
    return ContentTransform(
        targetContentEnter = fadeIn(tween(ImmersiveRowFadeInMs)),
        initialContentExit = fadeOut(tween(ImmersiveRowFadeOutMs)),
        sizeTransform = null,
    )
}

/**
 * The incoming row body's slide, for the Fade + nudge mode. Rises into place when [forward]
 * (moving to a later row, i.e. "down" the list), drops in otherwise. The shelf's fade is shorter
 * than this slide on purpose: a body that is still transparent while it travels shows no direction
 * at all, only a settle at the end.
 */
internal fun immersiveRowBodyEnter(forward: Boolean): EnterTransition =
    slideInVertically(
        animationSpec = tween(ImmersiveRowSlideMs, easing = ImmersiveRowSlideEasing),
        initialOffsetY = { if (forward) slideDistance(it) else -slideDistance(it) },
    )

/** The outgoing row body's slide: the mirror of [immersiveRowBodyEnter], over the fade-out. */
internal fun immersiveRowBodyExit(forward: Boolean): ExitTransition =
    slideOutVertically(
        animationSpec = tween(ImmersiveRowFadeOutMs, easing = ImmersiveRowSlideEasing),
        targetOffsetY = { if (forward) -slideDistance(it) else slideDistance(it) },
    )

/**
 * Which way the shelf is travelling, derived from successive row indices. `AnimatedContent`'s own
 * `initialState`/`targetState` are only in scope inside `transitionSpec`; the body nudge is applied
 * inside the content lambda, which sees only its own row index, so the direction is tracked here
 * and read from both places.
 */
internal class ImmersiveRowDirection {
    private var lastIndex: Int? = null

    /** True when the most recent change moved to a later row. Defaults to forward. */
    var forward: Boolean = true
        private set

    /** Call with the current row index on every composition; updates [forward] on a change. */
    fun observe(index: Int) {
        val last = lastIndex
        if (last != null && index != last) forward = index > last
        lastIndex = index
    }
}

private fun slideDistance(fullHeight: Int): Int = (fullHeight * ImmersiveRowSlideFraction).toInt()

/** Fraction of the row body height the posters travel during a nudge — a few dp, not a page turn. */
private const val ImmersiveRowSlideFraction = 0.028f
private const val ImmersiveRowSlideMs = 380
private const val ImmersiveRowFadeInMs = 240
private const val ImmersiveRowFadeOutMs = 220

/**
 * Gentle S-curve: a soft start, most of the travel in the middle, and a long settle. Softer at both
 * ends than FastOutSlowIn, which still launches quickly enough to feel like a snap at this size.
 */
private val ImmersiveRowSlideEasing = CubicBezierEasing(0.33f, 0f, 0.1f, 1f)
