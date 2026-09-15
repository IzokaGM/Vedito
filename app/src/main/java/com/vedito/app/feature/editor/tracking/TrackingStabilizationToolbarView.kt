package com.vedito.app.feature.editor.tracking

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.model.MotionTrackSpec
import com.vedito.app.core.model.StabilizationSpec
import kotlin.math.roundToInt

class TrackingStabilizationToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    enum class Action {
        TRACK_TOGGLE,
        ADD_ANCHOR,
        REMOVE_ANCHOR,
        PREVIOUS_ANCHOR,
        NEXT_ANCHOR,
        STABILIZE_TOGGLE,
        STRENGTH,
        AUTO_CROP,
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
        add(Action.TRACK_TOGGLE, "Track")
        add(Action.ADD_ANCHOR, "+ Anchor")
        add(Action.REMOVE_ANCHOR, "− Anchor")
        add(Action.PREVIOUS_ANCHOR, "← Point")
        add(Action.NEXT_ANCHOR, "Point →")
        add(Action.STABILIZE_TOGGLE, "Stabilize")
        add(Action.STRENGTH, "Strength")
        add(Action.AUTO_CROP, "Auto crop")
        add(Action.RESET, "Reset T/S")
    }

    fun setState(
        track: MotionTrackSpec?,
        stabilization: StabilizationSpec?,
        localTimeMs: Int,
        supportedForSelection: Boolean
    ) {
        buttons.values.forEach {
            it.isEnabled = supportedForSelection
            it.alpha = if (supportedForSelection) 1f else 0.35f
        }
        if (!supportedForSelection || track == null || stabilization == null) {
            buttons[Action.TRACK_TOGGLE]?.text = "Track"
            buttons[Action.STABILIZE_TOGGLE]?.text = "Stabilize"
            return
        }
        val count = track.points.size
        buttons[Action.TRACK_TOGGLE]?.text = if (track.enabled) "Track On · $count" else "Track Off · $count"
        buttons[Action.ADD_ANCHOR]?.text = "+ Anchor ${(localTimeMs / 100) / 10f}s"
        buttons[Action.STABILIZE_TOGGLE]?.text = if (stabilization.enabled) "Stabilize On" else "Stabilize Off"
        buttons[Action.STRENGTH]?.text = "Strength ${(stabilization.strength * 100f).roundToInt()}%"
        buttons[Action.AUTO_CROP]?.text = if (stabilization.autoCrop) "Auto crop On" else "Auto crop Off"
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
            minWidth = dp(70)
            setPadding(dp(10), 0, dp(10), 0)
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
