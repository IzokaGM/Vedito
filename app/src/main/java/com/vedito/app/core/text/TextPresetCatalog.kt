package com.vedito.app.core.text

import com.vedito.app.core.model.TextAlignment
import com.vedito.app.core.model.TextFontFamily
import com.vedito.app.core.model.TextPreset
import com.vedito.app.core.model.TextStyle
import com.vedito.app.core.model.TextTransform

/** Renderer-independent preset catalog. Export must consume the resulting state, not preset names. */
object TextPresetCatalog {
    private val order = listOf(
        TextPreset.CLASSIC,
        TextPreset.TITLE,
        TextPreset.MINIMAL,
        TextPreset.IMPACT,
        TextPreset.LOWER_THIRD
    )

    fun next(current: TextPreset): TextPreset {
        val index = order.indexOf(current)
        return if (index < 0 || index == order.lastIndex) order.first() else order[index + 1]
    }

    fun style(preset: TextPreset): TextStyle = when (preset) {
        TextPreset.CUSTOM, TextPreset.CLASSIC -> TextStyle(
            fontSizeSp = 32f,
            textColorArgb = 0xFFFFFFFF.toInt(),
            backgroundColorArgb = 0x00000000,
            bold = true,
            alignment = TextAlignment.CENTER,
            fontFamily = TextFontFamily.SANS
        )
        TextPreset.TITLE -> TextStyle(
            fontSizeSp = 46f,
            textColorArgb = 0xFFFFFFFF.toInt(),
            backgroundColorArgb = 0x00000000,
            bold = true,
            alignment = TextAlignment.CENTER,
            fontFamily = TextFontFamily.ROUNDED,
            letterSpacingEm = 0.02f,
            shadowEnabled = true
        )
        TextPreset.MINIMAL -> TextStyle(
            fontSizeSp = 28f,
            textColorArgb = 0xFFFFFFFF.toInt(),
            backgroundColorArgb = 0x00000000,
            bold = false,
            alignment = TextAlignment.CENTER,
            fontFamily = TextFontFamily.SANS,
            letterSpacingEm = 0.01f
        )
        TextPreset.IMPACT -> TextStyle(
            fontSizeSp = 42f,
            textColorArgb = 0xFFFFFFFF.toInt(),
            backgroundColorArgb = 0xB3000000.toInt(),
            bold = true,
            alignment = TextAlignment.CENTER,
            fontFamily = TextFontFamily.SANS,
            shadowEnabled = true
        )
        TextPreset.LOWER_THIRD -> TextStyle(
            fontSizeSp = 25f,
            textColorArgb = 0xFFFFFFFF.toInt(),
            backgroundColorArgb = 0xC21B1730.toInt(),
            bold = true,
            alignment = TextAlignment.LEFT,
            fontFamily = TextFontFamily.SANS
        )
    }

    fun transform(preset: TextPreset, current: TextTransform): TextTransform = when (preset) {
        TextPreset.LOWER_THIRD -> current.copy(positionX = -0.34f, positionY = 0.58f, scale = 1f, rotationDegrees = 0f, opacity = 1f)
        TextPreset.TITLE -> current.copy(positionX = 0f, positionY = -0.48f, scale = 1f, rotationDegrees = 0f, opacity = 1f)
        else -> current.copy(scale = 1f, rotationDegrees = 0f, opacity = 1f)
    }
}
