package com.vedito.app.core.caption

import com.vedito.app.core.model.CaptionPreset

data class CaptionVisualStyle(
    val fontSizeSp: Float,
    val textColorArgb: Int,
    val backgroundColorArgb: Int,
    val bold: Boolean,
    val shadowEnabled: Boolean,
    val bottomMarginDp: Int
)

object CaptionStyleCatalog {
    fun resolve(preset: CaptionPreset): CaptionVisualStyle = when (preset) {
        CaptionPreset.BOXED -> CaptionVisualStyle(24f, 0xFFFFFFFF.toInt(), 0xA6000000.toInt(), true, false, 20)
        CaptionPreset.CLEAN -> CaptionVisualStyle(25f, 0xFFFFFFFF.toInt(), 0x00000000, true, true, 20)
        CaptionPreset.LARGE -> CaptionVisualStyle(31f, 0xFFFFFFFF.toInt(), 0x88000000.toInt(), true, true, 28)
        CaptionPreset.YELLOW -> CaptionVisualStyle(26f, 0xFFFFE66D.toInt(), 0xB3000000.toInt(), true, true, 20)
        CaptionPreset.SOFT -> CaptionVisualStyle(24f, 0xFFFFFFFF.toInt(), 0x66211D2E.toInt(), false, true, 22)
    }
}
