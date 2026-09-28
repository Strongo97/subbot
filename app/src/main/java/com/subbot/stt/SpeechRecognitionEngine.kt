package com.subbot.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.subbot.audio.AudioUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/**
 * Speech-to-Text inference engine powered by Sherpa-ONNX Whisper offline models.
 */
class SpeechRecognitionEngine(
    private val config: SpeechRecognitionConfig,
    private val assetManager: AssetManager? = null
) {
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var isReleased = false
    private val engineLock = Any()

    companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_CHUNK = 1600 // 100ms
        const val MIN_SPEECH_SAMPLES = 20800 // ~1.3s minimum context before pause split (faster response)
        const val TARGET_SPEECH_SAMPLES = 48000 // ~3.0s ideal clause length
        const val MAX_BUFFER_SAMPLES = 96000 // ~6.0s maximum buffer before energy split
        const val SILENCE_THRESHOLD_DB = -40.0f // Filter out quiet ambient noise, hiss, and video BGM

        val KNOWN_HALLUCINATIONS = setOf(
            "ご視聴ありがとうございました",
            "ご視聴いただきありがとうございました",
            "視聴ありがとうございました",
            "ご覧いただきありがとうございます",
            "動画をご視聴いただき",
            "チャンネル登録",
            "チャンネル登録よろしく",
            "チャンネル登録お願いします",
            "高評価チャンネル登録",
            "お疲れ様でした",
            "おやすみなさい",
            "最後までご視聴",
            "thank you for watching",
            "thanks for watching",
            "thank you very much for watching",
            "thank you",
            "please subscribe",
            "like and subscribe",
            "subtitles by",
            "amara.org",
            "translated by"
        )

        /**
         * Cleans trailing ellipses, leading dashes, blank prompt tags, and formatting artifacts.
         */
        fun cleanUtterance(raw: String): String {
            return raw
                .replace(Regex("^\\[.*?\\]"), "") // remove [BLANK_AUDIO] or [MUSIC]
                .replace(Regex("^[\\s.,…—\\-~。、]+"), "") // remove leading punctuation, ellipses, dashes
                .replace(Regex("[\\s.,…—\\-~。、]+$"), "") // remove trailing ellipses, periods, dashes
                .replace(Regex("\\.{2,}"), ".") // replace multiple internal dots with single period
                .trim()
        }

        /**
         * Detects common Whisper hallucination artifacts generated on silence, background music, or noise.
         */
        fun isHallucination(text: String): Boolean {
            val lower = text.lowercase().trim()
            if (lower.length <= 1) return true
            // Check known phantom Whisper hallucination phrases on quiet/music segments
            if (KNOWN_HALLUCINATIONS.any { lower.contains(it) } && lower.length < 50) return true
            // Check for repeated character sequences (e.g. "ああああああ" or "you you you")
            if (Regex("(.)\\1{4,}").containsMatchIn(text)) return true
            if (Regex("(.{2})\\1{3,}").containsMatchIn(text)) return true
            // Check for repeated words (e.g. "you you you you")
            if (Regex("(?i)\\b(\\w+)(\\s+\\1){3,}\\b").containsMatchIn(text)) return true
            return false
        }
    }

    /**
     * Initializes the Sherpa-ONNX offline recognizer (Whisper or SenseVoice, per [SpeechRecognitionConfig.modelType]).
     */
    fun initialize() {
        if (!config.isValid()) {
            throw IllegalArgumentException("Invalid SpeechRecognitionConfig: required paths cannot be empty")
        }

        recognizer = OfflineRecognizer(
            assetManager = assetManager,
            config = config.toRecognizerConfig(sampleRate = SAMPLE_RATE)
        )
    }

    /**
     * Consumes audio float chunks (16kHz mono), performs VAD / RMS energy gating,
     * buffers speech until natural pause or boundary, decodes using Whisper, and emits recognized text.
     */
    suspend fun processStream(
        audioChannel: ReceiveChannel<FloatArray>,
        onUtterance: suspend (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val rec = recognizer ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        val buffer = ArrayList<Float>(120000)
        val chunkEnergies = ArrayList<Float>()
        val prerollChunks = ArrayDeque<Pair<FloatArray, Float>>(4)
        var trailingSilenceCount = 0
        var consecutiveVoiceChunks = 0
        var hasSpeech = false

        for (chunk in audioChannel) {
            if (isReleased) break
            val rms = AudioUtils.calculateRmsDb(chunk)
            val isSilent = AudioUtils.isSilence(rms, thresholdDb = SILENCE_THRESHOLD_DB)

            if (!isSilent) {
                consecutiveVoiceChunks++
                if (!hasSpeech) {
                    if (prerollChunks.size >= 3) {
                        prerollChunks.removeFirst()
                    }
                    prerollChunks.addLast(chunk to rms)
                    // Require at least 2 consecutive voice chunks (200ms) to reject transient clicks/noise
                    if (consecutiveVoiceChunks >= 2) {
                        hasSpeech = true
                        trailingSilenceCount = 0
                        // Flush all prerolled onset audio into buffer so no initial consonant/vowel is clipped
                        while (prerollChunks.isNotEmpty()) {
                            val (prevChunk, prevRms) = prerollChunks.removeFirst()
                            for (sample in prevChunk) buffer.add(sample)
                            chunkEnergies.add(prevRms)
                        }
                    }
                } else {
                    trailingSilenceCount = 0
                    for (sample in chunk) buffer.add(sample)
                    chunkEnergies.add(rms)
                }
            } else {
                consecutiveVoiceChunks = 0
                if (!hasSpeech) {
                    if (prerollChunks.size >= 3) {
                        prerollChunks.removeFirst()
                    }
                    prerollChunks.addLast(chunk to rms)
                } else {
                    trailingSilenceCount++
                    for (sample in chunk) buffer.add(sample)
                    chunkEnergies.add(rms)
                }
            }

            // Pause-based boundary conditions:
            // 1. Natural clause boundary: >= 1.3s of speech followed by >= 500ms pause (5 chunks)
            val naturalPause = buffer.size >= MIN_SPEECH_SAMPLES && trailingSilenceCount >= 5
            // 2. Longer sentence boundary: >= 3.0s of speech followed by >= 400ms pause (4 chunks)
            val sentencePause = buffer.size >= TARGET_SPEECH_SAMPLES && trailingSilenceCount >= 4
            // 3. Extended speech without pause reaching maximum capacity (6.0s)
            val bufferFull = buffer.size >= MAX_BUFFER_SAMPLES

            if (naturalPause || sentencePause) {
                val samplesToDecode = buffer.toFloatArray()
                buffer.clear()
                chunkEnergies.clear()
                prerollChunks.clear()
                trailingSilenceCount = 0
                consecutiveVoiceChunks = 0
                hasSpeech = false

                decodeAndEmit(rec, samplesToDecode, onUtterance)
            } else if (bufferFull) {
                // Find lowest energy chunk in the trailing 1.5s (up to 15 chunks) to split cleanly at a word boundary
                val searchStart = maxOf(0, chunkEnergies.size - 15)
                var minEnergyIndex = searchStart
                var minEnergy = Float.MAX_VALUE
                for (i in searchStart until chunkEnergies.size) {
                    if (chunkEnergies[i] < minEnergy) {
                        minEnergy = chunkEnergies[i]
                        minEnergyIndex = i
                    }
                }

                val splitSample = minOf((minEnergyIndex + 1) * SAMPLES_PER_CHUNK, buffer.size)
                val samplesToDecode = buffer.subList(0, splitSample).toFloatArray()

                // Carry over the audio after the split point into the next window
                val remaining = buffer.subList(splitSample, buffer.size).toList()
                val remainingEnergies = chunkEnergies.subList(minEnergyIndex + 1, chunkEnergies.size).toList()

                buffer.clear()
                buffer.addAll(remaining)
                chunkEnergies.clear()
                chunkEnergies.addAll(remainingEnergies)
                prerollChunks.clear()
                trailingSilenceCount = 0
                consecutiveVoiceChunks = 0
                hasSpeech = buffer.isNotEmpty()

                decodeAndEmit(rec, samplesToDecode, onUtterance)
            }
        }

        // Flush remaining buffer if sufficient audio exists
        if (!isReleased && buffer.size >= 8000) {
            decodeAndEmit(rec, buffer.toFloatArray(), onUtterance)
            buffer.clear()
            chunkEnergies.clear()
        }
    }

    private suspend fun decodeAndEmit(
        rec: OfflineRecognizer,
        samples: FloatArray,
        onUtterance: suspend (String) -> Unit
    ) {
        if (isReleased) return
        if (samples.size < 8000) return // Skip tiny acoustic clicks (< 0.5s)
        val rms = AudioUtils.calculateRmsDb(samples)
        if (rms < -34.0f) return // Skip quiet background hiss / low BGM without vocal energy

        val cleaned = synchronized(engineLock) {
            if (isReleased || recognizer == null) return
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val rawText = rec.getResult(stream).text.trim()
                cleanUtterance(rawText)
            } catch (e: Throwable) {
                android.util.Log.e("SpeechRecognitionEngine", "Error during decode", e)
                ""
            } finally {
                try {
                    stream.release()
                } catch (e: Throwable) {
                    // Ignore
                }
            }
        }

        if (cleaned.isNotBlank() && !isHallucination(cleaned) && !isReleased) {
            try {
                onUtterance(cleaned)
            } catch (e: Throwable) {
                android.util.Log.e("SpeechRecognitionEngine", "Error in onUtterance", e)
            }
        }
    }

    fun release() {
        synchronized(engineLock) {
            isReleased = true
            try {
                recognizer?.release()
            } catch (e: Throwable) {
                android.util.Log.e("SpeechRecognitionEngine", "Error releasing recognizer", e)
            }
            recognizer = null
        }
    }
}
