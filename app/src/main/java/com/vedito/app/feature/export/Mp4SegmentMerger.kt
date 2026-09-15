package com.vedito.app.feature.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import com.vedito.app.core.export.ExportSegment
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless final assembly for Patch 24.
 * Video is checkpointed in independently finalized MP4 chunks; audio is encoded once as one full
 * AAC track so checkpoint boundaries never introduce repeated AAC encoder priming/padding.
 */
internal class Mp4SegmentMerger : Closeable {
    data class SegmentFile(val segment: ExportSegment, val file: File)

    private var cancelled: (() -> Unit)? = null

    fun merge(
        output: ParcelFileDescriptor,
        videoSegments: List<SegmentFile>,
        audioFile: File,
        checkCancelled: () -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        require(videoSegments.isNotEmpty()) { "No recovery video segments to assemble" }
        val videoFormat = readFirstTrackFormat(videoSegments.first().file, VIDEO_MIME_PREFIX)
            ?: error("Recovery segment has no video track")
        val audioFormat = readFirstTrackFormat(audioFile, AUDIO_MIME_PREFIX)
            ?: error("Recovery audio checkpoint has no audio track")

        cancelled = checkCancelled
        val muxer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            var lastVideoPts = -1L
            videoSegments.forEachIndexed { index, item ->
                checkCancelled()
                lastVideoPts = copyTrack(
                    file = item.file,
                    mimePrefix = VIDEO_MIME_PREFIX,
                    muxer = muxer,
                    targetTrack = videoTrack,
                    ptsOffsetUs = item.segment.startUs,
                    lastPtsInput = lastVideoPts
                )
                onProgress(index + 1, videoSegments.size + 1)
            }

            checkCancelled()
            copyTrack(
                file = audioFile,
                mimePrefix = AUDIO_MIME_PREFIX,
                muxer = muxer,
                targetTrack = audioTrack,
                ptsOffsetUs = 0L,
                lastPtsInput = -1L
            )
            onProgress(videoSegments.size + 1, videoSegments.size + 1)
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            cancelled = null
        }
    }

    fun isUsableVideo(file: File): Boolean = isUsableTrack(file, VIDEO_MIME_PREFIX)
    fun isUsableAudio(file: File): Boolean = isUsableTrack(file, AUDIO_MIME_PREFIX)

    override fun close() {
        cancelled = null
    }

    private fun isUsableTrack(file: File, mimePrefix: String): Boolean = runCatching {
        if (!file.isFile || file.length() < 1_024L) return@runCatching false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = findTrack(extractor, mimePrefix) ?: return@runCatching false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val hintedMax = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(DEFAULT_BUFFER_BYTES)
            val buffer = ByteBuffer.allocateDirect(
                hintedMax.coerceAtLeast(DEFAULT_VALIDATION_BUFFER_BYTES).coerceAtMost(MAX_BUFFER_BYTES)
            )
            extractor.readSampleData(buffer, 0) >= 0
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrDefault(false)

    private fun copyTrack(
        file: File,
        mimePrefix: String,
        muxer: MediaMuxer,
        targetTrack: Int,
        ptsOffsetUs: Long,
        lastPtsInput: Long
    ): Long {
        val extractor = MediaExtractor()
        var lastPts = lastPtsInput
        try {
            extractor.setDataSource(file.absolutePath)
            val track = findTrack(extractor, mimePrefix)
                ?: error("Checkpoint file missing $mimePrefix track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val hintedMax = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(DEFAULT_BUFFER_BYTES)
            var buffer = ByteBuffer.allocateDirect(
                hintedMax.coerceAtLeast(DEFAULT_BUFFER_BYTES).coerceAtMost(MAX_BUFFER_BYTES)
            )
            var localBasePts = -1L
            val info = MediaCodec.BufferInfo()
            while (true) {
                cancelled?.invoke()
                buffer.clear()
                var read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                if (read > buffer.capacity()) {
                    check(read <= MAX_BUFFER_BYTES) { "Encoded sample is too large to assemble safely" }
                    buffer = ByteBuffer.allocateDirect(read + 64 * 1024)
                    buffer.clear()
                    read = extractor.readSampleData(buffer, 0)
                    check(read in 0..buffer.capacity()) { "Could not reread encoded sample safely" }
                }
                val sampleTime = extractor.sampleTime.coerceAtLeast(0L)
                if (localBasePts < 0L) localBasePts = sampleTime
                val rawPts = ptsOffsetUs + (sampleTime - localBasePts).coerceAtLeast(0L)
                val pts = if (rawPts <= lastPts) lastPts + 1L else rawPts
                lastPts = pts
                info.set(0, read, pts, extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(read)
                muxer.writeSampleData(targetTrack, buffer, info)
                if (!extractor.advance()) break
            }
            return lastPts
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun readFirstTrackFormat(file: File, mimePrefix: String): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            findTrack(extractor, mimePrefix)?.let(extractor::getTrackFormat)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
        }

    companion object {
        private const val VIDEO_MIME_PREFIX = "video/"
        private const val AUDIO_MIME_PREFIX = "audio/"
        private const val DEFAULT_VALIDATION_BUFFER_BYTES = 8 * 1024 * 1024
        private const val DEFAULT_BUFFER_BYTES = 8 * 1024 * 1024
        private const val MAX_BUFFER_BYTES = 32 * 1024 * 1024
    }
}
