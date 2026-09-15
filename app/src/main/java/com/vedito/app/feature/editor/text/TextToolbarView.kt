package com.vedito.app.feature.editor.text

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.text.TextKeyframeEngine
import kotlin.math.roundToInt

class TextToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    enum class Action {
        SCALE_DOWN,
        SCALE_UP,
        MOVE_LEFT,
        MOVE_RIGHT,
        MOVE_UP,
        MOVE_DOWN,
        ROTATE,
        OPACITY,
        FONT_DOWN,
        FONT_UP,
        COLOR,
        BACKGROUND,
        BOLD,
        ALIGN,
        PRESET,
        FONT_FAMILY,
        ANIMATION,
        SHADOW,
        KEYFRAME_TOGGLE,
        KEYFRAME_PREVIOUS,
        KEYFRAME_NEXT,
        KEYFRAME_EASING,
        RESET_TRANSFORM
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
        add(Action.SCALE_DOWN, "Scale −")
        add(Action.SCALE_UP, "Scale +")
        add(Action.MOVE_LEFT, "←")
        add(Action.MOVE_RIGHT, "→")
        add(Action.MOVE_UP, "↑")
        add(Action.MOVE_DOWN, "↓")
        add(Action.ROTATE, "Rotate")
        add(Action.OPACITY, "Opacity")
        add(Action.FONT_DOWN, "Font −")
        add(Action.FONT_UP, "Font +")
        add(Action.COLOR, "Color")
        add(Action.BACKGROUND, "Text BG")
        add(Action.BOLD, "Bold")
        add(Action.ALIGN, "Align")
        add(Action.PRESET, "Preset")
        add(Action.FONT_FAMILY, "Font")
        add(Action.ANIMATION, "Anim")
        add(Action.SHADOW, "Shadow")
        add(Action.KEYFRAME_TOGGLE, "◆ Key")
        add(Action.KEYFRAME_PREVIOUS, "◆ ←")
        add(Action.KEYFRAME_NEXT, "◆ →")
        add(Action.KEYFRAME_EASING, "Ease")
        add(Action.RESET_TRANSFORM, "Reset")
    }

    fun setState(clip: TextClip?, localTimeMs: Int = 0) {
        val enabled = clip != null
        buttons.values.forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.35f
        }
        if (clip == null) {
            buttons[Action.SCALE_DOWN]?.text = "Scale −"
            buttons[Action.SCALE_UP]?.text = "Scale +"
            buttons[Action.OPACITY]?.text = "Opacity"
            buttons[Action.FONT_DOWN]?.text = "Font −"
            buttons[Action.FONT_UP]?.text = "Font +"
            buttons[Action.BOLD]?.text = "Bold"
            buttons[Action.ALIGN]?.text = "Align"
            buttons[Action.PRESET]?.text = "Preset"
            buttons[Action.FONT_FAMILY]?.text = "Font"
            buttons[Action.ANIMATION]?.text = "Anim"
            buttons[Action.SHADOW]?.text = "Shadow"
            buttons[Action.KEYFRAME_TOGGLE]?.text = "◆ Key"
            buttons[Action.KEYFRAME_EASING]?.text = "Ease"
            return
        }
        val local = localTimeMs.coerceIn(0, clip.durationMs)
        val effective = TextKeyframeEngine.evaluate(clip.transform, clip.keyframes, local, clip.durationMs)
        buttons[Action.SCALE_DOWN]?.text = "− ${(effective.scale * 100).roundToInt()}%"
        buttons[Action.SCALE_UP]?.text = "+ ${(effective.scale * 100).roundToInt()}%"
        buttons[Action.OPACITY]?.text = "Opacity ${(effective.opacity * 100).roundToInt()}%"
        buttons[Action.FONT_DOWN]?.text = "− ${clip.style.fontSizeSp.roundToInt()}sp"
        buttons[Action.FONT_UP]?.text = "+ ${clip.style.fontSizeSp.roundToInt()}sp"
        buttons[Action.BOLD]?.text = if (clip.style.bold) "Bold on" else "Bold off"
        buttons[Action.ALIGN]?.text = clip.style.alignment.name.lowercase().replaceFirstChar { it.uppercase() }
        buttons[Action.PRESET]?.text = "Preset ${clip.preset.name.lowercase().replace('_', ' ')}"
        buttons[Action.FONT_FAMILY]?.text = "Font ${clip.style.fontFamily.name.lowercase()}"
        buttons[Action.ANIMATION]?.text = "Anim ${clip.animation.kind.name.lowercase().replace('_', ' ')}"
        buttons[Action.SHADOW]?.text = if (clip.style.shadowEnabled) "Shadow on" else "Shadow off"
        val count = clip.keyframes.pointCount
        val hasHere = TextKeyframeEngine.hasAt(clip.keyframes, local)
        buttons[Action.KEYFRAME_TOGGLE]?.text = when {
            hasHere -> "◆ Remove"
            count > 0 -> "◇ Add · $count"
            else -> "◇ Add key"
        }
        val easing = TextKeyframeEngine.easingAt(clip.keyframes, local)
        buttons[Action.KEYFRAME_EASING]?.text = easing?.name?.lowercase()?.replace('_', ' ')?.let { "Ease $it" } ?: "Ease —"
        val navigationEnabled = count > 0
        listOf(Action.KEYFRAME_PREVIOUS, Action.KEYFRAME_NEXT, Action.KEYFRAME_EASING).forEach { action ->
            buttons[action]?.isEnabled = navigationEnabled
            buttons[action]?.alpha = if (navigationEnabled) 1f else 0.35f
        }
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
            minWidth = dp(if (label.length <= 2) 44 else 68)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onAction?.invoke(action) }
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        strip.addView(button, params)
        buttons[action] = button
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
