package com.vedito.app.core.export

import com.vedito.app.core.model.Project

/** Export capability report shown before SAF destination selection. */
data class ExportSupportReport(
    val canExport: Boolean,
    val warnings: List<String>
)

object ExportSupport {
    fun inspect(project: Project): ExportSupportReport {
        val warnings = buildList {
            if (project.clips.any { it.chromaKey.enabled }) {
                add("Chroma key uses the software fallback during export and can render more slowly.")
            }
            if (project.clips.any { it.mask.feather > 0f }) {
                add("Mask feather remains a software approximation in the current production fallback compositor.")
            }
            if (project.clips.any { it.timing.speed != 1f }) {
                add("Speed-changed source audio uses Vedito's lightweight pitch-preserving overlap mixer; very aggressive edits may sound less clean than a dedicated studio time-stretcher.")
            }
        }
        return ExportSupportReport(
            canExport = project.clips.isNotEmpty() && AudioMixPlanner.build(project).durationMs > 0,
            warnings = warnings
        )
    }
}
