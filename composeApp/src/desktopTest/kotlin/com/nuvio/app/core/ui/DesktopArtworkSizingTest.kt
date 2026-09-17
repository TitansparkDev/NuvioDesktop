package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopArtworkSizingTest {
    @Test
    fun `layout uses physical pixels and updates the request when density or card size changes`() = runComposeUiTest {
        val resolver = DesktopArtworkSizeResolver()
        val density = mutableStateOf(1f)
        val cardWidth = mutableStateOf(210)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density.value)) {
                // requiredSize, not size: the test scene's own 1024x768 constraints would
                // otherwise clamp the tall card and the resolver would read the clamp, not the card.
                Box(Modifier.requiredSize(cardWidth.value.dp, (cardWidth.value * 1.5f).dp).then(resolver))
            }
        }
        waitForIdle()
        assertEquals(Size(224, 328), resolver.requestSize)
        runOnIdle { density.value = 2f }
        waitForIdle()
        assertEquals(Size(440, 656), resolver.requestSize)
        runOnIdle { cardWidth.value = 300 }
        waitForIdle()
        assertEquals(Size(624, 936), resolver.requestSize)
    }

    @Test
    fun `an unbounded axis stays intrinsic and focus headroom is never undersized`() {
        assertEquals(Dimension.Undefined, desktopArtworkSize(Size(Dimension.Pixels(210), Dimension.Undefined)).height)
        assertEquals(Dimension.Pixels(224), desktopArtworkSize(Size(Dimension.Pixels(210), Dimension.Undefined)).width)
        // Neither axis bounded would otherwise reach the decoder as Size.ORIGINAL.
        assertEquals(Size(1536, 1536), desktopArtworkSize(Size.ORIGINAL))
        for (pixels in listOf(34, 52, 210, 420, 630, 1920, 3840)) {
            assertTrue(desktopArtworkDimension(pixels) >= pixels * 1.04)
            assertTrue(desktopArtworkDimension(pixels) < pixels * 1.04 + 8)
        }
    }

    @Test
    fun `small artwork decodes separately from a large cached source and reuses its own entry`() = runBlocking {
        val file = Files.createTempFile("nuvio-artwork-test", ".png").toFile()
        ImageIO.write(BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB), "png", file)
        val context = PlatformContext.INSTANCE
        val loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build() }
            .components {
                add(DesktopArtworkSizeInterceptor())
                add(HighQualityBitmapDecoder.Factory())
            }.build()
        try {
            val large = loader.execute(ImageRequest.Builder(context).data(file)
                .memoryCacheKey("same-artwork").size(800, 1200).build()) as SuccessResult
            val request = ImageRequest.Builder(context).data(file).memoryCacheKey("same-artwork")
                .scale(Scale.FILL).nuvioArtworkRequestSize(250, 375).build()
            val small = loader.execute(request) as SuccessResult
            assertTrue(small.image.width in 260..270)
            assertTrue(small.image.height in 390..405)
            assertTrue(small.image.size < large.image.size / 4)
            assertNotEquals(large.memoryCacheKey, small.memoryCacheKey)
            assertEquals(DataSource.MEMORY_CACHE, (loader.execute(request) as SuccessResult).dataSource)
            // Neither size variant invalidates the other, including explicit caller cache keys.
            assertEquals(800, large.image.width)
            val before = loader.memoryCache!!.size
            val prefetch = loader.execute(ImageRequest.Builder(context).data(file)
                .nuvioArtworkRequestSize().build()) as SuccessResult
            assertEquals(before, loader.memoryCache!!.size)
            assertTrue(prefetch.image.size < 1024)
        } finally {
            loader.shutdown()
            file.delete()
        }
    }
}
