package com.subbot.stt

import com.subbot.audio.AudioUtils

/**
 * Tuning for [SpeechSegmenter]. Lengths are in samples (16 kHz) or in ~100 ms chunks.
 */
data class SegmenterConfig(
    /** Consecutive speech chunks required to open a segment (rejects transient clicks). */
    val onsetChunks: Int,
    /** Extra chunks kept *before* the first onset chunk so the leading consonant is never clipped. */
    val prerollChunks: Int,
    /** Natural clause close: at least this much buffered audio ... */
    val minSegmentSamples: Int,
    /** ... followed by this many trailing non-speech chunks. */
    val pauseChunks: Int,
    /** Sentence close: at least this much buffered audio ... */
    val targetSegmentSamples: Int,
    /** ... followed by this (shorter) number of trailing non-speech chunks. */
    val shortPauseChunks: Int,
    /** Hard cap: uninterrupted speech is split at the quietest recent chunk once reached. */
    val maxSegmentSamples: Int,
    /** How many trailing chunks to search for a split point when the cap is hit. */
    val splitSearchChunks: Int = 15,
    /** Segments shorter than this are dropped (acoustic clicks). */
    val minEmitSamples: Int = 8000,
    /** Segments must contain at least this many speech chunks (otherwise pure silence/noise). */
    val minVoicedChunks: Int = 2,
    /** Segments quieter than this overall RMS are dropped (null = no energy gate). */
    val minSegmentRmsDb: Float? = null
) {
    init {
        require(onsetChunks >= 1) { "onsetChunks must be >= 1" }
        require(prerollChunks >= 0) { "prerollChunks must be >= 0" }
        require(maxSegmentSamples > 0) { "maxSegmentSamples must be > 0" }
    }

    companion object {
        /**
         * Legacy energy-gating behaviour (unchanged from the pre-VAD pipeline): 2 x 100 ms onset
         * + 100 ms padding (300 ms preroll), 1.3 s + 500 ms / 3.0 s + 400 ms pause closes,
         * 6 s cap, and the -34 dB segment gate that used to live in decodeAndEmit.
         */
        val ENERGY = SegmenterConfig(
            onsetChunks = 2,
            prerollChunks = 1,
            minSegmentSamples = SpeechRecognitionEngine.MIN_SPEECH_SAMPLES,
            pauseChunks = 5,
            targetSegmentSamples = SpeechRecognitionEngine.TARGET_SPEECH_SAMPLES,
            shortPauseChunks = 4,
            maxSegmentSamples = SpeechRecognitionEngine.MAX_BUFFER_SAMPLES,
            minSegmentRmsDb = -34.0f
        )

        /**
         * Silero VAD: the VAD debounces onset itself (minSpeechDuration 250 ms), so a single
         * flagged chunk opens a segment, and 500 ms of preroll covers the VAD's detection latency.
         * Pause close is 400 ms (after >= 1 s) / 300 ms (after >= 3 s) on top of the VAD's 100 ms
         * release hysteresis. No RMS gate: the VAD rejects music, and quiet dialogue must pass.
         * A very low floor still rejects near-digital-silence.
         */
        val SILERO_VAD = SegmenterConfig(
            onsetChunks = 1,
            prerollChunks = 5,
            minSegmentSamples = 16000,
            pauseChunks = 4,
            targetSegmentSamples = SpeechRecognitionEngine.TARGET_SPEECH_SAMPLES,
            shortPauseChunks = 3,
            maxSegmentSamples = SpeechRecognitionEngine.MAX_BUFFER_SAMPLES,
            minSegmentRmsDb = -60.0f
        )
    }
}

/**
 * Turns a stream of ~100 ms audio chunks into utterance-sized segments using a [SpeechDetector]
 * for the speech/non-speech decision. Pure Kotlin (no Android / native dependencies of its own),
 * single-threaded, bounded memory (at most [SegmenterConfig.maxSegmentSamples] + one chunk).
 */
