package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * Builds the image request for the ambient backdrop with the blur applied **at decode time**.
 *
 * `Modifier.blur` is a render effect: it re-runs every frame the layer is drawn, and while a
 * `Crossfade` is transitioning it re-runs on both branches. Measured 2026-09-07, that made the blur
 * 3.32 ms of a 4.15 ms wash and took P95 from 7.9 ms to 21.0 ms — see the comment above
 * `AmbientBlurRadiusDp`. Nothing about the blurred backdrop depends on the frame, though: it is a
 * fixed picture for as long as its URL is, so it belongs in the image pipeline rather than the draw
 * loop, where it is computed once on a background thread and kept in the memory cache like any other
 * decoded artwork.
 *
 * The blur only. The saturation stays a draw-time `ColorFilter` on purpose: baking it in would also
 * change what [com.nuvio.app.features.home.extractHeroAccentColor] sees, and the accent drives the
 * whole tint.
 */
@Composable
internal expect fun rememberAmbientBackdropModel(imageUrl: String, blurRadius: Dp): Any
