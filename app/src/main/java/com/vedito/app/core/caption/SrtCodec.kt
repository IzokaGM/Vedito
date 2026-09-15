package com.vedito.app.core.caption

import com.vedito.app.core.model.CaptionPreset
import com.vedito.app.core.model.CaptionSegment
import java.util.UUID

object SrtCodec {
    private val timePattern = Regex("""(\d{1,3}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{3})""")

    fun parse(raw: String): List<CaptionSegment> {
        val normalized = raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return emptyList()

        val blocks = normalized.split(Regex("\\n{2,}"))
        return buildList {
            blocks.forEach { block ->
                val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
                if (lines.isEmpty()) return@forEach
                val timingIndex = lines.indexOfFirst { timePattern.matches(it.trim()) }
                if (timingIndex < 0) return@forEach
                val match = timePattern.matchEntire(lines[timingIndex].trim()) ?: return@forEach
                val start = toMillis(match.groupValues, 1)
                val end = toMillis(match.groupValues, 5)
                if (end <= start) return@forEach
                val text = lines.drop(timingIndex + 1).joinToString("\n").trim().take(CaptionTimelineEditor.MAX_TEXT_LENGTH)
                if (text.isBlank()) return@forEach
                add(
                    CaptionSegment(
                        id = UUID.randomUUID().toString(),
                        text = text,
                        timelineStartMs = start,
                        durationMs = end - start,
                        preset = CaptionPreset.BOXED
                    )
                )
            }
        }.sortedBy { it.timelineStartMs }
    }

    fun serialize(segments: List<CaptionSegment>): String = buildString {
        segments.sortedBy { it.timelineStartMs }.forEachIndexed { index, segment ->
            append(index + 1).append('\n')
            append(formatTime(segment.timelineStartMs))
                .append(" --> ")
                .append(formatTime(segment.timelineEndMs))
                .append('\n')
            append(segment.text.trim()).append("\n\n")
        }
    }

    private fun toMillis(values: List<String>, offset: Int): Int {
        val hours = values[offset].toIntOrNull() ?: 0
        val minutes = values[offset + 1].toIntOrNull() ?: 0
        val seconds = values[offset + 2].toIntOrNull() ?: 0
        val millis = values[offset + 3].toIntOrNull() ?: 0
        return (((hours * 60 + minutes) * 60 + seconds) * 1000 + millis).coerceAtLeast(0)
    }

    private fun formatTime(ms: Int): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe / 60_000) % 60
        val seconds = (safe / 1_000) % 60
        val millis = safe % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
