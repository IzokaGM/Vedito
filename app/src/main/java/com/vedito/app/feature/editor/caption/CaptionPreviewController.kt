package com.vedito.app.feature.editor.caption

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.caption.CaptionComposition
import com.vedito.app.core.caption.CaptionStyleCatalog
import com.vedito.app.core.model.CaptionSegment
import com.vedito.app.core.text.TextMotion
import com.vedito.app.feature.editor.text.TextTypefaceResolver
import kotlin.math.roundToInt

class CaptionPreviewController(
    private val context: Context,
    private val host: FrameLayout
) {
    var onCaptionSelected: ((String) -> Unit)? = null

    private data class Node(val segmentId: String, val frame: FrameLayout, val text: TextView)

    private val nodes = linkedMapOf<String, Node>()
    private var segments: List<CaptionSegment> = emptyList()
    private var positionMs: Int = 0
    private var selectedId: String? = null

    fun setTimeline(segments: List<CaptionSegment>) {
        this.segments = segments
        render(positionMs, selectedId)
    }

    fun render(positionMs: Int, selectedId: String?) {
        this.positionMs = positionMs.coerceAtLeast(0)
        this.selectedId = selectedId
        val layers = CaptionComposition.activeLayers(this.positionMs, segments)
        val activeIds = layers.mapTo(mutableSetOf()) { it.segmentId }
        nodes.keys.filterNot { it in activeIds }.toList().forEach(::removeNode)
        layers.forEachIndexed { index, layer ->
            val node = nodes[layer.segmentId] ?: createNode(layer.segment)
            applySegment(node, layer.segment, layer.segmentId == selectedId, index)
            node.frame.bringToFront()
        }
    }

    fun release() {
        nodes.keys.toList().forEach(::removeNode)
    }

    private fun createNode(segment: CaptionSegment): Node {
        val frame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            isClickable = true
            setOnClickListener { onCaptionSelected?.invoke(segment.id) }
        }
        val textView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            maxWidth = (host.resources.displayMetrics.widthPixels * 0.86f).roundToInt()
        }
        frame.addView(textView)
        host.addView(frame)
        return Node(segment.id, frame, textView).also { nodes[segment.id] = it }
    }

    private fun applySegment(node: Node, segment: CaptionSegment, selected: Boolean, stackIndex: Int) {
        node.text.text = segment.text
        val visual = CaptionStyleCatalog.resolve(segment.preset)
        node.text.textSize = visual.fontSizeSp
        node.text.setTextColor(visual.textColorArgb)
        node.text.setBackgroundColor(visual.backgroundColorArgb)
        node.text.typeface = TextTypefaceResolver.resolve(segment.fontFamily, visual.bold)
        if (visual.shadowEnabled) {
            node.text.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0xDD000000.toInt())
        } else {
            node.text.setShadowLayer(0f, 0f, 0f, 0x00000000)
        }
        val params = node.text.layoutParams as FrameLayout.LayoutParams
        params.bottomMargin = dp(visual.bottomMarginDp) + stackIndex.coerceAtMost(3) * dp(42)
        node.text.layoutParams = params
        val localMs = (positionMs - segment.timelineStartMs).coerceAtLeast(0)
        val motion = TextMotion.frame(segment.animation, localMs, segment.durationMs)
        node.frame.alpha = motion.alphaMultiplier
        node.frame.scaleX = motion.scaleMultiplier
        node.frame.scaleY = motion.scaleMultiplier
        node.frame.translationY = motion.translationYFraction * host.height
        node.frame.foreground = if (selected) selectionDrawable() else null
    }

    private fun selectionDrawable() = GradientDrawable().apply {
        setColor(0x00000000)
        setStroke(dp(2), context.getColor(R.color.vedito_text_track))
        cornerRadius = dp(6).toFloat()
    }

    private fun removeNode(id: String) {
        val node = nodes.remove(id) ?: return
        host.removeView(node.frame)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
