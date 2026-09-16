package com.vedito.app.feature.editor.overlay

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.vedito.app.R
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.model.OverlayMediaType
import com.vedito.app.core.overlay.OverlayComposition
import com.vedito.app.core.overlay.OverlayLayer
import com.vedito.app.core.visual.VisualTransformMath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * View-backed preview implementation of renderer-independent [OverlayComposition].
 * Audio from video overlays is intentionally muted; overlay audio can be extracted to the audio timeline.
 */
class OverlayPreviewController(
    private val context: Context,
    private val host: FrameLayout
) {
    var onOverlaySelected: ((String) -> Unit)? = null

    private sealed class Node(
        val clipId: String,
        val assetId: String,
        val frame: FrameLayout
    ) {
        class Image(clipId: String, assetId: String, frame: FrameLayout, val image: ImageView) : Node(clipId, assetId, frame)
        class Video(
            clipId: String,
            assetId: String,
            frame: FrameLayout,
            val texture: TextureView
        ) : Node(clipId, assetId, frame) {
            var player: MediaPlayer? = null
            var prepared = false
            var desiredPositionMs = 0
            var desiredPlaying = false
            var generation = 0
        }
    }

    private val nodes = linkedMapOf<String, Node>()
    private var assets: List<OverlayAsset> = emptyList()
    private var clips: List<OverlayClip> = emptyList()
    private var positionMs = 0
    private var playing = false
    private var selectedId: String? = null

    fun setTimeline(assets: List<OverlayAsset>, clips: List<OverlayClip>) {
        this.assets = assets
        this.clips = clips
        render(positionMs, playing, selectedId)
    }

    fun render(positionMs: Int, playing: Boolean, selectedId: String?) {
        this.positionMs = positionMs.coerceAtLeast(0)
        this.playing = playing
        this.selectedId = selectedId
        val layers = OverlayComposition.activeLayers(this.positionMs, assets, clips)
        val activeIds = layers.mapTo(mutableSetOf()) { it.clipId }
        nodes.keys.filterNot { it in activeIds }.toList().forEach(::removeNode)

        layers.forEach { layer ->
            val existing = nodes[layer.clipId]
            val node = if (existing == null || existing.assetId != layer.asset.id ||
                (layer.asset.type == OverlayMediaType.IMAGE && existing !is Node.Image) ||
                (layer.asset.type == OverlayMediaType.VIDEO && existing !is Node.Video)
            ) {
                if (existing != null) removeNode(layer.clipId)
                createNode(layer)
            } else existing

            applyLayer(node, layer, selectedId == layer.clipId)
            node.frame.bringToFront()
            when (node) {
                is Node.Image -> Unit
                is Node.Video -> syncVideo(node, layer.sourcePositionMs, playing)
            }
        }
    }

    fun release() {
        nodes.keys.toList().forEach(::removeNode)
        nodes.clear()
    }

    private fun createNode(layer: OverlayLayer): Node {
        val frame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clipChildren = true
            clipToPadding = true
            isClickable = true
            setOnClickListener { onOverlaySelected?.invoke(layer.clipId) }
        }
        host.addView(frame)
        val node = when (layer.asset.type) {
            OverlayMediaType.IMAGE -> {
                val image = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setImageURI(Uri.parse(layer.asset.uri))
                }
                frame.addView(image)
                Node.Image(layer.clipId, layer.asset.id, frame, image)
            }
            OverlayMediaType.VIDEO -> {
                val texture = TextureView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                frame.addView(texture)
                Node.Video(layer.clipId, layer.asset.id, frame, texture).also { prepareVideo(it, layer.asset) }
            }
        }
        nodes[layer.clipId] = node
        return node
    }

    private fun applyLayer(node: Node, layer: OverlayLayer, selected: Boolean) {
        val transform = VisualTransformMath.normalize(layer.transform)
        val frame = node.frame
        frame.alpha = transform.opacity
        frame.rotation = transform.rotationDegrees
        frame.scaleX = transform.scale * if (transform.flipHorizontal) -1f else 1f
        frame.scaleY = transform.scale * if (transform.flipVertical) -1f else 1f
        frame.translationX = transform.positionX * host.width * 0.5f
        frame.translationY = transform.positionY * host.height * 0.5f
        val left = (transform.cropLeft * frame.width).roundToInt().coerceAtLeast(0)
        val top = (transform.cropTop * frame.height).roundToInt().coerceAtLeast(0)
        val right = (frame.width - transform.cropRight * frame.width).roundToInt().coerceAtLeast(left + 1)
        val bottom = (frame.height - transform.cropBottom * frame.height).roundToInt().coerceAtLeast(top + 1)
        if (frame.width > 0 && frame.height > 0) frame.clipBounds = Rect(left, top, right, bottom)
        frame.foreground = if (selected) selectionDrawable() else null

        when (node) {
            is Node.Image -> node.image.scaleType = if (transform.fitMode == ClipFitMode.FILL) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            is Node.Video -> applyVideoFit(node.texture, layer.asset, transform.fitMode)
        }
    }

    private fun prepareVideo(node: Node.Video, asset: OverlayAsset) {
        val generation = ++node.generation
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                if (generation != node.generation) return
                val surface = Surface(surfaceTexture)
                try {
                    node.player?.release()
                    node.player = MediaPlayer().apply {
                        setDataSource(context, Uri.parse(asset.uri))
                        setSurface(surface)
                        setVolume(0f, 0f)
                        isLooping = false
                        setOnPreparedListener { player ->
                            if (generation != node.generation) return@setOnPreparedListener
                            node.prepared = true
                            player.seekTo(node.desiredPositionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                            if (node.desiredPlaying) player.start()
                        }
                        setOnErrorListener { _, _, _ -> true }
                        prepareAsync()
                    }
                } catch (_: Exception) {
                    node.prepared = false
                } finally {
                    surface.release()
                }
            }
            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                node.player?.release()
                node.player = null
                node.prepared = false
                return true
            }
            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
        node.texture.surfaceTextureListener = listener
        if (node.texture.isAvailable) {
            node.texture.surfaceTexture?.let { listener.onSurfaceTextureAvailable(it, node.texture.width, node.texture.height) }
        }
    }

    private fun syncVideo(node: Node.Video, sourcePositionMs: Int, shouldPlay: Boolean) {
        node.desiredPositionMs = sourcePositionMs.coerceAtLeast(0)
        node.desiredPlaying = shouldPlay
        val player = node.player ?: return
        if (!node.prepared) return
        val drift = abs(player.currentPosition - node.desiredPositionMs)
        if (drift > VIDEO_SYNC_TOLERANCE_MS) {
            runCatching { player.seekTo(node.desiredPositionMs.toLong(), MediaPlayer.SEEK_CLOSEST) }
        }
        if (shouldPlay && !player.isPlaying) runCatching { player.start() }
        if (!shouldPlay && player.isPlaying) runCatching { player.pause() }
    }

    private fun applyVideoFit(texture: TextureView, asset: OverlayAsset, fitMode: ClipFitMode) {
        val vw = asset.width.coerceAtLeast(1).toFloat()
        val vh = asset.height.coerceAtLeast(1).toFloat()
        val tw = texture.width.coerceAtLeast(1).toFloat()
        val th = texture.height.coerceAtLeast(1).toFloat()
        val scale = if (fitMode == ClipFitMode.FILL) maxOf(tw / vw, th / vh) else minOf(tw / vw, th / vh)
        val scaledW = vw * scale
        val scaledH = vh * scale
        val matrix = Matrix()
        matrix.setScale(scaledW / tw, scaledH / th, tw / 2f, th / 2f)
        texture.setTransform(matrix)
    }

    private fun selectionDrawable() = GradientDrawable().apply {
        setColor(0x00000000)
        setStroke(dp(2f).roundToInt(), context.getColor(R.color.vedito_video))
    }

    private fun removeNode(id: String) {
        val node = nodes.remove(id) ?: return
        if (node is Node.Video) {
            node.generation++
            node.player?.release()
            node.player = null
            node.prepared = false
        }
        host.removeView(node.frame)
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    companion object {
        private const val VIDEO_SYNC_TOLERANCE_MS = 300
    }
}
