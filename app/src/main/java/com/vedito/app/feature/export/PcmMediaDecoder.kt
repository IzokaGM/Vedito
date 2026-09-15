package com.vedito.app.feature.export

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Decodes an Android media audio track into seekable 16-bit stereo PCM at the export sample rate.
 * PTS-addressed writes keep decoder chunk boundaries/gaps deterministic for the offline mixer.
 */
internal class PcmMediaDecoder(
    private val context: Context,
    private val targetSampleRate: Int,
    private val tempRoot: File,
    private val checkCancelled: () -> Unit
) {
    data class DecodeResult(
        val asset: DecodedPcmAsset?,
        val warning: String? = null
    )

    fun decode(
        uriString: String,
        ordinal: Int,
        requiredStartMs: Int = 0,
        requiredEndMs: Int = Int.MAX_VALUE
    ): DecodeResult {
        checkCancelled()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputFile: RandomAccessFile? = null
        try {
            extractor.setDataSource(context, Uri.parse(uriString), null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return DecodeResult(null, "No audio stream in one media source")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return DecodeResult(null, "Audio stream has no codec MIME")
            extractor.selectTrack(trackIndex)
            val safeStartMs = requiredStartMs.coerceAtLeast(0)
            val safeEndMs = requiredEndMs.coerceAtLeast(safeStartMs + 1)
            val decodeBaseUs = (safeStartMs.toLong() * 1_000L - RANGE_PADDING_US).coerceAtLeast(0L)
            val decodeEndUs = if (safeEndMs == Int.MAX_VALUE) Long.MAX_VALUE else safeEndMs.toLong() * 1_000L + RANGE_PADDING_US
            extractor.seekTo(decodeBaseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val file = File(tempRoot, "pcm_${ordinal}_${uriString.hashCode().toUInt().toString(16)}.raw")
            val pcmOutput = RandomAccessFile(file, "rw").apply { setLength(0L) }
            outputFile = pcmOutput
            val decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            codec = decoder

            var sourceRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, targetSampleRate).coerceAtLeast(1)
            var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            var maxWrittenFrame = 0L
            var idleLoops = 0
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                checkCancelled()
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            input.clear()
                            val sampleTimeUs = extractor.sampleTime
                            val size = if (sampleTimeUs >= 0L && sampleTimeUs <= decodeEndUs) extractor.readSampleData(input, 0) else -1
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val ptsUs = sampleTimeUs.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inputIndex, 0, size, ptsUs, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val status = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++idleLoops > MAX_IDLE_LOOPS) error("Audio decoder EOS timeout")
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idleLoops = 0
                        val format = decoder.outputFormat
                        sourceRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sourceRate).coerceAtLeast(1)
                        channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        pcmEncoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    else -> if (status >= 0) {
                        idleLoops = 0
                        val buffer = decoder.getOutputBuffer(status)
                        if (buffer != null && info.size > 0) {
                            val endFrame = writeNormalizedChunk(
                                output = pcmOutput,
                                source = buffer,
                                offset = info.offset,
                                size = info.size,
                                ptsUs = info.presentationTimeUs.coerceAtLeast(0L),
                                basePtsUs = decodeBaseUs,
                                sourceRate = sourceRate,
                                channels = channels,
                                pcmEncoding = pcmEncoding
                            )
                            if (endFrame > maxWrittenFrame) maxWrittenFrame = endFrame
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(status, false)
                    }
                }
            }

            pcmOutput.fd.sync()
            pcmOutput.close()
            outputFile = null
            if (maxWrittenFrame <= 0L) {
                file.delete()
                return DecodeResult(null, "Audio stream decoded no PCM samples")
            }
            val absoluteStartFrame = decodeBaseUs * targetSampleRate / 1_000_000L
            return DecodeResult(DecodedPcmAsset(file, targetSampleRate, absoluteStartFrame, maxWrittenFrame))
        } catch (t: Throwable) {
            if (t is ExportAudioCancelledException) throw t
            return DecodeResult(null, "Audio decoder skipped a source: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { outputFile?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeNormalizedChunk(
        output: RandomAccessFile,
        source: ByteBuffer,
        offset: Int,
        size: Int,
        ptsUs: Long,
        basePtsUs: Long,
        sourceRate: Int,
        channels: Int,
        pcmEncoding: Int
    ): Long {
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val frameBytes = bytesPerSample * channels
        val sourceFrames = if (frameBytes > 0) size / frameBytes else 0
        if (sourceFrames <= 0) return (ptsUs - basePtsUs).coerceAtLeast(0L) * targetSampleRate / 1_000_000L

        val window = source.duplicate().apply {
            position(offset)
            limit(offset + sourceFrames * frameBytes)
        }.slice().order(ByteOrder.nativeOrder())

        val samples = FloatArray(sourceFrames * channels)
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                for (i in samples.indices) samples[i] = window.float.coerceIn(-1f, 1f)
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in samples.indices) samples[i] = ((window.get().toInt() and 0xFF) - 128) / 128f
            }
            else -> {
                for (i in samples.indices) samples[i] = window.short / 32768f
            }
        }

        val targetFrames = ceil(sourceFrames.toDouble() * targetSampleRate / sourceRate).toInt().coerceAtLeast(1)
        val targetStartFrame = (ptsUs - basePtsUs).coerceAtLeast(0L) * targetSampleRate / 1_000_000L
        val outBytes = ByteBuffer.allocate(targetFrames * TARGET_CHANNELS * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until targetFrames) {
            val srcPosition = frame.toDouble() * sourceRate / targetSampleRate
            val leftIndex = floor(srcPosition).toInt().coerceIn(0, sourceFrames - 1)
            val rightIndex = (leftIndex + 1).coerceAtMost(sourceFrames - 1)
            val fraction = (srcPosition - leftIndex).toFloat().coerceIn(0f, 1f)
            val l0 = channelSample(samples, leftIndex, channels, 0)
            val l1 = channelSample(samples, rightIndex, channels, 0)
            val r0 = channelSample(samples, leftIndex, channels, if (channels > 1) 1 else 0)
            val r1 = channelSample(samples, rightIndex, channels, if (channels > 1) 1 else 0)
            outBytes.putShort(floatToShort(l0 + (l1 - l0) * fraction))
            outBytes.putShort(floatToShort(r0 + (r1 - r0) * fraction))
        }
        output.seek(targetStartFrame * TARGET_CHANNELS * BYTES_PER_SAMPLE.toLong())
        output.write(outBytes.array())
        return targetStartFrame + targetFrames
    }

    private fun channelSample(samples: FloatArray, frame: Int, channels: Int, channel: Int): Float {
        return samples[(frame * channels + channel.coerceIn(0, channels - 1)).coerceIn(0, samples.lastIndex)]
    }

    private fun floatToShort(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_IDLE_LOOPS = 1_000
        private const val RANGE_PADDING_US = 1_000_000L
        private const val TARGET_CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
    }
}

