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

    @Test
    fun testFeatureDim_detectionAndDefaults() {
        val tinyConfig = SpeechRecognitionConfig(
            encoderPath = "whisper/tiny-encoder.int8.onnx",
            decoderPath = "whisper/tiny-decoder.int8.onnx",
            tokensPath = "whisper/tiny-tokens.txt"
        )
        org.junit.Assert.assertEquals(80, tinyConfig.effectiveFeatureDim)

        val baseConfig = SpeechRecognitionConfig(
            encoderPath = "whisper/base-encoder.int8.onnx",
            decoderPath = "whisper/base-decoder.int8.onnx",
            tokensPath = "whisper/base-tokens.txt"
        )
        org.junit.Assert.assertEquals(80, baseConfig.effectiveFeatureDim)

        val turboConfig = SpeechRecognitionConfig(
            encoderPath = "whisper/turbo-encoder.int8.onnx",
            decoderPath = "whisper/turbo-decoder.int8.onnx",
            tokensPath = "whisper/turbo-tokens.txt"
        )
        org.junit.Assert.assertEquals(128, turboConfig.effectiveFeatureDim)

        val explicitConfig = SpeechRecognitionConfig(
            encoderPath = "whisper/custom.onnx",
            decoderPath = "whisper/custom.onnx",
            tokensPath = "whisper/custom.txt",
            featureDim = 128
        )
        org.junit.Assert.assertEquals(128, explicitConfig.effectiveFeatureDim)

        org.junit.Assert.assertEquals(80, WhisperModelTier.TINY.featureDim)
        org.junit.Assert.assertEquals(80, WhisperModelTier.BASE.featureDim)
        org.junit.Assert.assertEquals(80, WhisperModelTier.SMALL.featureDim)
        org.junit.Assert.assertEquals(128, WhisperModelTier.TURBO.featureDim)
    }

    @Test
    fun testCleanUtterance_stripsEllipsesAndArtifacts() {
        org.junit.Assert.assertEquals("hello world", SpeechRecognitionEngine.cleanUtterance("...hello world..."))
        org.junit.Assert.assertEquals("hello world", SpeechRecognitionEngine.cleanUtterance("…hello world…"))
        org.junit.Assert.assertEquals("", SpeechRecognitionEngine.cleanUtterance("..."))
        org.junit.Assert.assertEquals("thank you", SpeechRecognitionEngine.cleanUtterance("[BLANK_AUDIO] thank you"))
        org.junit.Assert.assertEquals("Today is great", SpeechRecognitionEngine.cleanUtterance("--- Today is great ---"))
    }

    @Test
    fun testIsHallucination_detectsPhantomPhrasesAndRepetitions() {
        // Known phantom phrases on quiet/silence/BGM
        assertTrue(SpeechRecognitionEngine.isHallucination("ご視聴ありがとうございました"))
        assertTrue(SpeechRecognitionEngine.isHallucination("チャンネル登録よろしくお願いします"))
        assertTrue(SpeechRecognitionEngine.isHallucination("Thank you for watching."))
        assertTrue(SpeechRecognitionEngine.isHallucination("Subtitles by amara.org"))
        // Ambiguous phrases real dialogue uses are kept unless the segment was quiet
        assertFalse(SpeechRecognitionEngine.isHallucination("お疲れ様でした"))
        assertTrue(SpeechRecognitionEngine.isHallucination("お疲れ様でした", segmentRmsDb = -33f))

        // Repetitive character sequences
        assertTrue(SpeechRecognitionEngine.isHallucination("ああああああ"))
        assertTrue(SpeechRecognitionEngine.isHallucination("......"))
        assertTrue(SpeechRecognitionEngine.isHallucination("hahahaha"))

        // Repetitive words
        assertTrue(SpeechRecognitionEngine.isHallucination("you you you you"))
        assertTrue(SpeechRecognitionEngine.isHallucination("the the the the"))

        // Very short or empty
        assertTrue(SpeechRecognitionEngine.isHallucination(""))
        assertTrue(SpeechRecognitionEngine.isHallucination("a"))

        // Legitimate sentences must NOT be flagged as hallucination
        assertFalse(SpeechRecognitionEngine.isHallucination("Hello, how are you today?"))
        assertFalse(SpeechRecognitionEngine.isHallucination("こんにちは、本日の天気は晴れです。"))
        assertFalse(SpeechRecognitionEngine.isHallucination("This is a complete and valid sentence for translation."))
    }

    @Test
    fun testWhisperModelTier_properties() {
        val tiny = WhisperModelTier.TINY
        val base = WhisperModelTier.BASE

        org.junit.Assert.assertEquals("tiny", tiny.id)
        org.junit.Assert.assertEquals(104, tiny.approxSizeMb)
        assertTrue(tiny.tokenFile.contains("tiny"))

        org.junit.Assert.assertEquals("base", base.id)
        org.junit.Assert.assertEquals(160, base.approxSizeMb)
        assertTrue(base.tokenFile.contains("base"))

        org.junit.Assert.assertEquals(WhisperModelTier.BASE, WhisperModelTier.fromId("base"))
        org.junit.Assert.assertEquals(WhisperModelTier.TINY, WhisperModelTier.fromId("unknown"))
    }

    @Test
    fun testSenseVoiceConfig_validityRequiresModelAndTokensButNoDecoder() {
        val senseVoice = SpeechRecognitionConfig(
            modelType = SttModelType.SENSE_VOICE,
            modelPath = "models/sense-voice-model.int8.onnx",
            tokensPath = "models/sense-voice-tokens.txt",
            language = "ja"
        )
        assertTrue("SenseVoice needs no encoder/decoder", senseVoice.isValid())
        org.junit.Assert.assertEquals(
            listOf("models/sense-voice-model.int8.onnx", "models/sense-voice-tokens.txt"),
            senseVoice.requiredPaths
        )

        assertFalse(senseVoice.copy(modelPath = "").isValid())
        assertFalse(senseVoice.copy(tokensPath = "").isValid())

        // A Whisper config still requires a decoder, and ignores modelPath
        val whisperNoDecoder = SpeechRecognitionConfig(
            encoderPath = "whisper/tiny-encoder.int8.onnx",
            tokensPath = "whisper/tiny-tokens.txt",
            modelPath = "models/sense-voice-model.int8.onnx"
        )
        org.junit.Assert.assertEquals(SttModelType.WHISPER, whisperNoDecoder.modelType)
        assertFalse(whisperNoDecoder.isValid())
    }

    @Test
    fun testSenseVoiceConfig_filesExistChecksOnlyModelAndTokens() {
        val dir = java.nio.file.Files.createTempDirectory("sv-config").toFile()
        try {
            val model = java.io.File(dir, "sense-voice-model.int8.onnx")
            val tokens = java.io.File(dir, "sense-voice-tokens.txt")
            val config = SpeechRecognitionConfig(
                modelType = SttModelType.SENSE_VOICE,
                modelPath = model.absolutePath,
                tokensPath = tokens.absolutePath
            )
            assertFalse(config.filesExist())
            model.writeText("m")
            assertFalse(config.filesExist())
            tokens.writeText("t")
            assertTrue(config.filesExist())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testSenseVoiceConfig_featureDimLanguageAndRecognizerConfig() {
        val config = SpeechRecognitionConfig(
            modelType = SttModelType.SENSE_VOICE,
            modelPath = "large/turbo/model.int8.onnx",
            tokensPath = "tokens.txt",
            language = "ko",
            featureDim = 128,
            numThreads = 2
        )
        org.junit.Assert.assertEquals("SenseVoice always uses 80-dim fbank", 80, config.effectiveFeatureDim)
        org.junit.Assert.assertEquals("ko", config.senseVoiceLanguage)
        org.junit.Assert.assertEquals("auto", config.copy(language = "vi").senseVoiceLanguage)
        org.junit.Assert.assertEquals("yue", config.copy(language = "YUE").senseVoiceLanguage)

        val recognizerConfig = config.toRecognizerConfig()
        val sv = recognizerConfig.modelConfig.senseVoice
        org.junit.Assert.assertEquals("large/turbo/model.int8.onnx", sv.model)
        org.junit.Assert.assertEquals("ko", sv.language)
        assertTrue(sv.useInverseTextNormalization)
        org.junit.Assert.assertEquals("tokens.txt", recognizerConfig.modelConfig.tokens)
        org.junit.Assert.assertEquals(2, recognizerConfig.modelConfig.numThreads)
        org.junit.Assert.assertEquals("", recognizerConfig.modelConfig.whisper.encoder)
        org.junit.Assert.assertEquals(80, recognizerConfig.featConfig.featureDim)

        val whisper = SpeechRecognitionConfig(
            encoderPath = "turbo-encoder.int8.onnx",
            decoderPath = "turbo-decoder.int8.onnx",
            tokensPath = "turbo-tokens.txt",
            language = "ja",
            task = "translate"
        ).toRecognizerConfig()
        org.junit.Assert.assertEquals("whisper", whisper.modelConfig.modelType)
        org.junit.Assert.assertEquals("translate", whisper.modelConfig.whisper.task)
        org.junit.Assert.assertEquals("", whisper.modelConfig.senseVoice.model)
        org.junit.Assert.assertEquals(128, whisper.featConfig.featureDim)
    }

    @Test
    fun testSttModelType_fromId() {
        org.junit.Assert.assertEquals(SttModelType.SENSE_VOICE, SttModelType.fromId("sense_voice"))
        org.junit.Assert.assertEquals(SttModelType.WHISPER, SttModelType.fromId("whisper"))
        org.junit.Assert.assertEquals(SttModelType.WHISPER, SttModelType.fromId(null))
        org.junit.Assert.assertEquals(SttModelType.WHISPER, SttModelType.fromId("bogus"))
    }
}
