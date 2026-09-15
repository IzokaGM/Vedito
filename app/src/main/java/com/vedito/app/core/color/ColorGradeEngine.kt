package com.vedito.app.core.color

import com.vedito.app.core.model.ColorGradeSpec
import com.vedito.app.core.model.ColorLutPreset
import com.vedito.app.core.model.ColorLutSpec
import com.vedito.app.core.model.HslAdjustSpec
import com.vedito.app.core.model.RgbCurveSpec
import com.vedito.app.core.model.ToneCurveSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Renderer-independent color pipeline shared by preview, software fallback and GLES export.
 *
 * Canonical order for Patch 25:
 * legacy matrix -> RGB curves -> HSL -> built-in LUT look.
 * Curves use five fixed x anchors (0, .25, .50, .75, 1) with deterministic linear interpolation.
 */
object ColorGradeEngine {
    const val MIN_EXPOSURE = -2f
    const val MAX_EXPOSURE = 2f
    const val MIN_ADJUST = -1f
    const val MAX_ADJUST = 1f
    const val MIN_HUE_DEGREES = -180f
    const val MAX_HUE_DEGREES = 180f

    private val CURVE_PRESETS = listOf(
        "Linear" to RgbCurveSpec(),
        "Soft S" to RgbCurveSpec(master = ToneCurveSpec(0f, 0.20f, 0.50f, 0.80f, 1f)),
        "Matte" to RgbCurveSpec(master = ToneCurveSpec(0.06f, 0.28f, 0.52f, 0.77f, 0.96f)),
        "Punch" to RgbCurveSpec(master = ToneCurveSpec(0f, 0.16f, 0.50f, 0.84f, 1f))
    )

    fun normalize(spec: ColorGradeSpec): ColorGradeSpec = spec.copy(
        exposure = spec.exposure.coerceIn(MIN_EXPOSURE, MAX_EXPOSURE),
        contrast = spec.contrast.coerceIn(MIN_ADJUST, MAX_ADJUST),
        saturation = spec.saturation.coerceIn(MIN_ADJUST, MAX_ADJUST),
        temperature = spec.temperature.coerceIn(MIN_ADJUST, MAX_ADJUST),
        tint = spec.tint.coerceIn(MIN_ADJUST, MAX_ADJUST),
        fade = spec.fade.coerceIn(0f, 1f),
        curves = normalize(spec.curves),
        hsl = normalize(spec.hsl),
        lut = spec.lut.copy(intensity = spec.lut.intensity.coerceIn(0f, 1f))
    )

    fun normalize(curves: RgbCurveSpec): RgbCurveSpec = curves.copy(
        master = normalize(curves.master),
        red = normalize(curves.red),
        green = normalize(curves.green),
        blue = normalize(curves.blue)
    )

    fun normalize(curve: ToneCurveSpec): ToneCurveSpec {
        val black = curve.black.coerceIn(0f, 1f)
        val shadows = curve.shadows.coerceIn(black, 1f)
        val midtones = curve.midtones.coerceIn(shadows, 1f)
        val highlights = curve.highlights.coerceIn(midtones, 1f)
        val white = curve.white.coerceIn(highlights, 1f)
        return ToneCurveSpec(black, shadows, midtones, highlights, white)
    }

    fun normalize(hsl: HslAdjustSpec): HslAdjustSpec = hsl.copy(
        hueDegrees = wrapHueDegrees(hsl.hueDegrees),
        saturation = hsl.saturation.coerceIn(MIN_ADJUST, MAX_ADJUST),
        luminance = hsl.luminance.coerceIn(MIN_ADJUST, MAX_ADJUST)
    )

    fun isNeutral(spec: ColorGradeSpec): Boolean {
        val safe = normalize(spec)
        return isBaseNeutral(safe) && !hasAdvancedAdjustments(safe)
    }

    fun isBaseNeutral(spec: ColorGradeSpec): Boolean {
        val safe = normalize(spec)
        return safe.exposure == 0f && safe.contrast == 0f && safe.saturation == 0f &&
            safe.temperature == 0f && safe.tint == 0f && safe.fade == 0f
    }

    fun hasAdvancedAdjustments(spec: ColorGradeSpec): Boolean {
        val safe = normalize(spec)
        return safe.curves != RgbCurveSpec() || safe.hsl != HslAdjustSpec() ||
            (safe.lut.preset != ColorLutPreset.NONE && safe.lut.intensity > 0f)
    }

