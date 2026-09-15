package com.vedito.app.feature.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.system.Os
import com.vedito.app.core.export.ExportPlan
import com.vedito.app.core.export.ExportPlanner
import com.vedito.app.core.export.ExportRecoveryPlan
import com.vedito.app.core.export.ExportRecoveryPlanner
import com.vedito.app.core.export.ExportSegment
import com.vedito.app.core.export.ExportSettings
import com.vedito.app.core.model.Project
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Patch 24 production export pipeline.
 *
 * Video is checkpointed as independently finalized MP4 segments. Audio is mixed/encoded once into
 * one full-length AAC checkpoint, avoiding repeated AAC encoder priming at video checkpoint edges.
 * Final delivery is a no-reencode remux of the checkpoint video chunks + full audio track.
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

    data class RecoveryStatus(
        val canStart: Boolean,
        val completedSegments: Int,
        val totalSegments: Int,
        val requiredCacheBytes: Long,
        val requiredAdditionalCacheBytes: Long,
        val warnings: List<String>,
        val failureReason: String?
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelRequested = AtomicBoolean(false)
    private val recoveryStore = ExportRecoveryStore(appContext)
    @Volatile private var running = false
    private var worker: Thread? = null

    fun inspectRecovery(project: Project, settings: ExportSettings): RecoveryStatus {
        val requestedPlan = ExportPlanner.plan(project, settings)
        val devicePreflight = ExportCapabilityProbe.inspect(requestedPlan)
        val selection = devicePreflight.selection
        if (!devicePreflight.canEncode || selection == null) {
            return RecoveryStatus(
                canStart = false,
                completedSegments = 0,
                totalSegments = 0,
                requiredCacheBytes = 0L,
                requiredAdditionalCacheBytes = 0L,
                warnings = devicePreflight.warnings,
                failureReason = devicePreflight.failureReason
            )
        }
        val plan = requestedPlan.copy(videoBitrate = selection.effectiveBitrate)
        val recovery = ExportRecoveryPlanner.plan(project, plan, selection.codecName)
        val resume = recoveryStore.resumeStats(recovery)
        val environment = ExportEnvironmentProbe.inspectCache(
            appContext,
            recovery,
            reusableCacheBytes = resume.reusableBytes,
            audioCheckpointPresent = resume.audioCheckpointPresent
        )
        return RecoveryStatus(
            canStart = environment.canStart,
            completedSegments = resume.completedSegments,
            totalSegments = recovery.segmentCount,
            requiredCacheBytes = recovery.requiredCacheBytes,
            requiredAdditionalCacheBytes = environment.requiredAdditionalCacheBytes,
            warnings = recovery.warnings + environment.warnings,
            failureReason = environment.failureReason
        )
    }

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
        val recovery = ExportRecoveryPlanner.plan(project, plan, selection.codecName)
        val resume = recoveryStore.resumeStats(recovery)
        val environment = ExportEnvironmentProbe.inspectDestination(
            appContext,
            outputUri,
            plan,
            recovery,
            reusableCacheBytes = resume.reusableBytes,
            audioCheckpointPresent = resume.audioCheckpointPresent
        )
        if (!environment.canStart) {
            listener.onError(environment.failureReason ?: "Export environment preflight failed", null)
            return false
        }

        running = true
        cancelRequested.set(false)
        worker = Thread(
            { runExport(project, outputUri, plan, recovery, selection, listener) },
            "VeditoExport"
        ).apply { start() }
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

    private fun runExport(
        project: Project,
        outputUri: Uri,
        plan: ExportPlan,
        recovery: ExportRecoveryPlan,
        selection: VideoEncoderSelection,
        listener: Listener
    ) {
        val startedAt = System.currentTimeMillis()
        val merger = Mp4SegmentMerger()
        val session = recoveryStore.open(recovery)
        var audioMixer: OfflineAudioMixer? = null
        var outputPfd: android.os.ParcelFileDescriptor? = null
        var success = false
        var cancelled = false
        var terminalError: Throwable? = null
        var elapsedMs = 0L

        try {
            postProgress(listener, 0, "Preparing recovery export")
            checkCancelled()

            val validVideoSegments = linkedSetOf<Int>()
            session.resumedSegmentIndices.forEach { index ->
                val file = session.finalSegmentFile(index)
                if (merger.isUsableVideo(file)) {
                    validVideoSegments += index
                } else {
                    recoveryStore.discardSegment(session, index)
                }
            }
            if (validVideoSegments.isNotEmpty()) {
                postProgress(
                    listener,
                    videoProgressPercent(recovery, validVideoSegments),
                    "Resuming ${validVideoSegments.size}/${recovery.segmentCount} video checkpoint(s)"
                )
            }

            val audioCheckpoint = session.finalAudioFile()
            var audioReady = merger.isUsableAudio(audioCheckpoint)
            if (!audioReady && audioCheckpoint.exists()) recoveryStore.discardAudio(session)

            // Synchronous preflight may have counted size-valid but codec-corrupt checkpoints. Recheck
            // after media validation so a damaged cache cannot make the remaining-space budget unsafe.
            val validatedReusableBytes = validVideoSegments.sumOf { session.finalSegmentFile(it).length() } +
                if (audioReady) audioCheckpoint.length() else 0L
            val validatedEnvironment = ExportEnvironmentProbe.inspectCache(
                appContext,
                recovery,
                reusableCacheBytes = validatedReusableBytes,
                audioCheckpointPresent = audioReady
            )
            if (!validatedEnvironment.canStart) {
                throw ExportRecoverableException(
                    validatedEnvironment.failureReason ?: "Not enough working storage to continue recovery export."
                )
            }

            recovery.segments
                .filter { it.index !in validVideoSegments }
                .forEach { segment ->
                    checkCancelled()
                    enforceThermalCheckpoint(listener, segment.index + 1, recovery.segmentCount, "video checkpoint")
                    val partial = session.partialSegmentFile(segment.index)
                    runCatching { partial.delete() }
                    renderVideoSegment(
                        project = project,
                        plan = plan,
                        selection = selection,
                        segment = segment,
                        outputFile = partial,
                        listener = listener,
                        recovery = recovery,
                        completedBefore = validVideoSegments
                    )
                    check(merger.isUsableVideo(partial)) {
                        "Video checkpoint ${segment.index + 1} did not finalize correctly"
                    }
                    recoveryStore.markCompleted(session, segment.index)
                    validVideoSegments += segment.index
                    postProgress(
                        listener,
                        videoProgressPercent(recovery, validVideoSegments),
                        "Video checkpoint ${segment.index + 1}/${recovery.segmentCount} saved"
                    )
                }

            checkCancelled()
            if (!audioReady) {
                recoveryStore.discardAudio(session)
                enforceThermalCheckpoint(listener, recovery.segmentCount + 1, recovery.segmentCount + 1, "audio checkpoint")
                val activeMixer = OfflineAudioMixer(appContext, project, plan) {
                    if (cancelRequested.get() || Thread.currentThread().isInterrupted) throw ExportAudioCancelledException()
                }
                audioMixer = activeMixer
                val prepareReport = activeMixer.prepare { index, total, label ->
                    val percent = if (total <= 0) 83 else (81 + index * 4 / total).coerceIn(82, 85)
                    postProgress(listener, percent, label)
                }
                if (prepareReport.unavailableSources > 0) {
                    postProgress(listener, 85, "Audio ready · ${prepareReport.unavailableSources} source(s) have no decodable sound")
                } else {
                    postProgress(listener, 85, "Audio mixer ready")
                }
                val partialAudio = session.partialAudioFile()
                runCatching { partialAudio.delete() }
                renderAudioCheckpoint(plan, activeMixer, partialAudio, listener)
                check(merger.isUsableAudio(partialAudio)) { "Audio checkpoint did not finalize correctly" }
                recoveryStore.markAudioCompleted(session)
                audioReady = true
                postProgress(listener, 90, "Audio checkpoint saved")
            } else {
                postProgress(listener, 90, "Reusing completed audio checkpoint")
            }
            check(audioReady) { "Audio checkpoint is unavailable" }

            checkCancelled()
            val videoFiles = recovery.segments.map { segment ->
                val file = session.finalSegmentFile(segment.index)
                check(merger.isUsableVideo(file)) { "Video checkpoint ${segment.index + 1} is missing or damaged" }
                Mp4SegmentMerger.SegmentFile(segment, file)
            }
            val finalReusableBytes = videoFiles.sumOf { it.file.length() } + session.finalAudioFile().length()
            val finalEnvironment = ExportEnvironmentProbe.inspectDestination(
                appContext,
                outputUri,
                plan,
                recovery,
                reusableCacheBytes = finalReusableBytes,
                audioCheckpointPresent = true
            )
            if (!finalEnvironment.canStart) {
                throw ExportRecoverableException(
                    finalEnvironment.failureReason ?: "Save-location preflight failed before final MP4 assembly."
                )
            }
            postProgress(listener, 91, "Assembling ${videoFiles.size} video checkpoint(s) + AAC")
            val pfd = openOutput(outputUri) ?: error("Unable to open export destination")
            outputPfd = pfd
            merger.merge(
                output = pfd,
                videoSegments = videoFiles,
                audioFile = session.finalAudioFile(),
                checkCancelled = ::checkCancelled
            ) { completed, total ->
                val percent = (91 + completed * 7 / total.coerceAtLeast(1)).coerceIn(91, 98)
                postProgress(listener, percent, "Assembling MP4 $completed/$total")
            }
            pfd.close()
            outputPfd = null

            success = true
            elapsedMs = System.currentTimeMillis() - startedAt
            recoveryStore.discard(session)
        } catch (_: ExportCancelledException) {
            cancelled = true
        } catch (_: ExportAudioCancelledException) {
            cancelled = true
        } catch (t: Throwable) {
            terminalError = t
        } finally {
            runCatching { audioMixer?.close() }
            runCatching { merger.close() }
            runCatching { outputPfd?.close() }
            if (!success) runCatching { appContext.contentResolver.delete(outputUri, null, null) }
            running = false
            worker = null

            when {
                success -> {
                    post { listener.onProgress(100, "Export complete") }
                    post { listener.onCompleted(outputUri, plan, elapsedMs) }
                }
                cancelled -> post { listener.onCancelled() }
                terminalError != null -> post {
                    listener.onError(humanReadableError(terminalError, session), terminalError)
                }
                else -> post { listener.onError("Export failed", null) }
            }
        }
    }

    private fun renderVideoSegment(
        project: Project,
        plan: ExportPlan,
        selection: VideoEncoderSelection,
        segment: ExportSegment,
        outputFile: File,
        listener: Listener,
        recovery: ExportRecoveryPlan,
        completedBefore: Set<Int>
    ) {
        var videoCodec: MediaCodec? = null
        var codecSurface: CodecInputSurface? = null
        var composer: SoftwareFrameComposer? = null
        var muxSink: Mp4MuxSink? = null
        var completed = false

        try {
            postProgress(
                listener,
                videoProgressPercent(recovery, completedBefore),
                "Rendering video ${segment.index + 1}/${recovery.segmentCount}"
            )
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val sink = Mp4MuxSink(muxer, setOf(Mp4MuxSink.Track.VIDEO))
            muxSink = sink

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
            val frameComposer = SoftwareFrameComposer(appContext, project, plan) { checkCancelled() }
            composer = frameComposer

            for (globalFrame in segment.startFrame until segment.endFrameExclusive) {
                checkCancelled()
                val localFrame = globalFrame - segment.startFrame
                val frame = frameComposer.composeHybrid(framePositionMs(globalFrame, plan))
                try {
                    val presentationNs = localFrame.toLong() * 1_000_000_000L / plan.frameRate.coerceAtLeast(1)
                    activeCodecSurface.draw(frame, presentationNs)
                } finally {
                    frame.close()
                }
                drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = false)

                if (localFrame == 0 && !sink.started) {
                    var spin = 0
                    while (!sink.started && spin++ < FORMAT_READY_SPINS) {
                        checkCancelled()
                        drainCodec(
                            activeVideoCodec,
                            Mp4MuxSink.Track.VIDEO,
                            sink,
                            endOfStream = false,
                            timeoutUs = CODEC_TIMEOUT_US
                        )
                    }
                    check(sink.started) { "Video encoder format did not become ready" }
                }

                if (globalFrame % PROGRESS_FRAME_INTERVAL == 0 || globalFrame == segment.endFrameExclusive - 1) {
                    val priorFrames = completedBefore.sumOf { index -> recovery.segments[index].frameCount }
                    val renderedFrames = priorFrames + localFrame + 1
                    val decode = frameComposer.performanceSnapshot().shortLabel()
                    postProgress(
                        listener,
                        videoProgressPercentByFrames(renderedFrames, plan.frameCount),
                        "Video ${segment.index + 1}/${recovery.segmentCount} · frame ${globalFrame + 1}/${plan.frameCount} · $decode"
                    )
                }
            }

            activeVideoCodec.signalEndOfInputStream()
            drainCodec(activeVideoCodec, Mp4MuxSink.Track.VIDEO, sink, endOfStream = true)
            sink.stop()
            muxSink = null
            completed = true
        } finally {
            runCatching { composer?.close() }
            runCatching { codecSurface?.close() }
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { muxSink?.stop() }
            if (!completed) runCatching { outputFile.delete() }
        }
    }

    private fun renderAudioCheckpoint(
        plan: ExportPlan,
        mixer: OfflineAudioMixer,
        outputFile: File,
        listener: Listener
    ) {
        var audioCodec: MediaCodec? = null
        var muxSink: Mp4MuxSink? = null
        var completed = false
        try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val sink = Mp4MuxSink(muxer, setOf(Mp4MuxSink.Track.AUDIO))
            muxSink = sink
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

            val totalSamples = (plan.durationUs * plan.audioSampleRate / 1_000_000L).coerceAtLeast(1L)
            var samplesQueued = 0L
            var lastProgress = -1
            while (samplesQueued < totalSamples) {
                checkCancelled()
                val target = min(totalSamples, samplesQueued + AUDIO_CHUNK_SAMPLES)
                samplesQueued = queueAudioUntil(
                    codec = activeAudioCodec,
                    plan = plan,
                    mixer = mixer,
                    sink = sink,
                    samplesQueuedInput = samplesQueued,
                    targetSamples = target
                )
                val percent = (85L + samplesQueued * 5L / totalSamples).toInt().coerceIn(85, 90)
                if (percent != lastProgress) {
                    lastProgress = percent
                    postProgress(listener, percent, "Encoding mixed AAC")
                }
            }
            queueAudioEos(activeAudioCodec, plan, samplesQueued)
            drainCodec(activeAudioCodec, Mp4MuxSink.Track.AUDIO, sink, endOfStream = true)
            check(sink.started) { "AAC encoder format did not become ready" }
            sink.stop()
            muxSink = null
            completed = true
        } finally {
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            runCatching { muxSink?.stop() }
            if (!completed) runCatching { outputFile.delete() }
        }
    }

    private fun openOutput(uri: Uri): android.os.ParcelFileDescriptor? {
        runCatching { appContext.contentResolver.openFileDescriptor(uri, "rwt") }.getOrNull()?.let { return it }
        val fallback = runCatching { appContext.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull() ?: return null
        return runCatching {
            // Some SAF providers do not implement `rwt`. Explicit truncation prevents stale tail
            // bytes if the selected document replaces an older file.
            Os.ftruncate(fallback.fileDescriptor, 0L)
            fallback
        }.getOrElse {
            runCatching { fallback.close() }
            null
        }
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

    private fun enforceThermalCheckpoint(
        listener: Listener,
        checkpointNumber: Int,
        checkpointTotal: Int,
        label: String
    ) {
        val thermal = ExportEnvironmentProbe.currentThermalStatus(appContext) ?: return
        when {
            thermal >= ExportEnvironmentProbe.THERMAL_CRITICAL -> {
                throw ExportRecoverableException(
                    "Device reached critical thermal pressure before $label $checkpointNumber/$checkpointTotal. Completed checkpoints were kept."
                )
            }
            thermal >= ExportEnvironmentProbe.THERMAL_SEVERE -> {
                val checkpointProgress = if (label.startsWith("audio")) {
                    82
                } else {
                    (5 + (checkpointNumber - 1).coerceAtLeast(0) * 76 / checkpointTotal.coerceAtLeast(1)).coerceIn(5, 81)
                }
                postProgress(listener, checkpointProgress, "Device is hot · cooling briefly before $label")
                repeat(THERMAL_BACKOFF_STEPS) {
                    checkCancelled()
                    try {
                        Thread.sleep(THERMAL_BACKOFF_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        checkCancelled()
                    }
                    val current = ExportEnvironmentProbe.currentThermalStatus(appContext) ?: return
                    if (current < ExportEnvironmentProbe.THERMAL_SEVERE) return
                    if (current >= ExportEnvironmentProbe.THERMAL_CRITICAL) {
                        throw ExportRecoverableException(
                            "Device became too hot. Completed checkpoints were kept; retry later to resume."
                        )
                    }
                }
            }
        }
    }

    private fun framePositionMs(globalFrame: Int, plan: ExportPlan): Int =
        ((globalFrame.toLong() * 1_000L) / plan.frameRate.coerceAtLeast(1))
            .coerceAtMost((plan.durationMs - 1).coerceAtLeast(0).toLong())
            .toInt()

    private fun videoProgressPercent(recovery: ExportRecoveryPlan, completed: Set<Int>): Int {
        val frames = completed.sumOf { index -> recovery.segments.getOrNull(index)?.frameCount ?: 0 }
        val total = recovery.segments.sumOf { it.frameCount }.coerceAtLeast(1)
        return videoProgressPercentByFrames(frames, total)
    }

    private fun videoProgressPercentByFrames(renderedFrames: Int, totalFrames: Int): Int =
        (5L + renderedFrames.toLong().coerceAtLeast(0L) * 76L / totalFrames.coerceAtLeast(1))
            .toInt().coerceIn(5, 81)

    private fun humanReadableError(t: Throwable?, session: ExportRecoveryStore.Session): String {
        val message = t?.message.orEmpty()
        val videoCheckpointCount = session.directory.listFiles { file ->
            file.name.startsWith("segment_") && file.extension == "mp4"
        }?.size ?: 0
        val audioReady = session.finalAudioFile().isFile
        val recoverySuffix = if (videoCheckpointCount > 0 || audioReady) {
            buildString {
                append(' ')
                append(videoCheckpointCount).append(" video checkpoint(s)")
                if (audioReady) append(" + audio checkpoint")
                append(" were kept; rerun the same export settings to resume.")
            }
        } else {
            ""
        }
        return when {
            t is ExportRecoverableException -> message.ifBlank { "Export paused safely." } + recoverySuffix
            message.contains("codec", ignoreCase = true) || message.contains("encoder", ignoreCase = true) ->
                "This device could not complete the selected encode profile. Try H.264, a lower resolution, 30 fps, or resume after the device cools.$recoverySuffix"
            message.contains("space", ignoreCase = true) || message.contains("storage", ignoreCase = true) ->
                "Not enough storage space to finish export.$recoverySuffix"
            message.contains("destination", ignoreCase = true) ->
                "Vedito lost access to the selected save location. Choose another destination.$recoverySuffix"
            message.isNotBlank() -> message + recoverySuffix
            else -> "Export failed before the MP4 could be finalized.$recoverySuffix"
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
    private class ExportRecoverableException(message: String) : RuntimeException(message)

    companion object {
        private const val AUDIO_CHANNELS = 2
        private const val PCM_BYTES_PER_SAMPLE = 2
        private const val AUDIO_CHUNK_SAMPLES = 2_048
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val FORMAT_READY_SPINS = 120
        private const val MAX_AUDIO_INPUT_STALLS = 800
        private const val MAX_EOS_IDLE_LOOPS = 800
        private const val PROGRESS_FRAME_INTERVAL = 8
        private const val THERMAL_BACKOFF_STEPS = 3
        private const val THERMAL_BACKOFF_MS = 2_000L
    }
}
