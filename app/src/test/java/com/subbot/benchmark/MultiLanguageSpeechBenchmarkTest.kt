package com.subbot.benchmark

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class MultiLanguageSpeechBenchmarkTest {

    private fun getResourceStream(name: String): InputStream {
        val stream = javaClass.getResourceAsStream("/speech/$name")
        assertNotNull("Resource /speech/$name must exist in test resources", stream)
        return stream!!
    }

    @Test
    fun testManifestParsing_parsesAllLanguagesAndSpeechSamples() {
        val json = getResourceStream("benchmark_manifest.json").bufferedReader().use { it.readText() }
        val items = BenchmarkEngine.parseManifest(json)

        assertEquals("Must contain 9 benchmark speech items across EN, JA, and KO", 9, items.size)
        val ids = items.map { it.id }.toSet()
        val expectedIds = setOf("en", "ja", "ja_1", "ja_2", "ko", "ko_0", "ko_1", "ko_2", "ko_3")
        assertEquals(expectedIds, ids)

        val languages = items.groupBy { it.languageName }
        assertTrue("Must include English speech samples", languages.containsKey("English"))
        assertTrue("Must include Japanese speech samples", languages.containsKey("Japanese"))
        assertTrue("Must include Korean speech samples", languages.containsKey("Korean"))

        assertEquals("Must have 3 Japanese speech samples", 3, languages["Japanese"]?.size)
        assertEquals("Must have 5 Korean speech samples", 5, languages["Korean"]?.size)

        items.forEach { item ->
            assertTrue("Duration must be positive for ${item.id}", item.durationSeconds > 1.0)
            assertTrue("Transcript must not be blank for ${item.id}", item.transcript.isNotBlank())
            assertTrue("EN reference must not be blank for ${item.id}", item.referenceTranslationEn.isNotBlank())
            assertTrue("VI reference must not be blank for ${item.id}", item.referenceTranslationVi.isNotBlank())
        }
    }

    @Test
    fun testWavPcmExtraction_loadsValidAudioSamples() {
        val wavFiles = listOf(
            "en.wav",
            "ja.wav",
            "ja_1.wav",
            "ja_2.wav",
            "ko.wav",
            "ko_0.wav",
            "ko_1.wav",
            "ko_2.wav",
            "ko_3.wav"
        )
        for (wavName in wavFiles) {
            val stream = getResourceStream(wavName)
            val samples = BenchmarkEngine.parseWavToNormalizedFloats(stream)
            assertTrue("$wavName must contain audio samples", samples.isNotEmpty())
            assertTrue("$wavName must have at least 1 second (16000 samples)", samples.size >= 16000)

            // Verify samples are normalized in [-1.0, 1.0]
            val maxSample = samples.maxOrNull() ?: 0f
            val minSample = samples.minOrNull() ?: 0f
            assertTrue("Max sample must be <= 1.0f in $wavName", maxSample <= 1.0f)
            assertTrue("Min sample must be >= -1.0f in $wavName", minSample >= -1.0f)
        }
    }

    @Test
    fun testSimilarityScore_evaluatesFidelity() {
        assertEquals(100, BenchmarkEngine.calculateSimilarityScore("Hello world", "Hello world"))
        assertEquals(100, BenchmarkEngine.calculateSimilarityScore("こんにちは、田中です", "こんにちは、田中です。"))
        assertEquals(100, BenchmarkEngine.calculateSimilarityScore("안녕하세요", "안녕하세요!"))
        assertTrue(BenchmarkEngine.calculateSimilarityScore("Hello world", "Hello brave world") > 60)
        assertEquals(0, BenchmarkEngine.calculateSimilarityScore("", "Hello"))
    }

    @Test
    fun testRunBenchmark_evaluatesRealisticAcousticsAndInferenceMetrics() = runTest {
        val json = getResourceStream("benchmark_manifest.json").bufferedReader().use { it.readText() }
        val items = BenchmarkEngine.parseManifest(json)
        val engine = BenchmarkEngine(translationEngine = null)

        val progressUpdates = mutableListOf<String>()
        val report = engine.runBenchmark(
            manifestItems = items,
            audioProvider = { filename -> getResourceStream(filename) },
            targetLang = "vi",
            onProgress = { lang, step, percent ->
                progressUpdates.add("$lang: $step ($percent%)")
            }
        )

        assertNotNull(report)
        assertEquals(9, report.languageResults.size)
        assertTrue("Report must be real-time capable (RTF < 1.0)", report.isRealTimeCapable)
        assertTrue("Mean RTF must be between 0.15 and 0.45", report.meanRealTimeFactor in 0.15..0.45)
        assertTrue("VAD latency must be fast (< 0.5ms per chunk)", report.meanVadLatencyMs < 0.5)
        assertTrue("Overall score must reflect real performance (>= 70)", report.overallScore >= 70)
        assertTrue("Progress callbacks must have fired", progressUpdates.isNotEmpty())

        // Verify that RTF and similarity scores vary across audio samples (not hardcoded identical values)
        val distinctRtfs = report.languageResults.map { it.realTimeFactor }.toSet()
        assertTrue("RTF must vary across different audio samples", distinctRtfs.size > 3)

        val distinctSims = report.languageResults.map { it.similarityScore }.toSet()
        assertTrue("Similarity score must vary across different samples and languages", distinctSims.size > 3)

        println("=== Real Benchmark Performance Scorecard ===")
        println("Overall Score: ${report.overallScore}/100 [Grade: ${report.grade}]")
        println("Total Speech Duration: ${"%.2f".format(report.totalAudioDurationSec)}s across ${report.languageResults.size} samples")
        println("Mean RTF: ${"%.3f".format(report.meanRealTimeFactor)} (Real-Time Capable: ${report.isRealTimeCapable})")
        println("Mean VAD Latency: ${"%.3f".format(report.meanVadLatencyMs)} ms/chunk")
        println("Mean Similarity: ${"%.1f".format(report.meanSimilarityScore)}%")
        println("Per-Sample Details:")
        report.languageResults.forEach { res ->
            println("  ${res.flagEmoji} [${res.languageId}] ${res.languageName}: Audio=${"%.1f".format(res.durationSeconds)}s, RTF=${"%.3f".format(res.realTimeFactor)} (${"%.1f".format(1.0 / res.realTimeFactor)}x realtime), Speech=${"%.1f".format(res.speechActivityPercent)}%, Peak=${"%.1f".format(res.peakDbfs)}dBFS, Sim=${res.similarityScore}%, Passed=${res.passed}")
            println("     Model: \"${res.transcribedText}\"")
        }
    }
}
