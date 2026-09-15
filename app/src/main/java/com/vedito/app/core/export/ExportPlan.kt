package com.vedito.app.core.export

import com.vedito.app.core.model.CanvasAspect
import com.vedito.app.core.model.Project
import com.vedito.app.core.timeline.TimelineIndex
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.roundToInt

enum class ExportPreset(
    val label: String,
    val shortEdgePx: Int,
    val videoBitrate: Int
) {
    HD_720("720p · 30 fps", 720, 5_000_000),
    FULL_HD_1080("1080p · 30 fps", 1080, 10_000_000)
}

data class ExportSettings(
    val preset: ExportPreset = ExportPreset.HD_720,
    val frameRate: Int = 30,
    val audioSampleRate: Int = 48_000,
    val audioBitrate: Int = 128_000
)

data class ExportPlan(
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val frameRate: Int,
    val frameCount: Int,
    val videoBitrate: Int,
    val audioSampleRate: Int,
    val audioBitrate: Int
) {
    val durationUs: Long get() = durationMs.toLong() * 1_000L
}

/** Android-free export sizing/timing planner. */
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
        val width = even(rawWidth.coerceIn(MIN_DIMENSION, MAX_DIMENSION))
        val height = even(rawHeight.coerceIn(MIN_DIMENSION, MAX_DIMENSION))
        val duration = TimelineIndex(project.clips).totalDurationMs.coerceAtLeast(0)
        val fps = settings.frameRate.coerceIn(24, 60)
        val frames = if (duration <= 0) 0 else ((duration.toLong() * fps + 999L) / 1_000L).toInt().coerceAtLeast(1)

        return ExportPlan(
            width = width,
            height = height,
            durationMs = duration,
            frameRate = fps,
            frameCount = frames,
            videoBitrate = settings.preset.videoBitrate,
            audioSampleRate = settings.audioSampleRate.coerceIn(44_100, 48_000),
            audioBitrate = settings.audioBitrate.coerceIn(96_000, 256_000)
        )
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value + 1

    private const val MIN_DIMENSION = 240
    private const val MAX_DIMENSION = 3_840
    private const val MIN_RATIO = 0.35f
    private const val MAX_RATIO = 2.85f
}
