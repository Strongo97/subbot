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
 */
class WhisperModelManager(val targetDir: File) {

    companion object {
        const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main"
        val REQUIRED_FILES = listOf(
            "tiny.en-tokens.txt",
            "tiny.en-encoder.int8.onnx",
            "tiny.en-decoder.int8.onnx"
        )
    }

    val encoderFile: File get() = File(targetDir, "tiny.en-encoder.int8.onnx")
    val decoderFile: File get() = File(targetDir, "tiny.en-decoder.int8.onnx")
    val tokensFile: File get() = File(targetDir, "tiny.en-tokens.txt")

    fun isModelAvailable(): Boolean {
        return encoderFile.exists() && encoderFile.length() > 1000 &&
                decoderFile.exists() && decoderFile.length() > 1000 &&
                tokensFile.exists() && tokensFile.length() > 1000
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

    fun downloadModel(): Flow<ModelDownloadState> = flow {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for ((index, fileName) in REQUIRED_FILES.withIndex()) {
            val destFile = File(targetDir, fileName)
            // If valid file already exists, skip
            if (destFile.exists() && destFile.length() > 1000) {
                val progress = ((index + 1) * 100) / REQUIRED_FILES.size
                emit(ModelDownloadState.Progress(progress, fileName))
                continue
            }

            val tempFile = File(targetDir, "$fileName.tmp")
            val fileUrl = "$BASE_URL/$fileName"

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
                                val overallProgress = ((index * 100) + fileProgress) / REQUIRED_FILES.size
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
