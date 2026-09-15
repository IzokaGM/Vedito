package com.vedito.app.core.export

import com.vedito.app.core.model.Project

/** Export capability report shown before SAF destination selection. */
data class ExportSupportReport(
    val canExport: Boolean,
    val warnings: List<String>
)

object ExportSupport {
    fun inspect(project: Project, settings: ExportSettings = ExportSettings()): ExportSupportReport {
        val plan = ExportPlanner.plan(project, settings)
        val warnings = buildList {
            if (project.clips.any { it.chromaKey.enabled }) {
                add("Chroma key can use the software fallback during export and may render more slowly.")
            }
            if (project.clips.any { it.mask.feather > 0f }) {
                add("Mask feather remains a software approximation in the current production fallback compositor.")
            }
            if (project.clips.any { it.timing.speed != 1f }) {
                add("Speed-changed source audio uses Vedito's lightweight pitch-preserving overlap mixer; very aggressive edits may sound less clean than a dedicated studio time-stretcher.")
            }
            if (settings.preset == ExportPreset.UHD_2160) {
                add("4K export is device-dependent and can use substantially more memory, battery and render time.")
            } else if (settings.preset == ExportPreset.QHD_1440) {
                add("2K export can be significantly heavier than 1080p on mid-range devices.")
            }
            if (plan.frameRate >= 60) {
                add("60 fps roughly doubles frame processing pressure versus 30 fps; preflight will verify encoder size/rate support.")
            }
            if (settings.videoCodec == ExportVideoCodec.HEVC) {
                add("HEVC usually produces a smaller file, but playback/editing compatibility is lower on older apps and devices than H.264.")
            }
        }
        return ExportSupportReport(
            canExport = project.clips.isNotEmpty() && AudioMixPlanner.build(project).durationMs > 0,
            warnings = warnings
        )
    }
}
