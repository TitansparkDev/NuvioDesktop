package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.Bitmap
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface

@Composable
internal actual fun rememberAmbientBackdropModel(imageUrl: String, blurRadius: Dp): Any {
    val context = LocalPlatformContext.current
    val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
    return remember(context, imageUrl, radiusPx) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .transformations(AmbientBlurTransformation(radiusPx))
            .build()
    }
}

/**
 * Gaussian blur applied once, on Coil's decode thread, to the already-downsampled backdrop.
 *
 * [cacheKey] carries the radius, so this shares neither memory- nor disk-cache entries with the
 * unblurred backdrop the hero itself draws — the same URL legitimately yields two different bitmaps.
 */
private class AmbientBlurTransformation(private val radiusPx: Float) : Transformation() {

    override val cacheKey: String = "nuvio:ambient-blur:$radiusPx"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        blurAmbientBitmap(input, radiusPx)

    override fun equals(other: Any?): Boolean =
        other is AmbientBlurTransformation && other.radiusPx == radiusPx

    override fun hashCode(): Int = radiusPx.hashCode()
}

/**
 * The blur itself, separated from the [Transformation] wrapper so it can be compared against
 * `Modifier.blur` directly — `AmbientBackdropBlurTest` renders both and measures the difference,
 * because "the same blur, just earlier" is exactly the kind of claim that turns out not to be true.
 */
internal fun blurAmbientBitmap(input: Bitmap, radiusPx: Float): Bitmap {
    if (radiusPx <= 0f || input.width <= 0 || input.height <= 0) return input
    // Compose's own radius-to-sigma conversion, so swapping `Modifier.blur` for this does not
    // change how strong the blur looks.
    val sigma = 0.57735f * radiusPx + 0.5f
    val surface = Surface.makeRasterN32Premul(input.width, input.height)
    try {
        val paint = Paint().apply {
            // Clamp rather than decay to transparent at the edges: the result is stretched to fill
            // the window, so a softened border would show as a dark frame around it.
            imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
        }
        Image.makeFromBitmap(input).use { image ->
            surface.canvas.drawImage(image, 0f, 0f, paint)
        }
        val output = Bitmap().apply { allocPixels(input.imageInfo) }
        return if (surface.readPixels(output, 0, 0)) {
            output.setImmutable()
            output
        } else {
            input
        }
    } finally {
        surface.close()
    }
}
