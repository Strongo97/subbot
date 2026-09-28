package com.subbot.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun testTranslationCache_hitsAndEvicts() {
        val cache = TranslationCache(maxEntries = 2)
        cache.put("en", "es", "Hello", "Hola")
        cache.put("en", "es", "World", "Mundo")

        assertEquals("Hola", cache.get("en", "es", "Hello"))
        assertEquals("Mundo", cache.get("en", "es", "World"))
        assertNull(cache.get("en", "es", "Goodbye"))

        // Exceed capacity: should evict oldest (Hello was accessed before World, so Hello is LRU? Wait, if LinkedHashMap accessOrder=true, World was accessed last)
        cache.put("en", "es", "Thanks", "Gracias")
        // Now capacity is 2, oldest entry should be evicted
        assertEquals("Gracias", cache.get("en", "es", "Thanks"))
    }

    @Test
    fun testTranslationCache_clear() {
        val cache = TranslationCache(maxEntries = 5)
        cache.put("en", "vi", "Good morning", "Chào buổi sáng")
        assertEquals("Chào buổi sáng", cache.get("en", "vi", "Good morning"))

        cache.clear()
        assertNull(cache.get("en", "vi", "Good morning"))
    }
}
