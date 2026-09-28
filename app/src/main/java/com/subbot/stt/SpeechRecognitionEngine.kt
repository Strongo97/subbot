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

        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
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
     * buffers speech until pause or threshold, decodes using Whisper, and emits recognized text.
     */
    suspend fun processStream(
        audioChannel: ReceiveChannel<FloatArray>,
        onUtterance: suspend (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val rec = recognizer ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        // 16kHz mono: 16000 samples = 1 second. Accumulate up to 2.5 seconds (40000 samples).
        val buffer = ArrayList<Float>(40000)
        var silenceChunkCount = 0

        for (chunk in audioChannel) {
            val rms = AudioUtils.calculateRmsDb(chunk)
            val isSilent = AudioUtils.isSilence(rms, thresholdDb = -45.0f)

            if (!isSilent) {
                silenceChunkCount = 0
                for (sample in chunk) {
                    buffer.add(sample)
                }
            } else if (buffer.isNotEmpty()) {
                silenceChunkCount++
                for (sample in chunk) {
                    buffer.add(sample)
                }
            }

            // Trigger decode when:
            // 1. Buffer has at least ~1.0s (16000 samples) and silence pause is detected, or
            // 2. Buffer reached maximum capacity (2.5s = 40000 samples)
            val hasEnoughSpeech = buffer.size >= 16000
            val pauseDetected = silenceChunkCount >= 4 // ~400ms pause
            val bufferFull = buffer.size >= 40000

            if ((hasEnoughSpeech && pauseDetected) || bufferFull) {
                val samplesToDecode = buffer.toFloatArray()
                buffer.clear()
                silenceChunkCount = 0

                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samplesToDecode, 16000)
                    rec.decode(stream)
                    val result = rec.getResult(stream).text.trim()
                    if (result.isNotBlank()) {
                        onUtterance(result)
                    }
                } finally {
                    stream.release()
                }
            }
        }

        // Flush remaining buffer if any
        if (buffer.size >= 8000) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(buffer.toFloatArray(), 16000)
                rec.decode(stream)
                val result = rec.getResult(stream).text.trim()
                if (result.isNotBlank()) {
                    onUtterance(result)
                }
            } finally {
                stream.release()
            }
            buffer.clear()
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
