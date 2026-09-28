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
 */
enum class WhisperModelTier(
    val id: String,
    val displayName: String,
    val description: String,
    val baseUrl: String,
    val tokenFile: String,
    val encoderFile: String,
    val decoderFile: String,
    val approxSizeMb: Int,
    val featureDim: Int = 80
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
    );

    companion object {
        fun fromId(id: String?): WhisperModelTier {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TINY
        }
    }
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
     * Checks if all 3 files for the specified model tier exist and have non-trivial size.
     */
    fun isTierAvailable(tier: WhisperModelTier): Boolean {
        val encoder = File(targetDir, tier.encoderFile)
        val decoder = File(targetDir, tier.decoderFile)
        val tokens = File(targetDir, tier.tokenFile)
        if (encoder.exists() && encoder.length() > 1000 &&
            decoder.exists() && decoder.length() > 1000 &&
            tokens.exists() && tokens.length() > 1000
        ) {
            return true
        }

        // Check legacy directory (e.g. models/whisper-tiny) if targetDir is models/whisper
        if (tier == WhisperModelTier.TINY) {
            val legacy = File(targetDir.parentFile, "whisper-tiny")
            val lEnc = File(legacy, tier.encoderFile)
            val lDec = File(legacy, tier.decoderFile)
            val lTok = File(legacy, tier.tokenFile)
            if (lEnc.exists() && lEnc.length() > 1000 &&
                lDec.exists() && lDec.length() > 1000 &&
                lTok.exists() && lTok.length() > 1000
            ) {
                return true
            }
        }
        return false
    }

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
        return if (langCode.equals("en", ignoreCase = true)) {
            hasMultilingualModel() || hasEnglishOnlyModel()
        } else {
            hasMultilingualModel()
        }
    }

    /**
     * Resolves the files for the given tier, checking legacy locations if needed.
     */
    fun getModelFilesForTier(tier: WhisperModelTier): Triple<File, File, File> {
        val encoder = File(targetDir, tier.encoderFile)
        val decoder = File(targetDir, tier.decoderFile)
        val tokens = File(targetDir, tier.tokenFile)
        if (encoder.exists() && decoder.exists() && tokens.exists()) {
            return Triple(encoder, decoder, tokens)
        }
        if (tier == WhisperModelTier.TINY) {
            val legacy = File(targetDir.parentFile, "whisper-tiny")
            val lEnc = File(legacy, tier.encoderFile)
            val lDec = File(legacy, tier.decoderFile)
            val lTok = File(legacy, tier.tokenFile)
            if (lEnc.exists() && lDec.exists() && lTok.exists()) {
                return Triple(lEnc, lDec, lTok)
            }
        }
        return Triple(encoder, decoder, tokens)
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
     * Downloads the specified Whisper model tier (Tiny or Base).
     */
    fun downloadTier(tier: WhisperModelTier): Flow<ModelDownloadState> = flow {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val filesToDownload = listOf(tier.tokenFile, tier.encoderFile, tier.decoderFile)

        for ((index, fileName) in filesToDownload.withIndex()) {
            val destFile = File(targetDir, fileName)
            // If valid file already exists, skip
            if (destFile.exists() && destFile.length() > 1000) {
                val progress = ((index + 1) * 100) / filesToDownload.size
                emit(ModelDownloadState.Progress(progress, fileName))
                continue
            }

            val tempFile = File(targetDir, "$fileName.tmp")
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
}
