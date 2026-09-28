package com.subbot.stt

/**
 * Configuration for the Sherpa-ONNX Whisper on-device speech recognition engine.
 */
data class SpeechRecognitionConfig(
    val encoderPath: String = "",
    val decoderPath: String = "",
    val tokensPath: String = "",
    val language: String = "en",
    val task: String = "transcribe",
    val numThreads: Int = 2
) {
    fun isValid(): Boolean {
        return encoderPath.isNotBlank() &&
               decoderPath.isNotBlank() &&
               tokensPath.isNotBlank()
    }
}
