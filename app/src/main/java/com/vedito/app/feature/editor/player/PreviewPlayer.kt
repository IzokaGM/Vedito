package com.vedito.app.feature.editor.player

import android.content.Context
import android.graphics.Matrix
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.max

class PreviewPlayer(
    private val context: Context,
    private val textureView: TextureView,
    private val listener: Listener
) : TextureView.SurfaceTextureListener {

    interface Listener {
        fun onReady(uri: Uri, durationMs: Int)
        fun onProgress(positionMs: Int, durationMs: Int, isPlaying: Boolean)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var mediaUri: Uri? = null
    private var durationMs: Int = 0
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var prepared = false
    private var pendingPlayAfterSeek = false
    private var pendingStartPositionMs = 0
    private var pendingAutoPlay = false
    private var loadGeneration = 0
    private var visualTransform = ClipTransform()

    val currentUri: Uri?
        get() = mediaUri

    val isReady: Boolean
        get() = prepared && player != null

    private val ticker = object : Runnable {
        override fun run() {
            val active = player
            if (active != null && prepared) {
                listener.onProgress(active.currentPosition.coerceAtLeast(0), durationMs, active.isPlaying)
            }
            mainHandler.postDelayed(this, 60L)
        }
    }

    init {
        textureView.isOpaque = false
        textureView.surfaceTextureListener = this
        mainHandler.post(ticker)
    }

    fun setVisualTransform(transform: ClipTransform) {
        visualTransform = VisualTransformMath.normalize(transform)
        textureView.alpha = visualTransform.opacity
        applyVideoTransform(textureView.width, textureView.height)
    }

    fun load(uri: Uri, startPositionMs: Int = 0, playWhenReady: Boolean = false) {
        pendingStartPositionMs = startPositionMs.coerceAtLeast(0)
        pendingAutoPlay = playWhenReady

        if (mediaUri == uri && isReady) {
            if (playWhenReady) playFrom(pendingStartPositionMs) else seekTo(pendingStartPositionMs)
            return
        }

        mediaUri = uri
        prepared = false
        if (textureView.isAvailable) prepare(uri)
    }

    fun isPlaying(): Boolean = player?.let { prepared && it.isPlaying } == true

    fun playFrom(positionMs: Int) {
        val active = player ?: return
        if (!prepared) return
        val target = positionMs.coerceIn(0, max(0, durationMs))
        pendingPlayAfterSeek = true
        active.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun pause() {
        pendingPlayAfterSeek = false
        pendingAutoPlay = false
        val active = player ?: return
        if (prepared && active.isPlaying) active.pause()
        listener.onPlaybackStateChanged(false)
    }

    fun seekTo(positionMs: Int) {
        pendingPlayAfterSeek = false
        val active = player ?: return
        if (!prepared) return
        val target = positionMs.coerceIn(0, max(0, durationMs))
        active.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun release() {
        loadGeneration++
        mainHandler.removeCallbacks(ticker)
        player?.release()
        player = null
        prepared = false
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        textureView.alpha = visualTransform.opacity
        applyVideoTransform(width, height)
        mediaUri?.let(::prepare)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        applyVideoTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
        player?.setSurface(null)
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit

    private fun prepare(uri: Uri) {
        val generation = ++loadGeneration
        player?.release()
        player = null
        prepared = false

        val surfaceTexture = textureView.surfaceTexture ?: return
        val surface = Surface(surfaceTexture)

        try {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setSurface(surface)
                setOnPreparedListener { ready ->
                    if (generation != loadGeneration || mediaUri != uri) return@setOnPreparedListener
                    prepared = true
                    durationMs = max(0, ready.duration)
                    this@PreviewPlayer.videoWidth = ready.videoWidth
                    this@PreviewPlayer.videoHeight = ready.videoHeight
                    textureView.alpha = visualTransform.opacity
                    applyVideoTransform(textureView.width, textureView.height)
                    listener.onReady(uri, durationMs)
                    listener.onPlaybackStateChanged(false)

                    val target = pendingStartPositionMs.coerceIn(0, durationMs)
                    if (pendingAutoPlay) {
                        pendingAutoPlay = false
                        playFrom(target)
                    } else {
                        seekTo(target)
                    }
                }
                setOnVideoSizeChangedListener { _, width, height ->
                    if (generation != loadGeneration) return@setOnVideoSizeChangedListener
                    this@PreviewPlayer.videoWidth = width
                    this@PreviewPlayer.videoHeight = height
                    applyVideoTransform(textureView.width, textureView.height)
                }
                setOnSeekCompleteListener { ready ->
                    if (generation != loadGeneration) return@setOnSeekCompleteListener
                    if (pendingPlayAfterSeek) {
                        pendingPlayAfterSeek = false
                        ready.start()
                        listener.onPlaybackStateChanged(true)
                    }
                }
                setOnCompletionListener {
                    if (generation != loadGeneration) return@setOnCompletionListener
                    pendingPlayAfterSeek = false
                    listener.onPlaybackStateChanged(false)
                    listener.onProgress(durationMs, durationMs, false)
                }
                setOnErrorListener { _, _, _ ->
                    if (generation != loadGeneration) return@setOnErrorListener true
                    pendingPlayAfterSeek = false
                    prepared = false
                    listener.onError("This video codec cannot be previewed on this device.")
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            prepared = false
            listener.onError("Unable to open this video.")
        } finally {
            surface.release()
        }
    }

    /**
     * Applies Vedito's renderer-independent ClipTransform to the TextureView preview.
     * The export compositor must consume the same normalized transform values later.
     */
    private fun applyVideoTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val transform = VisualTransformMath.normalize(visualTransform)
        val crop = VisualTransformMath.cropWindow(transform)
        val croppedWidth = (videoWidth * crop.widthFraction).coerceAtLeast(1f)
        val croppedHeight = (videoHeight * crop.heightFraction).coerceAtLeast(1f)
        val swapsAxes = transform.rotationDegrees.toInt() % 180 != 0
        val fittedWidth = if (swapsAxes) croppedHeight else croppedWidth
        val fittedHeight = if (swapsAxes) croppedWidth else croppedHeight
        val fitScale = when (transform.fitMode) {
            ClipFitMode.FIT -> minOf(viewWidth.toFloat() / fittedWidth, viewHeight.toFloat() / fittedHeight)
            ClipFitMode.FILL -> maxOf(viewWidth.toFloat() / fittedWidth, viewHeight.toFloat() / fittedHeight)
        }

        val userScale = transform.scale
        val renderedWidth = videoWidth * fitScale * userScale
        val renderedHeight = videoHeight * fitScale * userScale
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val signX = if (transform.flipHorizontal) -1f else 1f
        val signY = if (transform.flipVertical) -1f else 1f

        val cropOffsetX = crop.centerOffsetX * videoWidth * fitScale * userScale
        val cropOffsetY = crop.centerOffsetY * videoHeight * fitScale * userScale
        val userOffsetX = transform.positionX * viewWidth * 0.42f
        val userOffsetY = transform.positionY * viewHeight * 0.42f

        val matrix = Matrix().apply {
            setScale(
                signX * renderedWidth / viewWidth,
                signY * renderedHeight / viewHeight,
                centerX,
                centerY
            )
            postTranslate(-cropOffsetX + userOffsetX, -cropOffsetY + userOffsetY)
            postRotate(transform.rotationDegrees, centerX, centerY)
        }
        textureView.setTransform(matrix)
        textureView.alpha = transform.opacity
    }
}
