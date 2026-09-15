package com.vedito.app.feature.editor.text

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.model.TextAlignment
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.text.TextComposition
import com.vedito.app.core.text.TextLayer
import com.vedito.app.core.text.TextMotion
import com.vedito.app.core.text.TextTimelineEditor
import kotlin.math.roundToInt

/** View-backed preview for renderer-independent text composition state. */
class TextPreviewController(
    private val context: Context,
    private val host: FrameLayout
) {
    var onTextSelected: ((String) -> Unit)? = null

    private data class Node(val clipId: String, val frame: FrameLayout, val text: TextView)

    private val nodes = linkedMapOf<String, Node>()
    private var clips: List<TextClip> = emptyList()
    private var positionMs: Int = 0
    private var selectedId: String? = null

    fun setTimeline(clips: List<TextClip>) {
        this.clips = clips
        render(positionMs, selectedId)
    }

    fun render(positionMs: Int, selectedId: String?) {
        this.positionMs = positionMs.coerceAtLeast(0)
        this.selectedId = selectedId
        val layers = TextComposition.activeLayers(this.positionMs, clips)
        val activeIds = layers.mapTo(mutableSetOf()) { it.clipId }
        nodes.keys.filterNot { it in activeIds }.toList().forEach(::removeNode)

        layers.forEach { layer ->
            val node = nodes[layer.clipId] ?: createNode(layer)
            applyLayer(node, layer, selectedId == layer.clipId)
            node.frame.bringToFront()
        }
    }

    fun release() {
        nodes.keys.toList().forEach(::removeNode)
    }

    private fun createNode(layer: TextLayer): Node {
        val frame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            isClickable = true
            setOnClickListener { onTextSelected?.invoke(layer.clipId) }
        }
        val textView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            includeFontPadding = false
            setPadding(dp(8), dp(5), dp(8), dp(5))
            maxWidth = (host.resources.displayMetrics.widthPixels * 0.82f).roundToInt()
        }
        frame.addView(textView)
        host.addView(frame)
        return Node(layer.clipId, frame, textView).also { nodes[layer.clipId] = it }
    }

    private fun applyLayer(node: Node, layer: TextLayer, selected: Boolean) {
        val clip = layer.clip
        val transform = TextTimelineEditor.normalizeTransform(clip.transform)
        val style = TextTimelineEditor.normalizeStyle(clip.style)
        node.text.text = clip.text
        node.text.textSize = style.fontSizeSp
        node.text.setTextColor(style.textColorArgb)
        node.text.setBackgroundColor(style.backgroundColorArgb)
        node.text.typeface = TextTypefaceResolver.resolve(style.fontFamily, style.bold)
        node.text.letterSpacing = style.letterSpacingEm
        if (style.shadowEnabled) {
            node.text.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
        } else {
            node.text.setShadowLayer(0f, 0f, 0f, 0x00000000)
        }
        node.text.gravity = when (style.alignment) {
            TextAlignment.LEFT -> Gravity.START
            TextAlignment.CENTER -> Gravity.CENTER
            TextAlignment.RIGHT -> Gravity.END
        }

        val motion = TextMotion.frame(clip.animation, layer.localTimelineMs, clip.durationMs)
        node.frame.alpha = transform.opacity * motion.alphaMultiplier
        node.frame.rotation = transform.rotationDegrees
        node.frame.scaleX = transform.scale * motion.scaleMultiplier
        node.frame.scaleY = transform.scale * motion.scaleMultiplier
        node.frame.translationX = transform.positionX * host.width * 0.45f
        node.frame.translationY = (transform.positionY * host.height * 0.45f) + (motion.translationYFraction * host.height)
        node.frame.foreground = if (selected) selectionDrawable() else null
    }

    private fun selectionDrawable() = GradientDrawable().apply {
        setColor(0x00000000)
        setStroke(dp(2), context.getColor(R.color.vedito_accent))
        cornerRadius = dp(6).toFloat()
    }

    private fun removeNode(id: String) {
        val node = nodes.remove(id) ?: return
        host.removeView(node.frame)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
