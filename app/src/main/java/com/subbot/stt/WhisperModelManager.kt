package com.subbot.stt

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed class ModelDownloadState {
    data class Progress(val progressPercent: Int, val currentFile: String) : ModelDownloadState()
    object Completed : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

/**
 * Speech recognition model tiers:
 * - TINY: 39M parameters, lightweight (~104MB), fastest inference, best for English.
 * - BASE: 74M parameters, higher accuracy (~160MB), 2x parameters, recommended for Japanese.
 * - SMALL / TURBO: larger Whisper models for flagship devices.
 * - SENSE_VOICE: non-autoregressive SenseVoice-Small (~229MB int8), ~10x faster than Whisper,
 *   zh/en/ja/ko/yue only, transcribe only (no Whisper "translate" task).
 *
 * File naming: [encoderFile], [decoderFile] and [tokenFile] are the file names on the download
 * server ([baseUrl]). They are stored locally as [localFilePrefix] + name (see [localFileName]) so
 * generic upstream names like `model.int8.onnx` cannot collide inside the shared model directory.
 * For single-file models ([modelType] == SENSE_VOICE) [encoderFile] is the model and
 * [decoderFile] is null.
 */
enum class WhisperModelTier(
    val id: String,
    val displayName: String,
    val description: String,
    val baseUrl: String,
    val tokenFile: String,
    val encoderFile: String,
    val decoderFile: String?,
    val approxSizeMb: Int,
    val featureDim: Int = 80,
    val modelType: SttModelType = SttModelType.WHISPER,
    /** Source language codes this tier can recognize; null = all (multilingual Whisper). */
    val supportedLanguages: Set<String>? = null,
    val localFilePrefix: String = ""
) {
    TINY(
        id = "tiny",
        displayName = "Whisper Tiny (Fast)",
        description = "Lightweight (~104MB) • Ultra-fast • Best for English",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main",
        tokenFile = "tiny-tokens.txt",
        encoderFile = "tiny-encoder.int8.onnx",
        decoderFile = "tiny-decoder.int8.onnx",
        approxSizeMb = 104,
        featureDim = 80
    ),
    BASE(
        id = "base",
        displayName = "Whisper Base (High Accuracy)",
        description = "High accuracy (~160MB) • 2x parameters • Recommended for Japanese",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main",
        tokenFile = "base-tokens.txt",
        encoderFile = "base-encoder.int8.onnx",
        decoderFile = "base-decoder.int8.onnx",
        approxSizeMb = 160,
        featureDim = 80
    ),
    SMALL(
        id = "small",
        displayName = "Whisper Small (Studio Quality)",
        description = "Studio quality (~374MB) • 244M parameters • Highest accuracy for Japanese & names",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main",
        tokenFile = "small-tokens.txt",
        encoderFile = "small-encoder.int8.onnx",
        decoderFile = "small-decoder.int8.onnx",
        approxSizeMb = 374,
        featureDim = 80
    ),
    TURBO(
        id = "turbo",
        displayName = "Whisper Large-v3-Turbo (Ultimate Flagship)",
        description = "809M params (~1036MB) • State-of-the-art accuracy • S23 Ultra / flagship recommended",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main",
        tokenFile = "turbo-tokens.txt",
        encoderFile = "turbo-encoder.int8.onnx",
        decoderFile = "turbo-decoder.int8.onnx",
        approxSizeMb = 1036,
        featureDim = 128
    ),
    SENSE_VOICE(
        id = "sense_voice",
        displayName = "SenseVoice Small (Fastest)",
        description = "Non-autoregressive (~229MB) • ~10x faster than Whisper • zh/en/ja/ko/yue only",
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main",
        tokenFile = "tokens.txt",
        encoderFile = "model.int8.onnx",
        decoderFile = null,
        approxSizeMb = 229,
        featureDim = 80,
        modelType = SttModelType.SENSE_VOICE,
        supportedLanguages = SpeechRecognitionConfig.SENSE_VOICE_LANGUAGES,
        localFilePrefix = "sense-voice-"
    );

    /** Remote file names to download, tokens first (matches the historical download order). */
    val remoteFiles: List<String>
        get() = listOfNotNull(tokenFile, encoderFile, decoderFile)

    fun localFileName(remoteName: String): String = localFilePrefix + remoteName

    fun supportsLanguage(langCode: String): Boolean =
        supportedLanguages == null || supportedLanguages.contains(langCode.lowercase())

    companion object {
        fun fromId(id: String?): WhisperModelTier {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TINY
        }

        /** Whisper tiers in preferred fallback order (largest first). */
        val WHISPER_FALLBACK_ORDER = listOf(TURBO, SMALL, BASE, TINY)
    }
}

/**
 * Resolved on-disk files for a tier. [model] is the Whisper encoder or the single SenseVoice model;
 * [decoder] is null for single-file models.
 */
data class TierModelFiles(
    val model: File,
    val decoder: File?,
    val tokens: File
) {
    val all: List<File> get() = listOfNotNull(model, decoder, tokens)
}

/**
 * Manages downloading and validating the Sherpa-ONNX Whisper speech recognition model files.
 * Supports both Tiny (fast) and Base (high accuracy) model tiers.
 */
class WhisperModelManager(val targetDir: File) {

    companion object {
        const val MULTILINGUAL_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"
        val MULTILINGUAL_FILES = listOf(
            "tiny-tokens.txt",
            "tiny-encoder.int8.onnx",
            "tiny-decoder.int8.onnx"
        )
    }

    // Legacy file references pointing to default Tiny
    val multilingualEncoder: File get() = getModelFilesForTier(WhisperModelTier.TINY).first
    val multilingualDecoder: File get() = getModelFilesForTier(WhisperModelTier.TINY).second
    val multilingualTokens: File get() = getModelFilesForTier(WhisperModelTier.TINY).third

    /**
     * Checks if all files for the specified model tier exist and have non-trivial size.
     */
    fun isTierAvailable(tier: WhisperModelTier): Boolean {
        fun File.isValidModelFile() = exists() && length() > 1000
        if (tierFilesIn(targetDir, tier).all.all { it.isValidModelFile() }) {
            return true
        }

        // Check legacy directory (e.g. models/whisper-tiny) if targetDir is models/whisper
        if (tier == WhisperModelTier.TINY) {
            val legacy = File(targetDir.parentFile, "whisper-tiny")
            if (tierFilesIn(legacy, tier).all.all { it.isValidModelFile() }) {
                return true
            }
        }
        return false
    }

    private fun tierFilesIn(dir: File, tier: WhisperModelTier): TierModelFiles = TierModelFiles(
        model = File(dir, tier.localFileName(tier.encoderFile)),
        decoder = tier.decoderFile?.let { File(dir, tier.localFileName(it)) },
        tokens = File(dir, tier.localFileName(tier.tokenFile))
    )

    // English-only model files
    val englishEncoder: File get() = File(targetDir, "tiny.en-encoder.int8.onnx")
    val englishDecoder: File get() = File(targetDir, "tiny.en-decoder.int8.onnx")
    val englishTokens: File get() = File(targetDir, "tiny.en-tokens.txt")

    fun hasEnglishOnlyModel(): Boolean {
        return englishEncoder.exists() && englishEncoder.length() > 1000 &&
                englishDecoder.exists() && englishDecoder.length() > 1000 &&
                englishTokens.exists() && englishTokens.length() > 1000
    }

    fun hasMultilingualModel(): Boolean {
        return isTierAvailable(WhisperModelTier.TINY) ||
               isTierAvailable(WhisperModelTier.BASE) ||
               isTierAvailable(WhisperModelTier.SMALL) ||
               isTierAvailable(WhisperModelTier.TURBO)
    }

    /**
     * Checks if a suitable speech recognition model is available for the given language.
     * English can use English-only or multilingual models.
     * Non-English languages (Japanese, etc.) require a multilingual model tier.
     */
    fun isModelAvailable(langCode: String = "en"): Boolean {
        val senseVoiceUsable = WhisperModelTier.SENSE_VOICE.supportsLanguage(langCode) &&
            isTierAvailable(WhisperModelTier.SENSE_VOICE)
        return if (langCode.equals("en", ignoreCase = true)) {
            hasMultilingualModel() || hasEnglishOnlyModel() || senseVoiceUsable
        } else {
            hasMultilingualModel() || senseVoiceUsable
        }
    }

    /**
     * Picks the tier to actually run: the selected tier when it is downloaded and supports
     * [langCode]; otherwise the largest downloaded Whisper tier; otherwise TINY (caller reports
     * missing files).
     */
    fun resolveEffectiveTier(selected: WhisperModelTier, langCode: String): WhisperModelTier {
        if (selected.supportsLanguage(langCode) && isTierAvailable(selected)) return selected
        return WhisperModelTier.WHISPER_FALLBACK_ORDER.firstOrNull { isTierAvailable(it) }
            ?: WhisperModelTier.TINY
    }

    /**
     * Resolves the files for any tier (Whisper or single-file), checking legacy locations if needed.
     */
    fun resolveTierFiles(tier: WhisperModelTier): TierModelFiles {
        val primary = tierFilesIn(targetDir, tier)
        if (primary.all.all { it.exists() }) {
            return primary
        }
        if (tier == WhisperModelTier.TINY) {
            val legacy = tierFilesIn(File(targetDir.parentFile, "whisper-tiny"), tier)
            if (legacy.all.all { it.exists() }) {
                return legacy
            }
        }
        return primary
    }

    /**
     * Resolves the (encoder, decoder, tokens) files for a Whisper tier, checking legacy locations if needed.
     * Single-file tiers (SenseVoice) must use [resolveTierFiles].
     */
    fun getModelFilesForTier(tier: WhisperModelTier): Triple<File, File, File> {
        require(tier.modelType == SttModelType.WHISPER) {
            "${tier.id} is not an encoder/decoder model; use resolveTierFiles()"
        }
        val files = resolveTierFiles(tier)
        return Triple(files.model, files.decoder!!, files.tokens)
    }

    /**
     * Returns the encoder, decoder, and tokens files for the given language.
     * Prioritizes TURBO tier if available, then SMALL tier, then BASE tier, then TINY tier.
     */
    fun getModelFiles(langCode: String = "en"): Triple<File, File, File> {
        return if (isTierAvailable(WhisperModelTier.TURBO)) {
            getModelFilesForTier(WhisperModelTier.TURBO)
        } else if (isTierAvailable(WhisperModelTier.SMALL)) {
            getModelFilesForTier(WhisperModelTier.SMALL)
        } else if (isTierAvailable(WhisperModelTier.BASE)) {
            getModelFilesForTier(WhisperModelTier.BASE)
        } else if (isTierAvailable(WhisperModelTier.TINY)) {
            getModelFilesForTier(WhisperModelTier.TINY)
        } else if (langCode.equals("en", ignoreCase = true) && hasEnglishOnlyModel()) {
            Triple(englishEncoder, englishDecoder, englishTokens)
        } else {
            getModelFilesForTier(WhisperModelTier.TINY)
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 5) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399) {
                val newUrl = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP redirect received with no Location header")
                conn.disconnect()
                currentUrl = newUrl
                redirects++
            } else {
                return conn
            }
        }
        throw IOException("Too many HTTP redirects when downloading model")
    }

    /**
     * Downloads the specified model tier (any Whisper tier or SenseVoice).
     */
    fun downloadTier(tier: WhisperModelTier): Flow<ModelDownloadState> = flow {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val filesToDownload = tier.remoteFiles

        for ((index, fileName) in filesToDownload.withIndex()) {
            val destFile = File(targetDir, tier.localFileName(fileName))
            // If valid file already exists, skip
            if (destFile.exists() && destFile.length() > 1000) {
                val progress = ((index + 1) * 100) / filesToDownload.size
                emit(ModelDownloadState.Progress(progress, fileName))
                continue
            }

            val tempFile = File(targetDir, "${tier.localFileName(fileName)}.tmp")
            val fileUrl = "${tier.baseUrl}/$fileName"

            try {
                val conn = openConnectionWithRedirects(fileUrl)
                if (conn.responseCode !in 200..299) {
                    emit(ModelDownloadState.Error("Server returned code ${conn.responseCode} for $fileName"))
                    return@flow
                }

                val totalBytes = conn.contentLengthLong
                var downloadedBytes = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val fileProgress = (downloadedBytes * 100) / totalBytes
                                val overallProgress = ((index * 100) + fileProgress) / filesToDownload.size
                                emit(ModelDownloadState.Progress(overallProgress.toInt(), fileName))
                            }
                        }
                    }
                }

                if (!tempFile.renameTo(destFile)) {
                    destFile.delete()
                    if (!tempFile.renameTo(destFile)) {
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                emit(ModelDownloadState.Error("Download failed for $fileName: ${e.message}"))
                return@flow
            }
        }
        emit(ModelDownloadState.Completed)
    }.flowOn(Dispatchers.IO)

    /**
     * Backward-compatible download method defaulting to TINY tier.
     */
    fun downloadModel(): Flow<ModelDownloadState> = downloadTier(WhisperModelTier.TINY)

    // ---------------------------------------------------------------------------------------
    // Silero VAD model (speech detection). Independent of the Whisper tier enum: a single small
    // file shared by every tier, stored next to the Whisper files.
    // ---------------------------------------------------------------------------------------

    /** Location of silero_vad.onnx (may not exist yet). */
    val vadModelFile: File get() = File(targetDir, VAD_MODEL_FILE_NAME)

    /** Absolute path of the VAD model if it is downloaded and plausibly complete, else null. */
    val vadModelPath: String?
        get() = if (isVadModelAvailable()) vadModelFile.absolutePath else null

    fun isVadModelAvailable(): Boolean {
        val f = vadModelFile
        return f.exists() && f.length() >= VAD_MIN_VALID_BYTES
    }

    /**
     * Downloads silero_vad.onnx if missing. Emits Progress / Completed / Error like [downloadTier].
     */
    fun downloadVadModel(): Flow<ModelDownloadState> = flow {
        if (isVadModelAvailable()) {
            emit(ModelDownloadState.Completed)
            return@flow
        }
        emit(ModelDownloadState.Progress(0, VAD_MODEL_FILE_NAME))
        val error = downloadVadModelOrError()
        if (error == null) {
            emit(ModelDownloadState.Progress(100, VAD_MODEL_FILE_NAME))
            emit(ModelDownloadState.Completed)
        } else {
            emit(ModelDownloadState.Error(error))
        }
    }.flowOn(Dispatchers.IO)

    /** Blocking best-effort VAD download; returns true if the model is available afterwards. */
    fun downloadVadModelBlocking(): Boolean {
        if (isVadModelAvailable()) return true
        return downloadVadModelOrError() == null
    }

    /** @return null on success, otherwise an error message. Must run off the main thread. */
    private fun downloadVadModelOrError(): String? {
        if (!targetDir.exists()) targetDir.mkdirs()
        val destFile = vadModelFile
        val tempFile = File(targetDir, "$VAD_MODEL_FILE_NAME.tmp")
        return try {
            val conn = openConnectionWithRedirects(VAD_MODEL_URL)
            try {
                if (conn.responseCode !in 200..299) {
                    return "Server returned code ${conn.responseCode} for $VAD_MODEL_FILE_NAME"
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
            } finally {
                conn.disconnect()
            }
            if (tempFile.length() < VAD_MIN_VALID_BYTES) {
                tempFile.delete()
                return "Downloaded $VAD_MODEL_FILE_NAME is truncated (${tempFile.length()} bytes)"
            }
            if (!tempFile.renameTo(destFile)) {
                destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
            }
            null
        } catch (e: Exception) {
            tempFile.delete()
            "Download failed for $VAD_MODEL_FILE_NAME: ${e.message}"
        }
    }
}

/** Silero VAD model published by sherpa-onnx (~630 KB). */
const val VAD_MODEL_FILE_NAME = "silero_vad.onnx"
const val VAD_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
/** Sanity floor used to reject truncated/HTML-error downloads. */
const val VAD_MIN_VALID_BYTES = 100_000L
