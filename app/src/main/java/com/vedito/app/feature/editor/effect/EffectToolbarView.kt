package com.vedito.app.feature.editor.effect

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
import com.vedito.app.core.model.EffectClip
import kotlin.math.roundToInt

class EffectToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    enum class Action { ADD_EFFECT, EFFECT_KIND, INTENSITY_DOWN, INTENSITY_UP, DELETE_EFFECT, TRANSITION_KIND, TRANSITION_DURATION }
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
        add(Action.ADD_EFFECT, "+ Effect")
        add(Action.EFFECT_KIND, "Effect")
        add(Action.INTENSITY_DOWN, "Intensity −")
        add(Action.INTENSITY_UP, "Intensity +")
        add(Action.DELETE_EFFECT, "Delete FX")
        add(Action.TRANSITION_KIND, "Transition")
        add(Action.TRANSITION_DURATION, "Trans time")
    }

    fun setState(effect: EffectClip?, clip: Clip?, hasNextClip: Boolean) {
        buttons[Action.ADD_EFFECT]?.apply { isEnabled = true; alpha = 1f }
        listOf(Action.EFFECT_KIND, Action.INTENSITY_DOWN, Action.INTENSITY_UP, Action.DELETE_EFFECT).forEach { action ->
            buttons[action]?.apply { isEnabled = effect != null; alpha = if (effect != null) 1f else 0.35f }
        }
        buttons[Action.EFFECT_KIND]?.text = effect?.let { "FX ${it.kind.name.lowercase()}" } ?: "Effect"
        buttons[Action.INTENSITY_DOWN]?.text = effect?.let { "− ${(it.intensity * 100).roundToInt()}%" } ?: "Intensity −"
        buttons[Action.INTENSITY_UP]?.text = effect?.let { "+ ${(it.intensity * 100).roundToInt()}%" } ?: "Intensity +"
        val transitionEnabled = clip != null && hasNextClip
        listOf(Action.TRANSITION_KIND, Action.TRANSITION_DURATION).forEach { action ->
            buttons[action]?.apply { isEnabled = transitionEnabled; alpha = if (transitionEnabled) 1f else 0.35f }
        }
        buttons[Action.TRANSITION_KIND]?.text = clip?.let { "Trans ${it.transitionOut.kind.name.lowercase().replace('_', ' ')}" } ?: "Transition"
        buttons[Action.TRANSITION_DURATION]?.text = clip?.let { "${it.transitionOut.durationMs}ms" } ?: "Trans time"
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
            minWidth = dp(82)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { if (isEnabled) onAction?.invoke(action) }
        }
        strip.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })
        buttons[action] = button
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
