package com.vedito.app.feature.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.system.Os
import com.vedito.app.core.export.ExportPlan
import com.vedito.app.core.export.ExportRecoveryPlan
import java.io.File

/** Runtime/storage/thermal guardrails for long segmented exports. */
object ExportEnvironmentProbe {
    data class Report(
        val canStart: Boolean,
        val cacheFreeBytes: Long,
        val destinationFreeBytes: Long?,
        val reusableCacheBytes: Long,
        val requiredAdditionalCacheBytes: Long,
        val runtimeHeadroomBytes: Long,
        val thermalStatus: Int?,
        val warnings: List<String>,
        val failureReason: String?
    )

    fun inspectCache(
        context: Context,
        recovery: ExportRecoveryPlan,
        reusableCacheBytes: Long = 0L,
        audioCheckpointPresent: Boolean = false
    ): Report {
        val cacheFree = availableBytes(context.cacheDir)
        val safeReusable = reusableCacheBytes.coerceIn(0L, recovery.estimatedCheckpointBytes)
        val requiredAdditional = recovery.additionalCacheBytes(safeReusable, audioCheckpointPresent)
        val headroom = runtimeHeadroomBytes()
        val thermal = currentThermalStatus(context)
        val failure = when {
            cacheFree < requiredAdditional ->
                "Not enough local working storage for recovery export. Need about ${formatBytes(requiredAdditional)} more free space, free ${formatBytes(cacheFree)}."
            thermal != null && thermal >= THERMAL_CRITICAL ->
                "Device is too hot to begin a long export. Let it cool, then try again."
            else -> null
        }
        val warnings = buildList {
            if (headroom < recovery.minimumRuntimeHeadroomBytes) {
                add("Memory headroom is low (${formatBytes(headroom)}). Vedito will release decoder/GPU resources at every segment boundary.")
            }
            if (thermal != null && thermal >= THERMAL_SEVERE && thermal < THERMAL_CRITICAL) {
                add("Device is already hot. Export may pause or checkpoint early if thermal pressure rises.")
            }
        }
        return Report(
            canStart = failure == null,
            cacheFreeBytes = cacheFree,
            destinationFreeBytes = null,
            reusableCacheBytes = safeReusable,
            requiredAdditionalCacheBytes = requiredAdditional,
            runtimeHeadroomBytes = headroom,
            thermalStatus = thermal,
            warnings = warnings,
            failureReason = failure
        )
    }

    fun inspectDestination(
        context: Context,
        outputUri: Uri,
        plan: ExportPlan,
        recovery: ExportRecoveryPlan,
        reusableCacheBytes: Long = 0L,
        audioCheckpointPresent: Boolean = false
    ): Report {
        val base = inspectCache(context, recovery, reusableCacheBytes, audioCheckpointPresent)
        if (!base.canStart) return base
        val destinationFree = destinationFreeBytes(context, outputUri)
        val requiredDestination = (plan.estimatedOutputBytes * 1.08).toLong() + 8L * MIB
        val failure = when {
            destinationFree != null && destinationFree < requiredDestination ->
                "Not enough space at the selected save location. Need about ${formatBytes(requiredDestination)}, free ${formatBytes(destinationFree)}."
            else -> null
        }
        return base.copy(
            canStart = failure == null,
            destinationFreeBytes = destinationFree,
            warnings = buildList {
                addAll(base.warnings)
                if (destinationFree == null) add("Save-location free space could not be measured; export will still detect write failures safely.")
            },
            failureReason = failure
        )
    }

    fun currentThermalStatus(context: Context): Int? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        }.getOrNull()
    }

    fun runtimeHeadroomBytes(): Long {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return (runtime.maxMemory() - used).coerceAtLeast(0L)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mib = bytes / (1024f * 1024f)
        return if (mib >= 1024f) String.format("%.2f GB", mib / 1024f) else String.format("%.0f MB", mib)
    }

    private fun availableBytes(file: File): Long = runCatching { StatFs(file.absolutePath).availableBytes }.getOrDefault(0L)

    private fun destinationFreeBytes(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val stat = Os.fstatvfs(pfd.fileDescriptor)
            stat.f_bavail * stat.f_frsize
        }
    }.getOrNull()

    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4
    private const val MIB = 1024L * 1024L
}