    /** Android-compatible 4x5 color matrix, but contains no Android dependencies. */
    fun colorMatrix(spec: ColorGradeSpec): FloatArray {
        val safe = normalize(spec)
        val saturation = 1f + safe.saturation
        val invSat = 1f - saturation
        val rLum = 0.2126f
        val gLum = 0.7152f
        val bLum = 0.0722f

        val sat = arrayOf(
            floatArrayOf(invSat * rLum + saturation, invSat * gLum, invSat * bLum),
            floatArrayOf(invSat * rLum, invSat * gLum + saturation, invSat * bLum),
            floatArrayOf(invSat * rLum, invSat * gLum, invSat * bLum + saturation)
        )

        val exposureGain = 2.0.pow(safe.exposure.toDouble()).toFloat()
        val contrastGain = 1f + safe.contrast * 0.55f
        val fadeGain = 1f - safe.fade * 0.22f
        val baseGain = exposureGain * contrastGain * fadeGain

        val redGain = (1f + safe.temperature * 0.10f + safe.tint * 0.045f).coerceAtLeast(0.1f)
        val greenGain = (1f - safe.tint * 0.08f).coerceAtLeast(0.1f)
        val blueGain = (1f - safe.temperature * 0.10f + safe.tint * 0.045f).coerceAtLeast(0.1f)
        val channelGain = floatArrayOf(redGain, greenGain, blueGain)

        val contrastOffset = 128f * (1f - contrastGain)
        val fadeOffset = 255f * safe.fade * 0.075f
        val temperatureOffset = 255f * safe.temperature * 0.018f
        val tintOffset = 255f * safe.tint * 0.012f
        val offsets = floatArrayOf(
            contrastOffset + fadeOffset + temperatureOffset + tintOffset,
            contrastOffset + fadeOffset - tintOffset * 1.5f,
            contrastOffset + fadeOffset - temperatureOffset + tintOffset
        )

        val out = FloatArray(20)
        for (row in 0..2) {
            val rowGain = baseGain * channelGain[row]
            out[row * 5] = sat[row][0] * rowGain
            out[row * 5 + 1] = sat[row][1] * rowGain
            out[row * 5 + 2] = sat[row][2] * rowGain
            out[row * 5 + 3] = 0f
            out[row * 5 + 4] = offsets[row]
        }
        out[15] = 0f
        out[16] = 0f
        out[17] = 0f
        out[18] = 1f
        out[19] = 0f
        return out
    }

    fun curveValues(curve: ToneCurveSpec): FloatArray {
        val safe = normalize(curve)
        return floatArrayOf(safe.black, safe.shadows, safe.midtones, safe.highlights, safe.white)
    }

    fun lutCode(preset: ColorLutPreset): Int = when (preset) {
        ColorLutPreset.NONE -> 0
        ColorLutPreset.CINEMATIC -> 1
        ColorLutPreset.TEAL_ORANGE -> 2
        ColorLutPreset.FILM_FADE -> 3
        ColorLutPreset.CLEAN_POP -> 4
    }

    fun lutLabel(preset: ColorLutPreset): String = when (preset) {
        ColorLutPreset.NONE -> "None"
        ColorLutPreset.CINEMATIC -> "Cinema"
        ColorLutPreset.TEAL_ORANGE -> "Teal+Orange"
        ColorLutPreset.FILM_FADE -> "Film"
        ColorLutPreset.CLEAN_POP -> "Clean"
    }

    fun nextLutPreset(current: ColorLutPreset): ColorLutPreset {
        val values = ColorLutPreset.values()
        return values[(current.ordinal + 1) % values.size]
    }

    fun curvePresetLabel(curves: RgbCurveSpec): String = CURVE_PRESETS[nearestCurvePreset(curves)].first

    fun nextCurvePreset(curves: RgbCurveSpec): RgbCurveSpec {
        val index = nearestCurvePreset(curves)
        return CURVE_PRESETS[(index + 1) % CURVE_PRESETS.size].second
    }

    /** Prepared CPU transformer for the rare software export fallback path. */
    fun prepare(spec: ColorGradeSpec): PreparedColorGrade = PreparedColorGrade(normalize(spec))

    class PreparedColorGrade internal constructor(private val spec: ColorGradeSpec) {
        private val matrix = colorMatrix(spec)
        private val masterCurve = curveValues(spec.curves.master)
        private val redCurve = curveValues(spec.curves.red)
        private val greenCurve = curveValues(spec.curves.green)
        private val blueCurve = curveValues(spec.curves.blue)
        private val scratch = FloatArray(3)

