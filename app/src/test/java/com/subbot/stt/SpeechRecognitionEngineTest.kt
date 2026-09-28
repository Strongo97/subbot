package com.subbot.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionEngineTest {

    @Test
    fun testCleanUtterance_removesBracketsEllipsesAndLeadingPunctuation() {
        assertEquals("Hello world", SpeechRecognitionEngine.cleanUtterance("[BLANK_AUDIO] Hello world..."))
        assertEquals("こんにちは", SpeechRecognitionEngine.cleanUtterance("...こんにちは。"))
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
    fun testConstants_areProperlyTunedForJapaneseAndConsonants() {
        assertEquals(16000, SpeechRecognitionEngine.SAMPLE_RATE)
        assertEquals(1600, SpeechRecognitionEngine.SAMPLES_PER_CHUNK)
        assertTrue(SpeechRecognitionEngine.MIN_SPEECH_SAMPLES >= 16000)
        assertTrue(SpeechRecognitionEngine.SILENCE_THRESHOLD_DB <= -35.0f)
    }
}
