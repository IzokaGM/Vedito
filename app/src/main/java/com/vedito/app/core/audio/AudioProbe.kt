package com.vedito.app.core.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class AudioProbe(private val context: Context) {
    data class Result(val uri: Uri, val durationMs: Int)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun probe(uris: List<Uri>, callback: (List<Result>) -> Unit) {
        if (uris.isEmpty()) {
            callback(emptyList())
            return
        }
        executor.execute {
            val results = uris.mapNotNull { uri ->
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?.coerceIn(1L, Int.MAX_VALUE.toLong())
                            ?.toInt()
                            ?: 0
                        if (duration > 0) Result(uri, duration) else null
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
            main.post { callback(results) }
        }
    }

    fun release() {
        executor.shutdownNow()
    }
}
