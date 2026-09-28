package com.subbot.ui.model

/**
 * Reactive UI state for the floating subtitle overlay window.
 *
 * @property originalText Speech recognized in source language.
 * @property translatedText Translated text to display.
 * @property isVisible Whether subtitle strip is actively shown or faded out.
 * @property isBilingual Whether both source and translated text are displayed simultaneously.
 */
data class SubtitleUiState(
    val originalText: String = "",
    val translatedText: String = "",
    val isVisible: Boolean = false,
    val isBilingual: Boolean = false
)
