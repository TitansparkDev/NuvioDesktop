package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressorAudioProcessorTest {

    @Test
    fun `off passes through unmodified`() {
        val processor = configuredProcessor(level = DIALOGUE_LEVELER_OFF)
        val samples = floatArrayOf(0.1f, -0.2f, 0.5f, -0.8f)
        val output = processFloatMono(processor, samples)

        assertEquals(samples.size, output.size)
        for (i in samples.indices) {
            assertEquals(samples[i], output[i], 0.0001f)
        }
    }

    @Test
    fun `max substantially narrows quiet to loud gap`() {
        val quietPeak = steadyStatePeak(level = DIALOGUE_LEVELER_MAX, inputAmplitude = 0.01f)
        val loudPeak = steadyStatePeak(level = DIALOGUE_LEVELER_MAX, inputAmplitude = 0.5f)
        val outputGapDb = 20.0 * log10((loudPeak / quietPeak).toDouble())

        // The source gap is about 34 dB. Max should bring it well below 20 dB.
        assertTrue("output gap was $outputGapDb dB", outputGapDb < 20.0)
        assertTrue("quiet speech was not lifted", quietPeak > 0.03f)
    }

    @Test
    fun `makeup gain cannot clip a sudden full scale transient`() {
        val processor = configuredProcessor(level = DIALOGUE_LEVELER_MAX)
        val samples = FloatArray(4_096) { index -> if (index < 2_048) 0.01f else 1.0f }
        val output = processFloatMono(processor, samples)

        assertTrue(output.all { it.isFinite() })
        assertTrue("peak was ${output.maxOf { abs(it) }}", output.maxOf { abs(it) } <= 0.98001f)
    }

    @Test
    fun `linked detector preserves stereo balance`() {
        val processor = configuredProcessor(level = DIALOGUE_LEVELER_MAX, channelCount = 2)
        val input = ByteBuffer.allocateDirect(4_096 * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        repeat(4_096) {
            input.putFloat(0.8f)
            input.putFloat(0.1f)
        }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        var left = 0f
        var right = 0f
        while (output.remaining() >= 2 * Float.SIZE_BYTES) {
            left = output.float
            right = output.float
        }
        assertEquals(8f, left / right, 0.001f)
    }

    @Test
    fun `mpv filter mirrors preset and includes named lookahead limiter`() {
        assertNull(buildMpvDialogueLevelerFilter(DIALOGUE_LEVELER_OFF))

        val filter = requireNotNull(buildMpvDialogueLevelerFilter(DIALOGUE_LEVELER_MAX))
        assertTrue(filter.startsWith("@$MPV_DIALOGUE_LEVELER_FILTER_LABEL:lavfi=[acompressor="))
        assertTrue(filter.contains(":ratio=5.0"))
        assertTrue(filter.contains(":makeup=3.981071"))
        assertTrue(filter.contains(":link=maximum:detection=peak,"))
        assertTrue(filter.contains("alimiter=limit=0.98"))
        assertTrue(filter.contains(":level=0:latency=1"))
    }

    private fun steadyStatePeak(level: Int, inputAmplitude: Float): Float {
        val processor = configuredProcessor(level)
        val output = processFloatMono(processor, FloatArray(48_000) { inputAmplitude })
        return output.takeLast(4_800).maxOf { abs(it) }
    }

    private fun configuredProcessor(
        level: Int,
        channelCount: Int = 1,
    ): CompressorAudioProcessor {
        return CompressorAudioProcessor { level }.apply {
            setLevel(level)
            configure(AudioProcessor.AudioFormat(48_000, channelCount, C.ENCODING_PCM_FLOAT))
            flush()
        }
    }

    private fun processFloatMono(
        processor: CompressorAudioProcessor,
        samples: FloatArray,
    ): FloatArray {
        val input = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach(input::putFloat)
        input.flip()
        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.nativeOrder())
        return FloatArray(output.remaining() / Float.SIZE_BYTES) { output.float }
    }
}
