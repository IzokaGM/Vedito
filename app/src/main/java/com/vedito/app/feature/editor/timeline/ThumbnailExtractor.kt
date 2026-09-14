package com.vedito.app.feature.editor.timeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.MediaAsset
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
        assets: List<MediaAsset>,
        clips: List<Clip>,
        frameCount: Int = 12,
        callback: (List<Bitmap?>) -> Unit
    ) {
        val requestId = generation.incrementAndGet()
        val assetMap = assets.associateBy { it.id }
        val clipSnapshot = clips.toList()

        executor.execute {
            val frames = mutableListOf<Bitmap?>()
            var retriever: MediaMetadataRetriever? = null
            var loadedAssetId: String? = null

            try {
                val total = TimelineMath.totalDurationMs(clipSnapshot)
                val count = frameCount.coerceIn(6, 48)
                repeat(count) { index ->
                    val fraction = (index + 0.5f) / count
                    val timelineMs = (total * fraction).roundToInt().coerceIn(0, total)
                    val location = TimelineMath.locate(clipSnapshot, timelineMs)
                    val asset = location?.clip?.assetId?.let(assetMap::get)
                    if (location == null || asset == null) {
                        frames += null
                        return@repeat
                    }

                    if (loadedAssetId != asset.id) {
                        runCatching { retriever?.release() }
                        retriever = MediaMetadataRetriever().apply {
                            setDataSource(appContext, Uri.parse(asset.uri))
                        }
                        loadedAssetId = asset.id
                    }

                    val raw = retriever?.getFrameAtTime(
                        location.sourcePositionMs * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    frames += raw?.let(::scaleDown)
                }
            } catch (_: Exception) {
                val wanted = frameCount.coerceIn(6, 48)
                while (frames.size < wanted) frames += null
            } finally {
                runCatching { retriever?.release() }
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
