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
import com.vedito.app.core.model.CanvasSettings
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.TransformKeyframeSet
import com.vedito.app.core.keyframe.KeyframeEngine
import kotlin.math.roundToInt

class TransformToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    enum class Action {
        KEYFRAME_TOGGLE,
        KEYFRAME_PREVIOUS,
        KEYFRAME_NEXT,
        KEYFRAME_EASING,
        KEYFRAME_CLEAR,
        SCALE_DOWN,
        SCALE_UP,
        MOVE_LEFT,
        MOVE_RIGHT,
        MOVE_UP,
        MOVE_DOWN,
        ROTATE_90,
        FLIP_HORIZONTAL,
        FLIP_VERTICAL,
        OPACITY_CYCLE,
        FIT_TOGGLE,
        CROP_LEFT,
        CROP_RIGHT,
        CROP_TOP,
        CROP_BOTTOM,
        CROP_RESET,
        CANVAS_RATIO,
        CANVAS_BACKGROUND,
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
        add(Action.KEYFRAME_TOGGLE, "◇ Add KF")
        add(Action.KEYFRAME_PREVIOUS, "◀ KF")
        add(Action.KEYFRAME_NEXT, "KF ▶")
        add(Action.KEYFRAME_EASING, "Ease")
        add(Action.KEYFRAME_CLEAR, "Clear KF")
        add(Action.SCALE_DOWN, "Scale −")
        add(Action.SCALE_UP, "Scale +")
        add(Action.MOVE_LEFT, "←")
        add(Action.MOVE_RIGHT, "→")
        add(Action.MOVE_UP, "↑")
        add(Action.MOVE_DOWN, "↓")
        add(Action.ROTATE_90, "Rotate")
        add(Action.FLIP_HORIZONTAL, "Flip H")
        add(Action.FLIP_VERTICAL, "Flip V")
        add(Action.OPACITY_CYCLE, "Opacity")
        add(Action.FIT_TOGGLE, "Fit")
        add(Action.CROP_LEFT, "Crop L")
        add(Action.CROP_RIGHT, "Crop R")
        add(Action.CROP_TOP, "Crop T")
        add(Action.CROP_BOTTOM, "Crop B")
        add(Action.CROP_RESET, "Crop reset")
        add(Action.CANVAS_RATIO, "Canvas")
        add(Action.CANVAS_BACKGROUND, "BG")
        add(Action.RESET_TRANSFORM, "Reset")
    }

    fun setState(
        transform: ClipTransform?,
        canvas: CanvasSettings,
        keyframes: TransformKeyframeSet? = null,
        localTimeMs: Int = 0
    ) {
        val enabled = transform != null
        val hasKeyframes = keyframes?.isEmpty == false
        val atKeyframe = keyframes?.let { KeyframeEngine.hasAt(it, localTimeMs) } == true
        buttons.forEach { (action, view) ->
            val isCanvasAction = action == Action.CANVAS_RATIO || action == Action.CANVAS_BACKGROUND
            val keyframeNavigation = action == Action.KEYFRAME_PREVIOUS ||
                action == Action.KEYFRAME_NEXT ||
                action == Action.KEYFRAME_EASING ||
                action == Action.KEYFRAME_CLEAR
            view.isEnabled = when {
                isCanvasAction -> true
                keyframeNavigation -> enabled && hasKeyframes
                else -> enabled
            }
            view.alpha = if (view.isEnabled) 1f else 0.35f
        }

        if (transform != null) {
            buttons[Action.SCALE_DOWN]?.text = "− ${(transform.scale * 100f).roundToInt()}%"
            buttons[Action.SCALE_UP]?.text = "+ ${(transform.scale * 100f).roundToInt()}%"
            buttons[Action.OPACITY_CYCLE]?.text = "Opacity ${(transform.opacity * 100f).roundToInt()}%"
            buttons[Action.FIT_TOGGLE]?.text = transform.fitMode.name.lowercase().replaceFirstChar { it.uppercase() }
        } else {
            buttons[Action.SCALE_DOWN]?.text = "Scale −"
            buttons[Action.SCALE_UP]?.text = "Scale +"
            buttons[Action.OPACITY_CYCLE]?.text = "Opacity"
            buttons[Action.FIT_TOGGLE]?.text = "Fit"
        }

        buttons[Action.KEYFRAME_TOGGLE]?.text = if (atKeyframe) "◆ Remove KF" else "◇ Add KF"
        val easing = keyframes?.let { KeyframeEngine.easingAt(it, localTimeMs) }
        buttons[Action.KEYFRAME_EASING]?.text = when (easing) {
            null -> "Ease"
            else -> "Ease ${easing.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}"
        }
        buttons[Action.CANVAS_RATIO]?.text = "Canvas ${canvas.aspect.label}"
        buttons[Action.CANVAS_BACKGROUND]?.text = "BG ${canvas.background.label}"
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
