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
 * Manages downloading and validating the Sherpa-ONNX Whisper speech recognition model files.
 * Supports both multilingual (Japanese, English, etc.) and English-only models.
 */
class WhisperModelManager(val targetDir: File) {

    companion object {
        // Multilingual Whisper Tiny model (supports 99 languages including Japanese, English, Vietnamese, etc.)
        const val MULTILINGUAL_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"
        val MULTILINGUAL_FILES = listOf(
            "tiny-tokens.txt",
            "tiny-encoder.int8.onnx",
            "tiny-decoder.int8.onnx"
        )

        // English-only Whisper Tiny model
        const val ENGLISH_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main"
        val ENGLISH_FILES = listOf(
            "tiny.en-tokens.txt",
            "tiny.en-encoder.int8.onnx",
            "tiny.en-decoder.int8.onnx"
        )
    }

    // Multilingual model files
    val multilingualEncoder: File get() = File(targetDir, "tiny-encoder.int8.onnx")
    val multilingualDecoder: File get() = File(targetDir, "tiny-decoder.int8.onnx")
    val multilingualTokens: File get() = File(targetDir, "tiny-tokens.txt")

    // English-only model files
    val englishEncoder: File get() = File(targetDir, "tiny.en-encoder.int8.onnx")
    val englishDecoder: File get() = File(targetDir, "tiny.en-decoder.int8.onnx")
    val englishTokens: File get() = File(targetDir, "tiny.en-tokens.txt")

    fun hasMultilingualModel(): Boolean {
        return multilingualEncoder.exists() && multilingualEncoder.length() > 1000 &&
                multilingualDecoder.exists() && multilingualDecoder.length() > 1000 &&
                multilingualTokens.exists() && multilingualTokens.length() > 1000
    }

    fun hasEnglishOnlyModel(): Boolean {
        return englishEncoder.exists() && englishEncoder.length() > 1000 &&
                englishDecoder.exists() && englishDecoder.length() > 1000 &&
                englishTokens.exists() && englishTokens.length() > 1000
    }

    /**
     * Checks if a suitable model is available for the given language.
     * English can use either multilingual or English-only model.
     * Japanese (and other non-English languages) require the multilingual model.
     */
    fun isModelAvailable(langCode: String = "en"): Boolean {
        return if (langCode.equals("en", ignoreCase = true)) {
            hasMultilingualModel() || hasEnglishOnlyModel()
        } else {
            hasMultilingualModel()
        }
    }

    /**
     * Returns the encoder, decoder, and tokens files for the given language.
     */
    fun getModelFiles(langCode: String = "en"): Triple<File, File, File> {
        return if (hasMultilingualModel()) {
            Triple(multilingualEncoder, multilingualDecoder, multilingualTokens)
        } else if (langCode.equals("en", ignoreCase = true) && hasEnglishOnlyModel()) {
            Triple(englishEncoder, englishDecoder, englishTokens)
        } else {
            // Default to multilingual paths
            Triple(multilingualEncoder, multilingualDecoder, multilingualTokens)
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
     * Downloads the multilingual Whisper model to support all languages (including Japanese and English).
     */
    fun downloadModel(): Flow<ModelDownloadState> = flow {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val filesToDownload = MULTILINGUAL_FILES

        for ((index, fileName) in filesToDownload.withIndex()) {
            val destFile = File(targetDir, fileName)
            // If valid file already exists, skip
            if (destFile.exists() && destFile.length() > 1000) {
                val progress = ((index + 1) * 100) / filesToDownload.size
                emit(ModelDownloadState.Progress(progress, fileName))
                continue
            }

            val tempFile = File(targetDir, "$fileName.tmp")
            val fileUrl = "$MULTILINGUAL_BASE_URL/$fileName"

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
}
