package com.subbot.stt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Three decoupled stages connected by small bounded channels:
 *
 *   audio chunks -> [segmenter] -> Channel<FloatArray> -> [decoder] -> Channel<String> -> [consumer: onUtterance]
 *
 * - The segmenter only runs the cheap speech detector, so it always keeps up with capture.
 * - A slow decoder never blocks segmentation: the segment queue drops its OLDEST pending segment
 *   (logged) instead of growing or back-pressuring the audio channel.
 * - A slow onUtterance (e.g. awaiting translation) never blocks decoding: the utterance queue
 *   likewise drops the oldest pending text so the overlay stays current.
 *
 * Runs in the caller's coroutine context (the engine wraps it in Dispatchers.Default); returns
 * once the audio channel is closed and every stage has drained, or when [isStopped] turns true.
 */
class SpeechPipeline(
    private val segmentQueueCapacity: Int = DEFAULT_SEGMENT_QUEUE_CAPACITY,
    private val utteranceQueueCapacity: Int = DEFAULT_UTTERANCE_QUEUE_CAPACITY,
    private val log: (String) -> Unit = SttLog::w
) {
    companion object {
        const val DEFAULT_SEGMENT_QUEUE_CAPACITY = 2
        const val DEFAULT_UTTERANCE_QUEUE_CAPACITY = 4
    }

    private val droppedSegmentCount = AtomicInteger(0)
    private val droppedUtteranceCount = AtomicInteger(0)

    /** Number of segments discarded because the decoder fell behind. */
    val droppedSegments: Int get() = droppedSegmentCount.get()

    /** Number of utterances discarded because onUtterance fell behind. */
    val droppedUtterances: Int get() = droppedUtteranceCount.get()

    suspend fun run(
        audio: ReceiveChannel<FloatArray>,
        segmenter: SpeechSegmenter,
        decode: suspend (FloatArray) -> String?,
        onUtterance: suspend (String) -> Unit,
        isStopped: () -> Boolean = { false }
    ) = coroutineScope {
        val segments = Channel<FloatArray>(
            capacity = segmentQueueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { dropped ->
                val n = droppedSegmentCount.incrementAndGet()
                log("Decoder behind: dropped pending segment of ${dropped.size / SpeechRecognitionEngine.SAMPLE_RATE.toFloat()}s (total dropped: $n)")
            }
        )
        val utterances = Channel<String>(
            capacity = utteranceQueueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { dropped ->
                val n = droppedUtteranceCount.incrementAndGet()
                log("Subtitle consumer behind: dropped utterance \"${dropped.take(40)}\" (total dropped: $n)")
            }
        )

        // Stage 1: segmentation (never suspends on a full segment queue thanks to DROP_OLDEST).
        val segmenterJob = launch {
            try {
                for (chunk in audio) {
                    if (isStopped()) break
                    for (segment in segmenter.process(chunk)) segments.send(segment)
                }
                if (!isStopped()) {
                    for (segment in segmenter.flush()) segments.send(segment)
                }
            } finally {
                segments.close()
            }
        }

        // Stage 2: decoding.
        launch {
            try {
                for (segment in segments) {
                    if (isStopped()) break
                    val text = try {
                        decode(segment)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        SttLog.e("Error during decode", e)
                        null
                    }
                    if (isStopped()) break
                    if (!text.isNullOrBlank()) utterances.send(text)
                }
            } finally {
                utterances.close()
                // On stop, don't leave the segmenter waiting for the next audio chunk / channel close.
                if (isStopped()) segmenterJob.cancel()
            }
        }

        // Stage 3: delivery (translation / overlay).
        launch {
            for (utterance in utterances) {
                if (isStopped()) break
                try {
                    onUtterance(utterance)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    SttLog.e("Error in onUtterance", e)
                }
            }
        }
    }
}
