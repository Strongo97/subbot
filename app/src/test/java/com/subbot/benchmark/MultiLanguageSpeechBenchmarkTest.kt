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
    fun testManifestParsing_parsesAllThreeLanguages() {
        val json = getResourceStream("benchmark_manifest.json").bufferedReader().use { it.readText() }
        val items = BenchmarkEngine.parseManifest(json)

        assertEquals("Must contain 3 benchmark speech items (EN, JA, KO)", 3, items.size)
        val ids = items.map { it.id }
        assertTrue("Must contain English", ids.contains("en"))
        assertTrue("Must contain Japanese", ids.contains("ja"))
        assertTrue("Must contain Korean", ids.contains("ko"))

        items.forEach { item ->
            assertTrue("Duration must be positive for ${item.id}", item.durationSeconds > 1.0)
            assertTrue("Transcript must not be blank for ${item.id}", item.transcript.isNotBlank())
            assertTrue("EN reference must not be blank for ${item.id}", item.referenceTranslationEn.isNotBlank())
            assertTrue("VI reference must not be blank for ${item.id}", item.referenceTranslationVi.isNotBlank())
        }
    }

    @Test
    fun testWavPcmExtraction_loadsValidAudioSamples() {
        val wavFiles = listOf("en.wav", "ja.wav", "ko.wav")
        for (wavName in wavFiles) {
            val stream = getResourceStream(wavName)
            val samples = BenchmarkEngine.parseWavToNormalizedFloats(stream)
            assertTrue("$wavName must contain audio samples", samples.isNotEmpty())
            assertTrue("$wavName must have at least 1 second (16000 samples)", samples.size >= 16000)

            // Verify samples are normalized in [-1.0, 1.0]
            val maxSample = samples.maxOrNull() ?: 0f
            val minSample = samples.minOrNull() ?: 0f
            assertTrue("Max sample must be <= 1.0f", maxSample <= 1.0f)
            assertTrue("Min sample must be >= -1.0f", minSample >= -1.0f)
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
    fun testRunBenchmark_producesHighPerformanceScorecard() = runTest {
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
        assertEquals(3, report.languageResults.size)
        assertTrue("Report must be real-time capable (RTF < 1.0)", report.isRealTimeCapable)
        assertTrue("Overall score must be >= 85 (Grade A or A+)", report.overallScore >= 85)
        assertTrue("VAD latency must be fast (< 2.0ms per chunk)", report.meanVadLatencyMs < 2.0)
        assertTrue("Progress callbacks must have fired", progressUpdates.isNotEmpty())

        println("=== Benchmark Performance Scorecard ===")
        println("Overall Score: ${report.overallScore}/100 [Grade: ${report.grade}]")
        println("Mean RTF: ${"%.3f".format(report.meanRealTimeFactor)} (Real-Time Capable: ${report.isRealTimeCapable})")
        println("Mean VAD Latency: ${"%.3f".format(report.meanVadLatencyMs)} ms/chunk")
        println("Mean Translation Latency: ${report.meanTranslationLatencyMs} ms")
        report.languageResults.forEach { res ->
            println("  ${res.flagEmoji} ${res.languageName}: Audio=${"%.1f".format(res.durationSeconds)}s, RTF=${"%.3f".format(res.realTimeFactor)}, Similarity=${res.similarityScore}%, Passed=${res.passed}")
        }
    }
}
