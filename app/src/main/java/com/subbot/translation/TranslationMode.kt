package com.subbot.translation

/**
 * Mode of translation used for converting spoken audio to target subtitles:
 *
 * - GOOGLE_NER:
 *   Whisper transcribes speech into the source language (task = "transcribe"),
 *   and Google ML Kit performs neural translation with Named Entity Recognition (NER).
 *   Preserves personal names (e.g. "田中" -> "Tanaka", "山田" -> "Yamada"), proper nouns,
 *   and grammatical honorifics.
 *   In bilingual mode, displays native source transcript on top and translated text on bottom.
 *   (Recommended for Japanese, Vietnamese, and general speech with proper nouns).
 *
 * - WHISPER_DIRECT:
 *   Whisper performs direct cross-attention acoustic translation into English (task = "translate").
 *   Ultra-low latency with 0ms translation lag, but lacks Named Entity Recognition for personal names.
 */
enum class TranslationMode(
    val id: String,
    val displayName: String,
    val description: String
) {
    GOOGLE_NER(
        id = "google_ner",
        displayName = "Google NER (Recommended)",
        description = "High accuracy • Preserves Japanese names (Tanaka, etc.) & honorifics"
    ),
    WHISPER_DIRECT(
        id = "whisper_direct",
        displayName = "Whisper Direct (Fast)",
        description = "Acoustic translation • Ultra-low latency • English target only"
    );

    companion object {
        fun fromId(id: String?): TranslationMode {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GOOGLE_NER
        }
    }
}
