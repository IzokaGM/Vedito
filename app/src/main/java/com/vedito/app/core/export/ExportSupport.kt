package com.vedito.app.core.export

import com.vedito.app.core.model.Project

data class ExportSupportReport(
    val canExport: Boolean,
    val warnings: List<String>
)

/**
 * Central capability report for the current exporter. Keeping this explicit prevents
 * preview-only behavior from silently disappearing during export.
 */
object ExportSupport {
    fun inspect(project: Project): ExportSupportReport {
        val warnings = buildList {
            add("Patch 19 writes a valid AAC track, but audible source/audio-track mixing is not enabled yet.")
            if (project.audioClips.isNotEmpty()) {
                add("Imported/extracted audio clips remain in the project and will be added to the export mixer in the next audio-render milestone.")
            }
            if (project.clips.any { it.chromaKey.enabled }) {
                add("Chroma key is rendered by the CPU fallback and may export slowly.")
            }
            if (project.clips.any { it.mask.feather > 0f }) {
                add("Mask feather is approximated by the software compositor in this foundation exporter.")
            }
        }
        return ExportSupportReport(
            canExport = project.clips.isNotEmpty() && TimelineDuration.durationMs(project) > 0,
            warnings = warnings
        )
    }
}

private object TimelineDuration {
    fun durationMs(project: Project): Int = project.clips.sumOf { it.durationMs }
}
