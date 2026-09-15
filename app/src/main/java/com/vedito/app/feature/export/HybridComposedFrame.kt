package com.vedito.app.feature.export

import android.graphics.Bitmap
import com.vedito.app.core.export.GpuPostProcessPlan

/**
 * Reusable export frame packet. Bitmaps are owned by SoftwareFrameComposer and must not be recycled.
 * GPU post-processing is applied to the base plane before overlayPlane is alpha-composited.
 */
data class HybridComposedFrame(
    val basePlane: Bitmap,
    val overlayPlane: Bitmap,
    val gpuPostProcess: GpuPostProcessPlan
)
