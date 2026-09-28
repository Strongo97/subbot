package com.subbot.stt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIsModelAvailable_returnsFalseWhenFilesMissing() {
        val dir = tempFolder.newFolder("whisper-empty")
        val manager = WhisperModelManager(dir)
        assertFalse(manager.isModelAvailable("en"))
        assertFalse(manager.isModelAvailable("ja"))
    }

    @Test
    fun testIsModelAvailable_englishOnlyModelWorksForEnglishButNotJapanese() {
        val dir = tempFolder.newFolder("whisper-english-only")
        val manager = WhisperModelManager(dir)

        val dummyData = ByteArray(2000) { 1 }
        File(dir, "tiny.en-encoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny.en-decoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny.en-tokens.txt").writeBytes(dummyData)

        assertTrue("English model should be available for English", manager.isModelAvailable("en"))
        assertFalse("English model cannot be used for Japanese", manager.isModelAvailable("ja"))

        val modelFiles = manager.getModelFiles("en")
        assertEquals("tiny.en-encoder.int8.onnx", modelFiles.first.name)
    }

    @Test
    fun testIsModelAvailable_multilingualModelWorksForBothEnglishAndJapanese() {
        val dir = tempFolder.newFolder("whisper-multilingual")
        val manager = WhisperModelManager(dir)

        val dummyData = ByteArray(2000) { 1 }
        File(dir, "tiny-encoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny-decoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny-tokens.txt").writeBytes(dummyData)

        assertTrue("Multilingual model should work for English", manager.isModelAvailable("en"))
        assertTrue("Multilingual model should work for Japanese", manager.isModelAvailable("ja"))

        val modelFiles = manager.getModelFiles("ja")
        assertEquals("tiny-encoder.int8.onnx", modelFiles.first.name)
    }

    @Test
    fun testDownloadModel_preservesFlowInvariantWhenCollected() = kotlinx.coroutines.test.runTest {
        val dir = tempFolder.newFolder("whisper-flow-test")
        val manager = WhisperModelManager(dir)

        // Seed multilingual files so it doesn't make real network calls in unit test
        val dummyData = ByteArray(2000) { 1 }
        File(dir, "tiny-encoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny-decoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny-tokens.txt").writeBytes(dummyData)

        val states = mutableListOf<ModelDownloadState>()
        manager.downloadModel().collect { state ->
            states.add(state)
        }

        assertTrue("Should emit progress states", states.any { it is ModelDownloadState.Progress })
        assertTrue("Should emit completed state", states.any { it is ModelDownloadState.Completed })
    }

    @Test
    fun testSmallTier_resolutionAndAvailability() {
        val dir = tempFolder.newFolder("whisper-small-test")
        val manager = WhisperModelManager(dir)

        assertFalse("Small tier should not be available when files are missing", manager.isTierAvailable(WhisperModelTier.SMALL))

        val dummyData = ByteArray(2000) { 1 }
        File(dir, "small-encoder.int8.onnx").writeBytes(dummyData)
        File(dir, "small-decoder.int8.onnx").writeBytes(dummyData)
        File(dir, "small-tokens.txt").writeBytes(dummyData)

        assertTrue("Small tier should be available after files are written", manager.isTierAvailable(WhisperModelTier.SMALL))
        assertTrue("Model should be available for Japanese", manager.isModelAvailable("ja"))

        val (enc, dec, tok) = manager.getModelFilesForTier(WhisperModelTier.SMALL)
        assertEquals("small-encoder.int8.onnx", enc.name)
        assertEquals("small-decoder.int8.onnx", dec.name)
        assertEquals("small-tokens.txt", tok.name)

        // getModelFiles should prioritize SMALL when available
        val preferred = manager.getModelFiles("ja")
        assertEquals("small-encoder.int8.onnx", preferred.first.name)

        assertEquals(WhisperModelTier.SMALL, WhisperModelTier.fromId("small"))
    }

    private fun seedTier(dir: File, tier: WhisperModelTier) {
        val dummyData = ByteArray(2000) { 1 }
        tier.remoteFiles.forEach { File(dir, tier.localFileName(it)).writeBytes(dummyData) }
    }

    @Test
    fun testSenseVoiceTier_definition() {
        val tier = WhisperModelTier.SENSE_VOICE
        assertEquals(SttModelType.SENSE_VOICE, tier.modelType)
        assertEquals(null, tier.decoderFile)
        assertEquals(listOf("tokens.txt", "model.int8.onnx"), tier.remoteFiles)
        assertEquals("sense-voice-model.int8.onnx", tier.localFileName(tier.encoderFile))
        assertEquals(80, tier.featureDim)
        assertEquals(WhisperModelTier.SENSE_VOICE, WhisperModelTier.fromId("sense_voice"))
        listOf("zh", "en", "ja", "ko", "yue", "JA").forEach { assertTrue(it, tier.supportsLanguage(it)) }
        listOf("vi", "es", "fr", "de").forEach { assertFalse(it, tier.supportsLanguage(it)) }
        // Whisper tiers remain fully multilingual with unprefixed local names
        WhisperModelTier.WHISPER_FALLBACK_ORDER.forEach {
            assertTrue(it.supportsLanguage("vi"))
            assertEquals(it.encoderFile, it.localFileName(it.encoderFile))
            assertEquals(3, it.remoteFiles.size)
        }
    }

    @Test
    fun testSenseVoiceTier_fileResolutionAndAvailability() {
        val dir = tempFolder.newFolder("sense-voice-test")
        val manager = WhisperModelManager(dir)
        val tier = WhisperModelTier.SENSE_VOICE

        assertFalse(manager.isTierAvailable(tier))

        // Unprefixed upstream names must not count (they would collide with other models)
        File(dir, "model.int8.onnx").writeBytes(ByteArray(2000))
        File(dir, "tokens.txt").writeBytes(ByteArray(2000))
        assertFalse(manager.isTierAvailable(tier))

        seedTier(dir, tier)
        assertTrue(manager.isTierAvailable(tier))

        val files = manager.resolveTierFiles(tier)
        assertEquals("sense-voice-model.int8.onnx", files.model.name)
        assertEquals(null, files.decoder)
        assertEquals("sense-voice-tokens.txt", files.tokens.name)
        assertEquals(2, files.all.size)

        // SenseVoice alone serves supported languages only, and is not a "multilingual Whisper" model
        assertTrue(manager.isModelAvailable("en"))
        assertTrue(manager.isModelAvailable("ja"))
        assertFalse(manager.isModelAvailable("vi"))
        assertFalse(manager.hasMultilingualModel())

        try {
            manager.getModelFilesForTier(tier)
            org.junit.Assert.fail("getModelFilesForTier must reject single-file tiers")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun testResolveEffectiveTier_fallsBackForMissingOrUnsupportedTier() {
        val dir = tempFolder.newFolder("effective-tier-test")
        val manager = WhisperModelManager(dir)

        // Nothing downloaded: TINY (caller reports missing model)
        assertEquals(WhisperModelTier.TINY, manager.resolveEffectiveTier(WhisperModelTier.SENSE_VOICE, "ja"))

        seedTier(dir, WhisperModelTier.BASE)
        // SenseVoice selected but not downloaded -> largest downloaded Whisper tier
        assertEquals(WhisperModelTier.BASE, manager.resolveEffectiveTier(WhisperModelTier.SENSE_VOICE, "ja"))

        seedTier(dir, WhisperModelTier.SENSE_VOICE)
        assertEquals(WhisperModelTier.SENSE_VOICE, manager.resolveEffectiveTier(WhisperModelTier.SENSE_VOICE, "ja"))
        // Unsupported language -> Whisper fallback even though SenseVoice is downloaded
        assertEquals(WhisperModelTier.BASE, manager.resolveEffectiveTier(WhisperModelTier.SENSE_VOICE, "vi"))
        // Selected Whisper tier missing -> largest downloaded Whisper tier, never SenseVoice
        assertEquals(WhisperModelTier.BASE, manager.resolveEffectiveTier(WhisperModelTier.TURBO, "ja"))
        assertEquals(WhisperModelTier.BASE, manager.resolveEffectiveTier(WhisperModelTier.BASE, "vi"))

        // Whisper resolution still returns encoder/decoder/tokens
        val (enc, dec, tok) = manager.getModelFilesForTier(WhisperModelTier.BASE)
        assertEquals("base-encoder.int8.onnx", enc.name)
        assertEquals("base-decoder.int8.onnx", dec.name)
        assertEquals("base-tokens.txt", tok.name)
    }

    @Test
    fun testDownloadTier_senseVoiceSkipsSeededPrefixedFiles() = kotlinx.coroutines.test.runTest {
        val dir = tempFolder.newFolder("sense-voice-download")
        val manager = WhisperModelManager(dir)
        seedTier(dir, WhisperModelTier.SENSE_VOICE)

        val states = mutableListOf<ModelDownloadState>()
        manager.downloadTier(WhisperModelTier.SENSE_VOICE).collect { states.add(it) }

        val progressFiles = states.filterIsInstance<ModelDownloadState.Progress>().map { it.currentFile }
        assertEquals(listOf("tokens.txt", "model.int8.onnx"), progressFiles)
        assertTrue(states.last() is ModelDownloadState.Completed)
    }
}
