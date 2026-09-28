package com.subbot.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages capturing internal device audio using Android 10+ AudioPlaybackCapture API.
 */
class AudioCaptureManager {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val SAMPLES_PER_CHUNK = 1600 // 100ms at 16kHz
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var captureScope: CoroutineScope? = null
    private var channel: Channel<FloatArray>? = null

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startCapture(mediaProjection: MediaProjection): ReceiveChannel<FloatArray> {
        stopCapture()

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_MASK)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        val bufferSizeBytes = maxOf(minBufferSize, SAMPLES_PER_CHUNK * 2 * 2)

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        audioRecord = record
        val audioChannel = Channel<FloatArray>(capacity = 64)
        channel = audioChannel

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            audioChannel.close()
            return audioChannel
        }

        val scope = CoroutineScope(Dispatchers.IO)
        captureScope = scope

        record.startRecording()

        captureJob = scope.launch {
            val byteBuffer = ByteArray(SAMPLES_PER_CHUNK * 2)
            try {
                while (isActive) {
                    val bytesRead = record.read(byteBuffer, 0, byteBuffer.size)
                    if (bytesRead > 0) {
                        val sampleCount = bytesRead / 2
                        val floats = AudioUtils.pcm16ToNormalizedFloats(byteBuffer, sampleCount)
                        audioChannel.send(floats)
                    }
                }
            } catch (e: Exception) {
                // Channel closed or job cancelled
            } finally {
                audioChannel.close()
            }
        }

        return audioChannel
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        captureScope?.cancel()
        captureScope = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore during release
        }
        audioRecord = null

        channel?.close()
        channel = null
    }
}
