package com.subbot.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Buffers speech-recognition fragments (emitted at acoustic pauses) until they form a
 * complete sentence, so that translation receives sentence-level input.
 *
 * This matters most for verb-final languages such as Japanese, where a fragment like
 * 「昨日友達と映画を」 cannot be translated well until 「見ました」 arrives.
 *
 * A pending sentence is emitted when:
 *  - terminal punctuation is found (`。！？!?` always; `.` unless it looks like an
 *    abbreviation, initial, decimal or ellipsis),
 *  - for Japanese, the accumulated text ends in a conservative sentence-final form
 *    (です, ます, ました, ません, でしょう, ください, ...), with or without a trailing 。,
 *  - [maxWaitMs] elapses since the pending text started (timeout),
 *  - the pending text reaches [maxPendingChars] (length cap),
 *  - [flush] is called.
 *
 * All state changes and callbacks are serialized by a [Mutex], so sentences are always
 * delivered in order. Callbacks run while the lock is held and must not call back into
 * this aggregator.
 *
 * @param scope Scope used for the timeout timer. Timer-fired callbacks run in this scope.
 * @param languageCode Language of the incoming fragments (e.g. "ja", "en", "zh-TW").
 * @param maxWaitMs Maximum time pending text may wait for a sentence end before being flushed.
 * @param maxPendingChars Pending text at or beyond this length is flushed immediately.
 * @param onPending Called with the provisional, not-yet-complete text after each [add]
 *   that leaves text pending.
 * @param onSentence Called with each complete unit, ready for translation.
 * @param onError Receives non-cancellation exceptions thrown by callbacks fired from the
 *   timer (exceptions from [add]/[flush] propagate to their caller instead).
 */
