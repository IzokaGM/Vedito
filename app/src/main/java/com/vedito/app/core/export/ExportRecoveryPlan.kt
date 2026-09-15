package com.vedito.app.core.export

import com.vedito.app.core.model.Project
import kotlin.math.ceil
import kotlin.math.max

/**
 * Android-free segmented export/recovery planning for Patch 24.
 *
 * A long export is split on frame boundaries. Completed MP4 segments can survive a process/device
 * interruption and be reused when the same project revision + export profile is started again.
 */
data class ExportSegment(
    val index: Int,
    val startFrame: Int,
    val endFrameExclusive: Int,
    val startUs: Long,
    val endUs: Long,
    val startAudioSample: Long,
    val endAudioSampleExclusive: Long
) {
    val frameCount: Int get() = (endFrameExclusive - startFrame).coerceAtLeast(0)
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)
}

enum class ExportLoadClass {
    STANDARD,
    HEAVY,
    EXTREME
}

data class ExportRecoveryPlan(
    val sessionFingerprint: String,
    val loadClass: ExportLoadClass,
    val segmentSeconds: Int,
    val segments: List<ExportSegment>,
    val estimatedCheckpointBytes: Long,
    val audioWorkingBytes: Long,
    val safetyReserveBytes: Long,
    val requiredCacheBytes: Long,
    val minimumRuntimeHeadroomBytes: Long,
    val warnings: List<String>
) {
    val segmentCount: Int get() = segments.size

    fun additionalCacheBytes(reusableCheckpointBytes: Long, audioCheckpointPresent: Boolean): Long {
        val safeReusable = reusableCheckpointBytes.coerceIn(0L, estimatedCheckpointBytes)
        val avoidedAudioWorking = if (audioCheckpointPresent) audioWorkingBytes else 0L
        return (requiredCacheBytes - safeReusable - avoidedAudioWorking)
            .coerceAtLeast(safetyReserveBytes)
    }
}

object ExportRecoveryPlanner {
    fun plan(project: Project, exportPlan: ExportPlan, encoderKey: String = ""): ExportRecoveryPlan {
        val load = loadClass(exportPlan)
        val segmentSeconds = when (load) {
            ExportLoadClass.STANDARD -> 18
            ExportLoadClass.HEAVY -> 12
            ExportLoadClass.EXTREME -> 8
        }
        val segmentFrames = (segmentSeconds * exportPlan.frameRate).coerceAtLeast(1)
        val segments = buildSegments(exportPlan, segmentFrames)

        // Recovery keeps completed encoded chunks until final MP4 assembly. The audio mixer also
        // materializes stereo PCM in cache, so reserve one project-duration stereo PCM stream plus
        // container/metadata slack. This is intentionally conservative rather than optimistic.
        val encodedReserve = (exportPlan.estimatedOutputBytes * 1.16).toLong()
        val audibleRangesMs = AudioMixPlanner.build(project).segments
            .filter { !it.muted && it.volume > 0f && it.sourceEndMs > it.sourceStartMs }
            .groupBy { it.uri }
            .values
            .sumOf { sourceSegments ->
                val start = sourceSegments.minOf { it.sourceStartMs }
                val end = sourceSegments.maxOf { it.sourceEndMs }
                (end - start).coerceAtLeast(0).toLong()
            }
        val pcmReserve = audibleRangesMs
            .times(exportPlan.audioSampleRate.toLong())
            .div(1_000L)
            .times(4L) // stereo 16-bit
        val fixedReserve = when (load) {
            ExportLoadClass.STANDARD -> 48L * MIB
            ExportLoadClass.HEAVY -> 96L * MIB
            ExportLoadClass.EXTREME -> 160L * MIB
        }
        val runtimeHeadroom = when (load) {
            ExportLoadClass.STANDARD -> 96L * MIB
            ExportLoadClass.HEAVY -> 160L * MIB
            ExportLoadClass.EXTREME -> 256L * MIB
        }
        val warnings = buildList {
            if (segments.size > 1) {
                add("Export uses ${segments.size} recovery segments; completed segments can be reused after an interruption.")
            }
            if (load != ExportLoadClass.STANDARD) {
                add("This is a ${load.name.lowercase()} export profile; Vedito will checkpoint between segments and release decoder/GPU resources regularly.")
            }
            if (exportPlan.durationMs >= 30 * 60 * 1_000) {
                add("Project is 30+ minutes. Keep the device powered and leave extra local storage for recovery segments.")
            }
        }

        return ExportRecoveryPlan(
            sessionFingerprint = fingerprint(project, exportPlan, encoderKey),
            loadClass = load,
            segmentSeconds = segmentSeconds,
            segments = segments,
            estimatedCheckpointBytes = encodedReserve,
            audioWorkingBytes = pcmReserve,
            safetyReserveBytes = fixedReserve,
            requiredCacheBytes = (encodedReserve + pcmReserve + fixedReserve).coerceAtLeast(64L * MIB),
            minimumRuntimeHeadroomBytes = runtimeHeadroom,
            warnings = warnings
        )
    }

