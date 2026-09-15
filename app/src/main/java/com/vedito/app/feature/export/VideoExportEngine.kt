package com.vedito.app.feature.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.vedito.app.core.export.ExportPlan
import com.vedito.app.core.export.ExportPlanner
import com.vedito.app.core.export.ExportSettings
import com.vedito.app.core.model.Project
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class VideoExportEngine(
    context: Context
) : Closeable {
    interface Listener {
        fun onProgress(percent: Int, message: String)
        fun onCompleted(uri: Uri, plan: ExportPlan, elapsedMs: Long)
        fun onCancelled()
        fun onError(message: String, throwable: Throwable?)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var running = false
    private var worker: Thread? = null

    fun export(project: Project, outputUri: Uri, settings: ExportSettings, listener: Listener): Boolean {
        if (running) return false
        val plan = ExportPlanner.plan(project, settings)
        if (plan.frameCount <= 0 || plan.durationMs <= 0) {
            listener.onError("Project has no video duration to export", null)
            return false
        }
        running = true
        cancelRequested.set(false)
        worker = Thread({ runExport(project, outputUri, plan, listener) }, "VeditoExport").apply { start() }
        return true
    }

    fun cancel() {
        cancelRequested.set(true)
    }

    fun isRunning(): Boolean = running

    override fun close() {
        cancel()
    }

    private fun runExport(project: Project, outputUri: Uri, plan: ExportPlan, listener: Listener) {
        val startedAt = System.currentTimeMillis()
        var pfd: android.os.ParcelFileDescriptor? = null
        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var codecSurface: CodecInputSurface? = null
        var composer: SoftwareFrameComposer? = null
        var muxSink: Mp4MuxSink? = null
        var success = false
        var cancelled = false
        var terminalError: Throwable? = null
        var elapsedMs = 0L
        try {
            postProgress(listener, 0, "Preparing ${plan.width}×${plan.height} export")
            val outputPfd = appContext.contentResolver.openFileDescriptor(outputUri, "rw")
                ?: error("Unable to open export destination")
            pfd = outputPfd
            val muxer = MediaMuxer(outputPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val sink = Mp4MuxSink(muxer)
            muxSink = sink

            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.width, plan.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val activeVideoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            videoCodec = activeVideoCodec
            val inputSurface = activeVideoCodec.createInputSurface()
            activeVideoCodec.start()
            val activeCodecSurface = CodecInputSurface(inputSurface)
            codecSurface = activeCodecSurface

            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, plan.audioSampleRate, AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val activeAudioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioCodec = activeAudioCodec
            val frameComposer = SoftwareFrameComposer(appContext, project, plan)
            composer = frameComposer

            checkCancelled()
            var audioSamplesQueued = 0L
            while (audioSamplesQueued == 0L) {
                checkCancelled()
                audioSamplesQueued = queueSilentAudio(activeAudioCodec, plan, 0L, AUDIO_PRIME_SAMPLES)
                if (audioSamplesQueued == 0L) {
                    drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false, timeoutUs = CODEC_TIMEOUT_US)
                }
            }
            drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false)

            val firstFrame = frameComposer.compose(0)
            activeCodecSurface.draw(firstFrame, 0L)
            drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = false)

            var spin = 0
            while (!sink.started && spin++ < 80) {
                checkCancelled()
                drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false, timeoutUs = 10_000L)
                drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = false, timeoutUs = 10_000L)
            }
            check(sink.started) { "Encoder formats did not become ready" }

            val frameDurationNs = 1_000_000_000L / plan.frameRate
            for (frameIndex in 1 until plan.frameCount) {
                checkCancelled()
                val positionMs = ((frameIndex.toLong() * 1_000L) / plan.frameRate)
                    .coerceAtMost((plan.durationMs - 1).coerceAtLeast(0).toLong())
                    .toInt()
                val bitmap = frameComposer.compose(positionMs)
                activeCodecSurface.draw(bitmap, frameIndex.toLong() * frameDurationNs)
                drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = false)
                val percent = ((frameIndex + 1L) * 88L / plan.frameCount.coerceAtLeast(1)).toInt().coerceIn(1, 88)
                if (frameIndex % PROGRESS_FRAME_INTERVAL == 0 || frameIndex == plan.frameCount - 1) {
                    postProgress(listener, percent, "Rendering frame ${frameIndex + 1}/${plan.frameCount}")
                }
            }

            activeVideoCodec.signalEndOfInputStream()
            drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = true)
            postProgress(listener, 90, "Finalizing AAC track")

            val totalAudioSamples = ((plan.durationUs * plan.audioSampleRate) / 1_000_000L).coerceAtLeast(1L)
            while (audioSamplesQueued < totalAudioSamples) {
                checkCancelled()
                val remaining = totalAudioSamples - audioSamplesQueued
                val chunk = min(AUDIO_CHUNK_SAMPLES.toLong(), remaining).toInt()
                val queued = queueSilentAudio(activeAudioCodec, plan, audioSamplesQueued, chunk)
                if (queued == audioSamplesQueued) {
                    drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false, timeoutUs = 10_000L)
                    continue
                }
                audioSamplesQueued = queued
                drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false)
            }
            queueAudioEos(activeAudioCodec, plan, audioSamplesQueued)
            drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = true)
            postProgress(listener, 99, "Writing MP4")
            sink.stop()
            muxSink = null
            outputPfd.close()
            pfd = null

            success = true
            elapsedMs = System.currentTimeMillis() - startedAt
        } catch (_: ExportCancelledException) {
            cancelled = true
        } catch (t: Throwable) {
            terminalError = t
        } finally {
            runCatching { composer?.close() }
            runCatching { codecSurface?.close() }
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            runCatching { muxSink?.stop() }
            runCatching { pfd?.close() }
            if (!success || cancelled) runCatching { appContext.contentResolver.delete(outputUri, null, null) }
            running = false
            worker = null
            when {
                success -> {
                    post { listener.onProgress(100, "Export complete") }
                    post { listener.onCompleted(outputUri, plan, elapsedMs) }
                }
                cancelled -> post { listener.onCancelled() }
                terminalError != null -> post { listener.onError(terminalError?.message ?: "Export failed", terminalError) }
                else -> post { listener.onError("Export failed", null) }
            }
        }
    }

    private fun queueSilentAudio(codec: MediaCodec, plan: ExportPlan, samplesQueued: Long, requestedSamples: Int): Long {
        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex < 0) return samplesQueued
        val input = codec.getInputBuffer(inputIndex) ?: return samplesQueued
        input.clear()
        val maxSamples = input.capacity() / (AUDIO_CHANNELS * PCM_BYTES_PER_SAMPLE)
        val samples = min(requestedSamples, maxSamples).coerceAtLeast(1)
        val bytes = samples * AUDIO_CHANNELS * PCM_BYTES_PER_SAMPLE
        repeat(bytes) { input.put(0.toByte()) }
        val ptsUs = samplesQueued * 1_000_000L / plan.audioSampleRate
        codec.queueInputBuffer(inputIndex, 0, bytes, ptsUs, 0)
        return samplesQueued + samples
    }

    private fun queueAudioEos(codec: MediaCodec, plan: ExportPlan, samplesQueued: Long) {
        while (true) {
            checkCancelled()
            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                val ptsUs = samplesQueued * 1_000_000L / plan.audioSampleRate
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    private fun drainCodec(
        codec: MediaCodec,
        track: Mp4MuxSink.Track,
        sink: Mp4MuxSink,
        endOfStream: Boolean,
        timeoutUs: Long = 0L
    ) {
        val info = MediaCodec.BufferInfo()
        var idleCount = 0
        while (true) {
            checkCancelled()
            val status = codec.dequeueOutputBuffer(info, if (endOfStream) CODEC_TIMEOUT_US else timeoutUs)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (idleCount++ > 500) error("$track encoder EOS timeout")
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onFormat(track, codec.outputFormat)
                status >= 0 -> {
                    val buffer: ByteBuffer = codec.getOutputBuffer(status) ?: ByteBuffer.allocate(0)
                    sink.write(track, buffer, info)
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(status, false)
                    if (eos) return
                }
            }
        }
    }

    private fun checkCancelled() {
        if (cancelRequested.get()) throw ExportCancelledException()
    }

    private fun postProgress(listener: Listener, percent: Int, message: String) {
        post { listener.onProgress(percent.coerceIn(0, 100), message) }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private class ExportCancelledException : RuntimeException()

    companion object {
        private const val AUDIO_CHANNELS = 2
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val AUDIO_PRIME_SAMPLES = 1_024
        private const val AUDIO_CHUNK_SAMPLES = 2_048
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val PROGRESS_FRAME_INTERVAL = 6
    }
}
