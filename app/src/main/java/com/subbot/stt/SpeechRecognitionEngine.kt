package com.subbot.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.subbot.audio.AudioUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/**
 * Speech-to-Text inference engine powered by Sherpa-ONNX Whisper offline models.
 */
class SpeechRecognitionEngine(
    private val config: SpeechRecognitionConfig,
    private val assetManager: AssetManager? = null
) {
    private var recognizer: OfflineRecognizer? = null

    companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_CHUNK = 1600 // 100ms
        const val MIN_SPEECH_SAMPLES = 20800 // ~1.3s minimum context before pause split
        const val TARGET_SPEECH_SAMPLES = 56000 // ~3.5s ideal clause length
        const val MAX_BUFFER_SAMPLES = 96000 // ~6.0s maximum buffer before energy split
        const val SILENCE_THRESHOLD_DB = -43.0f

        /**
         * Cleans trailing ellipses, leading dashes, blank prompt tags, and formatting artifacts.
         */
        fun cleanUtterance(raw: String): String {
            return raw
                .replace(Regex("^\\[.*?\\]"), "") // remove [BLANK_AUDIO] or [MUSIC]
                .replace(Regex("^[\\s.,…—\\-~]+"), "") // remove leading punctuation, ellipses, dashes
                .replace(Regex("[\\s.,…—\\-~]+$"), "") // remove trailing ellipses, periods, dashes
                .replace(Regex("\\.{2,}"), ".") // replace multiple internal dots with single period
                .trim()
        }
    }

    /**
     * Initializes the Sherpa-ONNX offline Whisper recognizer.
     */
    fun initialize() {
        if (!config.isValid()) {
            throw IllegalArgumentException("Invalid SpeechRecognitionConfig: required paths cannot be empty")
        }

        val whisperConfig = OfflineWhisperModelConfig(
            encoder = config.encoderPath,
            decoder = config.decoderPath,
            language = config.language,
            task = config.task
        )

        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = config.tokensPath,
            numThreads = config.numThreads,
            modelType = "whisper"
        )

        val featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)
        val recognizerConfig = OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        recognizer = OfflineRecognizer(
            assetManager = assetManager,
            config = recognizerConfig
        )
    }

    /**
     * Consumes audio float chunks (16kHz mono), performs VAD / RMS energy gating,
     * buffers speech until natural pause or boundary, decodes using Whisper, and emits recognized text.
     */
    suspend fun processStream(
        audioChannel: ReceiveChannel<FloatArray>,
        onUtterance: suspend (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val rec = recognizer ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        val buffer = ArrayList<Float>(100000)
        val chunkEnergies = ArrayList<Float>()
        var trailingSilenceCount = 0
        var hasSpeech = false

        for (chunk in audioChannel) {
            val rms = AudioUtils.calculateRmsDb(chunk)
            val isSilent = AudioUtils.isSilence(rms, thresholdDb = SILENCE_THRESHOLD_DB)

            if (!isSilent) {
                hasSpeech = true
                trailingSilenceCount = 0
                for (sample in chunk) buffer.add(sample)
                chunkEnergies.add(rms)
            } else if (hasSpeech) {
                trailingSilenceCount++
                for (sample in chunk) buffer.add(sample)
                chunkEnergies.add(rms)
            }

            // Pause-based boundary conditions:
            // 1. Natural clause boundary: >= 1.3s of speech followed by >= 350ms pause (4 chunks)
            val naturalPause = buffer.size >= MIN_SPEECH_SAMPLES && trailingSilenceCount >= 4
            // 2. Longer sentence boundary: >= 3.5s of speech followed by >= 200ms pause (2 chunks)
            val sentencePause = buffer.size >= TARGET_SPEECH_SAMPLES && trailingSilenceCount >= 2
            // 3. Extended speech without pause reaching maximum capacity (6.0s)
            val bufferFull = buffer.size >= MAX_BUFFER_SAMPLES

            if (naturalPause || sentencePause) {
                val samplesToDecode = buffer.toFloatArray()
                buffer.clear()
                chunkEnergies.clear()
                trailingSilenceCount = 0
                hasSpeech = false

                decodeAndEmit(rec, samplesToDecode, onUtterance)
            } else if (bufferFull) {
                // Find lowest energy chunk in the trailing 2.0s (up to 20 chunks) to split cleanly at a word boundary
                val searchStart = maxOf(0, chunkEnergies.size - 20)
                var minEnergyIndex = searchStart
                var minEnergy = Float.MAX_VALUE
                for (i in searchStart until chunkEnergies.size) {
                    if (chunkEnergies[i] < minEnergy) {
                        minEnergy = chunkEnergies[i]
                        minEnergyIndex = i
                    }
                }

                val splitSample = minOf((minEnergyIndex + 1) * SAMPLES_PER_CHUNK, buffer.size)
                val samplesToDecode = buffer.subList(0, splitSample).toFloatArray()

                // Carry over the audio after the split point into the next window
                val remaining = buffer.subList(splitSample, buffer.size).toList()
                val remainingEnergies = chunkEnergies.subList(minEnergyIndex + 1, chunkEnergies.size).toList()

                buffer.clear()
                buffer.addAll(remaining)
                chunkEnergies.clear()
                chunkEnergies.addAll(remainingEnergies)
                trailingSilenceCount = 0
                hasSpeech = buffer.isNotEmpty()

                decodeAndEmit(rec, samplesToDecode, onUtterance)
            }
        }

        // Flush remaining buffer if sufficient audio exists
        if (buffer.size >= 8000) {
            decodeAndEmit(rec, buffer.toFloatArray(), onUtterance)
            buffer.clear()
            chunkEnergies.clear()
        }
    }

    private suspend fun decodeAndEmit(
        rec: OfflineRecognizer,
        samples: FloatArray,
        onUtterance: suspend (String) -> Unit
    ) {
        if (samples.size < 8000) return // Skip tiny acoustic clicks (< 0.5s)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val rawText = rec.getResult(stream).text.trim()
            val cleaned = cleanUtterance(rawText)
            if (cleaned.isNotBlank()) {
                onUtterance(cleaned)
            }
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
