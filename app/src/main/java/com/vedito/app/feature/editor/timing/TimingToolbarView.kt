package com.vedito.app.feature.editor.timing

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipPlaybackMode
import kotlin.math.roundToInt

class TimingToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    enum class Action { SPEED_DOWN, SPEED_UP, FREEZE, REVERSE }

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
        add(Action.SPEED_DOWN, "Speed −")
        add(Action.SPEED_UP, "Speed +")
        add(Action.FREEZE, "Freeze 2s")
        add(Action.REVERSE, "Reverse")
    }

    fun setState(clip: Clip?) {
        val enabled = clip != null
        buttons.values.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.35f
        }
        if (clip == null) {
            buttons[Action.SPEED_DOWN]?.text = "Speed −"
            buttons[Action.SPEED_UP]?.text = "Speed +"
            buttons[Action.REVERSE]?.text = "Reverse"
            return
        }
        val speed = clip.timing.speed
        val speedLabel = if ((speed * 100).roundToInt() % 100 == 0) "${speed.toInt()}.0×" else String.format("%.2f×", speed).trimEnd('0')
        val freeze = clip.timing.mode == ClipPlaybackMode.FREEZE
        buttons[Action.SPEED_DOWN]?.apply {
            text = if (freeze) "Speed −" else "− $speedLabel"
            isEnabled = !freeze
            alpha = if (isEnabled) 1f else 0.35f
        }
        buttons[Action.SPEED_UP]?.apply {
            text = if (freeze) "Speed +" else "+ $speedLabel"
            isEnabled = !freeze
            alpha = if (isEnabled) 1f else 0.35f
        }
        buttons[Action.REVERSE]?.apply {
            text = when (clip.timing.mode) {
                ClipPlaybackMode.REVERSE -> "Reverse ON"
                ClipPlaybackMode.FREEZE -> "Reverse"
                ClipPlaybackMode.FORWARD -> "Reverse"
            }
            isEnabled = !freeze
            alpha = if (isEnabled) 1f else 0.35f
        }
        buttons[Action.FREEZE]?.text = if (freeze) "Freeze clip" else "Freeze 2s"
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
            minWidth = dp(72)
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
