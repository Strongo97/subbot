package com.subbot.ui.overlay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleQueueTest {

    @Test
    fun testPostSubtitle_updatesStateAndAutoFadesAfterTimeout() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        queue.postSubtitle(original = "Hello world", translated = "Xin chào")

        assertEquals("Xin chào", queue.uiState.value.translatedText)
        assertEquals("Hello world", queue.uiState.value.originalText)
        assertTrue(queue.uiState.value.isVisible)

        advanceTimeBy(1100L)
        assertFalse(queue.uiState.value.isVisible)
    }

    @Test
    fun testClear_resetsState() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        queue.postSubtitle(original = "Hello", translated = "Xin chào")
        assertTrue(queue.uiState.value.isVisible)

        queue.clear()
        assertFalse(queue.uiState.value.isVisible)
        assertEquals("", queue.uiState.value.originalText)
        assertEquals("", queue.uiState.value.translatedText)
    }

    @Test
    fun testSetBilingual_updatesState() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        assertFalse(queue.uiState.value.isBilingual)

        queue.setBilingual(true)
        assertTrue(queue.uiState.value.isBilingual)
    }
}