    fun fingerprint(project: Project, plan: ExportPlan, encoderKey: String = ""): String {
        // Hash only render-relevant state. Do not use updatedAt/playhead/selection because Vedito may
        // autosave those while the render graph itself is unchanged; recovery must survive that save.
        // Kotlin data-class/list string forms are deterministic for these ordered immutable models.
        val raw = buildString {
            // Recovery files are renderer output, not project state. Any future patch that can change
            // encoded pixels/timing must bump this salt so stale checkpoints are never reused.
            append(RENDER_FINGERPRINT_REVISION).append('|')
            append(project.id).append('|')
            append(project.assets).append('|')
            append(project.clips).append('|')
            append(project.audioAssets).append('|')
            append(project.audioClips).append('|')
            append(project.overlayAssets).append('|')
            append(project.overlayClips).append('|')
            append(project.textClips).append('|')
            append(project.captionSegments).append('|')
            append(project.effectClips).append('|')
            append(project.canvasSettings).append('|')
            append(plan.width).append('x').append(plan.height).append('|')
            append(plan.durationMs).append('|')
            append(plan.frameRate).append('|')
            append(plan.videoCodec.name).append('|')
            append(plan.videoBitrate).append('|')
            append(plan.audioSampleRate).append('|')
            append(plan.audioBitrate).append('|')
            append(encoderKey)
        }
        return fnv1a64(raw)
    }

    private fun buildSegments(plan: ExportPlan, segmentFrames: Int): List<ExportSegment> {
        if (plan.frameCount <= 0) return emptyList()
        val count = ceil(plan.frameCount.toDouble() / segmentFrames.toDouble()).toInt().coerceAtLeast(1)
        return List(count) { index ->
            val startFrame = index * segmentFrames
            val endFrame = minOf(plan.frameCount, startFrame + segmentFrames)
            val startUs = frameBoundaryUs(startFrame, plan.frameRate).coerceAtMost(plan.durationUs)
            val endUs = if (endFrame >= plan.frameCount) {
                plan.durationUs
            } else {
                frameBoundaryUs(endFrame, plan.frameRate).coerceAtMost(plan.durationUs)
            }.coerceAtLeast(startUs)
            ExportSegment(
                index = index,
                startFrame = startFrame,
                endFrameExclusive = endFrame,
                startUs = startUs,
                endUs = endUs,
                startAudioSample = usToSampleFloor(startUs, plan.audioSampleRate),
                endAudioSampleExclusive = usToSampleFloor(endUs, plan.audioSampleRate)
            )
        }
    }

    private fun loadClass(plan: ExportPlan): ExportLoadClass {
        val pixels = plan.width.toLong() * plan.height.toLong()
        val framePressure = pixels * plan.frameRate.toLong()
        return when {
            plan.durationMs >= 45 * 60 * 1_000 || framePressure >= 3840L * 2160L * 50L -> ExportLoadClass.EXTREME
            plan.durationMs >= 12 * 60 * 1_000 || framePressure >= 2560L * 1440L * 45L -> ExportLoadClass.HEAVY
            else -> ExportLoadClass.STANDARD
        }
    }

    private fun frameBoundaryUs(frame: Int, fps: Int): Long = frame.toLong() * 1_000_000L / max(1, fps)
    private fun usToSampleFloor(us: Long, sampleRate: Int): Long = us.coerceAtLeast(0L) * sampleRate.toLong() / 1_000_000L

    private fun fnv1a64(value: String): String {
        var hash = -0x340d631b7bdddcdbL // 1469598103934665603 as signed long
        value.encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }

    private const val RENDER_FINGERPRINT_REVISION = "vedito-render-p24-r1"
    private const val MIB = 1024L * 1024L
}
