/*
 * On-device STT benchmark: real Sherpa-ONNX decode on the phone's CPU (the JVM benchmark under
 * app/src/test/ runs on the dev host, so its RTF numbers do not reflect phones).
 *
 * For every model tier whose files are on the device it measures recognizer load time, per-file
 * decode time, RTF and character/word similarity against the ground truth in
 * app/src/test/resources/speech/benchmark_manifest.json (packaged as androidTest assets), sweeping
 * numThreads over {2, 4, 6, 8} capped at the CPU core count. Tiers without model files are skipped.
 *
 * 1) Put models on the device. Either download them in the app (Dashboard -> Speech Model Tier ->
 *    Download), which stores them in the app's internal files/models/whisper, or adb push them to the
 *    external files dir (also searched):
 *      D=/sdcard/Android/data/com.subbot/files/models/whisper
 *      adb shell mkdir -p $D
 *      adb push base-encoder.int8.onnx base-decoder.int8.onnx base-tokens.txt $D/
 *    SenseVoice files keep the app's local "sense-voice-" prefix:
 *      adb push model.int8.onnx $D/sense-voice-model.int8.onnx
 *      adb push tokens.txt $D/sense-voice-tokens.txt
 *
 * 2) Run. `connectedDebugAndroidTest` uninstalls the app afterwards (deleting downloaded models and the
 *    report), so prefer installing and running the instrumentation directly:
 *      ./gradlew installDebug installDebugAndroidTest
 *      adb shell am instrument -w -r \
 *        -e class com.subbot.benchmark.OnDeviceSttBenchmarkTest \
 *        [-e tiers sense_voice,base] [-e threads 2,4] [-e files en,ja,ko_long] \
 *        com.subbot.test/androidx.test.runner.AndroidJUnitRunner
 *    or through Gradle (keeps APKs installed):
 *      ./gradlew connectedDebugAndroidTest \
 *        -Pandroid.testInstrumentationRunnerArguments.class=com.subbot.benchmark.OnDeviceSttBenchmarkTest \
 *        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
 *
 * 3) Results: a table in `adb logcat -s SttBenchmark` (and in the `am instrument -r` stream output), plus
 *    a JSON report per tier:  adb pull /sdcard/Android/data/com.subbot/files/benchmark/
 */
