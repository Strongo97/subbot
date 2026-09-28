package com.subbot.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subbot.stt.WhisperModelTier
import com.subbot.translation.DownloadStatus
import com.subbot.translation.TranslationEngine
import com.subbot.translation.TranslationMode
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
    val fontSize: com.subbot.ui.model.SubtitleFontSize = com.subbot.ui.model.SubtitleFontSize.NORMAL,
    val isServiceRunning: Boolean = false,
    val isSourceModelDownloaded: Boolean = true,
    val isTargetModelDownloaded: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val selectedSpeechModelTier: WhisperModelTier = WhisperModelTier.TINY,
    val isTinyModelDownloaded: Boolean = false,
    val isBaseModelDownloaded: Boolean = false,
    val isSmallModelDownloaded: Boolean = false,
    val isTurboModelDownloaded: Boolean = false,
    val isSpeechModelDownloaded: Boolean = false,
    val isDownloadingSpeechModel: Boolean = false,
    val speechModelProgress: Int = 0,
    val speechModelFile: String = "",
    val translationMode: TranslationMode = TranslationMode.GOOGLE_NER,
    val statusMessage: String = ""
) {
    val areCorePermissionsGranted: Boolean
        get() = isOverlayPermissionGranted && isAudioPermissionGranted

    val areTranslationModelsDownloaded: Boolean
        get() = isSourceModelDownloaded && isTargetModelDownloaded

    val isReadyToStart: Boolean
        get() = areCorePermissionsGranted && areTranslationModelsDownloaded && isSpeechModelDownloaded
}

