package com.subbot.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Family of offline speech recognition model driven by Sherpa-ONNX.
 *
 * - WHISPER: autoregressive encoder/decoder (encoder + decoder + tokens files). Supports
 *   task = "translate" (speech -> English).
 * - SENSE_VOICE: non-autoregressive single-file CTC-style model (model + tokens files).
 *   Much faster than Whisper, supports zh/en/ja/ko/yue, transcribe only.
 */
enum class SttModelType(val id: String) {
    WHISPER("whisper"),
    SENSE_VOICE("sense_voice");

    companion object {
        fun fromId(id: String?): SttModelType =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: WHISPER
    }
}

/**
 * Configuration for the Sherpa-ONNX on-device speech recognition engine.
 *
 * Whisper uses [encoderPath] / [decoderPath]; SenseVoice uses the single [modelPath].
 * Both require [tokensPath].
 */
data class SpeechRecognitionConfig(
    val encoderPath: String = "",
    val decoderPath: String = "",
    val tokensPath: String = "",
    val language: String = "en",
    val task: String = "transcribe",
    val numThreads: Int = 4,
    val featureDim: Int = 80,
    val modelType: SttModelType = SttModelType.WHISPER,
    val modelPath: String = "",
    val useInverseTextNormalization: Boolean = true
) {
    companion object {
        /** Language codes understood by SenseVoice-Small (zh-en-ja-ko-yue-2024-07-17). */
        val SENSE_VOICE_LANGUAGES = setOf("zh", "en", "ja", "ko", "yue")
    }

    val effectiveFeatureDim: Int
        get() = if (modelType == SttModelType.SENSE_VOICE) {
            80
        } else if (featureDim != 80) {
            featureDim
        } else if (encoderPath.contains("turbo", ignoreCase = true) || encoderPath.contains("large", ignoreCase = true)) {
            128
        } else {
            80
        }

    /** Language hint passed to SenseVoice: the source language when supported, otherwise "auto". */
    val senseVoiceLanguage: String
        get() = language.lowercase().takeIf { it in SENSE_VOICE_LANGUAGES } ?: "auto"

    /** Paths of every model file this configuration needs, depending on [modelType]. */
    val requiredPaths: List<String>
        get() = when (modelType) {
            SttModelType.WHISPER -> listOf(encoderPath, decoderPath, tokensPath)
            SttModelType.SENSE_VOICE -> listOf(modelPath, tokensPath)
        }

    fun isValid(): Boolean = requiredPaths.all { it.isNotBlank() }

    fun filesExist(): Boolean = isValid() && requiredPaths.all { java.io.File(it).exists() }

    /**
     * Builds the Sherpa-ONNX recognizer configuration for this model.
     * Shared by [SpeechRecognitionEngine.initialize] and the on-device benchmark.
     */
    fun toRecognizerConfig(sampleRate: Int = 16000): OfflineRecognizerConfig {
        val modelConfig = when (modelType) {
            SttModelType.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    language = language,
                    task = task
                ),
                tokens = tokensPath,
                numThreads = numThreads,
                modelType = "whisper"
            )
            SttModelType.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelPath,
                    language = senseVoiceLanguage,
                    useInverseTextNormalization = useInverseTextNormalization
                ),
                tokens = tokensPath,
                numThreads = numThreads
            )
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = effectiveFeatureDim),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
    }
}
