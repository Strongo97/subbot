package com.subbot.stt

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VadModelDownloadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun vadModelAvailability_requiresPlausiblyCompleteFile() {
        val dir = tempFolder.newFolder("whisper")
        val manager = WhisperModelManager(dir)

        assertFalse(manager.isVadModelAvailable())
        assertNull(manager.vadModelPath)

        // Truncated / HTML error page must not count as a model.
        File(dir, VAD_MODEL_FILE_NAME).writeBytes(ByteArray(2000))
        assertFalse(manager.isVadModelAvailable())

        File(dir, VAD_MODEL_FILE_NAME).writeBytes(ByteArray(VAD_MIN_VALID_BYTES.toInt()))
        assertTrue(manager.isVadModelAvailable())
        assertEquals(File(dir, VAD_MODEL_FILE_NAME).absolutePath, manager.vadModelPath)
    }

    @Test
    fun downloadVadModel_skipsNetworkWhenPresent() = runTest {
        val dir = tempFolder.newFolder("whisper-vad")
        File(dir, VAD_MODEL_FILE_NAME).writeBytes(ByteArray(VAD_MIN_VALID_BYTES.toInt()))
        val manager = WhisperModelManager(dir)

        val states = manager.downloadVadModel().toList()

        assertEquals(listOf(ModelDownloadState.Completed), states)
        assertTrue(manager.downloadVadModelBlocking())
    }

    @Test
    fun vadModelIsIndependentOfWhisperTiers() {
        val dir = tempFolder.newFolder("whisper-indep")
        File(dir, VAD_MODEL_FILE_NAME).writeBytes(ByteArray(VAD_MIN_VALID_BYTES.toInt()))
        val manager = WhisperModelManager(dir)

        assertTrue(manager.isVadModelAvailable())
        assertFalse(manager.hasMultilingualModel())
        WhisperModelTier.values().forEach { tier ->
            assertFalse(tier.encoderFile == VAD_MODEL_FILE_NAME || tier.decoderFile == VAD_MODEL_FILE_NAME)
        }
    }
}
