package com.vedito.app.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Decodes a compact PCM amplitude envelope in the background and caches it on disk.
 * The cache is preview-only metadata; original media is never modified.
 */
class AudioWaveformCache(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cacheDir = File(appContext.cacheDir, "audio-waveforms").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, FloatArray>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    fun request(uri: Uri, durationMs: Int, callback: (FloatArray) -> Unit) {
        val key = cacheKey(uri, durationMs)
        memory[key]?.let { callback(it.copyOf()); return }
        readDisk(key)?.let {
            memory[key] = it
            callback(it.copyOf())
            return
        }
        if (!pending.add(key)) return

        executor.execute {
            val waveform = runCatching { decode(uri, durationMs) }.getOrElse { FloatArray(0) }
            if (waveform.isNotEmpty()) {
                memory[key] = waveform
                writeDisk(key, waveform)
            }
            pending.remove(key)
            main.post { callback(waveform.copyOf()) }
        }
    }

    fun release() {
        executor.shutdownNow()
        pending.clear()
    }

    private fun decode(uri: Uri, durationMs: Int): FloatArray {
        if (durationMs <= 0) return FloatArray(0)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) return FloatArray(0)

            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val sums = DoubleArray(BUCKETS)
            val counts = IntArray(BUCKETS)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var idleAfterInputDone = 0
            val durationUs = durationMs.toLong() * 1_000L

            while (!outputDone && !Thread.currentThread().isInterrupted && idleAfterInputDone < MAX_IDLE_ROUNDS) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) idleAfterInputDone++
                    }
                    else -> if (outputIndex >= 0) {
                        idleAfterInputDone = 0
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            copy.position(info.offset)
                            copy.limit(info.offset + info.size)
                            val rms = rms(copy, pcmEncoding)
                            val bucket = ((info.presentationTimeUs.coerceIn(0L, durationUs) * BUCKETS) /
                                durationUs.coerceAtLeast(1L)).toInt().coerceIn(0, BUCKETS - 1)
                            sums[bucket] += rms.toDouble()
                            counts[bucket] += 1
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val result = FloatArray(BUCKETS)
            var peak = 0f
            for (i in result.indices) {
                if (counts[i] > 0) result[i] = (sums[i] / counts[i]).toFloat()
                peak = maxOf(peak, result[i])
            }
            if (peak > 0f) {
                for (i in result.indices) result[i] = (result[i] / peak).coerceIn(0f, 1f)
            }
            fillGaps(result)
            return result
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun rms(buffer: java.nio.ByteBuffer, encoding: Int): Float {
        var sum = 0.0
        var count = 0
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (buffer.remaining() >= 4) {
                    val sample = buffer.float.coerceIn(-1f, 1f)
                    sum += sample * sample
                    count++
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (buffer.hasRemaining()) {
                    val sample = ((buffer.get().toInt() and 0xFF) - 128) / 128f
                    sum += sample * sample
                    count++
                }
            }
            else -> {
                while (buffer.remaining() >= 2) {
                    val sample = buffer.short / 32768f
                    sum += sample * sample
                    count++
                }
            }
        }
        return if (count == 0) 0f else sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }

    private fun fillGaps(values: FloatArray) {
        var last = -1
        for (i in values.indices) {
            if (values[i] <= 0f) continue
            if (last >= 0 && i - last > 1) {
                val start = values[last]
                val end = values[i]
                val span = i - last
                for (j in 1 until span) {
                    values[last + j] = start + (end - start) * (j.toFloat() / span)
                }
            }
            last = i
        }
    }

    private fun cacheKey(uri: Uri, durationMs: Int): String {
        val raw = "${uri}|$durationMs|$CACHE_VERSION"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readDisk(key: String): FloatArray? = runCatching {
        val file = File(cacheDir, "$key.wf")
        if (!file.isFile) return@runCatching null
        DataInputStream(FileInputStream(file)).use { input ->
            if (input.readInt() != CACHE_VERSION) return@use null
            val size = input.readInt()
            if (size !in 1..4_096) return@use null
            FloatArray(size) { input.readFloat().coerceIn(0f, 1f) }
        }
    }.getOrNull()

    private fun writeDisk(key: String, values: FloatArray) {
        runCatching {
            DataOutputStream(FileOutputStream(File(cacheDir, "$key.wf"))).use { output ->
                output.writeInt(CACHE_VERSION)
                output.writeInt(values.size)
                values.forEach(output::writeFloat)
            }
        }
    }

    companion object {
        private const val BUCKETS = 256
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_ROUNDS = 500
        private const val CACHE_VERSION = 1
    }
}
