package com.vedito.app.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.vedito.app.core.export.ExportPlan
import com.vedito.app.core.export.FrameAccessMode
import com.vedito.app.core.export.FrameAccessPlanner
import java.io.Closeable

/**
 * Bounded frame-source pool for Patch 23.
 * Forward/freeze prefers streaming MediaCodec decode. Reverse prefers a bounded GOP-tail cache and
 * only falls back to MediaMetadataRetriever if a device cannot negotiate the codec/surface path.
 */
class VideoFrameSourcePool(
    private val context: Context,
    private val plan: ExportPlan,
    private val cancelCheck: () -> Unit = {}
) : Closeable {
    class FrameLease(
        val bitmap: Bitmap,
        val recycleAfterUse: Boolean,
        private val onClose: () -> Unit = {}
    ) : Closeable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            if (recycleAfterUse && !bitmap.isRecycled) bitmap.recycle()
            onClose()
        }
    }

    data class Snapshot(
        val streamingFrames: Long,
        val reverseCacheFrames: Long,
        val reverseCacheHits: Long,
        val fallbackFrames: Long,
        val streamFallbacks: Int,
        val reverseFallbacks: Int,
        val activeStreamingDecoders: Int,
        val activeReverseDecoders: Int
    ) {
        fun shortLabel(): String = when {
            reverseCacheFrames > 0L && fallbackFrames > 0L -> "reverse cache $reverseCacheHits hit · fallback $fallbackFrames"
            reverseCacheFrames > 0L -> "reverse cache $reverseCacheHits hit"
            streamingFrames > 0L && fallbackFrames > 0L -> "stream $streamingFrames · fallback $fallbackFrames"
            streamingFrames > 0L -> "stream decode"
            fallbackFrames > 0L -> "fallback decode"
            else -> "decode ready"
        }
    }

    private data class StreamEntry(
        val decoder: StreamingVideoFrameDecoder,
        var lastUseTick: Long,
        var pinCount: Int = 0
    )

    private data class ReverseEntry(
        val decoder: ReverseVideoFrameDecoder,
        var lastUseTick: Long
    )

    private data class RetrieverEntry(
        val retriever: MediaMetadataRetriever,
        var lastUseTick: Long
    )

    private val streaming = linkedMapOf<String, StreamEntry>()
    private val disabledStreaming = hashSetOf<String>()
    private val reverse = linkedMapOf<String, ReverseEntry>()
    private val disabledReverse = hashSetOf<String>()
    private val retrievers = linkedMapOf<String, RetrieverEntry>()
    private var tick = 0L
    private var streamingFrames = 0L
    private var reverseCacheFrames = 0L
    private var fallbackFrames = 0L
    private var streamFallbacks = 0
    private var reverseFallbacks = 0

    fun frame(
        key: String,
        uriString: String,
        sourcePositionMs: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        nominalFrameRate: Float,
        mode: FrameAccessMode
    ): FrameLease? {
        cancelCheck()

        if (mode == FrameAccessMode.RANDOM_ACCESS && key !in disabledReverse) {
            val reverseLease = runCatching {
                val entry = reverseEntry(
                    key = key,
                    uriString = uriString,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    nominalFrameRate = nominalFrameRate
                )
                entry.lastUseTick = ++tick
                val beforeHits = entry.decoder.stats().cacheHits
                val bitmap = entry.decoder.frameAt(sourcePositionMs)
                val afterHits = entry.decoder.stats().cacheHits
                if (afterHits > beforeHits) {
                    // Count actual cache-served reverse frame requests separately from total reverse frames.
                }
                bitmap?.let { FrameLease(it, recycleAfterUse = false) }
            }.getOrNull()
            if (reverseLease != null && !reverseLease.bitmap.isRecycled) {
                reverseCacheFrames++
                trimReversePool()
                return reverseLease
            }
            disableReverse(key)
        }

        if (mode != FrameAccessMode.RANDOM_ACCESS && key !in disabledStreaming) {
            val streamed = runCatching {
                val entry = streamEntry(
                    key = key,
                    uriString = uriString,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    nominalFrameRate = nominalFrameRate
                )
                entry.lastUseTick = ++tick
                val bitmap = entry.decoder.frameAt(sourcePositionMs)
                if (bitmap == null) null else {
                    entry.pinCount++
                    FrameLease(bitmap, recycleAfterUse = false) {
                        entry.pinCount = (entry.pinCount - 1).coerceAtLeast(0)
                        trimStreamingPool()
                    }
                }
            }.getOrNull()
            if (streamed != null && !streamed.bitmap.isRecycled) {
                streamingFrames++
                trimStreamingPool()
                return streamed
            }
            disableStreaming(key)
        }

        val fallback = fallbackFrame(key, uriString, sourcePositionMs, sourceWidth, sourceHeight)
        if (fallback != null) {
            fallbackFrames++
            return FrameLease(fallback, recycleAfterUse = true)
        }
        return null
    }

    fun snapshot(): Snapshot {
        val hits = reverse.values.sumOf { it.decoder.stats().cacheHits }
        return Snapshot(
            streamingFrames = streamingFrames,
            reverseCacheFrames = reverseCacheFrames,
            reverseCacheHits = hits,
            fallbackFrames = fallbackFrames,
            streamFallbacks = streamFallbacks,
            reverseFallbacks = reverseFallbacks,
            activeStreamingDecoders = streaming.size,
            activeReverseDecoders = reverse.size
        )
    }

    override fun close() {
        streaming.values.forEach { runCatching { it.decoder.close() } }
        reverse.values.forEach { runCatching { it.decoder.close() } }
        retrievers.values.forEach { runCatching { it.retriever.release() } }
        streaming.clear()
        reverse.clear()
        retrievers.clear()
        disabledStreaming.clear()
        disabledReverse.clear()
    }

    private fun streamEntry(
        key: String,
        uriString: String,
        sourceWidth: Int,
        sourceHeight: Int,
        nominalFrameRate: Float
    ): StreamEntry {
        streaming[key]?.let { return it }
        trimStreamingPool(forceOneSlot = true)
        val decodeCap = decodeCapForKey(key)
        val target = FrameAccessPlanner.decodeTarget(
            sourceWidth = sourceWidth.coerceAtLeast(plan.width),
            sourceHeight = sourceHeight.coerceAtLeast(plan.height),
            outputWidth = plan.width,
            outputHeight = plan.height,
            maxDimension = decodeCap
        )
        val entry = StreamEntry(
            decoder = StreamingVideoFrameDecoder(
                context = context,
                uriString = uriString,
                requestedWidth = target.first,
                requestedHeight = target.second,
                nominalFrameRate = nominalFrameRate,
                cancelCheck = cancelCheck
            ),
            lastUseTick = ++tick
        )
        streaming[key] = entry
        return entry
    }

    private fun reverseEntry(
        key: String,
        uriString: String,
        sourceWidth: Int,
        sourceHeight: Int,
        nominalFrameRate: Float
    ): ReverseEntry {
        reverse[key]?.let { return it }
        trimReversePool(forceOneSlot = true)
        val decodeCap = decodeCapForKey(key)
        val target = FrameAccessPlanner.decodeTarget(
            sourceWidth = sourceWidth.coerceAtLeast(plan.width),
            sourceHeight = sourceHeight.coerceAtLeast(plan.height),
            outputWidth = plan.width,
            outputHeight = plan.height,
            maxDimension = decodeCap
        )
        val entry = ReverseEntry(
            decoder = ReverseVideoFrameDecoder(
                context = context,
                uriString = uriString,
                requestedWidth = target.first,
                requestedHeight = target.second,
                nominalFrameRate = nominalFrameRate,
                cancelCheck = cancelCheck
            ),
            lastUseTick = ++tick
        )
        reverse[key] = entry
        return entry
    }

    private fun decodeCapForKey(key: String): Int =
        if (key.startsWith("main:") && maxOf(plan.width, plan.height) > 2_560) 3_840 else 2_560

    private fun trimStreamingPool(forceOneSlot: Boolean = false) {
        val limit = if (forceOneSlot) MAX_ACTIVE_STREAMS - 1 else MAX_ACTIVE_STREAMS
        while (streaming.size > limit.coerceAtLeast(0)) {
            val victim = streaming
                .filterValues { it.pinCount == 0 }
                .minByOrNull { it.value.lastUseTick }
                ?: break
            streaming.remove(victim.key)
            runCatching { victim.value.decoder.close() }
        }
    }

    private fun trimReversePool(forceOneSlot: Boolean = false) {
        val limit = if (forceOneSlot) MAX_ACTIVE_REVERSE_DECODERS - 1 else MAX_ACTIVE_REVERSE_DECODERS
        while (reverse.size > limit.coerceAtLeast(0)) {
            val victim = reverse.minByOrNull { it.value.lastUseTick } ?: break
            reverse.remove(victim.key)
            runCatching { victim.value.decoder.close() }
        }
    }

    private fun disableStreaming(key: String) {
        val removed = streaming.remove(key)
        if (removed != null && removed.pinCount == 0) runCatching { removed.decoder.close() }
        if (disabledStreaming.add(key)) streamFallbacks++
    }

    private fun disableReverse(key: String) {
        val removed = reverse.remove(key)
        if (removed != null) runCatching { removed.decoder.close() }
        if (disabledReverse.add(key)) reverseFallbacks++
    }

    private fun fallbackFrame(
        key: String,
        uriString: String,
        sourcePositionMs: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Bitmap? {
        cancelCheck()
        val entry = retrievers[key] ?: run {
            trimRetrieverPool(forceOneSlot = true)
            val created = runCatching {
                MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(uriString)) }
            }.getOrNull() ?: return null
            RetrieverEntry(created, ++tick).also { retrievers[key] = it }
        }
        entry.lastUseTick = ++tick
        val retriever = entry.retriever
        val timeUs = sourcePositionMs.coerceAtLeast(0).toLong() * 1_000L
        val frame = runCatching {
            if (Build.VERSION.SDK_INT >= 27 && sourceWidth > 0 && sourceHeight > 0) {
                val decodeCap = decodeCapForKey(key)
                val target = FrameAccessPlanner.decodeTarget(
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    outputWidth = plan.width,
                    outputHeight = plan.height,
                    maxDimension = decodeCap
                )
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    target.first,
                    target.second
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull()
        trimRetrieverPool()
        return frame
    }

    private fun trimRetrieverPool(forceOneSlot: Boolean = false) {
        val limit = if (forceOneSlot) MAX_ACTIVE_RETRIEVERS - 1 else MAX_ACTIVE_RETRIEVERS
        while (retrievers.size > limit.coerceAtLeast(0)) {
            val victim = retrievers.minByOrNull { it.value.lastUseTick } ?: break
            retrievers.remove(victim.key)
            runCatching { victim.value.retriever.release() }
        }
    }

    companion object {
        /** Main stream + up to two overlay streams without pinning an unbounded decoder count. */
        private const val MAX_ACTIVE_STREAMS = 3
        private const val MAX_ACTIVE_REVERSE_DECODERS = 1
        private const val MAX_ACTIVE_RETRIEVERS = 2
    }
}