package com.subbot.benchmark

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.subbot.stt.SpeechRecognitionConfig
import com.subbot.stt.SttModelType
import com.subbot.stt.TierModelFiles
import com.subbot.stt.WhisperModelManager
import com.subbot.stt.WhisperModelTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class OnDeviceSttBenchmarkTest {

    private data class SpeechSample(
        val id: String,
        val language: String,
        val audioFile: String,
        val transcript: String,
        val samples: FloatArray,
        val sampleRate: Int
    ) {
        val durationSec: Double get() = samples.size.toDouble() / sampleRate
    }

    private data class FileResult(
        val sampleId: String,
        val language: String,
        val audioSec: Double,
        val decodeMs: Double,
        val rtf: Double,
        val charSimilarity: Int,
        val wordSimilarity: Int,
        val hypothesis: String,
        val reference: String
    )

    private data class ThreadRun(
        val numThreads: Int,
        val loadMsByLanguage: Map<String, Double>,
        val files: List<FileResult>
    ) {
        val totalAudioSec get() = files.sumOf { it.audioSec }
        val totalDecodeMs get() = files.sumOf { it.decodeMs }
        val overallRtf get() = if (totalAudioSec > 0) totalDecodeMs / 1000.0 / totalAudioSec else 0.0
        val meanCharSimilarity get() = files.map { it.charSimilarity }.average()
        val meanWordSimilarity get() = files.map { it.wordSimilarity }.average()
        val meanLoadMs get() = loadMsByLanguage.values.average()
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    @Test fun benchmarkWhisperTiny() = benchmarkTier(WhisperModelTier.TINY)
    @Test fun benchmarkWhisperBase() = benchmarkTier(WhisperModelTier.BASE)
    @Test fun benchmarkWhisperSmall() = benchmarkTier(WhisperModelTier.SMALL)
    @Test fun benchmarkWhisperTurbo() = benchmarkTier(WhisperModelTier.TURBO)
    @Test fun benchmarkSenseVoice() = benchmarkTier(WhisperModelTier.SENSE_VOICE)

    private fun benchmarkTier(tier: WhisperModelTier) {
        val tierFilter = argList("tiers")
        assumeTrue("Tier ${tier.id} excluded by 'tiers' argument", tierFilter == null || tier.id in tierFilter)

        val searchDirs = modelSearchDirs()
        val files = searchDirs.map { WhisperModelManager(it) }
            .firstOrNull { it.isTierAvailable(tier) }
            ?.resolveTierFiles(tier)
        assumeTrue(
            "Model files for ${tier.id} not found in ${searchDirs.joinToString()} (expected ${tier.remoteFiles.map(tier::localFileName)})",
            files != null
        )
        files!!

        val samples = loadSamples().filter { tier.supportsLanguage(it.language) }
        assumeTrue("No benchmark samples selected for ${tier.id}", samples.isNotEmpty())

        val runs = threadCounts().map { threads -> runWithThreads(tier, files, samples, threads) }

        val table = formatTable(tier, runs)
        table.lines().forEach { Log.i(TAG, it) }
        instrumentation.sendStatus(STATUS_IN_PROGRESS, Bundle().apply {
            putString(REPORT_KEY_STREAM, "\n$table\n")
        })

        val report = writeJsonReport(tier, files, runs)
        Log.i(TAG, "JSON report written to ${report.absolutePath}")
        instrumentation.sendStatus(STATUS_IN_PROGRESS, Bundle().apply {
            putString(REPORT_KEY_STREAM, "Report: ${report.absolutePath}\n")
        })
    }

    /** Decodes every sample with one recognizer per language (the app configures the source language up front). */
    private fun runWithThreads(
        tier: WhisperModelTier,
        files: TierModelFiles,
        samples: List<SpeechSample>,
        numThreads: Int
    ): ThreadRun {
        val loadMs = linkedMapOf<String, Double>()
        val results = mutableListOf<FileResult>()

        for ((language, group) in samples.groupBy { it.language }) {
            val config = SpeechRecognitionConfig(
                encoderPath = if (tier.modelType == SttModelType.WHISPER) files.model.absolutePath else "",
                decoderPath = files.decoder?.absolutePath ?: "",
                tokensPath = files.tokens.absolutePath,
                language = language,
                task = "transcribe",
                numThreads = numThreads,
                featureDim = tier.featureDim,
                modelType = tier.modelType,
                modelPath = if (tier.modelType == SttModelType.WHISPER) "" else files.model.absolutePath
            )

            val loadStart = SystemClock.elapsedRealtimeNanos()
            val recognizer = OfflineRecognizer(null, config.toRecognizerConfig())
            loadMs[language] = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1e6
            try {
                // Warm-up decode (not measured): first inference pays one-off allocation costs.
                decode(recognizer, group.first().samples.copyOf(min(group.first().samples.size, WARMUP_SAMPLES)), group.first().sampleRate)

                for (sample in group) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val text = decode(recognizer, sample.samples, sample.sampleRate)
                    val decodeMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    results += FileResult(
                        sampleId = sample.id,
                        language = language,
                        audioSec = sample.durationSec,
                        decodeMs = decodeMs,
                        rtf = decodeMs / 1000.0 / sample.durationSec,
                        charSimilarity = characterSimilarity(text, sample.transcript),
                        wordSimilarity = wordSimilarity(text, sample.transcript),
                        hypothesis = text,
                        reference = sample.transcript
                    )
                    Log.d(TAG, "${tier.id} t=$numThreads ${sample.id}: ${"%.0f".format(decodeMs)}ms -> $text")
                }
            } finally {
                recognizer.release()
            }
        }
        return ThreadRun(numThreads, loadMs, results)
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    // ---------------------------------------------------------------- inputs

    private fun modelSearchDirs(): List<File> = listOfNotNull(
        File(targetContext.filesDir, "models/whisper"), // where WhisperModelManager downloads
        targetContext.getExternalFilesDir(null)?.let { File(it, "models/whisper") } // adb-pushable
    )

    private fun argList(key: String): Set<String>? =
        args.getString(key)?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
            ?.takeIf { it.isNotEmpty() }

    private fun threadCounts(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val requested = argList("threads")?.mapNotNull { it.toIntOrNull() } ?: listOf(2, 4, 6, 8)
        return requested.filter { it in 1..cores }.distinct().sorted().ifEmpty { listOf(min(2, cores)) }
    }

    private fun loadSamples(): List<SpeechSample> {
        val assets = instrumentation.context.assets // test APK assets = app/src/test/resources/speech
        val manifest = JSONArray(assets.open(MANIFEST).bufferedReader().use { it.readText() })
        val fileFilter = argList("files")
        return (0 until manifest.length()).map { manifest.getJSONObject(it) }
            .filter { fileFilter == null || it.getString("id").lowercase() in fileFilter }
            .map { item ->
                val id = item.getString("id")
                val (pcm, rate) = assets.open(item.getString("audioFile")).use { parseWav(it.readBytes()) }
                SpeechSample(
                    id = id,
                    language = languageOf(id),
                    audioFile = item.getString("audioFile"),
                    transcript = item.getString("transcript"),
                    samples = pcm,
                    sampleRate = rate
                )
            }
    }

    /** "ja_long" -> "ja"; code-switched "mixed_ko_en" -> primary language "ko". */
    private fun languageOf(id: String): String {
        val parts = id.lowercase().split('_')
        return if (parts.first() == "mixed" && parts.size > 1) parts[1] else parts.first()
    }

    /** Parses a PCM16 WAV (downmixing to mono) into normalized floats + sample rate. */
    private fun parseWav(bytes: ByteArray): Pair<FloatArray, Int> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
        var channels = 1
        var sampleRate = 16000
        var bits = 16
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = buf.getInt(pos + 4)
            val body = pos + 8
            if (chunkId == "fmt ") {
                channels = buf.getShort(body + 2).toInt()
                sampleRate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (chunkId == "data") {
                require(bits == 16) { "Only PCM16 WAV supported (got $bits bits)" }
                val end = min(bytes.size, body + chunkSize)
                val frames = (end - body) / (2 * channels)
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until channels) acc += buf.getShort(body + (i * channels + c) * 2) / 32768f
                    out[i] = acc / channels
                }
                return out to sampleRate
            }
            pos = body + chunkSize + (chunkSize and 1)
        }
        error("WAV has no data chunk")
    }

    // ---------------------------------------------------------------- scoring
    // Mirrors BenchmarkEngine.calculateSimilarityScore (app/src/test), which the androidTest source set
    // cannot see; word similarity adds a WER-style score for space-delimited languages.

    private fun normalizeChars(text: String): String =
        text.lowercase().replace(Regex("[^a-zA-Z0-9\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]"), "")

    private fun normalizeWords(text: String): List<String> =
        text.lowercase().split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}']"), "") }
            .filter { it.isNotEmpty() }

    private fun characterSimilarity(candidate: String, reference: String): Int {
        val c = normalizeChars(candidate)
        val r = normalizeChars(reference)
        return similarity(c.toList(), r.toList())
    }

    private fun wordSimilarity(candidate: String, reference: String): Int =
        similarity(normalizeWords(candidate), normalizeWords(reference))

    /** (1 - editDistance / maxLen) * 100, clamped to 0..100. */
    private fun <T> similarity(candidate: List<T>, reference: List<T>): Int {
        if (candidate.isEmpty() || reference.isEmpty()) return if (candidate == reference) 100 else 0
        var prev = IntArray(reference.size + 1) { it }
        var cur = IntArray(reference.size + 1)
        for (i in 1..candidate.size) {
            cur[0] = i
            for (j in 1..reference.size) {
                val cost = if (candidate[i - 1] == reference[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        val distance = prev[reference.size]
        return ((1.0 - distance.toDouble() / max(candidate.size, reference.size)) * 100).toInt().coerceIn(0, 100)
    }

    // ---------------------------------------------------------------- reporting

    private fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (${socModel()}), ${Runtime.getRuntime().availableProcessors()} cores, " +
            "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()}"

    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

    private fun formatTable(tier: WhisperModelTier, runs: List<ThreadRun>): String = buildString {
        appendLine("=== On-device STT benchmark: ${tier.displayName} ===")
        appendLine(deviceSummary())
        appendLine(String.format(Locale.US, "%-4s %-12s %6s %9s %6s %6s %6s", "thr", "sample", "dur_s", "decode_ms", "RTF", "char%", "word%"))
        for (run in runs) {
            for (f in run.files) {
                appendLine(String.format(Locale.US, "%-4d %-12s %6.2f %9.0f %6.3f %6d %6d",
                    run.numThreads, f.sampleId, f.audioSec, f.decodeMs, f.rtf, f.charSimilarity, f.wordSimilarity))
            }
        }
        appendLine("--- summary (${tier.id}) ---")
        appendLine(String.format(Locale.US, "%-4s %9s %8s %8s %6s %6s", "thr", "load_ms", "audio_s", "RTF", "char%", "word%"))
        for (run in runs) {
            appendLine(String.format(Locale.US, "%-4d %9.0f %8.1f %8.3f %6.1f %6.1f",
                run.numThreads, run.meanLoadMs, run.totalAudioSec, run.overallRtf, run.meanCharSimilarity, run.meanWordSimilarity))
        }
        runs.minByOrNull { it.overallRtf }?.let {
            append(String.format(Locale.US, "fastest: %d threads (RTF %.3f, %.1fx real-time)", it.numThreads, it.overallRtf,
                if (it.overallRtf > 0) 1.0 / it.overallRtf else 0.0))
        }
    }

    private fun writeJsonReport(tier: WhisperModelTier, files: TierModelFiles, runs: List<ThreadRun>): File {
        val json = JSONObject().apply {
            put("tier", tier.id)
            put("displayName", tier.displayName)
            put("modelType", tier.modelType.id)
            put("modelFiles", JSONArray(files.all.map { "${it.name} (${it.length()} bytes)" }))
            put("timestamp", System.currentTimeMillis())
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("soc", socModel())
                put("abi", Build.SUPPORTED_ABIS.firstOrNull())
                put("sdkInt", Build.VERSION.SDK_INT)
                put("cores", Runtime.getRuntime().availableProcessors())
            })
            put("runs", JSONArray(runs.map { run ->
                JSONObject().apply {
                    put("numThreads", run.numThreads)
                    put("loadMsByLanguage", JSONObject(run.loadMsByLanguage.mapValues { round1(it.value) }))
                    put("meanLoadMs", round1(run.meanLoadMs))
                    put("totalAudioSec", round1(run.totalAudioSec))
                    put("totalDecodeMs", round1(run.totalDecodeMs))
                    put("overallRtf", round4(run.overallRtf))
                    put("meanCharSimilarity", round1(run.meanCharSimilarity))
                    put("meanWordSimilarity", round1(run.meanWordSimilarity))
                    put("files", JSONArray(run.files.map { f ->
                        JSONObject().apply {
                            put("id", f.sampleId)
                            put("language", f.language)
                            put("audioSec", round4(f.audioSec))
                            put("decodeMs", round1(f.decodeMs))
                            put("rtf", round4(f.rtf))
                            put("charSimilarity", f.charSimilarity)
                            put("wordSimilarity", f.wordSimilarity)
                            put("hypothesis", f.hypothesis)
                            put("reference", f.reference)
                        }
                    }))
                }
            }))
        }
        val dir = File(targetContext.getExternalFilesDir(null) ?: targetContext.filesDir, "benchmark").apply { mkdirs() }
        return File(dir, "stt_benchmark_${tier.id}_${System.currentTimeMillis()}.json").apply { writeText(json.toString(2)) }
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round4(v: Double) = Math.round(v * 10000) / 10000.0

    companion object {
        private const val TAG = "SttBenchmark"
        private const val MANIFEST = "benchmark_manifest.json"
        private const val WARMUP_SAMPLES = 16000 * 2
        /** Same key/code androidx.benchmark uses to stream text without affecting test status parsing. */
        private const val REPORT_KEY_STREAM = "stream"
        private const val STATUS_IN_PROGRESS = 2
    }
}
