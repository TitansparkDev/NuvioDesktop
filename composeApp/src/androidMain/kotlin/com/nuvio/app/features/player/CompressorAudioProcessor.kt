@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Dialogue Leveler — a real-time dynamic range compressor that sits in the ExoPlayer PCM
 * chain. It brings quiet speech up and tames loud music/effects so the perceived level stays
 * even without the viewer riding the volume. Implemented as a broadband feed-forward compressor
 * with a soft knee, attack/release smoothing, and make-up gain, followed by a zero-lookahead peak
 * ceiling.
 *
 * The detector is stereo-linked across channels so the stereo image never shifts.
 * Follows [GainAudioProcessor] in staying active for PCM even at level 0 (off), where it only copies,
 * so setting updates apply live to the next buffer without reconfiguring the audio sink.
 */
internal class CompressorAudioProcessor(
    private val levelProvider: () -> Int = { DIALOGUE_LEVELER_OFF },
) : BaseAudioProcessor() {

    @Volatile
    private var level: Int = DIALOGUE_LEVELER_OFF

    @Volatile
    private var thresholdDb: Float = 0f
    @Volatile
    private var ratioSlope: Float = 0f
    @Volatile
    private var makeupDb: Float = 0f
    @Volatile
    private var kneeDb: Float = 1f
    @Volatile
    private var releaseMs: Float = 200f

    @Volatile
    private var attackCoeff: Float = 0f
    @Volatile
    private var releaseCoeff: Float = 0f
    private var envelope: Float = 0f
    private var frameSamples = FloatArray(0)
    @Volatile
    private var resetEnvelopeRequested: Boolean = false

    init {
        val initial = levelProvider()
        if (initial != DIALOGUE_LEVELER_OFF) {
            setLevel(initial)
        }
    }

    fun setLevel(newLevel: Int) {
        val clamped = newLevel.coerceIn(DIALOGUE_LEVELER_OFF, DIALOGUE_LEVELER_MAX)
        if (clamped == level) return
        applyPreset(clamped)
        recomputeCoefficients()
        level = clamped
        resetEnvelopeRequested = true
    }

    fun isLevelerEnabled(): Boolean = level != DIALOGUE_LEVELER_OFF

    private fun applyPreset(presetLevel: Int) {
        val preset = dialogueLevelerPreset(presetLevel)
        thresholdDb = preset.thresholdDb
        ratioSlope = (1f / preset.ratio) - 1f
        makeupDb = preset.makeupDb
        kneeDb = preset.kneeDb.coerceAtLeast(0.01f)
        releaseMs = preset.releaseMs
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        recomputeCoefficients()
        envelope = 0f
        resetEnvelopeRequested = false
    }

    private fun recomputeCoefficients() {
        val sampleRate = inputAudioFormat.sampleRate
        if (sampleRate <= 0) {
            attackCoeff = 0f
            releaseCoeff = 0f
            return
        }
        attackCoeff = timeConstantToCoeff(ATTACK_MS, sampleRate)
        releaseCoeff = timeConstantToCoeff(releaseMs, sampleRate)
    }

    private fun timeConstantToCoeff(timeMs: Float, sampleRate: Int): Float {
        if (timeMs <= 0f) return 0f
        return exp(-1.0 / ((timeMs / 1000.0) * sampleRate)).toFloat()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val currentLevel = levelProvider()
        if (currentLevel != level) {
            setLevel(currentLevel)
        }

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())

        if (level == DIALOGUE_LEVELER_OFF) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        if (resetEnvelopeRequested) {
            envelope = 0f
            resetEnvelopeRequested = false
        }

        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        if (frameSamples.size < channelCount) {
            frameSamples = FloatArray(channelCount)
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, channelCount)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, channelCount)
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, channelCount: Int) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val frameBytes = channelCount * 2

        while (inputBuffer.remaining() >= frameBytes) {
            var peak = 0f
            for (c in 0 until channelCount) {
                val sample = inputBuffer.short.toInt() / 32768f
                frameSamples[c] = sample
                val magnitude = abs(sample)
                if (magnitude > peak) peak = magnitude
            }
            val gain = computeFrameGain(peak)
            for (c in 0 until channelCount) {
                val amplified = frameSamples[c] * gain
                outputBuffer.putShort(
                    (amplified * 32767f).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                )
            }
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun processPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, channelCount: Int) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val frameBytes = channelCount * 4

        while (inputBuffer.remaining() >= frameBytes) {
            var peak = 0f
            for (c in 0 until channelCount) {
                val sample = inputBuffer.float
                frameSamples[c] = sample
                val magnitude = abs(sample)
                if (magnitude > peak) peak = magnitude
            }
            val gain = computeFrameGain(peak)
            for (c in 0 until channelCount) {
                outputBuffer.putFloat(frameSamples[c] * gain)
            }
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun computeFrameGain(peak: Float): Float {
        envelope = if (peak > envelope) {
            attackCoeff * envelope + (1f - attackCoeff) * peak
        } else {
            releaseCoeff * envelope + (1f - releaseCoeff) * peak
        }

        val envDb = 20f * log10f(envelope.coerceAtLeast(1e-6f))
        val over = envDb - thresholdDb
        val reductionDb = when {
            2f * over < -kneeDb -> 0f
            2f * over > kneeDb -> ratioSlope * over
            else -> {
                val kneed = over + kneeDb / 2f
                ratioSlope * (kneed * kneed) / (2f * kneeDb)
            }
        }
        val totalDb = (reductionDb + makeupDb).coerceIn(MIN_TOTAL_GAIN_DB, MAX_TOTAL_GAIN_DB)
        val compressorGain = 10.0.pow(totalDb / 20.0).toFloat()
        val peakSafeGain = if (peak > 0f) MAX_OUTPUT_PEAK / peak else compressorGain
        return min(compressorGain, peakSafeGain)
    }

    private fun log10f(value: Float): Float = (ln(value.toDouble()) / LN_10).toFloat()

    companion object {
        const val ATTACK_MS = 5f
        const val MAX_OUTPUT_PEAK = 0.98f
        const val MIN_TOTAL_GAIN_DB = -24f
        const val MAX_TOTAL_GAIN_DB = 24f
        val LN_10 = ln(10.0)
    }
}
