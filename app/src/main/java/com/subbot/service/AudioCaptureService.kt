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

        // Global singleton access for overlay view to bind state
        var subtitleQueue: SubtitleQueue? = null
            private set
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handleStop()
                }
            }, Handler(Looper.getMainLooper()))

            // Initialize STT engine
            val config = SpeechRecognitionConfig(
                encoderPath = encoderPath,
                decoderPath = decoderPath,
                tokensPath = tokensPath,
                language = sourceLang
            )

            if (config.isValid()) {
                val engine = SpeechRecognitionEngine(config, assets)
                engine.initialize()
                speechEngine = engine
            }

            // Start Audio capture and streaming pipeline
            val audioChannel = audioCaptureManager.startCapture(projection)
            pipelineJob = serviceScope.launch(Dispatchers.Default) {
                speechEngine?.processStream(audioChannel) { recognizedUtterance ->
                    if (!isPaused && recognizedUtterance.isNotBlank()) {
                        val translatedText = translationEngine.translate(
                            text = recognizedUtterance,
                            sourceLang = sourceLang,
                            targetLang = targetLang
                        )
                        subtitleQueue?.postSubtitle(
                            original = recognizedUtterance,
                            translated = translatedText
                        )
                    }
                }
            }

            val isTop = intent.getBooleanExtra("extra_is_top", false)
            subtitleQueue?.let { queue ->
                overlayManager?.showOverlay(queue.uiState, isTop = isTop)
            }
        }
    }

    private fun handlePause() {
        isPaused = true
        updateNotification()
    }

    private fun handleResume() {
        isPaused = false
        updateNotification()
    }

    private fun handleStop() {
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
        pipelineJob?.cancel()
        serviceScope.cancel()

        overlayManager?.hideOverlay()
        overlayManager = null
        audioCaptureManager.stopCapture()
        speechEngine?.release()
        speechEngine = null
        translationEngine.close()
        mediaProjection?.stop()
        mediaProjection = null
        subtitleQueue?.clear()
        subtitleQueue = null
    }
}
