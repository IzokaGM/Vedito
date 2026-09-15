package com.vedito.app.core.text

import com.vedito.app.core.model.TextAlignment
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.model.TextStyle
import com.vedito.app.core.model.TextTransform

object TextTimelineEditor {
    const val MIN_DURATION_MS = 250
    const val MAX_TEXT_LENGTH = 500

    fun normalizeTransform(transform: TextTransform): TextTransform {
        return transform.copy(
            scale = transform.scale.coerceIn(0.25f, 4f),
            positionX = transform.positionX.coerceIn(-1f, 1f),
            positionY = transform.positionY.coerceIn(-1f, 1f),
            rotationDegrees = normalizeDegrees(transform.rotationDegrees),
            opacity = transform.opacity.coerceIn(0f, 1f)
        )
    }

    fun normalizeStyle(style: TextStyle): TextStyle {
        return style.copy(
            fontSizeSp = style.fontSizeSp.coerceIn(12f, 96f),
            alignment = when (style.alignment) {
                TextAlignment.LEFT -> TextAlignment.LEFT
                TextAlignment.CENTER -> TextAlignment.CENTER
                TextAlignment.RIGHT -> TextAlignment.RIGHT
            }
        )
    }

    fun normalized(clip: TextClip, projectDurationMs: Int): TextClip? {
        if (projectDurationMs <= 0) return null
        val text = clip.text.trim().take(MAX_TEXT_LENGTH)
        if (text.isBlank()) return null
        val start = clip.timelineStartMs.coerceIn(0, projectDurationMs)
        val available = (projectDurationMs - start).coerceAtLeast(0)
        if (available < MIN_DURATION_MS) return null
        val duration = clip.durationMs.coerceIn(MIN_DURATION_MS, available)
        return clip.copy(
            text = text,
            timelineStartMs = start,
            durationMs = duration,
            zIndex = clip.zIndex.coerceAtLeast(0),
            style = normalizeStyle(clip.style),
            transform = normalizeTransform(clip.transform)
        )
    }

    fun move(clip: TextClip, timelineStartMs: Int, projectDurationMs: Int): TextClip {
        val maxStart = (projectDurationMs - clip.durationMs).coerceAtLeast(0)
        return clip.copy(timelineStartMs = timelineStartMs.coerceIn(0, maxStart))
    }

    fun trimLeft(clip: TextClip, requestedStartMs: Int, projectDurationMs: Int): TextClip {
        val oldEnd = clip.timelineEndMs.coerceAtMost(projectDurationMs)
        val latestStart = (oldEnd - MIN_DURATION_MS).coerceAtLeast(0)
        val start = requestedStartMs.coerceIn(0, latestStart)
        return clip.copy(
            timelineStartMs = start,
            durationMs = (oldEnd - start).coerceAtLeast(MIN_DURATION_MS)
        )
    }

    fun trimRight(clip: TextClip, requestedEndMs: Int, projectDurationMs: Int): TextClip {
        val minEnd = clip.timelineStartMs + MIN_DURATION_MS
        val end = requestedEndMs.coerceIn(minEnd, projectDurationMs)
        return clip.copy(durationMs = (end - clip.timelineStartMs).coerceAtLeast(MIN_DURATION_MS))
    }

    fun raise(clips: List<TextClip>, id: String): List<TextClip> {
        val ordered = clips.sortedWith(compareBy<TextClip> { it.zIndex }.thenBy { it.id })
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0 || index == ordered.lastIndex) return clips
        val current = ordered[index]
        val other = ordered[index + 1]
        return clips.map { clip ->
            when (clip.id) {
                current.id -> clip.copy(zIndex = other.zIndex)
                other.id -> clip.copy(zIndex = current.zIndex)
                else -> clip
            }
        }
    }

    fun lower(clips: List<TextClip>, id: String): List<TextClip> {
        val ordered = clips.sortedWith(compareBy<TextClip> { it.zIndex }.thenBy { it.id })
        val index = ordered.indexOfFirst { it.id == id }
        if (index <= 0) return clips
        val current = ordered[index]
        val other = ordered[index - 1]
        return clips.map { clip ->
            when (clip.id) {
                current.id -> clip.copy(zIndex = other.zIndex)
                other.id -> clip.copy(zIndex = current.zIndex)
                else -> clip
            }
        }
    }

    private fun normalizeDegrees(value: Float): Float {
        var degrees = value % 360f
        if (degrees > 180f) degrees -= 360f
        if (degrees < -180f) degrees += 360f
        return degrees
    }
}
