package com.vedito.app.feature.editor.visual

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.model.ChromaKeySpec
import com.vedito.app.core.model.MaskShape
import com.vedito.app.core.model.MaskSpec
import kotlin.math.roundToInt

class MaskChromaToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    enum class Action {
        MASK_SHAPE,
        MASK_SMALLER,
        MASK_LARGER,
        MASK_LEFT,
        MASK_RIGHT,
        MASK_UP,
        MASK_DOWN,
        MASK_FEATHER,
        MASK_INVERT,
        CHROMA_TOGGLE,
        CHROMA_COLOR,
        CHROMA_TOLERANCE,
        CHROMA_SOFTNESS,
        CHROMA_SPILL,
        RESET
    }

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
        add(Action.MASK_SHAPE, "Mask")
        add(Action.MASK_SMALLER, "Size −")
        add(Action.MASK_LARGER, "Size +")
        add(Action.MASK_LEFT, "←")
        add(Action.MASK_RIGHT, "→")
        add(Action.MASK_UP, "↑")
        add(Action.MASK_DOWN, "↓")
        add(Action.MASK_FEATHER, "Feather")
        add(Action.MASK_INVERT, "Invert")
        add(Action.CHROMA_TOGGLE, "Chroma")
        add(Action.CHROMA_COLOR, "Key color")
        add(Action.CHROMA_TOLERANCE, "Tolerance")
        add(Action.CHROMA_SOFTNESS, "Softness")
        add(Action.CHROMA_SPILL, "Spill")
        add(Action.RESET, "Reset")
    }

    fun setState(mask: MaskSpec?, chroma: ChromaKeySpec?, supportedForSelection: Boolean) {
        buttons.values.forEach {
            it.isEnabled = supportedForSelection
            it.alpha = if (supportedForSelection) 1f else 0.35f
        }
        if (!supportedForSelection || mask == null || chroma == null) {
            buttons[Action.MASK_SHAPE]?.text = "Mask"
            buttons[Action.CHROMA_TOGGLE]?.text = "Chroma"
            return
        }
        buttons[Action.MASK_SHAPE]?.text = when (mask.shape) {
            MaskShape.NONE -> "Mask Off"
            MaskShape.RECTANGLE -> "Mask Rect"
            MaskShape.ELLIPSE -> "Mask Oval"
        }
        buttons[Action.MASK_FEATHER]?.text = "Feather ${(mask.feather * 100f).roundToInt()}%"
        buttons[Action.MASK_INVERT]?.text = if (mask.inverted) "Invert On" else "Invert Off"
        buttons[Action.CHROMA_TOGGLE]?.text = if (chroma.enabled) "Chroma On" else "Chroma Off"
        buttons[Action.CHROMA_TOLERANCE]?.text = "Tol ${(chroma.tolerance * 100f).roundToInt()}%"
        buttons[Action.CHROMA_SOFTNESS]?.text = "Soft ${(chroma.softness * 100f).roundToInt()}%"
        buttons[Action.CHROMA_SPILL]?.text = "Spill ${(chroma.spill * 100f).roundToInt()}%"
        buttons[Action.CHROMA_COLOR]?.text = when (chroma.keyColorArgb) {
            KEY_GREEN -> "Key Green"
            KEY_BLUE -> "Key Blue"
            KEY_MAGENTA -> "Key Magenta"
            else -> "Key Custom"
        }
    }

    private fun add(action: Action, label: String) {
        val button = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.vedito_text))
            textSize = 8.5f
            setBackgroundResource(R.drawable.bg_tool_button)
            isClickable = true
            isFocusable = true
            minWidth = dp(if (label.length <= 3) 42 else 66)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onAction?.invoke(action) }
        }
        strip.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        buttons[action] = button
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        val KEY_GREEN: Int = 0xFF00FF00.toInt()
        val KEY_BLUE: Int = 0xFF0066FF.toInt()
        val KEY_MAGENTA: Int = 0xFFFF00FF.toInt()
    }
}
