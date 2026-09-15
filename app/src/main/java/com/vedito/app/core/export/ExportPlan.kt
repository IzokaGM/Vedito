package com.vedito.app.core.export

import com.vedito.app.core.model.Project
import com.vedito.app.core.timeline.TimelineIndex
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.roundToInt

enum class ExportPreset(
    val label: String,
    val shortEdgePx: Int,
    val baseAvcBitrate30: Int
) {
    HD_720("720p", 720, 5_000_000),
    FULL_HD_1080("1080p", 1_080, 10_000_000),
    QHD_1440("1440p / 2K", 1_440, 18_000_000),
    UHD_2160("2160p / 4K", 2_160, 35_000_000)
}

enum class ExportVideoCodec(
    val label: String,
    val mimeType: String,
    val bitrateEfficiency: Float
) {
    AVC("H.264 · maximum compatibility", "video/avc", 1f),
    HEVC("H.265 / HEVC · smaller file", "video/hevc", 0.68f)
}

data class ExportSettings(
    val preset: ExportPreset = ExportPreset.HD_720,
    val frameRate: Int = 30,
    val videoCodec: ExportVideoCodec = ExportVideoCodec.AVC,
    val audioSampleRate: Int = 48_000,
    val audioBitrate: Int = 128_000
)

data class ExportPlan(
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val frameRate: Int,
    val frameCount: Int,
    val videoCodec: ExportVideoCodec,
    val videoBitrate: Int,
    val audioSampleRate: Int,
    val audioBitrate: Int
) {
    val durationUs: Long get() = durationMs.toLong() * 1_000L
    val videoMimeType: String get() = videoCodec.mimeType

    /** Conservative container estimate; used for preflight UX, not quota enforcement. */
    val estimatedOutputBytes: Long
        get() {
            if (durationMs <= 0) return 0L
            val totalBitsPerSecond = videoBitrate.toLong() + audioBitrate.toLong() + CONTAINER_OVERHEAD_BPS
            return ((totalBitsPerSecond * durationMs.toLong()) / 8_000L).coerceAtLeast(0L)
        }

    companion object {
        private const val CONTAINER_OVERHEAD_BPS = 96_000L
    }
}

/** Android-free export sizing/timing/bitrate planner. */
object ExportPlanner {
    fun plan(project: Project, settings: ExportSettings): ExportPlan {
        val firstAsset = project.clips.firstOrNull()?.let { project.asset(it.assetId) }
            ?: project.assets.firstOrNull()
        val sourceWidth = firstAsset?.width?.coerceAtLeast(1) ?: 1920
        val sourceHeight = firstAsset?.height?.coerceAtLeast(1) ?: 1080
        val ratio = VisualTransformMath.canvasRatio(project.canvasSettings.aspect, sourceWidth, sourceHeight)
            .coerceIn(MIN_RATIO, MAX_RATIO)

        val shortEdge = settings.preset.shortEdgePx
        val (rawWidth, rawHeight) = if (ratio >= 1f) {
            (shortEdge * ratio).roundToInt() to shortEdge
        } else {
            shortEdge to (shortEdge / ratio).roundToInt()
        }
        val maxRawDimension = maxOf(rawWidth, rawHeight).coerceAtLeast(1)
        val downScale = minOf(1f, MAX_DIMENSION.toFloat() / maxRawDimension.toFloat())
        val width = even((rawWidth * downScale).roundToInt().coerceIn(MIN_DIMENSION, MAX_DIMENSION))
        val height = even((rawHeight * downScale).roundToInt().coerceIn(MIN_DIMENSION, MAX_DIMENSION))
        val duration = TimelineIndex(project.clips).totalDurationMs.coerceAtLeast(0)
        val fps = settings.frameRate.coerceIn(MIN_FPS, MAX_FPS)
        val frames = if (duration <= 0) 0 else ((duration.toLong() * fps + 999L) / 1_000L).toInt().coerceAtLeast(1)
        val fpsScale = when {
            fps <= 24 -> 0.86f
            fps <= 30 -> 1f
            else -> 1f + ((fps - 30).coerceAtMost(30) / 30f) * 0.50f
        }
        val videoBitrate = (settings.preset.baseAvcBitrate30 * fpsScale * settings.videoCodec.bitrateEfficiency)
            .roundToInt()
            .coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)

        return ExportPlan(
            width = width,
            height = height,
            durationMs = duration,
            frameRate = fps,
            frameCount = frames,
            videoCodec = settings.videoCodec,
            videoBitrate = videoBitrate,
            audioSampleRate = settings.audioSampleRate.coerceIn(44_100, 48_000),
            audioBitrate = settings.audioBitrate.coerceIn(96_000, 256_000)
        )
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value + 1

    private const val MIN_DIMENSION = 240
    private const val MAX_DIMENSION = 3_840
    private const val MIN_RATIO = 0.35f
    private const val MAX_RATIO = 2.85f
    private const val MIN_FPS = 24
    private const val MAX_FPS = 60
    private const val MIN_VIDEO_BITRATE = 2_000_000
    private const val MAX_VIDEO_BITRATE = 80_000_000
}
