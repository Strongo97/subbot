package com.subbot.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SentenceAggregatorTest {

    private class Recorder {
        val sentences = mutableListOf<String>()
        val pending = mutableListOf<String>()
    }

    private fun TestScope.aggregator(
        lang: String,
        recorder: Recorder,
        maxWaitMs: Long = 3500L,
        maxPendingChars: Int? = null,
        scope: CoroutineScope = backgroundScope
    ) = SentenceAggregator(
        scope = scope,
        languageCode = lang,
        maxWaitMs = maxWaitMs,
        maxPendingChars = maxPendingChars,
        onPending = { recorder.pending += it },
        onSentence = { recorder.sentences += it }
    )

    // --- Japanese -----------------------------------------------------------------------

    @Test
    fun japanese_fragmentsJoinedWithoutSpaceAndSplitOnKuten() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)

        agg.add("昨日友達と映画を")
        assertEquals(emptyList<String>(), r.sentences)
        assertEquals(listOf("昨日友達と映画を"), r.pending)

        agg.add("見に行った。すごく")
        assertEquals(listOf("昨日友達と映画を見に行った。"), r.sentences)
        assertEquals("すごく", agg.pendingText)
        assertEquals("すごく", r.pending.last())
    }

    @Test
    fun japanese_splitsOnMashitaEndingWithoutPunctuation() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)

        agg.add("昨日友達と映画を")
        agg.add("見ました")
        assertEquals(listOf("昨日友達と映画を見ました"), r.sentences)
        assertEquals("", agg.pendingText)
    }

    @Test
    fun japanese_splitsOnDesuEnding() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)

        agg.add("私の名前は")
        agg.add("田中です")
        agg.add("よろしくお願いします")
        assertEquals(listOf("私の名前は田中です", "よろしくお願いします"), r.sentences)
    }

    @Test
    fun japanese_doesNotSplitOnDesuFollowedByConjunction() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)

        agg.add("そうですが、")
        assertEquals(emptyList<String>(), r.sentences)
        agg.add("違うと思います。")
        assertEquals(listOf("そうですが、違うと思います。"), r.sentences)
    }

    @Test
    fun japanese_closingBracketStaysWithSentence() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)

        agg.add("彼は「行くよ。」と言った")
        assertEquals(listOf("彼は「行くよ。」"), r.sentences)
        assertEquals("と言った", agg.pendingText)
    }

    @Test
    fun japaneseEndingsIgnoredForOtherLanguages() = runTest {
        val r = Recorder()
        val agg = aggregator("zh", r)
        agg.add("です")
        assertEquals(emptyList<String>(), r.sentences)
    }

    @Test
    fun cjk_asciiWordsAtBoundaryKeepASpace() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)
        agg.add("Hello")
        agg.add("world")
        assertEquals("Hello world", agg.pendingText)
    }

    // --- English / space-delimited ---------------------------------------------------

    @Test
    fun english_fragmentsJoinedWithSpaceAndSplitOnTerminators() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("I went to the")
        agg.add("store yesterday. Did you")
        agg.add("come? Wow! That")

        assertEquals(
            listOf("I went to the store yesterday.", "Did you come?", "Wow!"),
            r.sentences
        )
        assertEquals("That", agg.pendingText)
    }

    @Test
    fun english_doesNotSplitOnAbbreviationsOrDecimals() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("Mr. Smith paid 3.5 dollars in the U.S. today")
        assertEquals(emptyList<String>(), r.sentences)

        agg.add("and left.")
        assertEquals(listOf("Mr. Smith paid 3.5 dollars in the U.S. today and left."), r.sentences)
    }

    @Test
    fun english_abbreviationAtFragmentEndDoesNotSplit() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("Please welcome Dr.")
        agg.add("Jones to the stage.")
        assertEquals(listOf("Please welcome Dr. Jones to the stage."), r.sentences)
    }

    @Test
    fun english_ellipsisIsNotASentenceEnd() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("I was thinking...")
        agg.add("maybe we should go.")
        assertEquals(listOf("I was thinking... maybe we should go."), r.sentences)
    }

    @Test
    fun english_numberAtSentenceEndSplits() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("It happened in 2020. Then")
        assertEquals(listOf("It happened in 2020."), r.sentences)
        assertEquals("Then", agg.pendingText)
    }

    @Test
    fun korean_joinedWithSpaces() = runTest {
        val r = Recorder()
        val agg = aggregator("ko", r)

        agg.add("저는 어제")
        agg.add("영화를 봤어요. 네.")
        assertEquals(listOf("저는 어제 영화를 봤어요.", "네."), r.sentences)
    }

    @Test
    fun multipleSentencesInOneFragment() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)

        agg.add("おはよう。元気？うん！")
        assertEquals(listOf("おはよう。", "元気？", "うん！"), r.sentences)
        assertEquals("", agg.pendingText)
        assertEquals(emptyList<String>(), r.pending)
    }

    @Test
    fun fragmentWithoutTerminalPunctuationStaysPending() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("so what I mean is")
        assertEquals(emptyList<String>(), r.sentences)
        assertEquals("so what I mean is", agg.pendingText)
        assertEquals(listOf("so what I mean is"), r.pending)
    }

    // --- Timeout / cap / flush / clear --------------------------------------------------

    @Test
    fun timeoutFlushesPendingMeasuredFromFirstFragment() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r, maxWaitMs = 3500L)

        agg.add("昨日友達と")
        advanceTimeBy(2000L)
        agg.add("映画を")
        advanceTimeBy(1400L)
        runCurrent()
        assertEquals(emptyList<String>(), r.sentences)

        // 3500 ms after the *first* fragment, not the second.
        advanceTimeBy(101L)
        runCurrent()
        assertEquals(listOf("昨日友達と映画を"), r.sentences)
        assertEquals("", agg.pendingText)
    }

    @Test
    fun timeoutRestartsForRemainderAfterSentenceEmitted() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r, maxWaitMs = 1000L)

        agg.add("Hello")
        advanceTimeBy(900L)
        agg.add("there. How are")
        assertEquals(listOf("Hello there."), r.sentences)

        advanceTimeBy(900L)
        runCurrent()
        assertEquals(1, r.sentences.size)

        advanceTimeBy(101L)
        runCurrent()
        assertEquals(listOf("Hello there.", "How are"), r.sentences)
    }

    @Test
    fun completedSentenceCancelsTimer() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r, maxWaitMs = 1000L)

        agg.add("Hello")
        agg.add("world.")
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(listOf("Hello world."), r.sentences)
    }

    @Test
    fun lengthCapFlushesImmediately() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r, maxPendingChars = 20)

        agg.add("one two three")
        assertEquals(emptyList<String>(), r.sentences)
        agg.add("four five six")
        assertEquals(listOf("one two three four five six"), r.sentences)
        assertEquals("", agg.pendingText)
    }

    @Test
    fun flushEmitsPendingOnStop() = runTest {
        val r = Recorder()
        val agg = aggregator("ja", r)

        agg.add("それでは")
        agg.flush()
        assertEquals(listOf("それでは"), r.sentences)
        assertEquals("", agg.pendingText)

        // Timer must not fire a duplicate afterwards.
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(listOf("それでは"), r.sentences)

        // Flushing with nothing pending is a no-op.
        agg.flush()
        assertEquals(1, r.sentences.size)
    }

    @Test
    fun clearDropsPending() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("half a sentence")
        agg.clear()
        advanceTimeBy(10_000L)
        runCurrent()
        agg.flush()
        assertEquals(emptyList<String>(), r.sentences)
    }

    @Test
    fun closeStopsTimerAndIgnoresFurtherInput() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)

        agg.add("half a sentence")
        agg.close()
        advanceTimeBy(10_000L)
        runCurrent()
        agg.add("More text.")
        agg.flush()
        assertEquals(emptyList<String>(), r.sentences)
    }

    @Test
    fun blankFragmentsIgnored() = runTest {
        val r = Recorder()
        val agg = aggregator("en", r)
        agg.add("   ")
        assertEquals("", agg.pendingText)
        assertTrue(r.pending.isEmpty())
    }

    @Test
    fun timerErrorsAreReportedNotThrown() = runTest {
        val errors = mutableListOf<Throwable>()
        val agg = SentenceAggregator(
            scope = backgroundScope,
            languageCode = "en",
            maxWaitMs = 100L,
            onError = { errors += it },
            onSentence = { throw IllegalStateException("boom") }
        )
        agg.add("pending")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, errors.size)
    }

    @Test
    fun splitSentences_languageCodeNormalization() {
        assertEquals("zh", SentenceAggregator.normalizeLanguage("zh-TW"))
        assertEquals("ja", SentenceAggregator.normalizeLanguage("JA"))
    }
}
