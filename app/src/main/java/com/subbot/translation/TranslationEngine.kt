package com.subbot.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    object Downloading : DownloadStatus()
    object Completed : DownloadStatus()
    data class Failed(val error: Throwable) : DownloadStatus()
}

interface ITranslationEngine {
    suspend fun isModelDownloaded(langCode: String): Boolean
    fun downloadModel(langCode: String, requireWifi: Boolean = false): Flow<DownloadStatus>
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String

    /**
     * Pre-loads the translator for [sourceLang] -> [targetLang] so the first real subtitle does not
     * pay the model-load latency. Must never throw. Default: no-op (fakes / engines without warm-up).
     */
    suspend fun warmUp(sourceLang: String, targetLang: String) {}
}

/**
 * On-device neural translation engine backed by Google ML Kit.
 */
class TranslationEngine(
    private val cache: TranslationCache = TranslationCache()
) : ITranslationEngine {
    private companion object {
        const val WARM_UP_TEXT = "Hello."
    }

    private val modelManager = RemoteModelManager.getInstance()
    private var activeTranslator: Translator? = null
    private var currentSourceLang: String? = null
    private var currentTargetLang: String? = null

    /**
     * Checks if the offline model for a given language code (BCP-47 / ISO 639-1) is downloaded.
     */
    override suspend fun isModelDownloaded(langCode: String): Boolean {
        val model = TranslateRemoteModel.Builder(langCode).build()
        return try {
            val downloadedModels = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            downloadedModels.any { it.language == model.language }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Downloads the offline translation model for the specified language.
     */
    override fun downloadModel(langCode: String, requireWifi: Boolean): Flow<DownloadStatus> = flow {
        emit(DownloadStatus.Downloading)
        val model = TranslateRemoteModel.Builder(langCode).build()
        val conditionsBuilder = DownloadConditions.Builder()
        if (requireWifi) {
            conditionsBuilder.requireWifi()
        }
        val conditions = conditionsBuilder.build()

        try {
            modelManager.download(model, conditions).await()
            emit(DownloadStatus.Completed)
        } catch (e: Exception) {
            emit(DownloadStatus.Failed(e))
        }
    }

    /**
     * Translates input text from source language to target language.
     * Hits cache first; if missing, runs ML Kit on-device inference and caches result.
     */
    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank() || sourceLang.equals(targetLang, ignoreCase = true)) {
            return text
        }

        val cached = cache.get(sourceLang, targetLang, text)
        if (cached != null) {
            return cached
        }

        return try {
            val translator = getOrCreateTranslator(sourceLang, targetLang)
            val result = translator.translate(text).await()
            cache.put(sourceLang, targetLang, text, result)
            result
        } catch (e: Throwable) {
            android.util.Log.e("TranslationEngine", "Translation failed or cancelled", e)
            text
        }
    }

    /**
     * Creates the ML Kit translator and runs one tiny dummy translation to force the on-device model
     * into memory. The result is discarded and never cached. Errors (e.g. model not downloaded) are
     * swallowed: the real translate() path handles them as before.
     */
    override suspend fun warmUp(sourceLang: String, targetLang: String) {
        if (sourceLang.isBlank() || targetLang.isBlank() || sourceLang.equals(targetLang, ignoreCase = true)) return
        try {
            val start = System.currentTimeMillis()
            getOrCreateTranslator(sourceLang, targetLang).translate(WARM_UP_TEXT).await()
            android.util.Log.i(
                "TranslationEngine",
                "Warmed up $sourceLang->$targetLang in ${System.currentTimeMillis() - start}ms"
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("TranslationEngine", "Warm-up $sourceLang->$targetLang failed (ignored)", e)
        }
    }

    @Synchronized
    private fun getOrCreateTranslator(sourceLang: String, targetLang: String): Translator {
        if (activeTranslator == null || currentSourceLang != sourceLang || currentTargetLang != targetLang) {
            try {
                activeTranslator?.close()
            } catch (e: Throwable) {
                // Ignore
            }
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            activeTranslator = Translation.getClient(options)
            currentSourceLang = sourceLang
            currentTargetLang = targetLang
        }
        return activeTranslator!!
    }

    @Synchronized
    fun close() {
        try {
            activeTranslator?.close()
        } catch (e: Throwable) {
            // Ignore during shutdown
        }
        activeTranslator = null
        cache.clear()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
