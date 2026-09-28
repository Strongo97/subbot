package com.subbot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.subbot.service.AudioCaptureService
import com.subbot.ui.dashboard.DashboardScreen
import com.subbot.ui.dashboard.DashboardViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.updatePermissions(this)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.updatePermissions(this)
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val state = viewModel.uiState.value
            val whisperManager = com.subbot.stt.WhisperModelManager(java.io.File(filesDir, "models/whisper"))
            val effectiveTier = if (whisperManager.isTierAvailable(state.selectedSpeechModelTier)) {
                state.selectedSpeechModelTier
            } else if (whisperManager.isTierAvailable(com.subbot.stt.WhisperModelTier.TURBO)) {
                com.subbot.stt.WhisperModelTier.TURBO
            } else if (whisperManager.isTierAvailable(com.subbot.stt.WhisperModelTier.SMALL)) {
                com.subbot.stt.WhisperModelTier.SMALL
            } else if (whisperManager.isTierAvailable(com.subbot.stt.WhisperModelTier.BASE)) {
                com.subbot.stt.WhisperModelTier.BASE
            } else {
                com.subbot.stt.WhisperModelTier.TINY
            }
            val (encoderFile, decoderFile, tokensFile) = whisperManager.getModelFilesForTier(effectiveTier)

            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_SOURCE_LANG, state.sourceLanguage)
                putExtra(AudioCaptureService.EXTRA_TARGET_LANG, state.targetLanguage)
                putExtra(AudioCaptureService.EXTRA_IS_BILINGUAL, state.isBilingual)
                putExtra(AudioCaptureService.EXTRA_IS_TOP, state.isTopPosition)
                putExtra(AudioCaptureService.EXTRA_FONT_SIZE, state.fontSize.name)
                putExtra(AudioCaptureService.EXTRA_TRANSLATION_MODE, state.translationMode.id)
                putExtra(AudioCaptureService.EXTRA_ENCODER_PATH, encoderFile.absolutePath)
                putExtra(AudioCaptureService.EXTRA_DECODER_PATH, decoderFile.absolutePath)
                putExtra(AudioCaptureService.EXTRA_TOKENS_PATH, tokensFile.absolutePath)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            viewModel.setServiceRunning(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            com.subbot.ui.theme.SubBotTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestAudioPermission = {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStartService = { startProjectionService() },
                    onStopService = { stopProjectionService() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updatePermissions(this)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startProjectionService() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopProjectionService() {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        try {
            startService(intent)
        } catch (e: Throwable) {
            try {
                stopService(Intent(this, AudioCaptureService::class.java))
            } catch (ignored: Throwable) {}
        }
        viewModel.setServiceRunning(false)
    }
}
