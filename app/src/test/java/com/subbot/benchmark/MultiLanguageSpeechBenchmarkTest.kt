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

        assertEquals("Must contain 15 benchmark speech items across EN, JA, and KO", 15, items.size)
        val ids = items.map { it.id }.toSet()
        val expectedIds = setOf(
            "en", "en_long", "en_slang",
            "ja", "ja_1", "ja_2", "ja_long", "mixed_ja_en",
            "ko", "ko_0", "ko_1", "ko_2", "ko_3", "ko_long", "mixed_ko_en"
        )
        assertEquals(expectedIds, ids)

        val languages = items.groupBy { it.languageName }
        assertTrue("Must include English speech samples", languages.containsKey("English"))
        assertTrue("Must include Japanese speech samples", languages.containsKey("Japanese"))
        assertTrue("Must include Korean speech samples", languages.containsKey("Korean"))

        assertEquals("Must have 3 English speech samples", 3, languages["English"]?.size)
        assertEquals("Must have 5 Japanese speech samples", 5, languages["Japanese"]?.size)
        assertEquals("Must have 7 Korean speech samples", 7, languages["Korean"]?.size)

        // Verify that every language includes a sample >= 20 seconds
        languages.forEach { (lang, list) ->
            val hasLongSample = list.any { it.durationSeconds >= 20.0 }
            assertTrue("$lang must include at least one sample >= 20 seconds", hasLongSample)
        }

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
            "en_long.wav",
            "en_slang.wav",
            "ja.wav",
            "ja_1.wav",
            "ja_2.wav",
            "ja_long.wav",
            "mixed_ja_en.wav",
            "ko.wav",
            "ko_0.wav",
            "ko_1.wav",
            "ko_2.wav",
            "ko_3.wav",
            "ko_long.wav",
            "mixed_ko_en.wav"
        )
        for (wavName in wavFiles) {
            val stream = getResourceStream(wavName)
            val samples = BenchmarkEngine.parseWavToNormalizedFloats(stream)
            assertTrue("$wavName must contain audio samples", samples.isNotEmpty())
            assertTrue("$wavName must have at least 1 second (16000 samples)", samples.size >= 16000)

            if (wavName.contains("long")) {
                assertTrue("$wavName must have at least 20 seconds (320000 samples)", samples.size >= 320000)
            }

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
            modelType = BenchmarkEngine.WhisperModelType.BASE,
            onProgress = { lang, step, percent ->
                progressUpdates.add("$lang: $step ($percent%)")
            }
        )

        assertNotNull(report)
        assertEquals(15, report.languageResults.size)
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

        println("=== Real Benchmark Performance Scorecard (Whisper Base) ===")
        println("Overall Score: ${report.overallScore}/100 [Grade: ${report.grade}]")
        println("Total Speech Duration: ${"%.2f".format(report.totalAudioDurationSec)}s across ${report.languageResults.size} samples")
        println("Mean RTF: ${"%.3f".format(report.meanRealTimeFactor)} (Real-Time Capable: ${report.isRealTimeCapable})")
        println("Mean VAD Latency: ${"%.3f".format(report.meanVadLatencyMs)} ms/chunk")
        println("Mean Similarity: ${"%.1f".format(report.meanSimilarityScore)}%")
    }

    @Test
    fun testWhisperSmallModelBenchmark_achievesSuperiorJapaneseFidelityAndRealTimeCapable() = runTest {
        val json = getResourceStream("benchmark_manifest.json").bufferedReader().use { it.readText() }
        val items = BenchmarkEngine.parseManifest(json)
        val engine = BenchmarkEngine(translationEngine = null)

        val report = engine.runBenchmark(
            manifestItems = items,
            audioProvider = { filename -> getResourceStream(filename) },
            targetLang = "vi",
            modelType = BenchmarkEngine.WhisperModelType.SMALL
        )

        assertNotNull(report)
        assertEquals(15, report.languageResults.size)

        // Find Japanese extended sample result
        val jaLong = report.languageResults.firstOrNull { it.languageId == "ja_long" }
        assertNotNull(jaLong)
        assertTrue("Whisper Small must achieve >= 78% fidelity on ja_long", jaLong!!.similarityScore >= 78)
        assertTrue("Whisper Small ja_long must be real-time capable (RTF < 1.0)", jaLong.realTimeFactor < 1.0)
        assertTrue("Whisper Small ja_long must correctly recognize 'マイク'", jaLong.transcribedText.contains("マイク"))
        assertTrue("Whisper Small ja_long must not hallucinate 'マイコン'", !jaLong.transcribedText.contains("マイコン"))
        assertTrue("Whisper Small ja_long must correctly recognize 'ちゃんと聞こえて'", jaLong.transcribedText.contains("ちゃんと聞こえて"))

        // Whisper Small across all samples
        val ja1 = report.languageResults.firstOrNull { it.languageId == "ja_1" }
        assertNotNull(ja1)
        assertTrue("Whisper Small ja_1 must correctly recognize 'マイク'", ja1!!.transcribedText.contains("マイク"))

        println("=== Real Benchmark Performance Scorecard (Whisper Small) ===")
        println("Overall Score: ${report.overallScore}/100 [Grade: ${report.grade}]")
        println("Total Speech Duration: ${"%.2f".format(report.totalAudioDurationSec)}s")
        println("Mean RTF: ${"%.3f".format(report.meanRealTimeFactor)}")
        println("ja_long RTF: ${jaLong.realTimeFactor} (${"%.1f".format(1.0 / jaLong.realTimeFactor)}x realtime), Similarity: ${jaLong.similarityScore}%")
        println("ja_long Transcribed: ${jaLong.transcribedText}")
    }

    @Test
    fun testWhisperLargeV3TurboModelBenchmark_achievesTopTierAccuracyOnSlangAndCodeSwitchedSpeech() = runTest {
        val json = getResourceStream("benchmark_manifest.json").bufferedReader().use { it.readText() }
        val items = BenchmarkEngine.parseManifest(json)
        val engine = BenchmarkEngine(translationEngine = null)

        val report = engine.runBenchmark(
            manifestItems = items,
            audioProvider = { filename -> getResourceStream(filename) },
            targetLang = "vi",
            modelType = BenchmarkEngine.WhisperModelType.LARGE_V3_TURBO
        )

        assertNotNull(report)
        assertEquals(15, report.languageResults.size)

        // 1. Verify Slang English transcription accuracy
        val enSlang = report.languageResults.firstOrNull { it.languageId == "en_slang" }
        assertNotNull(enSlang)
        assertTrue("Large-v3-Turbo en_slang must recognize 'inference'", enSlang!!.transcribedText.contains("inference"))
        assertTrue("Large-v3-Turbo en_slang must recognize 'slide 53'", enSlang.transcribedText.contains("slide 53"))
        assertTrue("Large-v3-Turbo en_slang must recognize 'every 10 steps'", enSlang.transcribedText.contains("every 10 steps"))
        assertTrue("Large-v3-Turbo en_slang must be real-time on CPU (RTF < 1.0)", enSlang.realTimeFactor < 1.0)

        // 2. Verify Mixed Japanese + English speech
        val mixedJaEn = report.languageResults.firstOrNull { it.languageId == "mixed_ja_en" }
        assertNotNull(mixedJaEn)
        assertTrue("Large-v3-Turbo mixed_ja_en must accurately recognize 'ひどいなすごく'", mixedJaEn!!.transcribedText.contains("ひどいなすごく"))
        assertTrue("Large-v3-Turbo mixed_ja_en must accurately recognize 'ありがとうございます'", mixedJaEn.transcribedText.contains("ありがとうございます"))
        assertTrue("Large-v3-Turbo mixed_ja_en must not produce Base's hallucination", !mixedJaEn.transcribedText.contains("アルバー"))

        // 3. Verify Japanese Long accuracy & Real-Time execution
        val jaLong = report.languageResults.firstOrNull { it.languageId == "ja_long" }
        assertNotNull(jaLong)
        assertTrue("Large-v3-Turbo ja_long must achieve >= 78% fidelity", jaLong!!.similarityScore >= 78)
        assertTrue("Large-v3-Turbo ja_long must be real-time capable (RTF < 1.0)", jaLong.realTimeFactor < 1.0)
        assertTrue("Large-v3-Turbo ja_long must correctly recognize 'マイク'", jaLong.transcribedText.contains("マイク"))
        assertTrue("Large-v3-Turbo ja_long must not hallucinate 'マイコン'", !jaLong.transcribedText.contains("マイコン"))

        println("=== Real Benchmark Performance Scorecard (Whisper Large-v3-Turbo) ===")
        println("Overall Score: ${report.overallScore}/100 [Grade: ${report.grade}]")
        println("Total Speech Duration: ${"%.2f".format(report.totalAudioDurationSec)}s across 15 samples")
        println("Mean RTF: ${"%.3f".format(report.meanRealTimeFactor)}")
        println("en_slang RTF: ${enSlang.realTimeFactor}, Transcribed: \"${enSlang.transcribedText}\"")
        println("mixed_ja_en RTF: ${mixedJaEn.realTimeFactor}, Transcribed: \"${mixedJaEn.transcribedText}\"")
        println("ja_long RTF: ${jaLong.realTimeFactor}, Transcribed: \"${jaLong.transcribedText}\"")
    }
}

