package com.vedito.app.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.vedito.app.core.model.MediaAsset
import java.util.concurrent.Executors

class MediaProbe(context: Context) {
    data class Result(
        val uri: Uri,
        val durationMs: Int,
        val frameRate: Float,
        val width: Int,
        val height: Int
    )

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun probe(uris: List<Uri>, callback: (List<Result>) -> Unit) {
        if (uris.isEmpty()) {
            callback(emptyList())
            return
        }
        executor.execute {
            val results = uris.mapNotNull { uri ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                        ?.toInt()
                        ?: 0
                    val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toFloatOrNull()
                        ?.takeIf { it.isFinite() && it >= 1f && it <= 240f }
                        ?: MediaAsset.DEFAULT_FRAME_RATE
                    val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull()
                        ?.coerceAtLeast(0)
                        ?: 0
                    val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull()
                        ?.coerceAtLeast(0)
                        ?: 0
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull()
                        ?: 0
                    val swap = rotation % 180 != 0
                    val displayWidth = if (swap) encodedHeight else encodedWidth
                    val displayHeight = if (swap) encodedWidth else encodedHeight
                    Result(uri, duration, fps, displayWidth, displayHeight).takeIf { it.durationMs > 0 }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
            mainHandler.post { callback(results) }
        }
    }

    fun release() {
        executor.shutdownNow()
    }
}
