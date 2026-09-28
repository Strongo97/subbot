package com.subbot.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionEngineTest {

    @Test
    fun testCleanUtterance_removesBracketsEllipsesAndLeadingPunctuation() {
        assertEquals("Hello world", SpeechRecognitionEngine.cleanUtterance("[BLANK_AUDIO] Hello world..."))
        assertEquals("こんにちは。", SpeechRecognitionEngine.cleanUtterance("...こんにちは。"))
        assertEquals("田中です", SpeechRecognitionEngine.cleanUtterance("— 田中です —"))
        assertEquals("It is fine", SpeechRecognitionEngine.cleanUtterance("It is fine..."))
    }

    @Test
    fun testIsHallucination_detectsKnownRepetitionsAndArtifacts() {
        assertTrue(SpeechRecognitionEngine.isHallucination("ご視聴ありがとうございました"))
        assertTrue(SpeechRecognitionEngine.isHallucination("Thank you for watching"))
        assertTrue(SpeechRecognitionEngine.isHallucination("ああああああああ"))
        assertTrue(SpeechRecognitionEngine.isHallucination("you you you you"))
        assertTrue(SpeechRecognitionEngine.isHallucination("a"))
        assertFalse(SpeechRecognitionEngine.isHallucination("こんにちは、田中です。"))
        assertFalse(SpeechRecognitionEngine.isHallucination("Welcome to the presentation today."))
    }

    @Test
    fun testCleanUtterance_keepsSentenceFinalPunctuation() {
        assertEquals("Hello world.", SpeechRecognitionEngine.cleanUtterance("Hello world."))
        assertEquals("Are you okay?", SpeechRecognitionEngine.cleanUtterance("Are you okay?"))
        assertEquals("Watch out!", SpeechRecognitionEngine.cleanUtterance("  Watch out!  "))
        assertEquals("本当ですか？", SpeechRecognitionEngine.cleanUtterance("本当ですか？"))
        assertEquals("行くぞ！", SpeechRecognitionEngine.cleanUtterance("行くぞ！"))
        assertEquals("田中です。", SpeechRecognitionEngine.cleanUtterance("[BLANK_AUDIO] 田中です。"))
        assertEquals("Really?!", SpeechRecognitionEngine.cleanUtterance("Really?!..."))
        assertEquals("Hello world.", SpeechRecognitionEngine.cleanUtterance("Hello world. [MUSIC]"))
        assertEquals("Hello world.", SpeechRecognitionEngine.cleanUtterance("Hello world. ..."))
    }

    @Test
    fun testCleanUtterance_stripsTrailingJunkButNotSentenceEnd() {
        assertEquals("そうですね", SpeechRecognitionEngine.cleanUtterance("そうですね、"))
        assertEquals("Well", SpeechRecognitionEngine.cleanUtterance("Well,"))
        assertEquals("Wait", SpeechRecognitionEngine.cleanUtterance("Wait~"))
        assertEquals("まあ", SpeechRecognitionEngine.cleanUtterance("まあ～"))
        assertEquals("はい", SpeechRecognitionEngine.cleanUtterance("はい。。。"))
        assertEquals("So", SpeechRecognitionEngine.cleanUtterance("So…"))
        assertEquals("I think", SpeechRecognitionEngine.cleanUtterance("I think --"))
        assertEquals("I think… maybe", SpeechRecognitionEngine.cleanUtterance("I think... maybe"))
        assertEquals("OK", SpeechRecognitionEngine.cleanUtterance("! OK"))
        assertEquals("", SpeechRecognitionEngine.cleanUtterance("[MUSIC]"))
        assertEquals("", SpeechRecognitionEngine.cleanUtterance("…"))
    }

    @Test
    fun testIsHallucination_artifactsRequireExactPhraseMatch() {
        // Near-certain artifacts, any punctuation/case/spacing
        assertTrue(SpeechRecognitionEngine.isHallucination("ご視聴ありがとうございました。"))
        assertTrue(SpeechRecognitionEngine.isHallucination("「チャンネル登録よろしくお願いします！」"))
        assertTrue(SpeechRecognitionEngine.isHallucination("THANKS FOR WATCHING!"))
        assertTrue(SpeechRecognitionEngine.isHallucination("Subtitles by the Amara.org community"))
        // Concatenation of artifacts
        assertTrue(SpeechRecognitionEngine.isHallucination("ご視聴ありがとうございました。チャンネル登録よろしくお願いします。"))

        // Real dialogue that merely contains a phrase must be kept
        assertFalse(SpeechRecognitionEngine.isHallucination("Thank you, Tanaka-san!"))
        assertFalse(SpeechRecognitionEngine.isHallucination("Thank you for coming."))
        assertFalse(SpeechRecognitionEngine.isHallucination("お疲れ様でした、部長。"))
        assertFalse(SpeechRecognitionEngine.isHallucination("おやすみなさい、お兄ちゃん。"))
        assertFalse(SpeechRecognitionEngine.isHallucination("I translated by hand."))
    }

    @Test
    fun testIsHallucination_suspiciousPhrasesDependOnSegmentLoudness() {
        val quiet = SpeechRecognitionEngine.SUSPICIOUS_PHRASE_MAX_RMS_DB - 3f
        val loud = SpeechRecognitionEngine.SUSPICIOUS_PHRASE_MAX_RMS_DB + 10f

        // Unknown loudness: keep (never drop real speech)
        assertFalse(SpeechRecognitionEngine.isHallucination("Thank you."))
        assertFalse(SpeechRecognitionEngine.isHallucination("おやすみなさい。"))
        assertFalse(SpeechRecognitionEngine.isHallucination("お疲れ様でした！"))

        // Quiet segment: drop
        assertTrue(SpeechRecognitionEngine.isHallucination("Thank you.", quiet))
        assertTrue(SpeechRecognitionEngine.isHallucination("おやすみなさい。", quiet))
        assertTrue(SpeechRecognitionEngine.isHallucination("お疲れ様でした。お疲れ様でした。", quiet))
        assertTrue(SpeechRecognitionEngine.isHallucination("You", quiet))

        // Loud segment: keep
        assertFalse(SpeechRecognitionEngine.isHallucination("Thank you.", loud))
        assertFalse(SpeechRecognitionEngine.isHallucination("おやすみなさい。", loud))

        // Quiet but not composed only of suspicious phrases: keep
        assertFalse(SpeechRecognitionEngine.isHallucination("Thank you, Tanaka-san!", quiet))

        // Artifacts are rejected regardless of loudness
        assertTrue(SpeechRecognitionEngine.isHallucination("Thanks for watching.", loud))
    }

    @Test
    fun testIsHallucination_repetitionChecksAvoidFalsePositives() {
        assertTrue(SpeechRecognitionEngine.isHallucination("ああああああ"))
        assertTrue(SpeechRecognitionEngine.isHallucination("hahahaha"))
        assertTrue(SpeechRecognitionEngine.isHallucination("no no no no no"))

        assertFalse(SpeechRecognitionEngine.isHallucination("ははは"))
        assertFalse(SpeechRecognitionEngine.isHallucination("ははは、面白いね。"))
        assertFalse(SpeechRecognitionEngine.isHallucination("No no no, that's wrong."))
        assertFalse(SpeechRecognitionEngine.isHallucination("No, no, no, no!"))
        assertFalse(SpeechRecognitionEngine.isHallucination("It costs 100000 yen."))
        assertFalse(SpeechRecognitionEngine.isHallucination("1000000円です。"))
        assertFalse(SpeechRecognitionEngine.isHallucination("Wait!!!!! Come back!"))
    }

    @Test
    fun testConstants_areProperlyTunedForJapaneseAndConsonants() {
        assertEquals(16000, SpeechRecognitionEngine.SAMPLE_RATE)
        assertEquals(1600, SpeechRecognitionEngine.SAMPLES_PER_CHUNK)
        assertTrue(SpeechRecognitionEngine.MIN_SPEECH_SAMPLES >= 16000)
        assertTrue(SpeechRecognitionEngine.SILENCE_THRESHOLD_DB <= -35.0f)
    }
}