internal class DecodedPcmAsset(
    val file: File,
    val sampleRate: Int,
    val absoluteStartFrame: Long,
    val frameCount: Long
) : Closeable {
    val absoluteEndFrame: Long get() = absoluteStartFrame + frameCount
    private val input = RandomAccessFile(file, "r")

    @Synchronized
    fun readFrames(startFrameInput: Long, requestedFrames: Int): ShortArray {
        if (requestedFrames <= 0 || frameCount <= 0L) return ShortArray(0)
        val absoluteFrame = startFrameInput.coerceIn(absoluteStartFrame, absoluteEndFrame)
        val startFrame = (absoluteFrame - absoluteStartFrame).coerceIn(0L, frameCount)
        val available = (frameCount - startFrame).coerceAtLeast(0L)
        val frames = minOf(requestedFrames.toLong(), available).toInt()
        if (frames <= 0) return ShortArray(0)
        val bytes = ByteArray(frames * 4)
        input.seek(startFrame * 4L)
        val read = input.read(bytes)
        if (read <= 0) return ShortArray(0)
        val count = read / 2
        val result = ShortArray(count)
        val buffer = ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) result[i] = buffer.short
        return result
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { file.delete() }
    }
}

internal class ExportAudioCancelledException : RuntimeException()

private fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
