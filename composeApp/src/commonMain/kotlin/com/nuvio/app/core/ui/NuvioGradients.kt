package com.nuvio.app.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI

fun nuvioOverlayGradientBrush(): Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to Color(0xFF21113B),
        0.12f to Color(0xFF21113B),
        0.24f to Color(0xFF1A0E2F),
        0.34f to Color(0xFF130A23),
        0.44f to Color(0xFF0A060F),
        0.58f to Color(0xFF050408),
        0.64f to Color.Black,
        1f to Color.Black,
    ),
    start = Offset(0f, 0f),
    end = Offset(1000f, 1600f),
)

/**
 * The HTPC mark's own palette: the night-sky icon is a near-black navy that lifts to a deep blue
 * along its haze band and settles into indigo at the tip, so the launch wash uses the same three —
 * a blue glow off one corner, an indigo one off the other, and a slate haze bridging them.
 *
 * Fixed brand colours rather than the theme accent — the launch screen is the one place the
 * wordmark is on its own, and it should look like the icon the user just clicked whatever accent
 * they run the rest of the app in.
 */
object NuvioBrandRamp {
    val Horizon = Color(0xFF224C82)
    val Haze = Color(0xFF4E5C88)
    val Zenith = Color(0xFF362B74)
}

/**
 * A seamless 0f..1f phase for [nuvioLaunchBackdrop] to drive its drift from.
 *
 * Linear rather than eased, and consumed only through [sin]/[cos], so the loop closes on itself
 * with no visible jump at the wrap — the overlay is often on screen for less than one period, and a
 * seam would be the only part of the motion anyone actually noticed.
 */
@Composable
fun rememberNuvioLaunchDrift(): State<Float> {
    val transition = rememberInfiniteTransition(label = "launch_drift")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LaunchDriftPeriodMs, easing = LinearEasing),
        ),
        label = "launch_drift_phase",
    )
}

/** One full drift cycle. Long enough that the motion registers as a wash rather than a loop. */
private const val LaunchDriftPeriodMs = 20_000

/**
 * Paints [base] under two soft [NuvioBrandRamp] glows anchored at opposite corners, with a faint
 * slate haze bridging them the way the icon's band does and a sparse field of slowly twinkling
 * stars, all drifting on [phase] from [rememberNuvioLaunchDrift].
 *
 * Deliberately dim: the peaks land around #101823 and #161425 over a `#0D0D0D` background, so the
 * screen reads as tinted rather than lit, and the centre - where the wordmark and spinner sit -
 * stays the darkest part of the frame. The stars are the one thing allowed to be bright, and they
 * are a pixel or two across.
 *
 * The two glows drift in antiphase and the radius swells against them, so the wash never settles
 * into an obvious back-and-forth. [phase] is read inside the draw lambda rather than captured at
 * composition: this animates in the draw phase alone, which matters because it runs while startup
 * is still contending for the first frames.
 *
 * One draw, not four. A wash this dim spread across a whole screen moves through a level every
 * hundred-odd pixels, which is squarely in the range that reads as rings, and stacking the base,
 * both glows and the bridge as separate translucent rects quantised the result to 8 bits four
 * times over - each layer contouring against the one below it. [nuvioLaunchWashBrush] composites
 * the same four things in one shader and dithers once, at the end.
 */
fun Modifier.nuvioLaunchBackdrop(base: Color, phase: State<Float>): Modifier = drawBehind {
    drawRect(nuvioLaunchWashBrush(base, phase.value * 2f * PI.toFloat()))
}

/**
 * The whole launch wash - base, both corner glows, the haze bridge and the stars - as one brush,
 * with a level of noise mixed in before the result is written out.
 *
 * [phase] is in radians, already unwrapped from the 0..1 drift. The dither has to perturb the value
 * being rounded, so it cannot be a separate pass over the top; see [nuvioDitheredGradient], which
 * is the same technique at panel scale.
 */
internal expect fun nuvioLaunchWashBrush(base: Color, phase: Float): Brush
