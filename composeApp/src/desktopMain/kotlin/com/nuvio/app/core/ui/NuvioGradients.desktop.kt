package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The launch wash as one Skia shader: the background, a blue glow off the top-left, an indigo one
 * off the bottom-right, the slate haze between them, a star field, and a dither.
 *
 * The glows are the radial ramps this used to draw as separate `Brush.radialGradient` rects,
 * written out longhand. Skia interpolates gradient stops premultiplied — measured, not assumed —
 * so a stop fading to transparent black holds its own colour all the way out and only its alpha
 * falls: each ramp here is a constant tint under a piecewise-linear alpha, which is what makes the
 * shader land on the same pixels the four rects did. Compositing all four in one pass is what
 * removes three of the four 8-bit roundings; the dither removes the contour from the one left.
 *
 * The noise is interleaved gradient noise (Jimenez), the same cheap `fract` chain the panel fill
 * uses: well spread spatially, no visible tiling, and scaled to half a level so it scatters each
 * quantisation boundary across the pixels either side of it instead of adding a texture of its own.
 *
 * The stars: one candidate per cell of a grid, kept for about a third of the cells, each with its
 * own position inside the cell, size, brightness and twinkle rate from a hash of the cell. The
 * twinkle rates are whole multiples of the drift so the 20s loop still closes without a seam. The
 * grid is scaled with the frame so a 4K screen gets the same field as 1080p, not four times as many
 * stars at half the size.
 *
 * `phase` arrives in radians so the drift's `sin`/`cos` can be taken here rather than passed in as
 * four separate uniforms.
 */
private const val LAUNCH_WASH_SKSL = """
uniform float2 size;
uniform float phase;
uniform float3 base;
uniform float3 horizon;
uniform float3 zenith;
uniform float3 haze;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float3 over(float3 dst, float3 srcRgb, float srcAlpha) {
    return dst * (1.0 - srcAlpha) + srcRgb * srcAlpha;
}

// One radial glow: [tint] at [innerAlpha] in the middle, [midAlpha] at 45% of the reach, gone by
// the edge.
float3 glow(float3 dst, float2 coord, float2 center, float reach, float3 tint, float innerAlpha, float midAlpha) {
    float u = clamp(distance(coord, center) / reach, 0.0, 1.0);
    float a = u < 0.45
        ? mix(innerAlpha, midAlpha, u / 0.45)
        : mix(midAlpha, 0.0, (u - 0.45) / 0.55);
    return over(dst, tint, a);
}

half4 main(float2 coord) {
    float reach = max(size.x, size.y) * (0.95 + 0.05 * sin(phase));
    float3 c = base;

    c = glow(
        c,
        coord,
        float2(size.x * (0.12 + 0.07 * sin(phase)), size.y * (0.04 + 0.06 * cos(phase))),
        reach,
        horizon,
        0.20,
        0.06
    );
    c = glow(
        c,
        coord,
        float2(size.x * (0.88 - 0.07 * sin(phase)), size.y * (0.98 - 0.06 * cos(phase))),
        reach,
        zenith,
        0.22,
        0.07
    );

    // The bridge: transparent at both corners, slate at a drifting point in between.
    float peak = 0.5 + 0.12 * sin(phase);
    float u = clamp(dot(coord, size) / max(dot(size, size), 1.0), 0.0, 1.0);
    float k = u < peak ? u / peak : 1.0 - (u - peak) / (1.0 - peak);
    c = over(c, haze, 0.07 * k);

    // Stars. Each cell's candidate is checked from the eight neighbours too, so a star sitting
    // near a cell edge is not clipped by it.
    float px = max(size.x, size.y) / 1920.0;
    float cellSize = 64.0 * px;
    float2 cell = floor(coord / cellSize);
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            float2 n = cell + float2(float(dx), float(dy));
            if (hash(n) > 0.34) continue;
            float2 pos = (n + float2(hash(n + 11.0), hash(n + 23.0))) * cellSize;
            float radius = (0.7 + 1.1 * hash(n + 37.0)) * px;
            float d = distance(coord, pos);
            if (d > radius * 3.0) continue;
            float rate = floor(2.0 + 4.0 * hash(n + 53.0));
            float twinkle = 0.7 + 0.3 * sin(phase * rate + 6.2832 * hash(n + 71.0));
            float bright = (0.25 + 0.75 * hash(n + 89.0)) * twinkle;
            c += exp(-(d * d) / (radius * radius)) * bright * float3(0.86, 0.91, 1.0);
        }
    }

    float n = fract(52.9829189 * fract(dot(coord, float2(0.06711056, 0.00583715))));
    c += (n - 0.5) / 255.0;
    return half4(half3(c), 1.0);
}
"""

/** Compiled once: the SkSL is constant, and it is rebuilt every frame while the overlay is up. */
private val launchWashEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(LAUNCH_WASH_SKSL) }

internal actual fun nuvioLaunchWashBrush(base: Color, phase: Float): Brush = LaunchWash(base, phase)

private class LaunchWash(private val base: Color, private val phase: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val builder = RuntimeShaderBuilder(launchWashEffect)
        builder.uniform("size", size.width, size.height)
        builder.uniform("phase", phase)
        builder.uniform("base", base.red, base.green, base.blue)
        builder.uniform("horizon", NuvioBrandRamp.Horizon.red, NuvioBrandRamp.Horizon.green, NuvioBrandRamp.Horizon.blue)
        builder.uniform("zenith", NuvioBrandRamp.Zenith.red, NuvioBrandRamp.Zenith.green, NuvioBrandRamp.Zenith.blue)
        builder.uniform("haze", NuvioBrandRamp.Haze.red, NuvioBrandRamp.Haze.green, NuvioBrandRamp.Haze.blue)
        return builder.makeShader().asComposeShader()
    }

    override fun equals(other: Any?): Boolean =
        other is LaunchWash && other.base == base && other.phase == phase

    override fun hashCode(): Int = 31 * base.hashCode() + phase.hashCode()

    override fun toString(): String = "LaunchWash(base=$base, phase=$phase)"
}
