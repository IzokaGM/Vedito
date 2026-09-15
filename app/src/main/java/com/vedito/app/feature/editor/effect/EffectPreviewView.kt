package com.vedito.app.feature.editor.effect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.vedito.app.core.effect.EffectComposition
import com.vedito.app.core.model.EffectClip
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.VideoEffectKind
import java.util.Random

class EffectPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var activeEffects: List<EffectClip> = emptyList()
    private var transition: EffectComposition.TransitionFrame? = null
    private var positionMs: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun render(
        effects: List<EffectClip>,
        transitionFrame: EffectComposition.TransitionFrame?,
        timelinePositionMs: Int
    ) {
        activeEffects = effects
        transition = transitionFrame
        positionMs = timelinePositionMs
        visibility = if (effects.isEmpty() && transitionFrame == null) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        activeEffects.forEach { drawEffect(canvas, it) }
        transition?.let { drawTransition(canvas, it) }
    }

    private fun drawEffect(canvas: Canvas, effect: EffectClip) {
        val intensity = effect.intensity.coerceIn(0f, 1f)
        when (effect.kind) {
            VideoEffectKind.WARM -> overlay(canvas, Color.rgb(255, 130, 55), 0.20f * intensity)
            VideoEffectKind.COOL -> overlay(canvas, Color.rgb(70, 145, 255), 0.18f * intensity)
            VideoEffectKind.DREAM -> {
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.argb((80 * intensity).toInt(), 255, 210, 255), Color.argb((65 * intensity).toInt(), 120, 90, 255)),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
            }
            VideoEffectKind.VIGNETTE -> {
                val radius = maxOf(width, height) * 0.72f
                paint.shader = RadialGradient(
                    width / 2f,
                    height / 2f,
                    radius,
                    intArrayOf(Color.TRANSPARENT, Color.argb((210 * intensity).toInt(), 0, 0, 0)),
                    floatArrayOf(0.52f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
            }
            VideoEffectKind.GRAIN -> {
                val random = Random((positionMs / 60L) + effect.id.hashCode())
                paint.strokeWidth = 1f
                repeat(96) {
                    val alpha = (20 + random.nextInt(55)) * intensity
                    paint.color = if (random.nextBoolean()) Color.argb(alpha.toInt(), 255, 255, 255) else Color.argb(alpha.toInt(), 0, 0, 0)
                    canvas.drawPoint(random.nextFloat() * width, random.nextFloat() * height, paint)
                }
            }
        }
    }

    private fun drawTransition(canvas: Canvas, frame: EffectComposition.TransitionFrame) {
        when (frame.kind) {
            TransitionKind.NONE -> Unit
            TransitionKind.FADE_BLACK -> overlay(canvas, Color.BLACK, frame.strength)
            TransitionKind.FLASH_WHITE -> overlay(canvas, Color.WHITE, frame.strength * 0.95f)
            TransitionKind.WIPE -> {
                val progress = frame.progress.coerceIn(0f, 1f)
                val band = width * 0.16f
                val center = width * progress
                paint.shader = LinearGradient(
                    center - band,
                    0f,
                    center + band,
                    0f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((190 * frame.strength).toInt(), 0, 0, 0), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
            }
        }
    }

    private fun overlay(canvas: Canvas, color: Int, alpha: Float) {
        paint.shader = null
        paint.color = color
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.alpha = 255
    }
}
