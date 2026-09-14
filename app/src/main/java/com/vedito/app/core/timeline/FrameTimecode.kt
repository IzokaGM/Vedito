package com.vedito.app.core.timeline

import kotlin.math.roundToInt

object FrameTimecode {
    fun normalizeFrameRate(frameRate: Float): Float = when {
        !frameRate.isFinite() || frameRate < 1f -> 30f
        frameRate > 240f -> 240f
        else -> frameRate
    }

    fun frameDurationMs(frameRate: Float): Float = 1000f / normalizeFrameRate(frameRate)

    fun quantizeOffsetMs(offsetMs: Int, frameRate: Float): Int {
        val frameMs = frameDurationMs(frameRate)
        return ((offsetMs.coerceAtLeast(0) / frameMs).roundToInt() * frameMs).roundToInt()
    }

    fun format(positionMs: Int, frameRate: Float): String {
        val fps = normalizeFrameRate(frameRate)
        val nominal = fps.roundToInt().coerceAtLeast(1)
        val safeMs = positionMs.coerceAtLeast(0)
        val wholeSeconds = safeMs / 1000
        val hours = wholeSeconds / 3600
        val minutes = (wholeSeconds % 3600) / 60
        val seconds = wholeSeconds % 60
        val frames = (((safeMs % 1000) / 1000f) * fps).roundToInt().coerceIn(0, nominal - 1)
        return if (hours > 0) {
            "%02d:%02d:%02d:%02d".format(hours, minutes, seconds, frames)
        } else {
            "%02d:%02d:%02d".format(minutes, seconds, frames)
        }
    }
}
