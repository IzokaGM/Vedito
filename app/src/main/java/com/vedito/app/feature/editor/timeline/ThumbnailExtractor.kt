package com.vedito.app.feature.editor.timeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.vedito.app.core.model.Clip
import com.vedito.app.core.timeline.TimelineMath
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class ThumbnailExtractor(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val targetHeight = (68f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(68)

    fun request(
        uri: Uri,
        clips: List<Clip>,
        frameCount: Int = 10,
        callback: (List<Bitmap?>) -> Unit
    ) {
        val requestId = generation.incrementAndGet()
        val clipSnapshot = clips.toList()
        executor.execute {
            val retriever = MediaMetadataRetriever()
            val frames = mutableListOf<Bitmap?>()
            try {
                retriever.setDataSource(appContext, uri)
                val total = TimelineMath.totalDurationMs(clipSnapshot)
                val count = frameCount.coerceIn(4, 14)
                repeat(count) { index ->
                    val fraction = (index + 0.5f) / count
                    val timelineMs = (total * fraction).roundToInt().coerceIn(0, total)
                    val location = TimelineMath.locate(clipSnapshot, timelineMs)
                    val sourceMs = location?.sourcePositionMs ?: 0
                    val raw = retriever.getFrameAtTime(
                        sourceMs * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    frames += raw?.let(::scaleDown)
                }
            } catch (_: Exception) {
                while (frames.size < frameCount.coerceIn(4, 14)) frames += null
            } finally {
                runCatching { retriever.release() }
            }

            if (requestId != generation.get()) {
                frames.filterNotNull().forEach { if (!it.isRecycled) it.recycle() }
                return@execute
            }
            mainHandler.post {
                if (requestId == generation.get()) {
                    callback(frames)
                } else {
                    frames.filterNotNull().forEach { if (!it.isRecycled) it.recycle() }
                }
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        if (bitmap.height <= targetHeight) return bitmap
        val scale = targetHeight.toFloat() / bitmap.height
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return scaled
    }
}
