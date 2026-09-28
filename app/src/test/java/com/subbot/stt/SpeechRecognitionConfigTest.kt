package com.subbot.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionConfigTest {

    @Test
    fun testSpeechRecognitionConfig_validatesModelFiles() {
        val validConfig = SpeechRecognitionConfig(
            encoderPath = "whisper/encoder.int8.onnx",
            decoderPath = "whisper/decoder.int8.onnx",
            tokensPath = "whisper/tokens.txt",
            language = "en",
            task = "transcribe"
        )
        assertTrue(validConfig.isValid())

        val invalidConfig = SpeechRecognitionConfig(
            encoderPath = "",
            decoderPath = "whisper/decoder.int8.onnx",
            tokensPath = "whisper/tokens.txt"
        )
        assertFalse(invalidConfig.isValid())
    }
}
