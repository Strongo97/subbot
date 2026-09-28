package com.subbot.service

import android.app.Activity
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.subbot.audio.AudioCaptureManager
import com.subbot.stt.SpeechRecognitionConfig
import com.subbot.stt.SpeechRecognitionEngine
import com.subbot.translation.SentenceAggregator
import com.subbot.translation.TranslationEngine
import com.subbot.ui.overlay.SubtitleOverlayManager
import com.subbot.ui.overlay.SubtitleQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the MediaProjection audio stream, STT engine,
 * and translation pipeline while running over background apps.
 */
class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.subbot.action.START"
        const val ACTION_PAUSE = "com.subbot.action.PAUSE"
        const val ACTION_RESUME = "com.subbot.action.RESUME"
        const val ACTION_STOP = "com.subbot.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
        const val EXTRA_ENCODER_PATH = "extra_encoder_path"
        const val EXTRA_DECODER_PATH = "extra_decoder_path"
        const val EXTRA_TOKENS_PATH = "extra_tokens_path"
        const val EXTRA_IS_BILINGUAL = "extra_is_bilingual"
        const val EXTRA_IS_TOP = "extra_is_top"
        const val EXTRA_FONT_SIZE = "extra_font_size"
        const val EXTRA_TASK = "extra_task"
        const val EXTRA_TRANSLATION_MODE = "extra_translation_mode"

        // Global singleton access for overlay view to bind state
        var subtitleQueue: SubtitleQueue? = null
            private set

        private val _isServiceActive = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isServiceActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _isServiceActive

        fun setServiceActive(active: Boolean) {
            _isServiceActive.value = active
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var pipelineJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private val audioCaptureManager = AudioCaptureManager()
    private var speechEngine: SpeechRecognitionEngine? = null
    private val translationEngine = TranslationEngine()
    private var overlayManager: SubtitleOverlayManager? = null

    private var sourceLang: String = "en"
    private var targetLang: String = "es"
    private var isPaused = false
    private var isStopping = false
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    /** Buffers utterance fragments into sentences before ML Kit translation; null when bypassed. */
    @Volatile
    private var sentenceAggregator: SentenceAggregator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setServiceActive(true)
        NotificationHelper.createNotificationChannel(this)
        val queue = SubtitleQueue(serviceScope)
        subtitleQueue = queue
        val overlay = SubtitleOverlayManager(this)
        overlayManager = overlay
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "en"
        targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "es"
        val isBilingual = intent.getBooleanExtra(EXTRA_IS_BILINGUAL, false)
        subtitleQueue?.setBilingual(isBilingual)

        val encoderPath = intent.getStringExtra(EXTRA_ENCODER_PATH) ?: ""
        val decoderPath = intent.getStringExtra(EXTRA_DECODER_PATH) ?: ""
        val tokensPath = intent.getStringExtra(EXTRA_TOKENS_PATH) ?: ""

        val notification = NotificationHelper.buildNotification(this, isPaused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection = projection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    handleStop()
                }
            }
            mediaProjectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))

            val isTop = intent.getBooleanExtra(EXTRA_IS_TOP, false)
            val fontSizeName = intent.getStringExtra(EXTRA_FONT_SIZE)
            val fontSize = com.subbot.ui.model.SubtitleFontSize.fromName(fontSizeName)
            subtitleQueue?.let { queue ->
                queue.setFontSize(fontSize)
                queue.setTopPosition(isTop)
                overlayManager?.showOverlay(queue.uiState, isTop = isTop)
                queue.postSubtitle("SubBot Active", "Listening for speech...")
            }

            val translationModeStr = intent.getStringExtra(EXTRA_TRANSLATION_MODE)
            val translationMode = com.subbot.translation.TranslationMode.fromId(translationModeStr)

            val preferredTask = intent.getStringExtra(EXTRA_TASK)
            val requestedTask = preferredTask ?: if (translationMode == com.subbot.translation.TranslationMode.WHISPER_DIRECT && sourceLang != "en" && targetLang != sourceLang) {
                "translate"
            } else {
                "transcribe"
            }
            // large-v3-turbo was fine-tuned on transcription only; its translate task is poor,
            // so always transcribe and let ML Kit translate.
            val sttTask = if (requestedTask == "translate" && encoderPath.contains("turbo", ignoreCase = true)) {
                android.util.Log.i("AudioCaptureService", "Turbo encoder: forcing task=transcribe (ML Kit translation) instead of Whisper translate")
                "transcribe"
            } else {
                requestedTask
            }

            // Pre-load the ML Kit translator in parallel with the Whisper model load so the first
            // subtitle is not delayed. Whisper translate emits English, so warm up en->target then.
            val warmUpSource = if (sttTask == "translate") "en" else sourceLang
            if (!warmUpSource.equals(targetLang, ignoreCase = true)) {
                val warmUpTarget = targetLang
                serviceScope.launch(Dispatchers.Default) {
                    translationEngine.warmUp(warmUpSource, warmUpTarget)
                }
            }

            // Initialize STT engine with 4 threads and optimal task (128 mels for Turbo/Large, 80 for Tiny/Base/Small)
            val featureDim = if (encoderPath.contains("turbo", ignoreCase = true) || encoderPath.contains("large", ignoreCase = true)) 128 else 80
            val config = SpeechRecognitionConfig(
                encoderPath = encoderPath,
                decoderPath = decoderPath,
                tokensPath = tokensPath,
                language = sourceLang,
                task = sttTask,
                numThreads = 4,
                featureDim = featureDim
            )

            if (config.filesExist()) {
                try {
                    val engine = SpeechRecognitionEngine(config, assetManager = null)
                    engine.initialize()
                    speechEngine = engine
                } catch (e: Throwable) {
                    android.util.Log.e("AudioCaptureService", "Failed to initialize STT engine", e)
                    subtitleQueue?.postSubtitle("STT Init Error", e.localizedMessage ?: "Failed to load speech model")
                }
            } else {
                android.util.Log.e("AudioCaptureService", "Whisper model files not found at $encoderPath")
                subtitleQueue?.postSubtitle("Model Missing", "Please download the speech model in SubBot")
            }

            sentenceAggregator = createSentenceAggregator(sttTask, isBilingual)

            // Start Audio capture and streaming pipeline
            try {
                val audioChannel = audioCaptureManager.startCapture(projection)
                pipelineJob = serviceScope.launch(Dispatchers.Default) {
                    speechEngine?.processStream(audioChannel) { recognizedUtterance ->
                        handleUtterance(recognizedUtterance, sttTask, isBilingual)
                    }
                    // Stream ended on its own (not via stop): emit any trailing partial sentence.
                    if (!isStopping) {
                        sentenceAggregator?.flush()
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("AudioCaptureService", "Audio capture failed", e)
                subtitleQueue?.postSubtitle("Capture Error", e.localizedMessage ?: "Failed to start capture")
            }
        }
    }

    /**
     * Builds the sentence aggregator used ahead of ML Kit translation, or returns null when
     * no ML Kit translation happens (Whisper translate task to English, or source == target):
     * aggregation would then only add latency.
     */
    private fun createSentenceAggregator(sttTask: String, isBilingual: Boolean): SentenceAggregator? {
        val needsMlKit = if (sttTask == "translate") {
            !targetLang.equals("en", ignoreCase = true)
        } else {
            !sourceLang.equals(targetLang, ignoreCase = true)
        }
        if (!needsMlKit) return null

        // Whisper's translate task emits English text regardless of the spoken language.
        val aggregationLang = if (sttTask == "translate") "en" else sourceLang
        return SentenceAggregator(
            scope = CoroutineScope(serviceScope.coroutineContext + Dispatchers.Default),
            languageCode = aggregationLang,
            onPending = { pendingText ->
                if (!isPaused && !isStopping) {
                    if (isBilingual) {
                        // Bilingual viewers can read the source: show it right away next to
                        // the last translation (or a placeholder).
                        subtitleQueue?.postPendingOriginal(pendingText)
                    } else {
                        // Translation-only viewers may not read the source language, so don't
                        // show untranslated text; just keep the previous subtitle up.
                        subtitleQueue?.keepAlive()
                    }
                }
            },
            onError = { e ->
                android.util.Log.e("AudioCaptureService", "Timed sentence translation failed", e)
            },
            onSentence = { sentence ->
                if (!isPaused && !isStopping) {
                    translateAndPost(sentence, sttTask, isBilingual)
                }
            }
        )
    }

    /**
     * Entry point for each utterance emitted by the speech engine. Utterances are cut at
     * acoustic pauses, so when ML Kit translation is used they are first aggregated into
     * sentences; otherwise they are posted directly.
     */
    private suspend fun handleUtterance(recognizedUtterance: String, sttTask: String, isBilingual: Boolean) {
        if (isPaused || isStopping || recognizedUtterance.isBlank()) return
        val aggregator = sentenceAggregator
        if (aggregator != null) {
            aggregator.add(recognizedUtterance)
        } else {
            translateAndPost(recognizedUtterance, sttTask, isBilingual)
        }
    }

    private suspend fun translateAndPost(recognizedUtterance: String, sttTask: String, isBilingual: Boolean) {
        val originalText: String
        val translatedText: String

        if (sttTask == "translate") {
            // Whisper directly translated speech into English
            if (targetLang.equals("en", ignoreCase = true)) {
                originalText = if (isBilingual) recognizedUtterance else ""
                translatedText = recognizedUtterance
            } else {
                // Pivot through English to target language (e.g. Japanese -> English -> Vietnamese)
                val targetTranslated = translationEngine.translate(
                    text = recognizedUtterance,
                    sourceLang = "en",
                    targetLang = targetLang
                )
                originalText = if (isBilingual) recognizedUtterance else ""
                translatedText = targetTranslated
            }
        } else {
            // sttTask == "transcribe": recognizedUtterance is native text in sourceLang (e.g. Japanese "田中です")
            if (sourceLang.equals(targetLang, ignoreCase = true)) {
                originalText = ""
                translatedText = recognizedUtterance
            } else {
                val targetTranslated = translationEngine.translate(
                    text = recognizedUtterance,
                    sourceLang = sourceLang,
                    targetLang = targetLang
                )
                originalText = if (isBilingual) recognizedUtterance else ""
                translatedText = targetTranslated
            }
        }

        // Don't post to an overlay that is being torn down.
        if (isStopping) return
        subtitleQueue?.postSubtitle(
            original = originalText,
            translated = translatedText
        )
    }

    private fun handlePause() {
        isPaused = true
        updateNotification()
        // Drop the half-sentence spoken before pausing so it doesn't pop up later.
        sentenceAggregator?.let { aggregator ->
            serviceScope.launch { aggregator.clear() }
        }
    }

    private fun handleResume() {
        isPaused = false
        updateNotification()
    }

    private fun handleStop() {
        if (isStopping) return
        isStopping = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NotificationHelper.NOTIFICATION_ID)
        } catch (e: Throwable) {
            // Ignore
        }

        stopSelf()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, isPaused)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isStopping = true
        setServiceActive(false)

        // 1. Hide and detach overlay view immediately
        try {
            overlayManager?.hideOverlay()
        } catch (e: Throwable) {
            android.util.Log.e("AudioCaptureService", "Error hiding overlay", e)
        }
        overlayManager = null

        // 1b. Discard any half-aggregated sentence: nothing may be posted to the torn-down
        // overlay, so this is a synchronous close rather than a translate-and-post flush.
        try {
            sentenceAggregator?.close()
        } catch (e: Throwable) {
            android.util.Log.e("AudioCaptureService", "Error closing sentence aggregator", e)
        }
        sentenceAggregator = null

        // 2. Stop audio capture and close channel to unblock pipeline stream
        try {
            audioCaptureManager.stopCapture()
        } catch (e: Throwable) {
            android.util.Log.e("AudioCaptureService", "Error stopping audio capture", e)
        }

        // 3. Cancel pipeline coroutines
        try {
            pipelineJob?.cancel()
            pipelineJob = null
            serviceScope.cancel()
        } catch (e: Throwable) {
            android.util.Log.e("AudioCaptureService", "Error cancelling serviceScope", e)
        }

        // 4. Safely release STT engine (synchronized against active decodes)
        try {
            speechEngine?.release()
        } catch (e: Throwable) {
            android.util.Log.e("AudioCaptureService", "Error releasing speech engine", e)
        }
        speechEngine = null

        // 5. Close translation engine
        try {
            translationEngine.close()
        } catch (e: Throwable) {
            android.util.Log.e("AudioCaptureService", "Error closing translation engine", e)
        }

        // 6. Unregister MediaProjection callback before stopping projection to prevent recursive stop
        val callback = mediaProjectionCallback
        mediaProjectionCallback = null
        val projection = mediaProjection
        mediaProjection = null

        if (projection != null) {
            if (callback != null) {
                try {
                    projection.unregisterCallback(callback)
                } catch (e: Throwable) {
                    // Ignore
                }
            }
            try {
                projection.stop()
            } catch (e: Throwable) {
                // Ignore
            }
        }

        // 7. Clear subtitle queue and remove notification
        try {
            subtitleQueue?.clear()
        } catch (e: Throwable) {
            // Ignore
        }
        subtitleQueue = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NotificationHelper.NOTIFICATION_ID)
        } catch (e: Throwable) {
            // Ignore
        }
    }
}
