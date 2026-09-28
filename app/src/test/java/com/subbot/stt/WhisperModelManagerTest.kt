package com.subbot.stt

import java.io.File
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
        assertFalse(manager.isModelAvailable())
    }

    @Test
    fun testIsModelAvailable_returnsTrueWhenAllFilesExist() {
        val dir = tempFolder.newFolder("whisper-complete")
        val manager = WhisperModelManager(dir)

        val dummyData = ByteArray(2000) { 1 }
        File(dir, "tiny.en-encoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny.en-decoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny.en-tokens.txt").writeBytes(dummyData)

        assertTrue(manager.isModelAvailable())
    }

    @Test
    fun testIsModelAvailable_returnsFalseWhenFileIsTooSmallOrCorrupted() {
        val dir = tempFolder.newFolder("whisper-partial")
        val manager = WhisperModelManager(dir)

        File(dir, "tiny.en-encoder.int8.onnx").writeBytes(ByteArray(2000) { 1 })
        File(dir, "tiny.en-decoder.int8.onnx").writeBytes(ByteArray(10) { 1 }) // Too small
        File(dir, "tiny.en-tokens.txt").writeBytes(ByteArray(2000) { 1 })

        assertFalse(manager.isModelAvailable())
    }

    @Test
    fun testDownloadModel_preservesFlowInvariantWhenCollected() = kotlinx.coroutines.test.runTest {
        val dir = tempFolder.newFolder("whisper-flow-test")
        val manager = WhisperModelManager(dir)

        // Seed files so it doesn't make real network calls in unit test
        val dummyData = ByteArray(2000) { 1 }
        File(dir, "tiny.en-encoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny.en-decoder.int8.onnx").writeBytes(dummyData)
        File(dir, "tiny.en-tokens.txt").writeBytes(dummyData)

        val states = mutableListOf<ModelDownloadState>()
        manager.downloadModel().collect { state ->
            states.add(state)
        }

        assertTrue("Should emit progress states", states.any { it is ModelDownloadState.Progress })
        assertTrue("Should emit completed state", states.any { it is ModelDownloadState.Completed })
    }
}
