package com.vedito.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun View.safeClick(action: () -> Unit) {
    setOnClickListener { action() }
}

object Ui {
    const val BG = 0xFF090B0F.toInt()
    const val SURFACE = 0xFF11151B.toInt()
    const val SURFACE_2 = 0xFF191E26.toInt()
    const val TEXT = 0xFFF4F7FA.toInt()
    const val MUTED = 0xFF929BA7.toInt()
    const val ACCENT = 0xFF65F3C7.toInt()
    const val DIVIDER = 0xFF252B35.toInt()

    fun rounded(color: Int, radiusDp: Int, context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
        }

    fun outlined(
        fill: Int,
        stroke: Int,
        radiusDp: Int,
        context: Context
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(context.dp(1), stroke)
        cornerRadius = context.dp(radiusDp).toFloat()
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int = TEXT,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun labelButton(
        context: Context,
        value: String,
        backgroundColor: Int,
        textColor: Int,
        radiusDp: Int = 16
    ): TextView = text(context, value, 15f, textColor, true).apply {
        gravity = Gravity.CENTER
        background = rounded(backgroundColor, radiusDp, context)
        isClickable = true
        isFocusable = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(54)
        )
    }
}
