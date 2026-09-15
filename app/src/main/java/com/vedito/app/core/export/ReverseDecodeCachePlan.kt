package com.vedito.app.core.export

import kotlin.math.ceil

/** Android-free memory/timing policy for Patch 23 reverse GOP decode cache. */
data class ReverseDecodeCachePlan(
    val capacityFrames: Int,
    val frameToleranceUs: Long,
    val maxWindowUs: Long,
    val memoryBudgetBytes: Long
)

object ReverseDecodeCachePlanner {
    const val DEFAULT_MEMORY_BUDGET_BYTES = 48L * 1024L * 1024L
    const val MIN_FRAMES = 1
    const val MAX_FRAMES = 24

    fun plan(
        width: Int,
        height: Int,
        nominalFrameRate: Float,
        memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET_BYTES
    ): ReverseDecodeCachePlan {
        val safeW = width.coerceAtLeast(2)
        val safeH = height.coerceAtLeast(2)
        val fps = nominalFrameRate.takeIf { it.isFinite() && it > 1f } ?: 30f
        val bytesPerFrame = safeW.toLong() * safeH.toLong() * 4L
        val budget = memoryBudgetBytes.coerceAtLeast(bytesPerFrame)
        val capacity = (budget / bytesPerFrame).toInt().coerceIn(MIN_FRAMES, MAX_FRAMES)
        val frameDurationUs = (1_000_000f / fps).toLong().coerceAtLeast(1L)
        val tolerance = (frameDurationUs * 0.58f).toLong().coerceIn(8_000L, 45_000L)
        val windowFrames = capacity.coerceAtLeast(1)
        return ReverseDecodeCachePlan(
            capacityFrames = capacity,
            frameToleranceUs = tolerance,
            maxWindowUs = frameDurationUs * (windowFrames + 2L),
            memoryBudgetBytes = budget
        )
    }

    fun containsTarget(cacheStartUs: Long, cacheEndUs: Long, targetUs: Long, toleranceUs: Long): Boolean {
        if (cacheStartUs < 0L || cacheEndUs < cacheStartUs) return false
        return targetUs >= cacheStartUs - toleranceUs && targetUs <= cacheEndUs + toleranceUs
    }

    fun estimatedFramesForWindow(windowUs: Long, nominalFrameRate: Float): Int {
        val fps = nominalFrameRate.takeIf { it.isFinite() && it > 1f } ?: 30f
        return ceil(windowUs.coerceAtLeast(0L) * fps / 1_000_000.0).toInt().coerceAtLeast(1)
    }
}
