package com.subbot.pipeline

import com.subbot.audio.AudioUtils
import com.subbot.translation.TranslationCache
import com.subbot.ui.overlay.SubtitleQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineIntegrationTest {

    @Test
    fun testAudioEnergyGating_distinguishesSpeechFromSilence() {
        // 1. Generate 16kHz synthetic speech (440Hz sine wave, amplitude 0.5)
        val sampleRate = 16000
        val speechSamples = FloatArray(1600) { i ->
            (0.5 * sin(2.0 * PI * 440.0 * i / sampleRate)).toFloat()
        }
        val speechRms = AudioUtils.calculateRmsDb(speechSamples)
        assertFalse("Synthetic speech must not be detected as silence", AudioUtils.isSilence(speechRms, -45.0f))

        // 2. Generate silent samples (zeros)
        val silentSamples = FloatArray(1600) { 0.0f }
        val silentRms = AudioUtils.calculateRmsDb(silentSamples)
        assertTrue("Zero buffer must be detected as silence", AudioUtils.isSilence(silentRms, -45.0f))
    }

    @Test
    fun testEndToEndStreamingPipeline_updatesOverlayAndAutoFades() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 3500L)
        val translationCache = TranslationCache(maxEntries = 10)
        val audioChannel = Channel<FloatArray>(capacity = 16)

        // Seed translation cache for deterministic offline test verification
        translationCache.put("en", "vi", "Good morning", "Chào buổi sáng")

        var processedUtteranceCount = 0

        // Launch consumer pipeline
        val pipelineJob = launch {
            for (chunk in audioChannel) {
                val rms = AudioUtils.calculateRmsDb(chunk)
                if (!AudioUtils.isSilence(rms, -45.0f)) {
                    // Speech detected -> simulate recognizer emitting finalized phrase
                    val simulatedTranscript = "Good morning"
                    val cachedTranslation = translationCache.get("en", "vi", simulatedTranscript) ?: simulatedTranscript
                    queue.postSubtitle(simulatedTranscript, cachedTranslation)
                    processedUtteranceCount++
                }
            }
        }

        // Feed speech chunk
        val speechChunk = FloatArray(1600) { 0.4f }
        audioChannel.send(speechChunk)
        testScheduler.runCurrent()

        // Verify subtitle immediately visible with translation
        assertEquals("Chào buổi sáng", queue.uiState.value.translatedText)
        assertEquals("Good morning", queue.uiState.value.originalText)
        assertTrue(queue.uiState.value.isVisible)
        assertEquals(1, processedUtteranceCount)

        // Advance past silence auto-fade timeout (3500ms)
        advanceTimeBy(3600L)
        testScheduler.runCurrent()
        assertFalse("Subtitles must auto-fade after inactivity timeout", queue.uiState.value.isVisible)

        // Cleanup
        audioChannel.close()
        pipelineJob.cancel()
    }
}
