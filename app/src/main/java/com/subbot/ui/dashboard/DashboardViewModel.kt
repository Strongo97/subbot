package com.subbot.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subbot.translation.DownloadStatus
import com.subbot.translation.TranslationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LanguageOption(val code: String, val displayName: String)

data class DashboardUiState(
    val isOverlayPermissionGranted: Boolean = false,
    val isAudioPermissionGranted: Boolean = false,
    val isNotificationPermissionGranted: Boolean = false,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "vi",
    val isTopPosition: Boolean = false,
    val isBilingual: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isTargetModelDownloaded: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val statusMessage: String = ""
) {
    val areCorePermissionsGranted: Boolean
        get() = isOverlayPermissionGranted && isAudioPermissionGranted
}

class DashboardViewModel(
    private val translationEngine: TranslationEngine = TranslationEngine()
) : ViewModel() {

    companion object {
        val SUPPORTED_LANGUAGES = listOf(
            LanguageOption("en", "English"),
            LanguageOption("vi", "Vietnamese"),
            LanguageOption("es", "Spanish"),
            LanguageOption("fr", "French"),
            LanguageOption("ja", "Japanese"),
            LanguageOption("de", "German"),
            LanguageOption("zh", "Chinese"),
            LanguageOption("ko", "Korean")
        )
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun updatePermissions(context: Context) {
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        _uiState.update {
            it.copy(
                isOverlayPermissionGranted = overlayGranted,
                isAudioPermissionGranted = audioGranted,
                isNotificationPermissionGranted = notificationGranted
            )
        }

        checkTargetModelDownloaded()
    }

    fun setSourceLanguage(code: String) {
        _uiState.update { it.copy(sourceLanguage = code) }
    }

    fun setTargetLanguage(code: String) {
        _uiState.update { it.copy(targetLanguage = code) }
        checkTargetModelDownloaded()
    }

    fun togglePosition() {
        _uiState.update { it.copy(isTopPosition = !it.isTopPosition) }
    }

    fun toggleBilingual() {
        _uiState.update { it.copy(isBilingual = !it.isBilingual) }
    }

    fun setServiceRunning(running: Boolean) {
        _uiState.update { it.copy(isServiceRunning = running) }
    }

    fun checkTargetModelDownloaded() {
        viewModelScope.launch {
            val target = _uiState.value.targetLanguage
            val isDownloaded = translationEngine.isModelDownloaded(target)
            _uiState.update { it.copy(isTargetModelDownloaded = isDownloaded) }
        }
    }

    fun downloadTargetModel() {
        val target = _uiState.value.targetLanguage
        viewModelScope.launch {
            translationEngine.downloadModel(target).collect { status ->
                when (status) {
                    is DownloadStatus.Downloading -> {
                        _uiState.update {
                            it.copy(isDownloadingModel = true, statusMessage = "Downloading offline model...")
                        }
                    }
                    is DownloadStatus.Completed -> {
                        _uiState.update {
                            it.copy(
                                isDownloadingModel = false,
                                isTargetModelDownloaded = true,
                                statusMessage = "Model downloaded successfully!"
                            )
                        }
                    }
                    is DownloadStatus.Failed -> {
                        _uiState.update {
                            it.copy(
                                isDownloadingModel = false,
                                statusMessage = "Download failed: ${status.error.localizedMessage}"
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
