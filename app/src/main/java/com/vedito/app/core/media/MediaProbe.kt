package com.vedito.app.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class MediaProbe(context: Context) {
    data class Result(
        val uri: Uri,
        val durationMs: Int
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
                    Result(uri, duration).takeIf { it.durationMs > 0 }
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
