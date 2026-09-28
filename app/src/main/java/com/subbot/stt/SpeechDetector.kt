package com.subbot.stt

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.subbot.audio.AudioUtils
import java.io.Closeable

/**
 * Frame-level speech/non-speech classifier fed with consecutive ~100 ms audio chunks (16 kHz mono).
 *
 * Implementations may be stateful (e.g. Silero VAD keeps an RNN state and hysteresis), so a
 * detector instance must be fed a single continuous stream and is not thread-safe for concurrent
 * [isSpeech] calls. [close] may be called from any thread and must be idempotent.
 */
interface SpeechDetector : Closeable {
    /** Short identifier for logs. */
    val name: String

    /** Segmentation tuning matched to this detector's latency / reliability characteristics. */
    val segmenterConfig: SegmenterConfig

    /** Consumes [chunk] and returns whether it should be treated as speech. */
    fun isSpeech(chunk: FloatArray): Boolean

    override fun close() {}
}

/**
 * Fallback detector: plain RMS energy gate. Cannot distinguish speech from background music,
 * so it is only used when the Silero VAD model is unavailable.
 */
class EnergySpeechDetector(
    private val thresholdDb: Float = SpeechRecognitionEngine.SILENCE_THRESHOLD_DB
) : SpeechDetector {
    override val name: String = "energy"
    override val segmenterConfig: SegmenterConfig = SegmenterConfig.ENERGY

    override fun isSpeech(chunk: FloatArray): Boolean =
        !AudioUtils.isSilence(AudioUtils.calculateRmsDb(chunk), thresholdDb = thresholdDb)
}

/**
 * Silero VAD (via sherpa-onnx [Vad]) used as a per-chunk speech classifier.
 *
 * Each chunk is pushed through [Vad.acceptWaveform]; [Vad.isSpeechDetected] then reports whether
 * the VAD is currently inside a speech region (it already applies minSpeechDuration onset
 * debouncing and minSilenceDuration release hysteresis). The VAD's own segment queue is discarded
 * every chunk: segmentation (preroll, pause close, max cap) is done by [SpeechSegmenter] so both
 * detectors share the same, unit-tested boundary logic.
 *
 * All native access is guarded by [lock] + [released] so [close] can race safely with [isSpeech]
 * (no use-after-free on stop).
 */
class SileroVadSpeechDetector private constructor(
    private val vad: Vad,
    private val fallback: EnergySpeechDetector = EnergySpeechDetector()
) : SpeechDetector {

    override val name: String = "silero-vad"
    override val segmenterConfig: SegmenterConfig = SegmenterConfig.SILERO_VAD

    private val lock = Any()
    @Volatile private var released = false
    @Volatile private var failed = false

    override fun isSpeech(chunk: FloatArray): Boolean {
        if (failed) return fallback.isSpeech(chunk)
        synchronized(lock) {
            if (released) return false
            return try {
                vad.acceptWaveform(chunk)
                val speech = vad.isSpeechDetected()
                // Segments are produced by SpeechSegmenter; drop the VAD's own queue to bound memory.
                if (!vad.empty()) vad.clear()
                speech
            } catch (e: Throwable) {
                SttLog.e("Silero VAD failed; falling back to energy detection", e)
                failed = true
                fallback.isSpeech(chunk)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (released) return
            released = true
            try {
                vad.release()
            } catch (e: Throwable) {
                SttLog.e("Error releasing Silero VAD", e)
            }
        }
    }

    companion object {
        /** Silero speech probability threshold (sherpa-onnx default). */
        const val THRESHOLD = 0.5f
        /** Onset debounce inside the VAD; SegmenterConfig.SILERO_VAD preroll must cover this latency. */
        const val MIN_SPEECH_DURATION_S = 0.25f
        /** Short release hysteresis; the segmenter adds its own trailing-silence requirement. */
        const val MIN_SILENCE_DURATION_S = 0.1f
        const val WINDOW_SIZE = 512
        /** Only a safety bound for the VAD's internal buffer; SpeechSegmenter enforces the ~6 s cap. */
        const val MAX_SPEECH_DURATION_S = 20f

        /**
         * Creates a Silero VAD detector from a local model file. Throws if the native VAD cannot be
         * constructed (missing/corrupt model, native library load failure).
         */
        fun create(modelPath: String, sampleRate: Int = SpeechRecognitionEngine.SAMPLE_RATE): SileroVadSpeechDetector {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_DURATION_S,
                    minSpeechDuration = MIN_SPEECH_DURATION_S,
                    windowSize = WINDOW_SIZE,
                    maxSpeechDuration = MAX_SPEECH_DURATION_S
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            return SileroVadSpeechDetector(Vad(assetManager = null, config = config))
        }
    }
}

/** android.util.Log wrapper that is a no-op on the plain JVM (unit tests). */
internal object SttLog {
    private const val TAG = "SpeechPipeline"

    fun w(msg: String) {
        try {
            android.util.Log.w(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    fun i(msg: String) {
        try {
            android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        try {
            android.util.Log.e(TAG, msg, t)
        } catch (_: Throwable) {
        }
    }
}
