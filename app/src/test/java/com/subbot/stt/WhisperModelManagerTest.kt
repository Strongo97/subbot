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
}
