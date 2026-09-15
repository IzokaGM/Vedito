package com.vedito.app.feature.editor.player

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import com.vedito.app.core.color.ColorGradeEngine
import com.vedito.app.core.model.ChromaKeySpec
import com.vedito.app.core.model.ColorGradeSpec
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.visual.MaskChromaComposition
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.max
import kotlin.math.roundToInt

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

    private enum class VirtualMode { NONE, REVERSE, FREEZE }

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
    private var chromaKey = ChromaKeySpec()
    private var colorGrade = ColorGradeSpec()

    private var timingMode = ClipPlaybackMode.FORWARD
    private var playbackSpeed = 1f
    private var segmentStartMs = 0
    private var segmentEndMs = 0
    private var freezeDurationMs = 0
    private var startTimelineOffsetMs = 0

    private var virtualMode = VirtualMode.NONE
    private var virtualStartedAt = 0L
    private var virtualStartSourceMs = 0
    private var virtualLastSeekAt = 0L
    private var virtualPlaying = false

    val currentUri: Uri?
        get() = mediaUri

    val isReady: Boolean
        get() = prepared && player != null

    val virtualTimelineElapsedMs: Int
        get() = if (!virtualPlaying || virtualMode != VirtualMode.FREEZE) startTimelineOffsetMs else {
            (startTimelineOffsetMs + (SystemClock.uptimeMillis() - virtualStartedAt).toInt()).coerceAtMost(freezeDurationMs)
        }

    private val ticker = object : Runnable {
        override fun run() {
            val active = player
            if (active != null && prepared) {
                when (virtualMode) {
                    VirtualMode.NONE -> listener.onProgress(active.currentPosition.coerceAtLeast(0), durationMs, active.isPlaying)
                    VirtualMode.REVERSE -> tickReverse(active)
                    VirtualMode.FREEZE -> tickFreeze(active)
                }
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

    fun setChromaKey(spec: ChromaKeySpec) {
        val safe = MaskChromaComposition.normalize(spec)
        if (safe == chromaKey) return
        chromaKey = safe
        applyRenderPipeline()
    }

    fun setColorGrade(spec: ColorGradeSpec) {
        val safe = ColorGradeEngine.normalize(spec)
        if (safe == colorGrade) return
        colorGrade = safe
        applyRenderPipeline()
    }

    fun configureTiming(
        mode: ClipPlaybackMode,
        speed: Float,
        sourceStartMs: Int,
        sourceEndMs: Int,
        freezeDurationMs: Int = 0,
        startTimelineOffsetMs: Int = 0
    ) {
        timingMode = mode
        playbackSpeed = speed.coerceIn(0.5f, 2f)
        segmentStartMs = sourceStartMs.coerceAtLeast(0)
        segmentEndMs = sourceEndMs.coerceAtLeast(segmentStartMs)
        this.freezeDurationMs = freezeDurationMs.coerceAtLeast(0)
        this.startTimelineOffsetMs = startTimelineOffsetMs.coerceAtLeast(0)
    }

    fun load(uri: Uri, startPositionMs: Int = 0, playWhenReady: Boolean = false) {
        pendingStartPositionMs = startPositionMs.coerceAtLeast(0)
        pendingAutoPlay = playWhenReady
        stopVirtual(notify = false)

        if (mediaUri == uri && isReady) {
            if (playWhenReady) playFrom(pendingStartPositionMs) else seekTo(pendingStartPositionMs)
            return
        }

        mediaUri = uri
        prepared = false
        if (textureView.isAvailable) prepare(uri)
    }

    fun isPlaying(): Boolean = virtualPlaying || player?.let { prepared && it.isPlaying } == true

    fun playFrom(positionMs: Int) {
        val active = player ?: return
        if (!prepared) return
        val target = positionMs.coerceIn(0, max(0, durationMs))
        when (timingMode) {
            ClipPlaybackMode.FORWARD -> startForward(active, target)
            ClipPlaybackMode.REVERSE -> startReverse(active, target)
            ClipPlaybackMode.FREEZE -> startFreeze(active, target)
        }
    }

    fun pause() {
        pendingPlayAfterSeek = false
        pendingAutoPlay = false
        stopVirtual(notify = false)
        val active = player
        if (active != null && prepared && active.isPlaying) active.pause()
        listener.onPlaybackStateChanged(false)
    }

    fun seekTo(positionMs: Int) {
        pendingPlayAfterSeek = false
        stopVirtual(notify = false)
        val active = player ?: return
        if (!prepared) return
        val target = positionMs.coerceIn(0, max(0, durationMs))
        runCatching { active.setVolume(1f, 1f) }
        active.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun release() {
        loadGeneration++
        stopVirtual(notify = false)
        mainHandler.removeCallbacks(ticker)
        player?.release()
        player = null
        prepared = false
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        textureView.alpha = visualTransform.opacity
        applyVideoTransform(width, height)
        applyChromaKeyPreview()
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
        stopVirtual(notify = false)

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
                    applyChromaKeyPreview()
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
                        applyForwardPlaybackParams(ready)
                        ready.start()
                        listener.onPlaybackStateChanged(true)
                    }
                }
                setOnCompletionListener {
                    if (generation != loadGeneration) return@setOnCompletionListener
                    pendingPlayAfterSeek = false
                    stopVirtual(notify = false)
                    listener.onPlaybackStateChanged(false)
                    listener.onProgress(durationMs, durationMs, false)
                }
                setOnErrorListener { _, _, _ ->
                    if (generation != loadGeneration) return@setOnErrorListener true
                    pendingPlayAfterSeek = false
                    stopVirtual(notify = false)
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

    private fun applyRenderPipeline() {
        if (Build.VERSION.SDK_INT < 31) return

        runCatching {
            var composed: RenderEffect? = null
            val safeColor = ColorGradeEngine.normalize(colorGrade)
            if (!ColorGradeEngine.isNeutral(safeColor)) {
                val matrix = ColorMatrix(ColorGradeEngine.colorMatrix(safeColor))
                composed = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            }

            if (chromaKey.enabled && Build.VERSION.SDK_INT >= 33) {
                val safe = MaskChromaComposition.normalize(chromaKey)
                val shader = RuntimeShader(CHROMA_SHADER)
                shader.setFloatUniform(
                    "keyColor",
                    Color.red(safe.keyColorArgb) / 255f,
                    Color.green(safe.keyColorArgb) / 255f,
                    Color.blue(safe.keyColorArgb) / 255f
                )
                shader.setFloatUniform("tolerance", safe.tolerance)
                shader.setFloatUniform("softness", safe.softness)
                shader.setFloatUniform("spill", safe.spill)
                val chromaEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
                composed = composed?.let { RenderEffect.createChainEffect(it, chromaEffect) } ?: chromaEffect
            }

            textureView.setRenderEffect(composed)
        }.onFailure { textureView.setRenderEffect(null) }
    }

    private fun startForward(active: MediaPlayer, target: Int) {
        stopVirtual(notify = false)
        runCatching { active.setVolume(1f, 1f) }
        pendingPlayAfterSeek = true
        active.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    private fun startReverse(active: MediaPlayer, target: Int) {
        pendingPlayAfterSeek = false
        if (active.isPlaying) active.pause()
        runCatching { active.setVolume(0f, 0f) }
        virtualMode = VirtualMode.REVERSE
        virtualPlaying = true
        virtualStartedAt = SystemClock.uptimeMillis()
        val playableEnd = (segmentEndMs - 1).coerceAtLeast(segmentStartMs)
        virtualStartSourceMs = target.coerceIn(segmentStartMs, playableEnd)
        virtualLastSeekAt = 0L
        active.seekTo(virtualStartSourceMs.toLong(), MediaPlayer.SEEK_CLOSEST)
        listener.onPlaybackStateChanged(true)
    }

    private fun startFreeze(active: MediaPlayer, target: Int) {
        pendingPlayAfterSeek = false
        if (active.isPlaying) active.pause()
        runCatching { active.setVolume(0f, 0f) }
        virtualMode = VirtualMode.FREEZE
        virtualPlaying = true
        virtualStartedAt = SystemClock.uptimeMillis()
        virtualStartSourceMs = target
        active.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        listener.onPlaybackStateChanged(true)
    }

    private fun tickReverse(active: MediaPlayer) {
        if (!virtualPlaying) return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - virtualStartedAt
        val target = (virtualStartSourceMs - elapsed * playbackSpeed).roundToInt().coerceAtLeast(segmentStartMs)
        if (now - virtualLastSeekAt >= REVERSE_SEEK_INTERVAL_MS) {
            active.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            virtualLastSeekAt = now
        }
        val finished = target <= segmentStartMs
        if (finished) {
            stopVirtual(notify = false)
            listener.onPlaybackStateChanged(false)
            listener.onProgress(target, durationMs, false)
        } else {
            listener.onProgress(target, durationMs, true)
        }
    }

    private fun tickFreeze(active: MediaPlayer) {
        if (!virtualPlaying) return
        val elapsed = (SystemClock.uptimeMillis() - virtualStartedAt).toInt()
        val absoluteElapsed = startTimelineOffsetMs + elapsed
        val finished = freezeDurationMs > 0 && absoluteElapsed >= freezeDurationMs
        if (active.currentPosition != virtualStartSourceMs && elapsed < 200) {
            active.seekTo(virtualStartSourceMs.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
        if (finished) {
            val finalElapsed = freezeDurationMs
            startTimelineOffsetMs = finalElapsed
            stopVirtual(notify = false)
            listener.onPlaybackStateChanged(false)
            listener.onProgress(virtualStartSourceMs, durationMs, false)
        } else {
            listener.onProgress(virtualStartSourceMs, durationMs, true)
        }
    }

    private fun stopVirtual(notify: Boolean) {
        val wasPlaying = virtualPlaying
        virtualPlaying = false
        virtualMode = VirtualMode.NONE
        if (wasPlaying && notify) listener.onPlaybackStateChanged(false)
    }

    private fun applyForwardPlaybackParams(active: MediaPlayer) {
        runCatching {
            active.playbackParams = PlaybackParams()
                .setSpeed(playbackSpeed)
                .setPitch(1f)
        }
    }

    /** Applies Vedito's renderer-independent ClipTransform to the TextureView preview. */
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

    companion object {
        private const val CHROMA_SHADER = """
            uniform shader content;
            uniform float3 keyColor;
            uniform float tolerance;
            uniform float softness;
            uniform float spill;

            half4 main(float2 p) {
                half4 src = content.eval(p);
                float3 rgb = float3(src.rgb);
                float distanceFromKey = distance(rgb, keyColor);
                float alpha = smoothstep(tolerance, tolerance + max(softness, 0.001), distanceFromKey);
                float proximity = 1.0 - smoothstep(tolerance, tolerance + 0.25, distanceFromKey);
                float luma = dot(rgb, float3(0.299, 0.587, 0.114));
                float3 despilled = mix(rgb, float3(luma), proximity * spill);
                return half4(half3(despilled), src.a * half(alpha));
            }
        """
        private const val REVERSE_SEEK_INTERVAL_MS = 85L
    }
}