class SpeechSegmenter(
    private val detector: SpeechDetector,
    private val config: SegmenterConfig = detector.segmenterConfig
) {
    private class Chunk(val samples: FloatArray, val rmsDb: Float, val speech: Boolean)

    private val preroll = ArrayDeque<Chunk>()
    private val segment = ArrayList<Chunk>()
    private var segmentSamples = 0
    private var inSpeech = false
    private var consecutiveSpeech = 0
    private var trailingSilence = 0

    /** Feeds one chunk; returns 0..n finished segments (usually 0 or 1). */
    fun process(samples: FloatArray): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val chunk = Chunk(samples, AudioUtils.calculateRmsDb(samples), detector.isSpeech(samples))
        val out = ArrayList<FloatArray>(1)

        if (!inSpeech) {
            preroll.addLast(chunk)
            while (preroll.size > config.prerollChunks + config.onsetChunks) preroll.removeFirst()
            consecutiveSpeech = if (chunk.speech) consecutiveSpeech + 1 else 0
            if (consecutiveSpeech >= config.onsetChunks) {
                inSpeech = true
                trailingSilence = 0
                while (preroll.isNotEmpty()) append(preroll.removeFirst())
            }
        } else {
            append(chunk)
            trailingSilence = if (chunk.speech) 0 else trailingSilence + 1
        }

        if (inSpeech) {
            val naturalPause = segmentSamples >= config.minSegmentSamples && trailingSilence >= config.pauseChunks
            val sentencePause = segmentSamples >= config.targetSegmentSamples && trailingSilence >= config.shortPauseChunks
            if (naturalPause || sentencePause) {
                emit(segment, out)
                resetToIdle()
            } else if (segmentSamples >= config.maxSegmentSamples) {
                splitAtQuietPoint(out)
            }
        }
        return out
    }

    /** Flushes any in-progress speech (end of stream). */
    fun flush(): List<FloatArray> {
        val out = ArrayList<FloatArray>(1)
        if (inSpeech) emit(segment, out)
        resetToIdle()
        return out
    }

    private fun append(chunk: Chunk) {
        segment.add(chunk)
        segmentSamples += chunk.samples.size
    }

    private fun resetToIdle() {
        segment.clear()
        segmentSamples = 0
        preroll.clear()
        inSpeech = false
        consecutiveSpeech = 0
        trailingSilence = 0
    }

    /**
     * Cap reached with no pause: split after the quietest chunk in the trailing search window
     * (non-speech chunks preferred), emit the head and carry the tail into the next segment.
     */
    private fun splitAtQuietPoint(out: MutableList<FloatArray>) {
        val searchStart = maxOf(0, segment.size - config.splitSearchChunks)
        var splitIndex = segment.size - 1
        var best = Float.MAX_VALUE
        for (i in searchStart until segment.size) {
            val c = segment[i]
            val score = if (c.speech) c.rmsDb else c.rmsDb - 1000f
            if (score < best) {
                best = score
                splitIndex = i
            }
        }

        val head = segment.subList(0, splitIndex + 1).toList()
        val tail = segment.subList(splitIndex + 1, segment.size).toList()
        emit(head, out)
        resetToIdle()

        if (tail.any { it.speech }) {
            inSpeech = true
            tail.forEach { append(it) }
            trailingSilence = tail.takeLastWhile { !it.speech }.size
        } else {
            // Nothing but pause after the split point: keep it only as preroll for the next onset.
            tail.takeLast(config.prerollChunks + config.onsetChunks).forEach { preroll.addLast(it) }
        }
    }

    private fun emit(chunks: List<Chunk>, out: MutableList<FloatArray>) {
        val total = chunks.sumOf { it.samples.size }
        if (total < config.minEmitSamples) return
        if (chunks.count { it.speech } < config.minVoicedChunks) return
        val merged = FloatArray(total)
        var offset = 0
        for (c in chunks) {
            c.samples.copyInto(merged, offset)
            offset += c.samples.size
        }
        val gate = config.minSegmentRmsDb
        if (gate != null && AudioUtils.calculateRmsDb(merged) < gate) return
        out.add(merged)
    }
}
