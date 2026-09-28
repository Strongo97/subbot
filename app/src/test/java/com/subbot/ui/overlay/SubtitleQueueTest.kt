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

    @Test
    fun testSetFontSize_updatesState() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        assertEquals(com.subbot.ui.model.SubtitleFontSize.NORMAL, queue.uiState.value.fontSize)

        queue.setFontSize(com.subbot.ui.model.SubtitleFontSize.EXTRA_LARGE)
        assertEquals(com.subbot.ui.model.SubtitleFontSize.EXTRA_LARGE, queue.uiState.value.fontSize)
    }

    @Test
    fun testSetTopPosition_updatesState() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        assertFalse(queue.uiState.value.isTopPosition)

        queue.setTopPosition(true)
        assertTrue(queue.uiState.value.isTopPosition)
    }

    @Test
    fun testSetListening_updatesState() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        assertTrue(queue.uiState.value.isListening)

        queue.setListening(false)
        assertFalse(queue.uiState.value.isListening)
    }

    @Test
    fun testPostPendingOriginal_keepsVisibleTranslation() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        queue.postSubtitle(original = "田中です", translated = "I'm Tanaka")

        queue.postPendingOriginal("昨日友達と映画を")
        assertEquals("昨日友達と映画を", queue.uiState.value.originalText)
        assertEquals("I'm Tanaka", queue.uiState.value.translatedText)
        assertTrue(queue.uiState.value.isVisible)
        advanceTimeBy(1100L)
    }

    @Test
    fun testPostPendingOriginal_usesPlaceholderWhenNothingVisible() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        queue.postSubtitle(original = "old", translated = "stale")
        advanceTimeBy(1100L)
        assertFalse(queue.uiState.value.isVisible)

        queue.postPendingOriginal("昨日友達と映画を")
        assertEquals(SubtitleQueue.PENDING_PLACEHOLDER, queue.uiState.value.translatedText)
        assertTrue(queue.uiState.value.isVisible)
        advanceTimeBy(1100L)
    }

    @Test
    fun testKeepAlive_extendsVisibleSubtitleOnly() = runTest {
        val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
        queue.keepAlive()
        assertFalse(queue.uiState.value.isVisible)

        queue.postSubtitle(original = "", translated = "Hello")
        advanceTimeBy(800L)
        queue.keepAlive()
        advanceTimeBy(800L)
        assertTrue(queue.uiState.value.isVisible)
        assertEquals("Hello", queue.uiState.value.translatedText)

        advanceTimeBy(300L)
        assertFalse(queue.uiState.value.isVisible)
    }
}
