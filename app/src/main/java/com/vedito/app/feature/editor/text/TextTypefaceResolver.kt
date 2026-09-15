package com.vedito.app.feature.editor.text

import android.graphics.Typeface
import com.vedito.app.core.model.TextFontFamily

object TextTypefaceResolver {
    fun resolve(family: TextFontFamily, bold: Boolean): Typeface {
        val name = when (family) {
            TextFontFamily.SANS -> "sans-serif"
            TextFontFamily.SERIF -> "serif"
            TextFontFamily.MONO -> "monospace"
            TextFontFamily.ROUNDED -> "sans-serif-rounded"
        }
        return Typeface.create(name, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
