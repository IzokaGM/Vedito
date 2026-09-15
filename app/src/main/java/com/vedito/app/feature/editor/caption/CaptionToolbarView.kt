package com.vedito.app.feature.editor.caption

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.model.CaptionSegment
import kotlin.math.roundToInt

class CaptionToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    enum class Action { FONT_FAMILY, ANIMATION }

    var onAction: ((Action) -> Unit)? = null

    private val strip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, dp(2), 0)
    }
    private val buttons = linkedMapOf<Action, TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        add(Action.FONT_FAMILY, "Font")
        add(Action.ANIMATION, "Animation")
    }

    fun setState(segment: CaptionSegment?) {
        buttons.values.forEach {
            it.isEnabled = segment != null
            it.alpha = if (segment != null) 1f else 0.38f
        }
        buttons[Action.FONT_FAMILY]?.text = segment?.let { "Font ${it.fontFamily.name.lowercase()}" } ?: "Font"
        buttons[Action.ANIMATION]?.text = segment?.let { "Anim ${it.animation.kind.name.lowercase().replace('_', ' ')}" } ?: "Animation"
    }

    private fun add(action: Action, label: String) {
        val button = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.vedito_text))
            textSize = 9f
            setBackgroundResource(R.drawable.bg_tool_button)
            isClickable = true
            isFocusable = true
            minWidth = dp(92)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onAction?.invoke(action) }
        }
        strip.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        buttons[action] = button
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
