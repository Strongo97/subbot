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
    private val assetManager: AssetManager? = null,
    /** Path to silero_vad.onnx; null/missing file falls back to energy-based speech detection. */
    private val vadModelPath: String? = null
) {
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var activeDetector: SpeechDetector? = null
    @Volatile private var isReleased = false
    private val engineLock = Any()

    companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_CHUNK = 1600 // 100ms
        const val MIN_SPEECH_SAMPLES = 20800 // ~1.3s minimum context before pause split (faster response)
        const val TARGET_SPEECH_SAMPLES = 48000 // ~3.0s ideal clause length
        const val MAX_BUFFER_SAMPLES = 96000 // ~6.0s maximum buffer before energy split
        const val SILENCE_THRESHOLD_DB = -40.0f // Filter out quiet ambient noise, hiss, and video BGM

        /**
         * Segment RMS (dBFS) below which a "suspicious" phrase (see [SUSPICIOUS_PHRASES]) is treated
         * as a hallucination. Whisper tends to invent "Thank you." / 「お疲れ様でした」 on quiet tails,
         * BGM and room tone; genuine dialogue carrying those words is usually well above -30 dB
         * (decodeAndEmit already drops whole segments below -34 dB).
         */
        const val SUSPICIOUS_PHRASE_MAX_RMS_DB = -30.0f

        /**
         * Near-certain Whisper artifacts (YouTube outros / subtitle credits learned from training data).
         * An utterance is rejected unconditionally when, after [normalizeForMatch], it consists
         * entirely of one or more of these phrases.
         */
        val ARTIFACT_PHRASES: Set<String> = setOf(
            // Japanese outros
            "ご視聴ありがとうございました",
            "ご視聴ありがとうございます",
            "ご視聴いただきありがとうございました",
            "ご視聴いただきありがとうございます",
            "視聴ありがとうございました",
            "最後までご視聴ありがとうございました",
            "最後までご視聴いただきありがとうございました",
            "ご覧いただきありがとうございました",
            "ご覧いただきありがとうございます",
            "動画をご覧いただきありがとうございました",
            "チャンネル登録",
            "チャンネル登録よろしく",
            "チャンネル登録よろしくお願いします",
            "チャンネル登録お願いします",
            "チャンネル登録をお願いします",
            "チャンネル登録をよろしくお願いします",
            "高評価チャンネル登録よろしくお願いします",
            "高評価とチャンネル登録よろしくお願いします",
            "高評価チャンネル登録お願いします",
            // Korean outro
            "시청해주셔서 감사합니다",
            // English outros / credits
            "thank you for watching",
            "thanks for watching",
            "thank you very much for watching",
            "thank you so much for watching",
            "please subscribe",
            "like and subscribe",
            "please like and subscribe",
            "don't forget to like and subscribe",
            "subtitles by",
            "subtitles by the amara.org community",
            "translated by"
        )

        /**
         * Substrings that never occur in real dialogue; any utterance containing one is rejected.
         */
        val ARTIFACT_MARKERS: Set<String> = setOf("amara.org")

        /**
         * Phrases Whisper often hallucinates on quiet audio but that real dialogue also uses
         * (anime/drama greetings, thanks). Rejected only when the utterance consists entirely of
         * these (or artifact) phrases AND the segment RMS is below [SUSPICIOUS_PHRASE_MAX_RMS_DB].
         */
        val SUSPICIOUS_PHRASES: Set<String> = setOf(
            "you",
            "thank you",
            "thank you very much",
            "thank you so much",
            "thanks",
            "bye",
            "bye bye",
            "ありがとうございました",
            "ありがとうございます",
            "ご清聴ありがとうございました",
            "お疲れ様でした",
            "お疲れ様です",
            "おやすみなさい"
        )

        private val MATCH_STRIP_REGEX = Regex("[\\p{P}\\p{S}\\p{Z}\\s]+")
        private val normalizedArtifacts by lazy { ARTIFACT_PHRASES.map(::normalizeForMatch).toSet() }
        private val normalizedMarkers by lazy { ARTIFACT_MARKERS.map(::normalizeForMatch).toSet() }
        private val normalizedSuspiciousOrArtifacts by lazy {
            (SUSPICIOUS_PHRASES + ARTIFACT_PHRASES).map(::normalizeForMatch).toSet()
        }

        /** Lowercases and strips whitespace, punctuation and symbols (incl. 。、！？「」～♪). */
        fun normalizeForMatch(text: String): String =
            text.lowercase().replace(MATCH_STRIP_REGEX, "")

        /** True when [text] can be segmented entirely into phrases from [phrases] (word-break DP). */
        private fun isComposedOf(text: String, phrases: Set<String>): Boolean {
            if (text.isEmpty()) return false
            val reachable = BooleanArray(text.length + 1)
            reachable[0] = true
            for (i in text.indices) {
                if (!reachable[i]) continue
                for (p in phrases) {
                    if (p.isNotEmpty() && text.startsWith(p, i)) reachable[i + p.length] = true
                }
            }
            return reachable[text.length]
        }

        private val LEADING_TAGS_REGEX = Regex("^(?:\\s*\\[[^\\]]*\\])+")
        private val INLINE_TAG_REGEX = Regex("\\[[A-Z_ ]+\\]")
        private val LEADING_JUNK_REGEX = Regex("^[\\s.,，…—–\\-~～。、!?！？・]+")
        // Trailing whitespace, commas, 、, dashes, tildes and ellipses ("...", "…", "。。。").
        // A single trailing sentence-final mark (. ! ? 。 ！ ？) is kept for sentence aggregation.
        private val TRAILING_JUNK_REGEX = Regex("(?:[\\s,，、…—–\\-~～]|\\.{2,}|。{2,})+$")
        private val INTERNAL_DOTS_REGEX = Regex("\\.{2,}")

        /**
         * Removes [BLANK_AUDIO]/[MUSIC]-style tags, leading punctuation/dashes/ellipses and trailing
         * ellipses/dashes/tildes/commas, while keeping trailing sentence-final punctuation
         * (`. ! ? 。 ！ ？`) so downstream consumers can detect sentence boundaries.
         */
        fun cleanUtterance(raw: String): String {
            return raw
                .replace(LEADING_TAGS_REGEX, "") // remove leading [BLANK_AUDIO] / [MUSIC]
                .replace(INLINE_TAG_REGEX, " ") // remove inline/trailing [MUSIC]-style tags
                .replace(LEADING_JUNK_REGEX, "")
                .replace(TRAILING_JUNK_REGEX, "")
                .replace(INTERNAL_DOTS_REGEX, "…") // normalize internal "..." to "…" so it is not read as a sentence end
                .trim()
        }

        // Repetition loops. Only letters (incl. kana/kanji) count, so numbers such as "100000"
        // and emphatic punctuation never trigger; pure-punctuation text is caught by the length check.
        private val REPEATED_CHAR_REGEX = Regex("(\\p{L})\\1{4,}") // "ああああああ" (5+), not "ははは"
        private val REPEATED_BIGRAM_REGEX = Regex("(\\p{L}{2})\\1{3,}") // "hahahaha" (4+), not "hahaha"
        private val REPEATED_WORD_REGEX = Regex("(?i)\\b(\\w+)(\\s+\\1){3,}\\b") // "you you you you", not "no no no"

        /**
         * Detects common Whisper hallucination artifacts generated on silence, background music, or noise.
         *
         * @param segmentRmsDb RMS level (dBFS) of the decoded audio segment, if known. Suspicious-but-
         *   plausible phrases ("Thank you.", 「お疲れ様でした」) are rejected only when this is below
         *   [SUSPICIOUS_PHRASE_MAX_RMS_DB]; when null they are kept so real dialogue is not dropped.
         */
        fun isHallucination(text: String, segmentRmsDb: Float? = null): Boolean {
            val normalized = normalizeForMatch(text)
            if (normalized.length <= 1) return true
            if (normalizedMarkers.any { normalized.contains(it) }) return true
            if (isComposedOf(normalized, normalizedArtifacts)) return true
            if (segmentRmsDb != null && segmentRmsDb < SUSPICIOUS_PHRASE_MAX_RMS_DB &&
                isComposedOf(normalized, normalizedSuspiciousOrArtifacts)
            ) return true
            val lower = text.lowercase()
            if (REPEATED_CHAR_REGEX.containsMatchIn(lower)) return true
            if (REPEATED_BIGRAM_REGEX.containsMatchIn(lower)) return true
            if (REPEATED_WORD_REGEX.containsMatchIn(lower)) return true
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
     * Consumes audio float chunks (16kHz mono) and emits recognized utterances.
     *
     * Runs a three-stage pipeline (see [SpeechPipeline]): speech segmentation (Silero VAD when
     * [vadModelPath] points to a usable model, RMS energy gating otherwise) -> Whisper decoding ->
     * [onUtterance] delivery. Each stage runs in its own coroutine so a slow decode never stalls
     * audio intake and a slow [onUtterance] never stalls decoding.
     */
    suspend fun processStream(
        audioChannel: ReceiveChannel<FloatArray>,
        onUtterance: suspend (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val rec = recognizer ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        val detector = createSpeechDetector()
        activeDetector = detector
        try {
            SpeechPipeline().run(
                audio = audioChannel,
                segmenter = SpeechSegmenter(detector),
                decode = { samples -> decodeSegment(rec, samples) },
                onUtterance = onUtterance,
                isStopped = { isReleased }
            )
        } finally {
            activeDetector = null
            detector.close()
        }
    }

    /**
     * Silero VAD if the model file is present and loads, otherwise the energy-gating fallback.
     */
    private fun createSpeechDetector(): SpeechDetector {
        val path = vadModelPath
        if (!path.isNullOrBlank() && java.io.File(path).let { it.exists() && it.length() > 0 }) {
            try {
                val vad = SileroVadSpeechDetector.create(path)
                SttLog.i("Speech detector: Silero VAD ($path)")
                return vad
            } catch (e: Throwable) {
                SttLog.e("Failed to load Silero VAD from $path; using energy gating", e)
            }
        } else {
            SttLog.i("Silero VAD model not available; using energy gating")
        }
        return EnergySpeechDetector()
    }

    /**
     * Decodes one speech segment with Whisper. Returns the cleaned utterance, or null if the
     * engine was released, the result is blank, or it matches a known hallucination.
     * Holds [engineLock] for the whole native call so [release] cannot free the recognizer mid-decode.
     */
    private fun decodeSegment(rec: OfflineRecognizer, samples: FloatArray): String? {
        if (isReleased) return null

        val cleaned = synchronized(engineLock) {
            if (isReleased || recognizer == null) return null
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

        if (cleaned.isBlank() || isReleased) return null
        // Segment loudness lets the filter reject "Thank you."-style phrases only when they were
        // decoded from quiet audio (typical of Whisper hallucinating on near-silence).
        val segmentRmsDb = AudioUtils.calculateRmsDb(samples)
        return if (!isHallucination(cleaned, segmentRmsDb)) cleaned else null
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
        // Detector has its own lock; closing it here waits for any in-flight VAD call to finish.
        activeDetector?.close()
    }
}
