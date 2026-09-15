package com.vedito.app.feature.editor.caption

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.caption.CaptionComposition
import com.vedito.app.core.model.CaptionPreset
import com.vedito.app.core.model.CaptionSegment
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
        when (segment.preset) {
            CaptionPreset.BOXED -> {
                node.text.textSize = 24f
                node.text.setTextColor(0xFFFFFFFF.toInt())
                node.text.setBackgroundColor(0xA6000000.toInt())
                node.text.typeface = Typeface.DEFAULT_BOLD
            }
            CaptionPreset.CLEAN -> {
                node.text.textSize = 25f
                node.text.setTextColor(0xFFFFFFFF.toInt())
                node.text.setBackgroundColor(0x00000000)
                node.text.typeface = Typeface.DEFAULT_BOLD
            }
            CaptionPreset.LARGE -> {
                node.text.textSize = 31f
                node.text.setTextColor(0xFFFFFFFF.toInt())
                node.text.setBackgroundColor(0x88000000.toInt())
                node.text.typeface = Typeface.DEFAULT_BOLD
            }
        }
        val params = node.text.layoutParams as FrameLayout.LayoutParams
        val baseMargin = when (segment.preset) {
            CaptionPreset.LARGE -> dp(28)
            else -> dp(20)
        }
        params.bottomMargin = baseMargin + stackIndex.coerceAtMost(3) * dp(42)
        node.text.layoutParams = params
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