class DashboardViewModel(
    private val translationEngine: com.subbot.translation.ITranslationEngine? = null,
    private val whisperModelManager: com.subbot.stt.WhisperModelManager? = null
) : ViewModel() {

    private var modelManager: com.subbot.stt.WhisperModelManager? = whisperModelManager

    private val engine: com.subbot.translation.ITranslationEngine by lazy {
        translationEngine ?: TranslationEngine()
    }

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

    init {
        viewModelScope.launch {
            com.subbot.service.AudioCaptureService.isServiceActive.collect { active ->
                _uiState.update { it.copy(isServiceRunning = active) }
            }
        }
    }

    fun syncServiceState() {
        _uiState.update { it.copy(isServiceRunning = com.subbot.service.AudioCaptureService.isServiceActive.value) }
    }

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
        initModelManager(context)
    }

    fun initModelManager(context: Context) {
        if (modelManager == null) {
            modelManager = com.subbot.stt.WhisperModelManager(java.io.File(context.filesDir, "models/whisper"))
        }
        checkSpeechModel()
    }

    fun selectSpeechModelTier(tier: WhisperModelTier) {
        _uiState.update { it.copy(selectedSpeechModelTier = tier) }
        checkSpeechModel()
    }

    fun setTranslationMode(mode: TranslationMode) {
        _uiState.update { it.copy(translationMode = mode) }
        checkTranslationModelsDownloaded()
    }

    fun checkSpeechModel() {
        val manager = modelManager ?: return
        val tinyDownloaded = manager.isTierAvailable(WhisperModelTier.TINY)
        val baseDownloaded = manager.isTierAvailable(WhisperModelTier.BASE)
        val smallDownloaded = manager.isTierAvailable(WhisperModelTier.SMALL)
        val turboDownloaded = manager.isTierAvailable(WhisperModelTier.TURBO)
        val selectedTier = _uiState.value.selectedSpeechModelTier
        val isSelectedDownloaded = manager.isTierAvailable(selectedTier)

        _uiState.update {
            it.copy(
                isTinyModelDownloaded = tinyDownloaded,
                isBaseModelDownloaded = baseDownloaded,
                isSmallModelDownloaded = smallDownloaded,
                isTurboModelDownloaded = turboDownloaded,
                isSpeechModelDownloaded = isSelectedDownloaded || (tinyDownloaded || baseDownloaded || smallDownloaded || turboDownloaded)
            )
        }
    }

    fun downloadSpeechModel(tier: WhisperModelTier = _uiState.value.selectedSpeechModelTier) {
        val manager = modelManager ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingSpeechModel = true,
                    speechModelProgress = 0,
                    statusMessage = "Starting ${tier.displayName} download..."
                )
            }
            try {
                manager.downloadTier(tier).collect { state ->
                    when (state) {
                        is com.subbot.stt.ModelDownloadState.Progress -> {
                            _uiState.update {
                                it.copy(
                                    speechModelProgress = state.progressPercent,
                                    speechModelFile = state.currentFile,
                                    statusMessage = "Downloading ${state.currentFile} (${state.progressPercent}%)"
                                )
                            }
                        }
                        is com.subbot.stt.ModelDownloadState.Completed -> {
                            checkSpeechModel()
                            _uiState.update {
                                it.copy(
                                    isDownloadingSpeechModel = false,
                                    isSpeechModelDownloaded = true,
                                    speechModelProgress = 100,
                                    statusMessage = "${tier.displayName} downloaded successfully!"
                                )
                            }
                        }
                        is com.subbot.stt.ModelDownloadState.Error -> {
                            _uiState.update {
                                it.copy(
                                    isDownloadingSpeechModel = false,
                                    statusMessage = "Speech model error: ${state.message}"
                                )
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isDownloadingSpeechModel = false,
                        statusMessage = "Speech model download error: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun setSourceLanguage(code: String) {
        val autoTier = if (code.equals("ja", ignoreCase = true)) {
            if (_uiState.value.isTurboModelDownloaded) WhisperModelTier.TURBO
            else if (_uiState.value.isSmallModelDownloaded) WhisperModelTier.SMALL
            else if (_uiState.value.isBaseModelDownloaded) WhisperModelTier.BASE
            else if (_uiState.value.selectedSpeechModelTier == WhisperModelTier.TINY) WhisperModelTier.SMALL
            else _uiState.value.selectedSpeechModelTier
        } else {
            _uiState.value.selectedSpeechModelTier
        }
        val autoMode = if (code.equals("ja", ignoreCase = true)) {
            TranslationMode.GOOGLE_NER
        } else {
            _uiState.value.translationMode
        }
        _uiState.update { it.copy(sourceLanguage = code, selectedSpeechModelTier = autoTier, translationMode = autoMode) }
        checkTranslationModelsDownloaded()
        checkSpeechModel()
    }

    fun setTargetLanguage(code: String) {
        _uiState.update { it.copy(targetLanguage = code) }
        checkTranslationModelsDownloaded()
    }

    fun swapLanguages() {
        val oldSource = _uiState.value.sourceLanguage
        val oldTarget = _uiState.value.targetLanguage
        _uiState.update {
            it.copy(
                sourceLanguage = oldTarget,
                targetLanguage = oldSource
            )
        }
        checkTranslationModelsDownloaded()
        checkSpeechModel()
    }

    fun setFontSize(fontSize: com.subbot.ui.model.SubtitleFontSize) {
        _uiState.update { it.copy(fontSize = fontSize) }
        com.subbot.service.AudioCaptureService.subtitleQueue?.setFontSize(fontSize)
    }

    fun setPosition(isTop: Boolean) {
        _uiState.update { it.copy(isTopPosition = isTop) }
        com.subbot.service.AudioCaptureService.subtitleQueue?.setTopPosition(isTop)
    }

    fun togglePosition() {
        val newPos = !_uiState.value.isTopPosition
        _uiState.update { it.copy(isTopPosition = newPos) }
        com.subbot.service.AudioCaptureService.subtitleQueue?.setTopPosition(newPos)
    }

    fun toggleBilingual() {
        val newBilingual = !_uiState.value.isBilingual
        _uiState.update { it.copy(isBilingual = newBilingual) }
        com.subbot.service.AudioCaptureService.subtitleQueue?.setBilingual(newBilingual)
    }

    fun setServiceRunning(running: Boolean) {
        _uiState.update { it.copy(isServiceRunning = running) }
    }

    fun checkTargetModelDownloaded() {
        checkTranslationModelsDownloaded()
    }

    fun checkTranslationModelsDownloaded() {
        viewModelScope.launch {
            try {
                val source = _uiState.value.sourceLanguage
                val target = _uiState.value.targetLanguage

                val isDirectWhisperTranslation = !source.equals("en", ignoreCase = true) && !source.equals(target, ignoreCase = true) && _uiState.value.translationMode == TranslationMode.WHISPER_DIRECT

                val sourceDownloaded = if (source.equals("en", ignoreCase = true) || isDirectWhisperTranslation || source.equals(target, ignoreCase = true)) {
                    true
                } else {
                    engine.isModelDownloaded(source)
                }

                val targetDownloaded = if (target.equals("en", ignoreCase = true) && isDirectWhisperTranslation) {
                    true
                } else if (source.equals(target, ignoreCase = true)) {
                    true
                } else {
                    engine.isModelDownloaded(target)
                }

                _uiState.update {
                    it.copy(
                        isSourceModelDownloaded = sourceDownloaded,
                        isTargetModelDownloaded = targetDownloaded
                    )
                }
            } catch (e: Throwable) {
                // Non-Android unit test environment fallback
            }
        }
    }

    fun downloadTargetModel() {
        downloadTranslationModels()
    }

    fun downloadTranslationModels() {
        val source = _uiState.value.sourceLanguage
        val target = _uiState.value.targetLanguage
        viewModelScope.launch {
            try {
                val isDirectWhisperTranslation = !source.equals("en", ignoreCase = true) && !source.equals(target, ignoreCase = true) && _uiState.value.translationMode == TranslationMode.WHISPER_DIRECT
                val missing = mutableListOf<String>()

                if (!source.equals("en", ignoreCase = true) && !isDirectWhisperTranslation && !source.equals(target, ignoreCase = true) && !_uiState.value.isSourceModelDownloaded) {
                    missing.add(source)
                }

                val isTargetExempt = (target.equals("en", ignoreCase = true) && isDirectWhisperTranslation) || source.equals(target, ignoreCase = true)
                if (!isTargetExempt && !_uiState.value.isTargetModelDownloaded) {
                    missing.add(target)
                }

                if (missing.isEmpty()) {
                    checkTranslationModelsDownloaded()
                    return@launch
                }

                _uiState.update { it.copy(isDownloadingModel = true, statusMessage = "Downloading translation models...") }

                for (lang in missing) {
                    engine.downloadModel(lang).collect { status ->
                        when (status) {
                            is DownloadStatus.Downloading -> {
                                _uiState.update {
                                    it.copy(isDownloadingModel = true, statusMessage = "Downloading $lang model...")
                                }
                            }
                            is DownloadStatus.Completed -> {}
                            is DownloadStatus.Failed -> {
                                _uiState.update {
                                    it.copy(
                                        isDownloadingModel = false,
                                        statusMessage = "Failed to download $lang model: ${status.error.localizedMessage}"
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
                checkTranslationModelsDownloaded()
                _uiState.update {
                    it.copy(
                        isDownloadingModel = false,
                        statusMessage = "Translation models ready!"
                    )
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isDownloadingModel = false,
                        statusMessage = "Download error: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}
