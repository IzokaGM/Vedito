package com.vedito.app.feature.export

import android.graphics.Bitmap
import com.vedito.app.core.export.GpuPostProcessPlan
import com.vedito.app.core.export.GpuSourceGraphPlan
import java.io.Closeable

/**
 * Reusable export frame packet.
 *
 * Patch 23 can hand the raw main-source bitmap directly to the encoder GLES source graph while the
 * CPU-owned base plane remains as a correctness fallback. The retained source lease pins streaming
 * decoder ownership until the encoder surface has consumed the bitmap.
 */
data class HybridComposedFrame(
    val basePlane: Bitmap,
    val overlayPlane: Bitmap,
    val gpuPostProcess: GpuPostProcessPlan,
    val sourcePlane: Bitmap? = null,
    val sourceGraph: GpuSourceGraphPlan = GpuSourceGraphPlan.disabled(),
    private val sourceLease: Closeable? = null
) : Closeable {
    val usesGpuSourceGraph: Boolean
        get() = sourcePlane != null && sourceGraph.enabled

    override fun close() {
        sourceLease?.close()
    }
}
