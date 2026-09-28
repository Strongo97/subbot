package com.subbot.ui.overlay

import com.subbot.ui.model.SubtitleUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages subtitle lifecycle, state emissions, and automatic fade-out timers upon silence.
 *
 * @param scope CoroutineScope used to launch auto-fade debounce timer jobs.
 * @param autoFadeTimeoutMs Time in milliseconds before subtitles automatically disappear during silence.
 */
class SubtitleQueue(
    private val scope: CoroutineScope,
    private val autoFadeTimeoutMs: Long = 3500L
) {
    private val _uiState = MutableStateFlow(SubtitleUiState())
    val uiState: StateFlow<SubtitleUiState> = _uiState.asStateFlow()

    private var fadeJob: Job? = null

    /**
     * Posts a new utterance and resets the silence fade-out timer.
     *
     * @param original Recognized source text.
     * @param translated Translated target text.
     */
    fun postSubtitle(original: String, translated: String) {
        fadeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            originalText = original,
            translatedText = translated,
            isVisible = true
        )
        fadeJob = scope.launch {
            delay(autoFadeTimeoutMs)
            _uiState.value = _uiState.value.copy(isVisible = false)
        }
    }

    /**
     * Toggles bilingual display mode.
     */
    fun setBilingual(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isBilingual = enabled)
    }

    /**
     * Updates font size configuration.
     */
    fun setFontSize(fontSize: com.subbot.ui.model.SubtitleFontSize) {
        _uiState.value = _uiState.value.copy(fontSize = fontSize)
    }

    /**
     * Updates screen position preference.
     */
    fun setTopPosition(isTop: Boolean) {
        _uiState.value = _uiState.value.copy(isTopPosition = isTop)
    }

    /**
     * Updates audio listening indicator activity state.
     */
    fun setListening(isListening: Boolean) {
        _uiState.value = _uiState.value.copy(isListening = isListening)
    }

    /**
     * Clears all subtitle text and immediately hides the overlay.
     */
    fun clear() {
        fadeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            originalText = "",
            translatedText = "",
            isVisible = false
        )
    }
}
