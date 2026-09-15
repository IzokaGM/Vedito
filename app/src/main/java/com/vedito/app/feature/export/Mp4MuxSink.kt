package com.vedito.app.feature.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/** Small muxer gate that can finalize video-only, audio-only or A/V checkpoint files. */
internal class Mp4MuxSink(
    private val muxer: MediaMuxer,
    private val requiredTracks: Set<Track> = setOf(Track.VIDEO, Track.AUDIO)
) {
    enum class Track { VIDEO, AUDIO }

    private data class PendingSample(
        val track: Track,
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    private var videoTrack = -1
    private var audioTrack = -1
    private val pending = mutableListOf<PendingSample>()
    private val lastPtsUs = mutableMapOf(Track.VIDEO to -1L, Track.AUDIO to -1L)
    var started: Boolean = false
        private set
    private var released = false

    init {
        require(requiredTracks.isNotEmpty()) { "At least one muxer track is required" }
    }

    fun onFormat(track: Track, format: MediaFormat) {
        if (released || track !in requiredTracks) return
        when (track) {
            Track.VIDEO -> if (videoTrack < 0) videoTrack = muxer.addTrack(format)
            Track.AUDIO -> if (audioTrack < 0) audioTrack = muxer.addTrack(format)
        }
        maybeStart()
    }

    fun write(track: Track, source: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (released || track !in requiredTracks || info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val pts = normalizedPts(track, info.presentationTimeUs)
        if (!started) {
            check(pending.size < MAX_PENDING_SAMPLES) { "Encoder produced too many samples before muxer start" }
            val duplicate = source.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            }
            val bytes = ByteArray(info.size)
            duplicate.get(bytes)
            pending += PendingSample(track, bytes, pts, info.flags)
            return
        }
        writeNow(track, source, info.offset, info.size, pts, info.flags)
    }

    fun stop() {
        if (released) return
        var failure: Throwable? = null
        if (started) {
            try { muxer.stop() } catch (t: Throwable) { failure = t }
            started = false
        }
        try { muxer.release() } catch (t: Throwable) { if (failure == null) failure = t }
        released = true
        pending.clear()
        failure?.let { throw it }
    }

    private fun maybeStart() {
        if (released || started) return
        val ready = requiredTracks.all { track ->
            when (track) {
                Track.VIDEO -> videoTrack >= 0
                Track.AUDIO -> audioTrack >= 0
            }
        }
        if (!ready) return
        muxer.start()
        started = true
        pending.forEach { sample ->
            val info = MediaCodec.BufferInfo().apply {
                set(0, sample.bytes.size, sample.presentationTimeUs, sample.flags)
            }
            muxer.writeSampleData(indexFor(sample.track), ByteBuffer.wrap(sample.bytes), info)
        }
        pending.clear()
    }

    private fun writeNow(track: Track, source: ByteBuffer, offset: Int, size: Int, pts: Long, flags: Int) {
        val duplicate = source.duplicate().apply {
            position(offset)
            limit(offset + size)
        }
        val normalized = MediaCodec.BufferInfo().apply { set(0, size, pts, flags) }
        muxer.writeSampleData(indexFor(track), duplicate.slice(), normalized)
    }

    private fun normalizedPts(track: Track, ptsInput: Long): Long {
        val last = lastPtsUs.getValue(track)
        val safe = ptsInput.coerceAtLeast(0L)
        val normalized = if (safe <= last) last + 1L else safe
        lastPtsUs[track] = normalized
        return normalized
    }

    private fun indexFor(track: Track): Int = when (track) {
        Track.VIDEO -> videoTrack
        Track.AUDIO -> audioTrack
    }.also { check(it >= 0) { "Muxer track is not ready: $track" } }

    companion object {
        private const val MAX_PENDING_SAMPLES = 64
    }
}