class SentenceAggregator(
    private val scope: CoroutineScope,
    languageCode: String,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    maxPendingChars: Int? = null,
    private val onPending: suspend (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val onSentence: suspend (String) -> Unit
) {
    private val lang: String = normalizeLanguage(languageCode)
    private val joinWithoutSpaces: Boolean = lang in NO_SPACE_LANGUAGES
    private val maxChars: Int = maxPendingChars
        ?: if (joinWithoutSpaces) DEFAULT_MAX_CHARS_NO_SPACE else DEFAULT_MAX_CHARS_SPACED

    private val mutex = Mutex()
    private var pending: String = ""
    private var timerJob: Job? = null
    private var timerGeneration = 0L

    @Volatile
    private var closed = false

    /** Current provisional text (for diagnostics/tests). */
    val pendingText: String get() = pending

    /**
     * Appends a recognized fragment, emits every complete sentence now available via
     * [onSentence], and reports any remainder via [onPending].
     */
    suspend fun add(fragment: String) {
        val cleaned = fragment.trim()
        if (cleaned.isEmpty()) return
        mutex.withLock {
            if (closed) return
            val hadPending = pending.isNotEmpty()
            val combined = join(pending, cleaned)

            val (sentences, remainder) = splitSentences(combined)
            pending = remainder

            var emittedAny = sentences.isNotEmpty()
            for (sentence in sentences) {
                onSentence(sentence)
            }

            if (pending.isNotEmpty() && (endsWithSentenceFinalForm(pending) || pending.length >= maxChars)) {
                val text = pending
                pending = ""
                emittedAny = true
                onSentence(text)
            }

            when {
                pending.isEmpty() -> cancelTimer()
                // The remainder began inside this fragment: restart the max-wait window.
                emittedAny || !hadPending -> startTimer()
                // Otherwise keep the timer running from the first pending fragment.
            }

            if (pending.isNotEmpty()) {
                onPending(pending)
            }
        }
    }

    /** Emits whatever is pending as a sentence (e.g. when the stream ends). */
    suspend fun flush() {
        mutex.withLock {
            cancelTimer()
            if (closed || pending.isEmpty()) return
            val text = pending
            pending = ""
            onSentence(text)
        }
    }

    /** Drops pending text without emitting it. */
    suspend fun clear() {
        mutex.withLock {
            cancelTimer()
            pending = ""
        }
    }

    /**
     * Non-suspending shutdown for teardown paths (e.g. `Service.onDestroy`): cancels the
     * timer and turns all further calls into no-ops. Pending text is discarded.
     */
    fun close() {
        closed = true
        timerJob?.cancel()
    }

    private fun startTimer() {
        timerJob?.cancel()
        val generation = ++timerGeneration
        timerJob = scope.launch {
            delay(maxWaitMs)
            mutex.withLock {
                if (generation != timerGeneration || closed || pending.isEmpty()) return@withLock
                val text = pending
                pending = ""
                timerJob = null // don't cancel ourselves while delivering
                try {
                    onSentence(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        }
    }

    private fun cancelTimer() {
        timerGeneration++
        timerJob?.cancel()
        timerJob = null
    }

    private fun join(existing: String, fragment: String): String {
        if (existing.isEmpty()) return fragment
        val needsSpace = !joinWithoutSpaces ||
            (existing.last().isAsciiLetterOrDigit() && fragment.first().isAsciiLetterOrDigit())
        return if (needsSpace) "$existing $fragment" else existing + fragment
    }

    private fun endsWithSentenceFinalForm(text: String): Boolean {
        if (lang != "ja") return false
        val trimmed = text.trimEnd()
        return JAPANESE_SENTENCE_ENDINGS.any { trimmed.endsWith(it) }
    }

    companion object {
        const val DEFAULT_MAX_WAIT_MS = 3500L
        const val DEFAULT_MAX_CHARS_NO_SPACE = 60
        const val DEFAULT_MAX_CHARS_SPACED = 200

        /** Languages whose script does not separate words with spaces. Korean does use spaces. */
        private val NO_SPACE_LANGUAGES = setOf("ja", "zh", "yue", "th", "lo", "km", "my", "bo")

        private val STRONG_TERMINATORS = setOf('。', '｡', '！', '？', '!', '?')

        /** Characters that belong to the sentence when they directly follow its terminator. */
        private val CLOSERS = setOf('」', '』', '）', ')', '"', '\'', '”', '’', '】', '》', '〉', ']')

        /** Conservative Japanese sentence-final forms, checked only at the end of the text. */
        private val JAPANESE_SENTENCE_ENDINGS = listOf(
            "です", "ます", "でした", "ました", "ません", "ませんでした",
            "でしょう", "ましょう", "ですか", "ますか", "でしょうか",
            "ですね", "ますね", "ですよ", "ますよ", "だよ", "よね", "だね",
            "ください"
        )

        /** Lower-cased words (without the trailing '.') that are not sentence ends. */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc", "inc", "ltd",
            "co", "corp", "vol", "approx", "dept", "est", "fig", "gen", "gov", "lt", "col",
            "sgt", "capt", "rev", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
            "oct", "nov", "dec"
        )

        internal fun normalizeLanguage(code: String): String =
            code.trim().lowercase().substringBefore('-').substringBefore('_')

        /**
         * Splits [text] into complete sentences and a trailing remainder (both trimmed).
         */
        internal fun splitSentences(text: String): Pair<List<String>, String> {
            val sentences = mutableListOf<String>()
            var start = 0
            var i = 0
            while (i < text.length) {
                if (isSentenceTerminator(text, i)) {
                    var end = i + 1
                    // Absorb runs like "?!", "。」" or "!)" into the same sentence.
                    while (end < text.length &&
                        (text[end] in STRONG_TERMINATORS || text[end] in CLOSERS)
                    ) {
                        end++
                    }
                    val sentence = text.substring(start, end).trim()
                    if (sentence.isNotEmpty()) sentences += sentence
                    start = end
                    i = end
                } else {
                    i++
                }
            }
            return sentences to text.substring(start).trim()
        }

        private fun isSentenceTerminator(text: String, index: Int): Boolean {
            val c = text[index]
            if (c in STRONG_TERMINATORS) return true
            if (c == '.' || c == '．') return isPeriodSentenceEnd(text, index)
            return false
        }

        private fun isPeriodSentenceEnd(text: String, index: Int): Boolean {
            val prev = text.getOrNull(index - 1)
            val next = text.getOrNull(index + 1)

            // Ellipsis ("..." / "…") usually marks a trailing-off fragment, not a sentence end.
            if (prev == '.' || next == '.') return false
            // "3.5", "example.com", "U.S.A": a sentence-ending period is followed by space/end.
            if (next != null && !next.isWhitespace() && next !in CLOSERS) return false

            var wordStart = index
            while (wordStart > 0 && (text[wordStart - 1].isLetter() || text[wordStart - 1] == '.')) {
                wordStart--
            }
            val word = text.substring(wordStart, index)
            if (word.isEmpty()) return true // e.g. "in 2020."
            if ('.' in word) return false // "U.S.", "e.g.", "i.e."
            if (word.length == 1 && word[0] in 'A'..'Z') return false // initials: "J. K. Rowling"
            if (word.lowercase() in ABBREVIATIONS) return false
            return true
        }

        private fun Char.isAsciiLetterOrDigit(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