        fun transformArgb(argb: Int): Int {
            val a = (argb ushr 24) and 0xFF
            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f

            var rr = matrixChannel(matrix, 0, r, g, b)
            var gg = matrixChannel(matrix, 1, r, g, b)
            var bb = matrixChannel(matrix, 2, r, g, b)

            rr = applyCurveValues(applyCurveValues(rr, masterCurve), redCurve)
            gg = applyCurveValues(applyCurveValues(gg, masterCurve), greenCurve)
            bb = applyCurveValues(applyCurveValues(bb, masterCurve), blueCurve)

            rgbToHsl(rr, gg, bb, scratch)
            val adjustedH = wrapUnitHue(scratch[0] + spec.hsl.hueDegrees / 360f)
            val adjustedS = (scratch[1] + spec.hsl.saturation).coerceIn(0f, 1f)
            val adjustedL = (scratch[2] + spec.hsl.luminance * 0.5f).coerceIn(0f, 1f)
            hslToRgb(adjustedH, adjustedS, adjustedL, scratch)
            rr = scratch[0]
            gg = scratch[1]
            bb = scratch[2]

            applyLut(rr, gg, bb, spec.lut, scratch)
            rr = scratch[0]
            gg = scratch[1]
            bb = scratch[2]

            return (a shl 24) or
                ((rr.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or
                ((gg.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or
                ((bb.coerceIn(0f, 1f) * 255f + 0.5f).toInt())
        }
    }

    private fun matrixChannel(matrix: FloatArray, row: Int, r: Float, g: Float, b: Float): Float {
        val i = row * 5
        return (
            matrix[i] * r + matrix[i + 1] * g + matrix[i + 2] * b +
                matrix[i + 4] / 255f
            ).coerceIn(0f, 1f)
    }

    private fun applyCurveValues(value: Float, y: FloatArray): Float {
        val x = value.coerceIn(0f, 1f) * 4f
        val segment = min(3, x.toInt())
        val t = (x - segment).coerceIn(0f, 1f)
        return (y[segment] + (y[segment + 1] - y[segment]) * t).coerceIn(0f, 1f)
    }

    private fun applyLut(r: Float, g: Float, b: Float, lut: ColorLutSpec, out: FloatArray) {
        val intensity = lut.intensity.coerceIn(0f, 1f)
        if (lut.preset == ColorLutPreset.NONE || intensity <= 0f) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        val luma = r * 0.299f + g * 0.587f + b * 0.114f
        var tr = r
        var tg = g
        var tb = b
        when (lut.preset) {
            ColorLutPreset.NONE -> Unit
            ColorLutPreset.CINEMATIC -> {
                tr = 1.06f * r + 0.01f * g - 0.02f * b - 0.005f
                tg = -0.01f * r + 1.01f * g
                tb = -0.03f * r + 0.02f * g + 1.07f * b + 0.01f
            }
            ColorLutPreset.TEAL_ORANGE -> {
                val shadow = 1f - luma
                val highlight = luma
                tr = r + 0.10f * highlight - 0.03f * shadow
                tg = g + 0.025f * shadow
                tb = b + 0.08f * shadow - 0.06f * highlight
            }
            ColorLutPreset.FILM_FADE -> {
                tr = r * 0.90f + 0.075f
                tg = g * 0.90f + 0.055f
                tb = b * 0.88f + 0.045f
            }
            ColorLutPreset.CLEAN_POP -> {
                val pr = (r - 0.5f) * 1.12f + 0.5f
                val pg = (g - 0.5f) * 1.12f + 0.5f
                val pb = (b - 0.5f) * 1.12f + 0.5f
                val popLuma = pr * 0.299f + pg * 0.587f + pb * 0.114f
                tr = popLuma + (pr - popLuma) * 1.08f
                tg = popLuma + (pg - popLuma) * 1.08f
                tb = popLuma + (pb - popLuma) * 1.08f
            }
        }
        out[0] = (r + (tr - r) * intensity).coerceIn(0f, 1f)
        out[1] = (g + (tg - g) * intensity).coerceIn(0f, 1f)
        out[2] = (b + (tb - b) * intensity).coerceIn(0f, 1f)
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) * 0.5f
        val delta = maxC - minC
        if (delta <= 0.00001f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = l
            return
        }
        val saturation = delta / max(0.00001f, 1f - abs(2f * l - 1f))
        val h = when (maxC) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } / 6f
        out[0] = wrapUnitHue(h)
        out[1] = saturation.coerceIn(0f, 1f)
        out[2] = l.coerceIn(0f, 1f)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = wrapUnitHue(h) * 6f
        val x = c * (1f - abs(hp % 2f - 1f))
        var r1 = 0f
        var g1 = 0f
        var b1 = 0f
        when {
            hp < 1f -> { r1 = c; g1 = x }
            hp < 2f -> { r1 = x; g1 = c }
            hp < 3f -> { g1 = c; b1 = x }
            hp < 4f -> { g1 = x; b1 = c }
            hp < 5f -> { r1 = x; b1 = c }
            else -> { r1 = c; b1 = x }
        }
        val m = l - c * 0.5f
        out[0] = r1 + m
        out[1] = g1 + m
        out[2] = b1 + m
    }

    private fun nearestCurvePreset(curves: RgbCurveSpec): Int {
        val safe = normalize(curves)
        return CURVE_PRESETS.indices.minByOrNull { index -> curveDistance(safe, CURVE_PRESETS[index].second) } ?: 0
    }

    private fun curveDistance(a: RgbCurveSpec, b: RgbCurveSpec): Float =
        toneDistance(a.master, b.master) + toneDistance(a.red, b.red) +
            toneDistance(a.green, b.green) + toneDistance(a.blue, b.blue)

    private fun toneDistance(a: ToneCurveSpec, b: ToneCurveSpec): Float {
        val aa = curveValues(a)
        val bb = curveValues(b)
        return aa.indices.sumOf { abs(aa[it] - bb[it]).toDouble() }.toFloat()
    }

    private fun wrapHueDegrees(value: Float): Float {
        var out = value
        while (out > MAX_HUE_DEGREES) out -= 360f
        while (out < MIN_HUE_DEGREES) out += 360f
        return out
    }

    private fun wrapUnitHue(value: Float): Float {
        var out = value % 1f
        if (out < 0f) out += 1f
        return out
    }
}
