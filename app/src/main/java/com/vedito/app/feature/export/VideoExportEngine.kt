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
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Patch 22 production export pipeline.
 * Video uses bounded streaming decode plus a hybrid CPU/GPU compositor; mixed PCM remains deterministic.
 */
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
        val requestedPlan = ExportPlanner.plan(project, settings)
        if (requestedPlan.frameCount <= 0 || requestedPlan.durationMs <= 0) {
            listener.onError("Project has no video duration to export", null)
            return false
        }
        val devicePreflight = ExportCapabilityProbe.inspect(requestedPlan)
        val selection = devicePreflight.selection
        if (!devicePreflight.canEncode || selection == null) {
            listener.onError(devicePreflight.failureReason ?: "No compatible video encoder is available for this export profile", null)
            return false
        }
        val plan = requestedPlan.copy(videoBitrate = selection.effectiveBitrate)
        running = true
        cancelRequested.set(false)
        worker = Thread({ runExport(project, outputUri, plan, selection, listener) }, "VeditoExport").apply { start() }
        return true
    }

    fun cancel() {
        cancelRequested.set(true)
        worker?.interrupt()
    }

    fun isRunning(): Boolean = running

    override fun close() {
        cancel()
    }

    private fun runExport(project: Project, outputUri: Uri, plan: ExportPlan, selection: VideoEncoderSelection, listener: Listener) {
        val startedAt = System.currentTimeMillis()
        var pfd: android.os.ParcelFileDescriptor? = null
        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var codecSurface: CodecInputSurface? = null
        var composer: SoftwareFrameComposer? = null
        var audioMixer: OfflineAudioMixer? = null
        var muxSink: Mp4MuxSink? = null
        var success = false
        var cancelled = false
        var terminalError: Throwable? = null
        var elapsedMs = 0L

        try {
            postProgress(listener, 0, "Preparing export")
            checkCancelled()

            val outputPfd = openOutput(outputUri) ?: error("Unable to open export destination")
            pfd = outputPfd
            val muxer = MediaMuxer(outputPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val sink = Mp4MuxSink(muxer)
            muxSink = sink

            val activeMixer = OfflineAudioMixer(appContext, project, plan) {
                if (cancelRequested.get() || Thread.currentThread().isInterrupted) throw ExportAudioCancelledException()
            }
            audioMixer = activeMixer
            val prepareReport = activeMixer.prepare { index, total, label ->
                val percent = if (total <= 0) 4 else (2 + index * 8 / total).coerceIn(2, 10)
                postProgress(listener, percent, label)
            }
            if (prepareReport.unavailableSources > 0) {
                postProgress(listener, 10, "Audio ready · ${prepareReport.unavailableSources} source(s) have no decodable sound")
            } else {
                postProgress(listener, 10, "Audio mixer ready")
            }
            checkCancelled()

            val videoFormat = MediaFormat.createVideoFormat(plan.videoMimeType, plan.width, plan.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val activeVideoCodec = MediaCodec.createByCodecName(selection.codecName).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            videoCodec = activeVideoCodec
            val inputSurface = activeVideoCodec.createInputSurface()
            activeVideoCodec.start()
            val activeCodecSurface = CodecInputSurface(inputSurface)
            codecSurface = activeCodecSurface

            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                plan.audioSampleRate,
                AUDIO_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
            }
            val activeAudioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioCodec = activeAudioCodec

            val frameComposer = SoftwareFrameComposer(appContext, project, plan) { checkCancelled() }
            composer = frameComposer
            val totalAudioSamples = ((plan.durationUs * plan.audioSampleRate) / 1_000_000L).coerceAtLeast(1L)

            // Prime both encoders so MediaMuxer receives both formats before the main render loop.
            var audioSamplesQueued = 0L
            while (audioSamplesQueued == 0L) {
                checkCancelled()
                val queued = queueMixedAudio(activeAudioCodec, plan, activeMixer, audioSamplesQueued, AUDIO_PRIME_SAMPLES)
                if (queued == audioSamplesQueued) {
                    drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false, timeoutUs = CODEC_TIMEOUT_US)
                } else {
                    audioSamplesQueued = queued
                }
            }
            drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false)

            val firstFrame = frameComposer.composeHybrid(0)
            activeCodecSurface.draw(firstFrame, 0L)
            drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = false)

            var spin = 0
            while (!sink.started && spin++ < FORMAT_READY_SPINS) {
                checkCancelled()
                drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false, timeoutUs = CODEC_TIMEOUT_US)
                drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = false, timeoutUs = CODEC_TIMEOUT_US)
            }
            check(sink.started) { "Encoder formats did not become ready" }

            val frameDurationNs = 1_000_000_000L / plan.frameRate
            for (frameIndex in 1 until plan.frameCount) {
                checkCancelled()
                val positionMs = ((frameIndex.toLong() * 1_000L) / plan.frameRate)
                    .coerceAtMost((plan.durationMs - 1).coerceAtLeast(0).toLong())
                    .toInt()
                val frame = frameComposer.composeHybrid(positionMs)
                activeCodecSurface.draw(frame, frameIndex.toLong() * frameDurationNs)
                drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = false)

                val targetAudioSamples = min(
                    totalAudioSamples,
                    ((frameIndex + 1L) * plan.audioSampleRate / plan.frameRate).coerceAtLeast(audioSamplesQueued)
                )
                audioSamplesQueued = queueAudioUntil(
                    codec = activeAudioCodec,
                    plan = plan,
                    mixer = activeMixer,
                    sink = sink,
                    samplesQueuedInput = audioSamplesQueued,
                    targetSamples = targetAudioSamples
                )

                val percent = (10L + (frameIndex + 1L) * 78L / plan.frameCount.coerceAtLeast(1))
                    .toInt().coerceIn(11, 88)
                if (frameIndex % PROGRESS_FRAME_INTERVAL == 0 || frameIndex == plan.frameCount - 1) {
                    val decode = frameComposer.performanceSnapshot().shortLabel()
                    postProgress(listener, percent, "Rendering ${frameIndex + 1}/${plan.frameCount} · $decode · GPU post")
                }
            }

            activeVideoCodec.signalEndOfInputStream()
            drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = true)
            postProgress(listener, 90, "Finalizing mixed AAC audio")

            audioSamplesQueued = queueAudioUntil(
                codec = activeAudioCodec,
                plan = plan,
                mixer = activeMixer,
                sink = sink,
                samplesQueuedInput = audioSamplesQueued,
                targetSamples = totalAudioSamples
            )
            queueAudioEos(activeAudioCodec, plan, audioSamplesQueued)
            drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = true)

            checkCancelled()
            postProgress(listener, 98, "Finalizing MP4")
            sink.stop()
            muxSink = null
            outputPfd.close()
            pfd = null

            success = true
            elapsedMs = System.currentTimeMillis() - startedAt
        } catch (_: ExportCancelledException) {
            cancelled = true
        } catch (_: ExportAudioCancelledException) {
            cancelled = true
        } catch (t: Throwable) {
            terminalError = t
        } finally {
            runCatching { audioMixer?.close() }
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
                terminalError != null -> post {
                    listener.onError(humanReadableError(terminalError), terminalError)
                }
                else -> post { listener.onError("Export failed", null) }
            }
        }
    }

    private fun openOutput(uri: Uri): android.os.ParcelFileDescriptor? {
        return runCatching { appContext.contentResolver.openFileDescriptor(uri, "rwt") }.getOrNull()
            ?: runCatching { appContext.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
    }

    private fun queueAudioUntil(
        codec: MediaCodec,
        plan: ExportPlan,
        mixer: OfflineAudioMixer,
        sink: Mp4MuxSink,
        samplesQueuedInput: Long,
        targetSamples: Long
    ): Long {
        var samplesQueued = samplesQueuedInput
        var stallCount = 0
        while (samplesQueued < targetSamples) {
            checkCancelled()
            val remaining = targetSamples - samplesQueued
            val requested = min(AUDIO_CHUNK_SAMPLES.toLong(), remaining).toInt()
            val queued = queueMixedAudio(codec, plan, mixer, samplesQueued, requested)
            if (queued == samplesQueued) {
                drainCodec(codec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false, timeoutUs = CODEC_TIMEOUT_US)
                if (++stallCount > MAX_AUDIO_INPUT_STALLS) error("AAC encoder input stalled")
            } else {
                samplesQueued = queued
                stallCount = 0
                drainCodec(codec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = false)
            }
        }
        return samplesQueued
    }

    private fun queueMixedAudio(
        codec: MediaCodec,
        plan: ExportPlan,
        mixer: OfflineAudioMixer,
        samplesQueued: Long,
        requestedSamples: Int
    ): Long {
        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex < 0) return samplesQueued
        val input = codec.getInputBuffer(inputIndex) ?: return samplesQueued
        input.clear()
        input.order(ByteOrder.LITTLE_ENDIAN)
        val maxSamples = input.capacity() / (AUDIO_CHANNELS * PCM_BYTES_PER_SAMPLE)
        val samples = min(requestedSamples, maxSamples).coerceAtLeast(1)
        val pcm = mixer.mix(samplesQueued, samples)
        val actualSamples = pcm.size / AUDIO_CHANNELS
        if (actualSamples <= 0) return samplesQueued
        pcm.forEach { input.putShort(it) }
        val ptsUs = samplesQueued * 1_000_000L / plan.audioSampleRate
        codec.queueInputBuffer(inputIndex, 0, pcm.size * PCM_BYTES_PER_SAMPLE, ptsUs, 0)
        return samplesQueued + actualSamples
    }

    private fun queueAudioEos(codec: MediaCodec, plan: ExportPlan, samplesQueued: Long) {
        var stalls = 0
        while (true) {
            checkCancelled()
            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                val ptsUs = samplesQueued * 1_000_000L / plan.audioSampleRate
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            if (++stalls > MAX_AUDIO_INPUT_STALLS) error("AAC encoder EOS input stalled")
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
                    if (idleCount++ > MAX_EOS_IDLE_LOOPS) error("$track encoder EOS timeout")
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

    private fun humanReadableError(t: Throwable?): String {
        val message = t?.message.orEmpty()
        return when {
            message.contains("codec", ignoreCase = true) || message.contains("encoder", ignoreCase = true) -> "This device could not complete the selected encode profile. Try H.264, a lower resolution, 30 fps, or a shorter project."
            message.contains("space", ignoreCase = true) -> "Not enough storage space to finish export."
            message.contains("destination", ignoreCase = true) -> "Vedito lost access to the selected save location. Choose another destination."
            message.isNotBlank() -> message
            else -> "Export failed before the MP4 could be finalized."
        }
    }

    private fun checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted) throw ExportCancelledException()
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
        private const val FORMAT_READY_SPINS = 120
        private const val MAX_AUDIO_INPUT_STALLS = 800
        private const val MAX_EOS_IDLE_LOOPS = 800
        private const val PROGRESS_FRAME_INTERVAL = 6
    }
}
