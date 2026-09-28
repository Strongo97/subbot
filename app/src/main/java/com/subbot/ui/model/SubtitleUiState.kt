package com.subbot.ui.model

/**
 * Font sizing configuration for the subtitle overlay display.
 */
enum class SubtitleFontSize(
    val label: String,
    val textSp: Float,
    val originalSp: Float
) {
    NORMAL("Normal", 16f, 13f),
    LARGE("Large", 19f, 15f),
    EXTRA_LARGE("Extra Large", 23f, 18f);

    companion object {
        fun fromName(name: String?): SubtitleFontSize {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: NORMAL
        }
    }
}

/**
 * Reactive UI state for the floating subtitle overlay window.
 *
 * @property originalText Speech recognized in source language.
 * @property translatedText Translated text to display.
 * @property isVisible Whether subtitle strip is actively shown or faded out.
 * @property isBilingual Whether both source and translated text are displayed simultaneously.
 * @property fontSize Chosen subtitle font sizing scale.
 * @property isTopPosition Whether subtitles are anchored to the top of the display.
 * @property isListening Whether speech/audio activity is actively being captured.
 */
data class SubtitleUiState(
    val originalText: String = "",
    val translatedText: String = "",
    val isVisible: Boolean = false,
    val isBilingual: Boolean = false,
    val fontSize: SubtitleFontSize = SubtitleFontSize.NORMAL,
    val isTopPosition: Boolean = false,
    val isListening: Boolean = true
)
