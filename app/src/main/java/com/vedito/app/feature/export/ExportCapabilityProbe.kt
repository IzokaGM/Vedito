package com.vedito.app.feature.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.vedito.app.core.export.ExportPlan

/** Concrete encoder selected by the Patch 22 device preflight. */
data class VideoEncoderSelection(
    val codecName: String,
    val hardwareAccelerated: Boolean,
    val effectiveBitrate: Int,
    val widthAlignment: Int,
    val heightAlignment: Int
)

data class DeviceExportPreflight(
    val canEncode: Boolean,
    val selection: VideoEncoderSelection?,
    val warnings: List<String>,
    val failureReason: String? = null
)

/**
 * Device-aware MediaCodec preflight.
 * The export engine re-runs the same selector immediately before encoding, so the UI result is advisory
 * rather than a stale source of truth.
 */
object ExportCapabilityProbe {
    fun inspect(plan: ExportPlan): DeviceExportPreflight {
        val all = candidates(plan)
        val supported = all.filter { it.sizeSupported && it.rateSupported && it.surfaceInput }
        if (supported.isEmpty()) {
            val sizeCapable = all.any { it.sizeSupported && it.surfaceInput }
            val reason = when {
                all.isEmpty() -> "No ${plan.videoCodec.label.substringBefore('·').trim()} encoder is available on this device."
                !sizeCapable -> "This device encoder does not support ${plan.width}×${plan.height} surface export."
                else -> "This device encoder cannot sustain ${plan.width}×${plan.height} at ${plan.frameRate} fps."
            }
            return DeviceExportPreflight(false, null, emptyList(), reason)
        }

        val chosen = supported.maxByOrNull { candidateScore(it, plan.videoBitrate) } ?: supported.first()
        val effective = plan.videoBitrate.coerceIn(chosen.minBitrate, chosen.maxBitrate)
        val selection = VideoEncoderSelection(
            codecName = chosen.name,
            hardwareAccelerated = chosen.hardware,
            effectiveBitrate = effective,
            widthAlignment = chosen.widthAlignment,
            heightAlignment = chosen.heightAlignment
        )
        val warnings = buildList {
            if (!chosen.hardware) {
                add("Only a software ${plan.videoCodec.label.substringBefore('·').trim()} encoder matched this profile; export may be slow and battery-heavy.")
            }
            if (effective != plan.videoBitrate) {
                add("Video bitrate was adjusted from ${mbps(plan.videoBitrate)} to ${mbps(effective)} Mbps to stay inside this encoder's advertised range.")
            }
            if (plan.width >= 3_000 || plan.height >= 3_000) {
                add("High-resolution encoding passed codec preflight, but thermal throttling or memory pressure can still affect long exports.")
            }
        }
        return DeviceExportPreflight(true, selection, warnings)
    }

    fun select(plan: ExportPlan): VideoEncoderSelection? = inspect(plan).selection

    private fun candidates(plan: ExportPlan): List<Candidate> {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrElse { emptyList() }
        return infos.asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(plan.videoMimeType, ignoreCase = true) } }
            .mapNotNull { info ->
                val caps = runCatching { info.getCapabilitiesForType(plan.videoMimeType) }.getOrNull() ?: return@mapNotNull null
                val video = caps.videoCapabilities ?: return@mapNotNull null
                val surfaceInput = caps.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }
                val sizeSupported = runCatching { video.isSizeSupported(plan.width, plan.height) }.getOrDefault(false)
                val rateSupported = sizeSupported && runCatching {
                    video.areSizeAndRateSupported(plan.width, plan.height, plan.frameRate.toDouble())
                }.getOrDefault(false)
                val bitrateRange = video.bitrateRange
                Candidate(
                    name = info.name,
                    hardware = isHardwareAccelerated(info),
                    surfaceInput = surfaceInput,
                    sizeSupported = sizeSupported,
                    rateSupported = rateSupported,
                    minBitrate = bitrateRange.lower.coerceAtLeast(1),
                    maxBitrate = bitrateRange.upper.coerceAtLeast(bitrateRange.lower),
                    widthAlignment = video.widthAlignment.coerceAtLeast(1),
                    heightAlignment = video.heightAlignment.coerceAtLeast(1)
                )
            }
            .toList()
    }

    private fun candidateScore(candidate: Candidate, requestedBitrate: Int): Int {
        var score = if (candidate.hardware) 1_000 else 0
        if (requestedBitrate in candidate.minBitrate..candidate.maxBitrate) score += 100
        score += (candidate.maxBitrate / 1_000_000).coerceAtMost(90)
        return score
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains("software"))
    }

    private fun mbps(value: Int): String {
        val mbps = value / 1_000_000f
        return if (mbps >= 10f) "%.0f".format(mbps) else "%.1f".format(mbps)
    }

    private data class Candidate(
        val name: String,
        val hardware: Boolean,
        val surfaceInput: Boolean,
        val sizeSupported: Boolean,
        val rateSupported: Boolean,
        val minBitrate: Int,
        val maxBitrate: Int,
        val widthAlignment: Int,
        val heightAlignment: Int
    )
}
