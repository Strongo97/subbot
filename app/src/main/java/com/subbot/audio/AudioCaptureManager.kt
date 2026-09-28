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
    @Volatile private var isRecording = false
    private val recordLock = Any()

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

        synchronized(recordLock) {
            audioRecord = record
        }

        val audioChannel = Channel<FloatArray>(capacity = 64)
        channel = audioChannel

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            audioChannel.close()
            return audioChannel
        }

        val scope = CoroutineScope(Dispatchers.IO)
        captureScope = scope
        isRecording = true

        try {
            record.startRecording()
        } catch (e: Throwable) {
            android.util.Log.e("AudioCaptureManager", "Failed to start AudioRecord recording", e)
            audioChannel.close()
            return audioChannel
        }

        captureJob = scope.launch {
            val byteBuffer = ByteArray(SAMPLES_PER_CHUNK * 2)
            try {
                while (isActive && isRecording) {
                    val currentRecord = audioRecord ?: break
                    if (currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                    val bytesRead = currentRecord.read(byteBuffer, 0, byteBuffer.size)
                    if (bytesRead > 0 && isActive && isRecording) {
                        val sampleCount = bytesRead / 2
                        val floats = AudioUtils.pcm16ToNormalizedFloats(byteBuffer, sampleCount)
                        audioChannel.send(floats)
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            } catch (e: Throwable) {
                // Channel closed or job cancelled
            } finally {
                audioChannel.close()
            }
        }

        return audioChannel
    }

    fun stopCapture() {
        isRecording = false
        captureJob?.cancel()
        captureJob = null
        captureScope?.cancel()
        captureScope = null

        try {
            channel?.close()
        } catch (e: Throwable) {
            // Ignore
        }
        channel = null

        synchronized(recordLock) {
            val record = audioRecord
            audioRecord = null
            if (record != null) {
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("AudioCaptureManager", "Error stopping AudioRecord", e)
                }
                try {
                    record.release()
                } catch (e: Throwable) {
                    android.util.Log.e("AudioCaptureManager", "Error releasing AudioRecord", e)
                }
            }
        }
    }
}
