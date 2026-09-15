package com.vedito.app.feature.editor.color

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vedito.app.R
import com.vedito.app.core.color.ColorGradeEngine
import com.vedito.app.core.model.ColorGradeSpec
import kotlin.math.roundToInt

class ColorGradeToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    enum class Action {
        EXPOSURE_DOWN, EXPOSURE_UP,
        CONTRAST_DOWN, CONTRAST_UP,
        SATURATION_DOWN, SATURATION_UP,
        TEMPERATURE_DOWN, TEMPERATURE_UP,
        TINT_DOWN, TINT_UP,
        FADE,
        CURVE_PRESET,
        HUE_DOWN, HUE_UP,
        HSL_SAT_DOWN, HSL_SAT_UP,
        HSL_LUMA_DOWN, HSL_LUMA_UP,
        LUT_PRESET,
        LUT_STRENGTH,
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
        add(Action.EXPOSURE_DOWN, "Exp −")
        add(Action.EXPOSURE_UP, "Exp +")
        add(Action.CONTRAST_DOWN, "Con −")
        add(Action.CONTRAST_UP, "Con +")
        add(Action.SATURATION_DOWN, "Sat −")
        add(Action.SATURATION_UP, "Sat +")
        add(Action.TEMPERATURE_DOWN, "Temp −")
        add(Action.TEMPERATURE_UP, "Temp +")
        add(Action.TINT_DOWN, "Tint −")
        add(Action.TINT_UP, "Tint +")
        add(Action.FADE, "Fade")
        add(Action.CURVE_PRESET, "Curve")
        add(Action.HUE_DOWN, "Hue −")
        add(Action.HUE_UP, "Hue +")
        add(Action.HSL_SAT_DOWN, "HSL Sat −")
        add(Action.HSL_SAT_UP, "HSL Sat +")
        add(Action.HSL_LUMA_DOWN, "HSL Lum −")
        add(Action.HSL_LUMA_UP, "HSL Lum +")
        add(Action.LUT_PRESET, "LUT")
        add(Action.LUT_STRENGTH, "LUT 100%")
        add(Action.RESET, "Reset color")
    }

    fun setState(spec: ColorGradeSpec?, supportedForSelection: Boolean) {
        buttons.values.forEach {
            it.isEnabled = supportedForSelection
            it.alpha = if (supportedForSelection) 1f else 0.35f
        }
        val s = ColorGradeEngine.normalize(spec ?: ColorGradeSpec())
        buttons[Action.EXPOSURE_UP]?.text = "Exp ${signed(s.exposure)}"
        buttons[Action.CONTRAST_UP]?.text = "Con ${percent(s.contrast)}"
        buttons[Action.SATURATION_UP]?.text = "Sat ${percent(s.saturation)}"
        buttons[Action.TEMPERATURE_UP]?.text = "Temp ${percent(s.temperature)}"
        buttons[Action.TINT_UP]?.text = "Tint ${percent(s.tint)}"
        buttons[Action.FADE]?.text = "Fade ${(s.fade * 100f).roundToInt()}%"
        buttons[Action.CURVE_PRESET]?.text = "Curve ${ColorGradeEngine.curvePresetLabel(s.curves)}"
        buttons[Action.HUE_UP]?.text = "Hue ${s.hsl.hueDegrees.roundToInt()}°"
        buttons[Action.HSL_SAT_UP]?.text = "HSL Sat ${percent(s.hsl.saturation)}"
        buttons[Action.HSL_LUMA_UP]?.text = "HSL Lum ${percent(s.hsl.luminance)}"
        buttons[Action.LUT_PRESET]?.text = "LUT ${ColorGradeEngine.lutLabel(s.lut.preset)}"
        buttons[Action.LUT_STRENGTH]?.text = "LUT ${(s.lut.intensity * 100f).roundToInt()}%"
    }

    private fun signed(value: Float): String = if (value >= 0f) "+%.1f".format(value) else "%.1f".format(value)
    private fun percent(value: Float): String = if (value >= 0f) "+${(value * 100f).roundToInt()}%" else "${(value * 100f).roundToInt()}%"

    private fun add(action: Action, label: String) {
        val button = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.vedito_text))
            textSize = 9f
            setBackgroundResource(R.drawable.bg_tool_button)
            isClickable = true
            isFocusable = true
            minWidth = dp(68)
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
