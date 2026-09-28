package com.subbot.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/**
 * Speech-to-Text inference engine powered by Sherpa-ONNX Whisper offline models.
 */
class SpeechRecognitionEngine(
    private val config: SpeechRecognitionConfig,
    private val assetManager: AssetManager? = null,
    /** Path to silero_vad.onnx; null/missing file falls back to energy-based speech detection. */
    private val vadModelPath: String? = null
) {
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var activeDetector: SpeechDetector? = null
    @Volatile private var isReleased = false
    private val engineLock = Any()

    companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_CHUNK = 1600 // 100ms
        const val MIN_SPEECH_SAMPLES = 20800 // ~1.3s minimum context before pause split (faster response)
        const val TARGET_SPEECH_SAMPLES = 48000 // ~3.0s ideal clause length
        const val MAX_BUFFER_SAMPLES = 96000 // ~6.0s maximum buffer before energy split
        const val SILENCE_THRESHOLD_DB = -40.0f // Filter out quiet ambient noise, hiss, and video BGM

        val KNOWN_HALLUCINATIONS = setOf(
            "ご視聴ありがとうございました",
            "ご視聴いただきありがとうございました",
            "視聴ありがとうございました",
            "ご覧いただきありがとうございます",
            "動画をご視聴いただき",
            "チャンネル登録",
            "チャンネル登録よろしく",
            "チャンネル登録お願いします",
            "高評価チャンネル登録",
            "お疲れ様でした",
            "おやすみなさい",
            "最後までご視聴",
            "thank you for watching",
            "thanks for watching",
            "thank you very much for watching",
            "thank you",
            "please subscribe",
            "like and subscribe",
            "subtitles by",
            "amara.org",
            "translated by"
        )

        /**
         * Cleans trailing ellipses, leading dashes, blank prompt tags, and formatting artifacts.
         */
        fun cleanUtterance(raw: String): String {
            return raw
                .replace(Regex("^\\[.*?\\]"), "") // remove [BLANK_AUDIO] or [MUSIC]
                .replace(Regex("^[\\s.,…—\\-~。、]+"), "") // remove leading punctuation, ellipses, dashes
                .replace(Regex("[\\s.,…—\\-~。、]+$"), "") // remove trailing ellipses, periods, dashes
                .replace(Regex("\\.{2,}"), ".") // replace multiple internal dots with single period
                .trim()
        }

        /**
         * Detects common Whisper hallucination artifacts generated on silence, background music, or noise.
         */
        fun isHallucination(text: String): Boolean {
            val lower = text.lowercase().trim()
            if (lower.length <= 1) return true
            // Check known phantom Whisper hallucination phrases on quiet/music segments
            if (KNOWN_HALLUCINATIONS.any { lower.contains(it) } && lower.length < 50) return true
            // Check for repeated character sequences (e.g. "ああああああ" or "you you you")
            if (Regex("(.)\\1{4,}").containsMatchIn(text)) return true
            if (Regex("(.{2})\\1{3,}").containsMatchIn(text)) return true
            // Check for repeated words (e.g. "you you you you")
            if (Regex("(?i)\\b(\\w+)(\\s+\\1){3,}\\b").containsMatchIn(text)) return true
            return false
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

        val featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = config.effectiveFeatureDim)
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
     * Consumes audio float chunks (16kHz mono) and emits recognized utterances.
     *
     * Runs a three-stage pipeline (see [SpeechPipeline]): speech segmentation (Silero VAD when
     * [vadModelPath] points to a usable model, RMS energy gating otherwise) -> Whisper decoding ->
     * [onUtterance] delivery. Each stage runs in its own coroutine so a slow decode never stalls
     * audio intake and a slow [onUtterance] never stalls decoding.
     */
    suspend fun processStream(
        audioChannel: ReceiveChannel<FloatArray>,
        onUtterance: suspend (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val rec = recognizer ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        val detector = createSpeechDetector()
        activeDetector = detector
        try {
            SpeechPipeline().run(
                audio = audioChannel,
                segmenter = SpeechSegmenter(detector),
                decode = { samples -> decodeSegment(rec, samples) },
                onUtterance = onUtterance,
                isStopped = { isReleased }
            )
        } finally {
            activeDetector = null
            detector.close()
        }
    }

    /**
     * Silero VAD if the model file is present and loads, otherwise the energy-gating fallback.
     */
    private fun createSpeechDetector(): SpeechDetector {
        val path = vadModelPath
        if (!path.isNullOrBlank() && java.io.File(path).let { it.exists() && it.length() > 0 }) {
            try {
                val vad = SileroVadSpeechDetector.create(path)
                SttLog.i("Speech detector: Silero VAD ($path)")
                return vad
            } catch (e: Throwable) {
                SttLog.e("Failed to load Silero VAD from $path; using energy gating", e)
            }
        } else {
            SttLog.i("Silero VAD model not available; using energy gating")
        }
        return EnergySpeechDetector()
    }

    /**
     * Decodes one speech segment with Whisper. Returns the cleaned utterance, or null if the
     * engine was released, the result is blank, or it matches a known hallucination.
     * Holds [engineLock] for the whole native call so [release] cannot free the recognizer mid-decode.
     */
    private fun decodeSegment(rec: OfflineRecognizer, samples: FloatArray): String? {
        if (isReleased) return null

        val cleaned = synchronized(engineLock) {
            if (isReleased || recognizer == null) return null
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val rawText = rec.getResult(stream).text.trim()
                cleanUtterance(rawText)
            } catch (e: Throwable) {
                android.util.Log.e("SpeechRecognitionEngine", "Error during decode", e)
                ""
            } finally {
                try {
                    stream.release()
                } catch (e: Throwable) {
                    // Ignore
                }
            }
        }

        return if (cleaned.isNotBlank() && !isHallucination(cleaned) && !isReleased) cleaned else null
    }

    fun release() {
        synchronized(engineLock) {
            isReleased = true
            try {
                recognizer?.release()
            } catch (e: Throwable) {
                android.util.Log.e("SpeechRecognitionEngine", "Error releasing recognizer", e)
            }
            recognizer = null
        }
        // Detector has its own lock; closing it here waits for any in-flight VAD call to finish.
        activeDetector?.close()
    }
}
