package com.subbot.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUtilsTest {

    @Test
    fun testPcm16ToNormalizedFloats_convertsCorrectly() {
        // 0, 32767 (max positive), -32768 (min negative)
        val bytes = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F.toByte(), 0x00, 0x80.toByte())
        val floats = AudioUtils.pcm16ToNormalizedFloats(bytes, 3)
        assertEquals(3, floats.size)
        assertEquals(0.0f, floats[0], 0.001f)
        assertEquals(1.0f, floats[1], 0.001f)
        assertEquals(-1.0f, floats[2], 0.001f)
    }

    @Test
    fun testCalculateRmsDb_detectsSilence() {
        val silentSamples = FloatArray(1600) { 0.0f }
        val rms = AudioUtils.calculateRmsDb(silentSamples)
        assertTrue(AudioUtils.isSilence(rms, -45.0f))
    }

    @Test
    fun testCalculateRmsDb_detectsActiveSpeech() {
        // High amplitude sine wave or values
        val activeSamples = FloatArray(1600) { 0.5f }
        val rms = AudioUtils.calculateRmsDb(activeSamples)
        assertFalse(AudioUtils.isSilence(rms, -45.0f))
    }
}
