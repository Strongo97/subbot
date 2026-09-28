package com.subbot.translation

/**
 * Thread-safe LRU cache for translated phrases.
 * Keyed by "sourceLang->targetLang:text".
 */
class TranslationCache(private val maxEntries: Int = 100) {

    private val cache = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(sourceLang: String, targetLang: String, text: String): String? {
        return cache["$sourceLang->$targetLang:$text"]
    }

    @Synchronized
    fun put(sourceLang: String, targetLang: String, text: String, translation: String) {
        cache["$sourceLang->$targetLang:$text"] = translation
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
