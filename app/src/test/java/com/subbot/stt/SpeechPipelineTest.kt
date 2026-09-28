package com.subbot.stt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPipelineTest {

    private companion object {
        const val CHUNK = 1600
    }

    private class FakeDetector : SpeechDetector {
        override val name = "fake"
        override val segmenterConfig = SegmenterConfig.SILERO_VAD
        override fun isSpeech(chunk: FloatArray) = chunk[0] != 0f
    }

    /** [count] utterances of 1.5 s speech + 0.8 s pause, each producing exactly one segment. */
    private fun utteranceAudio(count: Int): List<FloatArray> {
        val chunks = ArrayList<FloatArray>()
        repeat(count) { n ->
            repeat(15) { chunks.add(FloatArray(CHUNK) { 0.1f + n * 0.01f }) }
            repeat(8) { chunks.add(FloatArray(CHUNK)) }
        }
        return chunks
    }

    @Test
    fun slowOnUtteranceDoesNotStallDecoding() = runTest {
        val audio = Channel<FloatArray>(Channel.UNLIMITED)
        utteranceAudio(3).forEach { audio.trySend(it) }

        var decoded = 0
        val delivered = ArrayList<String>()
        // Pre-filled audio is segmented in one burst; give the segment queue room for all of it.
        val pipeline = SpeechPipeline(segmentQueueCapacity = 8, utteranceQueueCapacity = 8, log = {})

        val job = launch {
            pipeline.run(
                audio = audio,
                segmenter = SpeechSegmenter(FakeDetector()),
                decode = { decoded++; "utterance $decoded" },
                onUtterance = { text ->
                    delay(60_000) // e.g. a translation that takes a minute
                    delivered.add(text)
                }
            )
        }

        runCurrent()
        // All three segments decoded while the first onUtterance is still suspended.
        assertEquals(3, decoded)
        assertTrue(delivered.isEmpty())

        audio.close()
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("utterance 1", "utterance 2", "utterance 3"), delivered)
    }

    @Test
    fun slowDecoderDoesNotStallSegmentationAndDropsOldestSegments() = runTest {
        // Rendezvous audio channel: the producer can only finish if the segmenter keeps reading.
        val audio = Channel<FloatArray>(Channel.RENDEZVOUS)
        val logs = ArrayList<String>()
        val pipeline = SpeechPipeline(segmentQueueCapacity = 2, log = { logs.add(it) })
        val decodedFirstSamples = ArrayList<Float>()
        val delivered = ArrayList<String>()

        val job = launch {
            pipeline.run(
                audio = audio,
                segmenter = SpeechSegmenter(FakeDetector()),
                decode = { seg ->
                    delay(30_000) // very slow model
                    decodedFirstSamples.add(seg.first { it != 0f })
                    "text"
                },
                onUtterance = { delivered.add(it) }
            )
        }

        var producerDone = false
        launch {
            utteranceAudio(6).forEach { audio.send(it) }
            producerDone = true
        }

        runCurrent()
        assertTrue("segmenter must drain audio while decoder is busy", producerDone)
        assertTrue("decoder has not finished its first segment yet", decodedFirstSamples.isEmpty())
        // Segment 1 is in the decoder, queue holds 2 -> 3 of the 6 were dropped (oldest first).
        assertEquals(3, pipeline.droppedSegments)
        assertTrue(logs.any { it.contains("dropped pending segment") })

        audio.close()
        advanceUntilIdle()
        job.join()

        // Decoded: the first one (in flight) + the two newest.
        assertEquals(listOf(0, 4, 5).map { n -> 0.1f + n * 0.01f }, decodedFirstSamples)
        assertEquals(3, delivered.size)
    }

    @Test
    fun stopsWhenEngineReleased() = runTest {
        val audio = Channel<FloatArray>(Channel.UNLIMITED)
        utteranceAudio(3).forEach { audio.trySend(it) }
        var released = false
        var decoded = 0

        val job = launch {
            SpeechPipeline(log = {}).run(
                audio = audio,
                segmenter = SpeechSegmenter(FakeDetector()),
                decode = { decoded++; released = true; "x" },
                onUtterance = {},
                isStopped = { released }
            )
        }
        advanceUntilIdle()
        // Returns without the audio channel being closed, after at most the in-flight decode.
        assertTrue(job.isCompleted)
        assertEquals(1, decoded)
    }
}
