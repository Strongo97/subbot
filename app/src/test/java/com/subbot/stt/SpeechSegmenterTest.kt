package com.subbot.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    private companion object {
        const val CHUNK = 1600 // 100 ms
    }

    /**
     * Fake detector: a chunk is speech when its first sample is non-zero. [onsetLagChunks]
     * simulates a real VAD's detection latency: the first N speech chunks of every speech run are
     * reported as non-speech.
     */
    private class FakeDetector(
        override val segmenterConfig: SegmenterConfig,
        private val onsetLagChunks: Int = 0
    ) : SpeechDetector {
        override val name = "fake"
        private var run = 0
        override fun isSpeech(chunk: FloatArray): Boolean {
            val raw = chunk[0] != 0f
            run = if (raw) run + 1 else 0
            return raw && run > onsetLagChunks
        }
    }

    /** Builds a list of chunks; speech chunks carry their global index in every sample (+0.1 base amplitude). */
    private class Script {
        val chunks = ArrayList<FloatArray>()
        fun silence(ms: Int) = repeat(ms / 100) { chunks.add(FloatArray(CHUNK)) }
        fun speech(ms: Int) = repeat(ms / 100) {
            val tag = 0.1f + chunks.size * 0.001f
            chunks.add(FloatArray(CHUNK) { tag })
        }
        fun tagOf(index: Int) = 0.1f + index * 0.001f
    }

    private fun runAll(segmenter: SpeechSegmenter, chunks: List<FloatArray>): List<Pair<Int, FloatArray>> {
        val out = ArrayList<Pair<Int, FloatArray>>()
        chunks.forEachIndexed { i, c -> segmenter.process(c).forEach { out.add(i to it) } }
        segmenter.flush().forEach { out.add(chunks.size to it) }
        return out
    }

    @Test
    fun onsetIsPreservedDespiteDetectorLatency() {
        // VAD-style config with a detector that only notices speech 3 chunks (300 ms) late.
        val detector = FakeDetector(SegmenterConfig.SILERO_VAD, onsetLagChunks = 3)
        val s = Script().apply { silence(1000); speech(1500); silence(600) }
        val firstSpeechIndex = 10

        val segments = runAll(SpeechSegmenter(detector), s.chunks)

        assertEquals(1, segments.size)
        val seg = segments[0].second
        // The very first speech chunk (before the detector fired) must be inside the segment.
        val firstTag = s.tagOf(firstSpeechIndex)
        val firstPos = seg.indexOfFirst { it == firstTag }
        assertTrue("first speech chunk must not be clipped", firstPos >= 0)
        // ...with leading padding in front of it (at least 100 ms of pre-onset audio).
        assertTrue("expected preroll padding before onset, got $firstPos samples", firstPos >= CHUNK)
        // Every speech chunk is present.
        for (i in firstSpeechIndex until firstSpeechIndex + 15) {
            assertTrue("speech chunk $i missing", seg.any { it == s.tagOf(i) })
        }
    }

    @Test
    fun energyConfigKeepsLegacyPreroll() {
        val detector = FakeDetector(SegmenterConfig.ENERGY)
        val s = Script().apply { silence(1000); speech(1500); silence(600) }

        val segments = runAll(SpeechSegmenter(detector), s.chunks)

        assertEquals(1, segments.size)
        val seg = segments[0].second
        // 1 chunk (100 ms) of padding before the first voiced chunk, as in the pre-VAD pipeline.
        assertEquals(CHUNK, seg.indexOfFirst { it == s.tagOf(10) })
    }

    @Test
    fun pauseClosesSegmentPromptly() {
        val config = SegmenterConfig.SILERO_VAD
        val detector = FakeDetector(config)
        val s = Script().apply {
            silence(500); speech(1500); silence(800); speech(1500); silence(800)
        }

        val segments = runAll(SpeechSegmenter(detector), s.chunks)

        assertEquals(2, segments.size)
        // First speech run is chunks 5..19; segment must close exactly after `pauseChunks` silence chunks.
        assertEquals(19 + config.pauseChunks, segments[0].first)
        assertTrue("pause close latency must be <= 500 ms", config.pauseChunks * 100 <= 500)
        // Second segment must not contain first-segment speech.
        assertTrue(segments[1].second.none { it == s.tagOf(10) })
        assertTrue(segments[1].second.any { it == s.tagOf(28 + 5) })
    }

    @Test
    fun maxCapSplitsLongUninterruptedSpeech() {
        val config = SegmenterConfig.SILERO_VAD
        val detector = FakeDetector(config)
        val s = Script().apply { silence(300); speech(15000); silence(800) }

        val segments = runAll(SpeechSegmenter(detector), s.chunks)

        assertTrue("15 s of speech must be split into >= 3 segments, got ${segments.size}", segments.size >= 3)
        segments.forEach { (_, seg) ->
            assertTrue("segment ${seg.size} exceeds cap", seg.size <= config.maxSegmentSamples)
        }
        // No audio lost or duplicated across splits: every speech chunk appears exactly once.
        for (i in 3 until 3 + 150) {
            val tag = s.tagOf(i)
            val count = segments.count { (_, seg) -> seg.any { it == tag } }
            assertEquals("speech chunk $i must appear in exactly one segment", 1, count)
        }
        // First split happens as soon as the cap is reached (not after the whole stream).
        assertTrue(segments[0].first < s.chunks.size / 2)
    }

    @Test
    fun maxCapPrefersNonSpeechSplitPoint() {
        val config = SegmenterConfig.SILERO_VAD.copy(pauseChunks = 50, shortPauseChunks = 50)
        val detector = FakeDetector(config)
        // One non-speech chunk in the middle of the search window must be chosen as split point.
        val s = Script().apply { speech(5000); silence(100); speech(2000) }

        val segments = runAll(SpeechSegmenter(detector), s.chunks)

        assertEquals(2, segments.size)
        // 5 s speech + the silent chunk = 51 chunks in the first segment.
        assertEquals(51 * CHUNK, segments[0].second.size)
    }

    @Test
    fun silenceProducesNoSegments() {
        val zeros = List(100) { FloatArray(CHUNK) }

        assertTrue(runAll(SpeechSegmenter(FakeDetector(SegmenterConfig.SILERO_VAD)), zeros).isEmpty())
        assertTrue(runAll(SpeechSegmenter(EnergySpeechDetector()), zeros).isEmpty())
    }

    @Test
    fun transientClickProducesNoSegmentWithEnergyDetector() {
        val s = Script().apply { silence(1000); speech(100); silence(2000) }
        assertTrue(runAll(SpeechSegmenter(EnergySpeechDetector()), s.chunks).isEmpty())
    }

    @Test
    fun shortVadBlipBelowMinimumVoicedChunksIsDropped() {
        val s = Script().apply { silence(1000); speech(100); silence(2000) }
        assertTrue(runAll(SpeechSegmenter(FakeDetector(SegmenterConfig.SILERO_VAD)), s.chunks).isEmpty())
    }

    @Test
    fun energyConfigDropsQuietSegments() {
        // Detector says "speech" but the audio is at ~-46 dBFS: below the legacy -34 dB segment gate.
        val quiet = FloatArray(CHUNK) { 0.005f }
        val chunks = List(5) { FloatArray(CHUNK) } + List(20) { quiet } + List(10) { FloatArray(CHUNK) }
        val detector = FakeDetector(SegmenterConfig.ENERGY)
        assertTrue(runAll(SpeechSegmenter(detector), chunks).isEmpty())
        // The VAD config has no such gate: quiet dialogue must still be emitted.
        assertEquals(1, runAll(SpeechSegmenter(FakeDetector(SegmenterConfig.SILERO_VAD)), chunks).size)
    }

    @Test
    fun flushEmitsTrailingSpeechAtEndOfStream() {
        val s = Script().apply { silence(300); speech(2000) }
        val segments = runAll(SpeechSegmenter(FakeDetector(SegmenterConfig.SILERO_VAD)), s.chunks)
        assertEquals(1, segments.size)
        assertEquals(s.chunks.size, segments[0].first)
    }
}
