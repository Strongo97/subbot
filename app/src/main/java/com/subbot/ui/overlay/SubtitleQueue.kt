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

    companion object {
        /** Shown in the translated slot while the first sentence is still being aggregated. */
        const val PENDING_PLACEHOLDER = "…"
    }

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
     * Shows provisional (not yet translated) source text in the original slot while a
     * sentence is still being aggregated. The currently visible translation is kept so the
     * viewer can finish reading it; if nothing is visible, [PENDING_PLACEHOLDER] is shown
     * instead so the primary line does not duplicate the source text.
     */
    fun postPendingOriginal(original: String) {
        val current = _uiState.value
        val translated = if (current.isVisible && current.translatedText.isNotBlank()) {
            current.translatedText
        } else {
            PENDING_PLACEHOLDER
        }
        postSubtitle(original = original, translated = translated)
    }

    /**
     * Restarts the fade-out timer of the currently visible subtitle without changing its
     * text. Used while speech continues but no new translation is ready yet. No-op when
     * nothing is visible.
     */
    fun keepAlive() {
        if (!_uiState.value.isVisible) return
        fadeJob?.cancel()
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
