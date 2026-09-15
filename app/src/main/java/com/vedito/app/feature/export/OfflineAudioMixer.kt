package com.vedito.app.feature.export

import android.content.Context
import com.vedito.app.core.export.AudioMixMath
import com.vedito.app.core.export.AudioMixPlan
import com.vedito.app.core.export.AudioMixPlanner
import com.vedito.app.core.export.AudioMixSegment
import com.vedito.app.core.export.AudioMixSegmentKind
import com.vedito.app.core.export.ExportPlan
import com.vedito.app.core.model.Project
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Deterministic offline stereo mixer used by MP4 export.
 * - source-video audio follows clip trim + forward speed timing
 * - speed-changed source audio uses lightweight overlap-add so pitch stays materially steadier
 * - independent audio clips honor timeline position, volume, mute and fades
 * - all layers are mixed before AAC encoding
 */
internal class OfflineAudioMixer(
    private val context: Context,
    project: Project,
    private val exportPlan: ExportPlan,
    private val checkCancelled: () -> Unit
) : Closeable {
    data class PrepareReport(
        val decodedSources: Int,
        val unavailableSources: Int,
        val warnings: List<String>
    )

    private val mixPlan: AudioMixPlan = AudioMixPlanner.build(project)
    private val tempRoot = File(context.cacheDir, "vedito_export_audio_${UUID.randomUUID()}").apply { mkdirs() }
    private val decoded = linkedMapOf<String, DecodedPcmAsset?>()
    private val ownedAssets = mutableListOf<DecodedPcmAsset>()
    private var prepared = false

    fun prepare(onSource: (index: Int, total: Int, label: String) -> Unit = { _, _, _ -> }): PrepareReport {
        if (prepared) {
            val unavailable = decoded.values.count { it == null }
            return PrepareReport(decoded.size - unavailable, unavailable, emptyList())
        }
        val uris = mixPlan.audibleUris
        val ranges = mixPlan.segments
            .filter { !it.muted && it.volume > 0f && it.sourceDurationMs > 0 }
            .groupBy { it.uri }
            .mapValues { (_, segments) ->
                (segments.minOf { it.sourceStartMs }) to (segments.maxOf { it.sourceEndMs })
            }
        val warnings = mutableListOf<String>()
        val decoder = PcmMediaDecoder(context, exportPlan.audioSampleRate, tempRoot, checkCancelled)
        uris.forEachIndexed { index, uri ->
            checkCancelled()
            onSource(index + 1, uris.size, "Decoding audio ${index + 1}/${uris.size}")
            val range = ranges[uri] ?: (0 to Int.MAX_VALUE)
            val result = decoder.decode(uri, index, range.first, range.second)
            decoded[uri] = result.asset
            result.asset?.let(ownedAssets::add)
            result.warning?.let(warnings::add)
        }
        prepared = true
        val unavailable = decoded.values.count { it == null }
        return PrepareReport(decoded.size - unavailable, unavailable, warnings.distinct())
    }

    fun mix(startSample: Long, requestedSamples: Int): ShortArray {
        check(prepared) { "OfflineAudioMixer.prepare() must run first" }
        checkCancelled()
        val totalSamples = exportPlan.durationUs * exportPlan.audioSampleRate / 1_000_000L
        val count = min(requestedSamples.toLong(), (totalSamples - startSample).coerceAtLeast(0L)).toInt()
        if (count <= 0) return ShortArray(0)
        val mixed = FloatArray(count * 2)
        val chunkStartMs = startSample * 1_000f / exportPlan.audioSampleRate
        val chunkEndMs = (startSample + count) * 1_000f / exportPlan.audioSampleRate

        mixPlan.segments.forEach { segment ->
            if (segment.muted || segment.volume <= 0f) return@forEach
            if (segment.timelineEndMs <= chunkStartMs || segment.timelineStartMs >= chunkEndMs) return@forEach
            val asset = decoded[segment.uri] ?: return@forEach
            mixSegment(segment, asset, startSample, count, mixed)
        }

        val output = ShortArray(count * 2)
        for (i in output.indices) {
            val value = mixed[i]
            val limited = if (abs(value) <= 1f) value else value / abs(value)
            output[i] = (limited * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    override fun close() {
        ownedAssets.forEach { runCatching { it.close() } }
        ownedAssets.clear()
        decoded.clear()
        runCatching { tempRoot.deleteRecursively() }
    }

    private fun mixSegment(
        segment: AudioMixSegment,
        asset: DecodedPcmAsset,
        globalStartSample: Long,
        chunkSamples: Int,
        mixed: FloatArray
    ) {
        val sampleRate = exportPlan.audioSampleRate
        val segmentStartSample = msToSampleCeil(segment.timelineStartMs, sampleRate)
        val segmentEndSample = msToSampleCeil(segment.timelineEndMs, sampleRate)
        val overlapStart = max(globalStartSample, segmentStartSample)
        val overlapEnd = min(globalStartSample + chunkSamples, segmentEndSample)
        if (overlapEnd <= overlapStart) return

        val localOutStart = (overlapStart - segmentStartSample).coerceAtLeast(0L)
        val localOutEnd = (overlapEnd - segmentStartSample).coerceAtLeast(localOutStart)
        val sourceBase = msToSampleFloor(segment.sourceStartMs, sampleRate)
        val sourceLimit = msToSampleCeil(segment.sourceEndMs, sampleRate).coerceAtMost(asset.absoluteEndFrame)

        val range = when {
            segment.kind == AudioMixSegmentKind.SOURCE_VIDEO && abs(segment.speed - 1f) > 0.001f -> {
                sourceRangeForOla(sourceBase, sourceLimit, localOutStart, localOutEnd, segment.speed)
            }
            else -> {
                val start = sourceBase + floor(localOutStart.toDouble() * segment.speed).toLong() - 2L
                val end = sourceBase + ceil(localOutEnd.toDouble() * segment.speed).toLong() + 3L
                start.coerceAtLeast(0L) to end.coerceAtMost(sourceLimit)
            }
        }
        if (range.second <= range.first) return
        val frames = (range.second - range.first).coerceAtMost(MAX_READ_FRAMES.toLong()).toInt()
        if (frames <= 0) return
        val pcm = asset.readFrames(range.first, frames)
        if (pcm.size < 2) return
        val availableFrames = pcm.size / 2

        var outputSample = overlapStart
        while (outputSample < overlapEnd) {
            checkCancelled()
            val chunkIndex = (outputSample - globalStartSample).toInt()
            val localOut = outputSample - segmentStartSample
            val timelineMs = outputSample * 1_000f / sampleRate
            val gain = AudioMixMath.gainAt(segment, timelineMs)
            if (gain > 0f) {
                val pair = if (segment.kind == AudioMixSegmentKind.SOURCE_VIDEO && abs(segment.speed - 1f) > 0.001f) {
                    sampleOlaStereo(
                        pcm = pcm,
                        availableFrames = availableFrames,
                        readStartFrame = range.first,
                        sourceBaseFrame = sourceBase,
                        sourceLimitFrame = sourceLimit,
                        localOutputFrame = localOut,
                        speed = segment.speed
                    )
                } else {
                    val sourceFrame = sourceBase.toDouble() + localOut.toDouble() * segment.speed
                    sampleLinearStereo(pcm, availableFrames, range.first, sourceFrame)
                }
                val out = chunkIndex * 2
                mixed[out] += pair.first * gain
                mixed[out + 1] += pair.second * gain
            }
            outputSample++
        }
    }

    /** Two-grain Hann overlap-add. At speed=1 this collapses to the original source position. */
    private fun sampleOlaStereo(
        pcm: ShortArray,
        availableFrames: Int,
        readStartFrame: Long,
        sourceBaseFrame: Long,
        sourceLimitFrame: Long,
        localOutputFrame: Long,
        speed: Float
    ): Pair<Float, Float> {
        val hop = OLA_HOP_FRAMES.toLong()
        val grain = OLA_GRAIN_FRAMES.toLong()
        val currentGrain = floor(localOutputFrame.toDouble() / hop).toLong()
        var left = 0f
        var right = 0f
        var weightSum = 0f
        for (grainIndex in (currentGrain - 1)..currentGrain) {
            if (grainIndex < 0L) continue
            val outputGrainStart = grainIndex * hop
            val phase = localOutputFrame - outputGrainStart
            if (phase < 0L || phase >= grain) continue
            val sourceGrainStart = sourceBaseFrame + (grainIndex.toDouble() * hop.toDouble() * speed).roundToLong()
            val sourceFrame = (sourceGrainStart + phase).coerceIn(sourceBaseFrame, (sourceLimitFrame - 1).coerceAtLeast(sourceBaseFrame))
            val weight = hann(phase.toFloat() / (grain - 1L).coerceAtLeast(1L))
            val sample = sampleLinearStereo(pcm, availableFrames, readStartFrame, sourceFrame.toDouble())
            left += sample.first * weight
            right += sample.second * weight
            weightSum += weight
        }
        return if (weightSum > 0.0001f) left / weightSum to right / weightSum else 0f to 0f
    }

    private fun sourceRangeForOla(
        sourceBase: Long,
        sourceLimit: Long,
        localStart: Long,
        localEnd: Long,
        speed: Float
    ): Pair<Long, Long> {
        val hop = OLA_HOP_FRAMES.toLong()
        val firstGrain = (floor(localStart.toDouble() / hop).toLong() - 2L).coerceAtLeast(0L)
        val lastGrain = (floor(localEnd.toDouble() / hop).toLong() + 2L).coerceAtLeast(firstGrain)
        val start = sourceBase + (firstGrain.toDouble() * hop.toDouble() * speed).roundToLong() - 2L
        val end = sourceBase + (lastGrain.toDouble() * hop.toDouble() * speed).roundToLong() + OLA_GRAIN_FRAMES + 3L
        return start.coerceAtLeast(0L) to end.coerceAtMost(sourceLimit)
    }

    private fun sampleLinearStereo(
        pcm: ShortArray,
        availableFrames: Int,
        readStartFrame: Long,
        sourceFrame: Double
    ): Pair<Float, Float> {
        if (availableFrames <= 0) return 0f to 0f
        val local = sourceFrame - readStartFrame
        if (local < 0.0 || local > availableFrames - 1.0) return 0f to 0f
        val i0 = floor(local).toInt().coerceIn(0, availableFrames - 1)
        val i1 = (i0 + 1).coerceAtMost(availableFrames - 1)
        val f = (local - i0).toFloat().coerceIn(0f, 1f)
        val l0 = pcm[i0 * 2] / 32768f
        val r0 = pcm[i0 * 2 + 1] / 32768f
        val l1 = pcm[i1 * 2] / 32768f
        val r1 = pcm[i1 * 2 + 1] / 32768f
        return (l0 + (l1 - l0) * f) to (r0 + (r1 - r0) * f)
    }

    private fun hann(normalized: Float): Float =
        (0.5 - 0.5 * cos(2.0 * PI * normalized.coerceIn(0f, 1f))).toFloat()

    private fun msToSampleFloor(ms: Int, sampleRate: Int): Long = ms.toLong() * sampleRate / 1_000L
    private fun msToSampleCeil(ms: Int, sampleRate: Int): Long = (ms.toLong() * sampleRate + 999L) / 1_000L

    companion object {
        // 20 ms grains / 10 ms hop at 48 kHz; scaled values are kept fixed in frame domain because
        // export currently constrains the audio sample rate to 44.1/48 kHz.
        private const val OLA_GRAIN_FRAMES = 960
        private const val OLA_HOP_FRAMES = 480
        private const val MAX_READ_FRAMES = 65_536
    }
}
