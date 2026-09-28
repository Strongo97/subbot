package com.subbot.audio

import kotlin.math.log10
import kotlin.math.sqrt

object AudioUtils {

    /**
     * Converts a 16-bit little-endian PCM byte array into normalized 32-bit floats [-1.0f, 1.0f].
     *
     * @param pcmBytes The raw PCM byte array.
     * @param sampleCount The number of 16-bit samples to convert.
     * @return FloatArray containing normalized audio samples.
     */
    fun pcm16ToNormalizedFloats(pcmBytes: ByteArray, sampleCount: Int): FloatArray {
        val floats = FloatArray(sampleCount)
        val maxAvailable = minOf(sampleCount, pcmBytes.size / 2)
        for (i in 0 until maxAvailable) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt() shl 8
            val sample = (low or high).toShort()
            floats[i] = (sample / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        return floats
    }

    /**
     * Computes the Root Mean Square (RMS) energy in decibels relative to full scale (dBFS).
     *
     * @param samples Array of audio samples in range [-1.0f, 1.0f].
     * @return Energy in dBFS (e.g., 0 dBFS is maximum volume, -45 dBFS is faint background).
     */
    fun calculateRmsDb(samples: FloatArray): Float {
        if (samples.isEmpty()) return -100.0f

        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }

        val meanSquare = sumSquares / samples.size
        val rms = sqrt(meanSquare)
        if (rms <= 1e-9) return -100.0f

        return (20.0 * log10(rms)).toFloat()
    }

    /**
     * Determines whether an audio frame constitutes silence or background noise.
     *
     * @param rmsDb The RMS decibels.
     * @param thresholdDb The threshold below which audio is treated as silence (default -45.0 dBFS).
     * @return True if silent, false if active speech/audio.
     */
    fun isSilence(rmsDb: Float, thresholdDb: Float = -45.0f): Boolean {
        return rmsDb.isNaN() || rmsDb < thresholdDb
    }
}
