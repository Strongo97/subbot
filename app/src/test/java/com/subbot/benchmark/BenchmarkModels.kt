package com.subbot.benchmark

/**
 * Benchmark item definition representing a speech audio sample with references.
 */
data class SpeechBenchmarkItem(
    val id: String,
    val languageName: String,
    val flagEmoji: String,
    val audioFile: String,
    val sampleRate: Int = 16000,
    val durationSeconds: Double,
    val transcript: String,
    val referenceTranslationEn: String,
    val referenceTranslationVi: String
)

/**
 * Results for a single language audio sample in the benchmark.
 */
data class LanguageBenchmarkResult(
    val languageId: String,
    val languageName: String,
    val flagEmoji: String,
    val durationSeconds: Double,
    val audioChunksProcessed: Int,
    val avgVadLatencyMs: Double,
    val sttLatencyMs: Long,
    val realTimeFactor: Double, // STT time / Audio duration (< 1.0 is real-time capable)
    val translationLatencyMs: Long,
    val transcribedText: String,
    val translatedText: String,
    val similarityScore: Int, // 0 - 100%
    val passed: Boolean,
    val speechActivityPercent: Double = 0.0,
    val peakDbfs: Float = 0.0f,
    val snrDb: Float = 0.0f
)

/**
 * Aggregate benchmark scorecard and performance rating report.
 */
data class BenchmarkReport(
    val overallScore: Int, // 0 - 100
    val grade: String,     // "A+ (Studio Quality)", "A (Real-Time Excellent)", "B+ (Good Performance)", "B (Acceptable)"
    val totalAudioDurationSec: Double,
    val totalElapsedMs: Long,
    val meanRealTimeFactor: Double,
    val meanVadLatencyMs: Double,
    val meanTranslationLatencyMs: Long,
    val meanSimilarityScore: Double,
    val languageResults: List<LanguageBenchmarkResult>,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isRealTimeCapable: Boolean get() = meanRealTimeFactor < 1.0

    companion object {
        fun calculateGrade(score: Int): String {
            return when {
                score >= 95 -> "A+ (Studio Quality)"
                score >= 85 -> "A (Real-Time Excellent)"
                score >= 75 -> "B+ (Good Performance)"
                score >= 65 -> "B (Acceptable)"
                else -> "C (Needs Tuning)"
            }
        }
    }
}
