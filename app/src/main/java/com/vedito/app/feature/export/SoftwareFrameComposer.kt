package com.vedito.app.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vedito.app.core.caption.CaptionComposition
import com.vedito.app.core.caption.CaptionStyleCatalog
import com.vedito.app.core.color.ColorGradeEngine
import com.vedito.app.core.compositor.FrameCompositionBuilder
import com.vedito.app.core.effect.EffectComposition
import com.vedito.app.core.export.ExportPlan
import com.vedito.app.core.export.FrameAccessMode
import com.vedito.app.core.export.FrameAccessPlanner
import com.vedito.app.core.export.GpuPostProcessPlanner
import com.vedito.app.core.model.CaptionSegment
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.MaskShape
import com.vedito.app.core.model.MaskSpec
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayMediaType
import com.vedito.app.core.model.Project
import com.vedito.app.core.model.TextAlignment
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.model.TextFontFamily
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.VideoEffectKind
import com.vedito.app.core.overlay.OverlayComposition
import com.vedito.app.core.text.TextComposition
import com.vedito.app.core.text.TextMotion
import com.vedito.app.core.visual.MaskChromaComposition
import com.vedito.app.core.visual.VisualTransformMath
import java.io.Closeable
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Patch 21 hybrid export compositor. CPU handles canonical source/color/chroma/mask/text layers,
 * while supported timed post effects/transitions are deferred to the encoder EGL/GLES surface.
 * It still consumes the same renderer-independent project/composition state as preview.
 */
class SoftwareFrameComposer(
    private val context: Context,
    private val project: Project,
    private val plan: ExportPlan,
    cancelCheck: () -> Unit = {}
) : Closeable {
    private val framePool = VideoFrameSourcePool(context, plan, cancelCheck)
    private val imageCache = linkedMapOf<String, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val baseBitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
    private val baseCanvas = Canvas(baseBitmap)
    private val overlayBitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
    private val overlayCanvas = Canvas(overlayBitmap)
    private val dpScale = plan.width / 360f

    /**
     * Composes reusable base/overlay planes. Timed effects/transitions are moved to the encoder GPU
     * when the effect stack is supported; unsupported stacks are rendered on the CPU before return.
     */
    fun composeHybrid(timelinePositionMs: Int): HybridComposedFrame {
        val position = timelinePositionMs.coerceIn(0, plan.durationMs.coerceAtLeast(0))
        baseCanvas.drawColor(project.canvasSettings.background.argb)
        overlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val frame = FrameCompositionBuilder.build(project.clips, project.effectClips, position)
        var gpuPlan = if (frame != null) {
            GpuPostProcessPlanner.plan(frame.activeEffects, frame.transition)
        } else {
            com.vedito.app.core.export.GpuPostProcessPlan.NEUTRAL
        }

        if (frame != null) {
            val clip = project.clips.getOrNull(frame.clipIndex)
            val asset = clip?.let { project.asset(it.assetId) }
            if (clip != null && asset != null) {
                val lease = decodeVideoFrame(
                    asset = asset,
                    sourcePositionMs = frame.sourcePositionMs,
                    accessMode = FrameAccessPlanner.forPlaybackMode(clip.timing.mode)
                )
                lease?.let { decoded ->
                    val bitmap = decoded.bitmap
                    val processed = if (frame.chromaKey.enabled) applyChroma(bitmap, frame.chromaKey) else bitmap
                    val framePaint = Paint(paint).apply {
                        alpha = (frame.transform.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
                        if (!ColorGradeEngine.isNeutral(frame.colorGrade)) {
                            colorFilter = ColorMatrixColorFilter(ColorMatrix(ColorGradeEngine.colorMatrix(frame.colorGrade)))
                        }
                    }
                    drawVisualBitmap(baseCanvas, processed, frame.transform, framePaint, 0.42f)
                    if (processed !== bitmap) processed.recycle()
                    if (decoded.recycleAfterUse && !bitmap.isRecycled) bitmap.recycle()
                }

                if (!gpuPlan.gpuEligible) {
                    drawEffects(baseCanvas, frame.activeEffects, frame.transition, position)
                    gpuPlan = com.vedito.app.core.export.GpuPostProcessPlan.NEUTRAL
                }
                // Keep Patch 19/20 visual ordering: mask occlusion sits after effect/transition and before overlays.
                drawMaskOcclusion(overlayCanvas, frame.mask, project.canvasSettings.background.argb)
            }
        }

        OverlayComposition.activeLayers(position, project.overlayAssets, project.overlayClips).forEach { layer ->
            val lease = when (layer.asset.type) {
                OverlayMediaType.IMAGE -> decodeOverlayImage(layer.asset)?.let { VideoFrameSourcePool.FrameLease(it, false) }
                OverlayMediaType.VIDEO -> decodeOverlayVideoFrame(layer.asset, layer.sourcePositionMs)
            } ?: return@forEach
            val layerPaint = Paint(paint).apply {
                alpha = (layer.transform.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
            }
            drawVisualBitmap(overlayCanvas, lease.bitmap, layer.transform, layerPaint, 0.5f)
            if (lease.recycleAfterUse && !lease.bitmap.isRecycled) lease.bitmap.recycle()
        }

        TextComposition.activeLayers(position, project.textClips).forEach { layer ->
            drawTextClip(overlayCanvas, layer.clip, layer.localTimelineMs)
        }
        CaptionComposition.activeLayers(position, project.captionSegments).forEachIndexed { index, layer ->
            drawCaption(overlayCanvas, layer.segment, layer.localTimelineMs, index)
        }
        return HybridComposedFrame(baseBitmap, overlayBitmap, gpuPlan)
    }

    fun performanceSnapshot(): VideoFrameSourcePool.Snapshot = framePool.snapshot()

    override fun close() {
        runCatching { framePool.close() }
        imageCache.values.forEach { if (!it.isRecycled) it.recycle() }
        imageCache.clear()
        if (!baseBitmap.isRecycled) baseBitmap.recycle()
        if (!overlayBitmap.isRecycled) overlayBitmap.recycle()
    }

    private fun decodeVideoFrame(
        asset: MediaAsset,
        sourcePositionMs: Int,
        accessMode: FrameAccessMode
    ): VideoFrameSourcePool.FrameLease? = framePool.frame(
        key = "main:${asset.id}",
        uriString = asset.uri,
        sourcePositionMs = sourcePositionMs,
        sourceWidth = asset.width,
        sourceHeight = asset.height,
        nominalFrameRate = asset.frameRate,
        mode = accessMode
    )

    private fun decodeOverlayVideoFrame(asset: OverlayAsset, sourcePositionMs: Int): VideoFrameSourcePool.FrameLease? =
        framePool.frame(
            key = "overlay:${asset.id}",
            uriString = asset.uri,
            sourcePositionMs = sourcePositionMs,
            sourceWidth = asset.width,
            sourceHeight = asset.height,
            nominalFrameRate = 30f,
            mode = FrameAccessMode.STREAMING
        )

    private fun decodeOverlayImage(asset: OverlayAsset): Bitmap? = imageCache[asset.id] ?: run {
        val decoded = runCatching {
            context.contentResolver.openInputStream(Uri.parse(asset.uri))?.use { stream -> BitmapFactory.decodeStream(stream) }
        }.getOrNull() ?: return null
        val scaled = scaleStaticBitmap(decoded)
        if (scaled !== decoded) decoded.recycle()
        imageCache[asset.id] = scaled
        scaled
    }

    private fun scaleStaticBitmap(bitmap: Bitmap): Bitmap {
        val target = FrameAccessPlanner.decodeTarget(bitmap.width, bitmap.height, plan.width, plan.height, maxDimension = 2_560)
        if (target.first == bitmap.width && target.second == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, target.first, target.second, true)
    }

    private fun drawVisualBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        transformInput: ClipTransform,
        layerPaint: Paint,
        positionMultiplier: Float
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val transform = VisualTransformMath.normalize(transformInput)
        val crop = VisualTransformMath.cropWindow(transform)
        val left = (bitmap.width * crop.left).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * crop.top).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * (1f - crop.right)).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * (1f - crop.bottom)).roundToInt().coerceIn(top + 1, bitmap.height)
        val src = Rect(left, top, right, bottom)
        val croppedW = src.width().coerceAtLeast(1).toFloat()
        val croppedH = src.height().coerceAtLeast(1).toFloat()
        val swapsAxes = transform.rotationDegrees.roundToInt() % 180 != 0
        val fittedW = if (swapsAxes) croppedH else croppedW
        val fittedH = if (swapsAxes) croppedW else croppedH
        val fitScale = when (transform.fitMode) {
            ClipFitMode.FIT -> min(plan.width / fittedW, plan.height / fittedH)
            ClipFitMode.FILL -> max(plan.width / fittedW, plan.height / fittedH)
        }
        val baseW = croppedW * fitScale
        val baseH = croppedH * fitScale
        val centerX = plan.width / 2f + transform.positionX * plan.width * positionMultiplier
        val centerY = plan.height / 2f + transform.positionY * plan.height * positionMultiplier

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(transform.rotationDegrees)
        canvas.scale(
            transform.scale * if (transform.flipHorizontal) -1f else 1f,
            transform.scale * if (transform.flipVertical) -1f else 1f
        )
        canvas.drawBitmap(bitmap, src, RectF(-baseW / 2f, -baseH / 2f, baseW / 2f, baseH / 2f), layerPaint)
        canvas.restore()
    }

    private fun drawMaskOcclusion(canvas: Canvas, maskInput: MaskSpec, backgroundColor: Int) {
        val mask = MaskChromaComposition.normalize(maskInput)
        if (mask.shape == MaskShape.NONE) return
        val w = mask.width * plan.width
        val h = mask.height * plan.height
        val cx = mask.centerX * plan.width
        val cy = mask.centerY * plan.height
        val bounds = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }

        path.reset()
        if (mask.inverted) {
            addMaskShape(path, mask.shape, bounds)
            canvas.drawPath(path, maskPaint)
        } else {
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, plan.width.toFloat(), plan.height.toFloat(), Path.Direction.CW)
            addMaskShape(path, mask.shape, bounds)
            canvas.drawPath(path, maskPaint)
        }

        val featherPx = mask.feather * min(plan.width, plan.height)
        if (featherPx > 1f) {
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = backgroundColor
                style = Paint.Style.STROKE
                strokeWidth = featherPx.coerceAtLeast(1f)
                alpha = 105
                maskFilter = BlurMaskFilter(featherPx * 0.45f, BlurMaskFilter.Blur.NORMAL)
            }
            path.reset()
            addMaskShape(path, mask.shape, bounds)
            canvas.drawPath(path, edge)
        }
    }

    private fun addMaskShape(target: Path, shape: MaskShape, bounds: RectF) {
        when (shape) {
            MaskShape.NONE -> Unit
            MaskShape.RECTANGLE -> target.addRoundRect(bounds, 8f * dpScale, 8f * dpScale, Path.Direction.CW)
            MaskShape.ELLIPSE -> target.addOval(bounds, Path.Direction.CW)
        }
    }

    private fun applyChroma(source: Bitmap, specInput: com.vedito.app.core.model.ChromaKeySpec): Bitmap {
        val spec = MaskChromaComposition.normalize(specInput)
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = mutable.width
        val height = mutable.height
        val pixels = IntArray(width * height)
        mutable.getPixels(pixels, 0, width, 0, 0, width, height)
        val keyR = Color.red(spec.keyColorArgb) / 255f
        val keyG = Color.green(spec.keyColorArgb) / 255f
        val keyB = Color.blue(spec.keyColorArgb) / 255f
        val edge0 = spec.tolerance
        val edge1 = spec.tolerance + max(spec.softness, 0.001f)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = Color.red(color) / 255f
            val g = Color.green(color) / 255f
            val b = Color.blue(color) / 255f
            val distance = sqrt((r - keyR) * (r - keyR) + (g - keyG) * (g - keyG) + (b - keyB) * (b - keyB))
            val alphaFactor = smoothstep(edge0, edge1, distance)
            val proximity = 1f - smoothstep(spec.tolerance, spec.tolerance + 0.25f, distance)
            val luma = r * 0.299f + g * 0.587f + b * 0.114f
            val spillMix = (proximity * spec.spill).coerceIn(0f, 1f)
            val nr = lerp(r, luma, spillMix)
            val ng = lerp(g, luma, spillMix)
            val nb = lerp(b, luma, spillMix)
            val na = (Color.alpha(color) * alphaFactor).roundToInt().coerceIn(0, 255)
            pixels[index] = Color.argb(
                na,
                (nr * 255f).roundToInt().coerceIn(0, 255),
                (ng * 255f).roundToInt().coerceIn(0, 255),
                (nb * 255f).roundToInt().coerceIn(0, 255)
            )
        }
        mutable.setPixels(pixels, 0, width, 0, 0, width, height)
        return mutable
    }

    private fun drawEffects(
        canvas: Canvas,
        effects: List<com.vedito.app.core.model.EffectClip>,
        transition: EffectComposition.TransitionFrame?,
        timelinePositionMs: Int
    ) {
        effects.forEach { effect ->
            val intensity = effect.intensity.coerceIn(0f, 1f)
            when (effect.kind) {
                VideoEffectKind.WARM -> overlay(canvas, Color.rgb(255, 130, 55), 0.20f * intensity)
                VideoEffectKind.COOL -> overlay(canvas, Color.rgb(70, 145, 255), 0.18f * intensity)
                VideoEffectKind.DREAM -> {
                    effectPaint.shader = LinearGradient(
                        0f, 0f, plan.width.toFloat(), plan.height.toFloat(),
                        intArrayOf(Color.argb((80 * intensity).toInt(), 255, 210, 255), Color.argb((65 * intensity).toInt(), 120, 90, 255)),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, plan.width.toFloat(), plan.height.toFloat(), effectPaint)
                    effectPaint.shader = null
                }
                VideoEffectKind.VIGNETTE -> {
                    val radius = max(plan.width, plan.height) * 0.72f
                    effectPaint.shader = RadialGradient(
                        plan.width / 2f,
                        plan.height / 2f,
                        radius,
                        intArrayOf(Color.TRANSPARENT, Color.argb((210 * intensity).toInt(), 0, 0, 0)),
                        floatArrayOf(0.52f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, plan.width.toFloat(), plan.height.toFloat(), effectPaint)
                    effectPaint.shader = null
                }
                VideoEffectKind.GRAIN -> {
                    val random = Random((timelinePositionMs / 60L) + effect.id.hashCode())
                    effectPaint.strokeWidth = max(1f, dpScale * 0.5f)
                    repeat((96 * dpScale.coerceAtMost(2f)).roundToInt()) {
                        val alpha = ((20 + random.nextInt(55)) * intensity).roundToInt()
                        effectPaint.color = if (random.nextBoolean()) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)
                        canvas.drawPoint(random.nextFloat() * plan.width, random.nextFloat() * plan.height, effectPaint)
                    }
                }
            }
        }

        transition?.let { frame ->
            when (frame.kind) {
                TransitionKind.NONE -> Unit
                TransitionKind.FADE_BLACK -> overlay(canvas, Color.BLACK, frame.strength)
                TransitionKind.FLASH_WHITE -> overlay(canvas, Color.WHITE, frame.strength * 0.95f)
                TransitionKind.WIPE -> {
                    val band = plan.width * 0.16f
                    val center = plan.width * frame.progress.coerceIn(0f, 1f)
                    effectPaint.shader = LinearGradient(
                        center - band, 0f, center + band, 0f,
                        intArrayOf(Color.TRANSPARENT, Color.argb((190 * frame.strength).toInt(), 0, 0, 0), Color.TRANSPARENT),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, plan.width.toFloat(), plan.height.toFloat(), effectPaint)
                    effectPaint.shader = null
                }
            }
        }
    }

    private fun overlay(canvas: Canvas, color: Int, alpha: Float) {
        effectPaint.shader = null
        effectPaint.color = color
        effectPaint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt()
        canvas.drawRect(0f, 0f, plan.width.toFloat(), plan.height.toFloat(), effectPaint)
        effectPaint.alpha = 255
    }

    private fun drawTextClip(canvas: Canvas, clip: TextClip, localMs: Int) {
        val motion = TextMotion.frame(clip.animation, localMs, clip.durationMs)
        val style = clip.style
        val transform = clip.transform
        val maxWidth = (plan.width * 0.82f).roundToInt().coerceAtLeast(16)
        val padding = (8f * dpScale).roundToInt()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textColorArgb
            textSize = style.fontSizeSp * dpScale
            typeface = resolveTypeface(style.fontFamily, style.bold)
            letterSpacing = style.letterSpacingEm
            alpha = (255f * transform.opacity.coerceIn(0f, 1f) * motion.alphaMultiplier).roundToInt()
            if (style.shadowEnabled) setShadowLayer(2f * dpScale, 0f, dpScale, 0xCC000000.toInt())
        }
        val alignment = when (style.alignment) {
            TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = buildTextLayout(clip.text, textPaint, maxWidth - padding * 2, alignment)
        val contentWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) }?.roundToInt() ?: 1
        val boxWidth = (contentWidth + padding * 2).coerceIn(padding * 2 + 1, maxWidth)
        val boxHeight = layout.height + padding * 2
        val centerX = plan.width / 2f + transform.positionX * plan.width * 0.45f
        val centerY = plan.height / 2f + transform.positionY * plan.height * 0.45f + motion.translationYFraction * plan.height
        val scale = transform.scale.coerceIn(0.2f, 5f) * motion.scaleMultiplier

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(transform.rotationDegrees)
        canvas.scale(scale, scale)
        val left = -boxWidth / 2f
        val top = -boxHeight / 2f
        if (Color.alpha(style.backgroundColorArgb) > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.backgroundColorArgb
                alpha = (Color.alpha(style.backgroundColorArgb) * transform.opacity.coerceIn(0f, 1f) * motion.alphaMultiplier).roundToInt()
            }
            canvas.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), 6f * dpScale, 6f * dpScale, bg)
        }
        canvas.translate(left + padding, top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawCaption(canvas: Canvas, segment: CaptionSegment, localMs: Int, stackIndex: Int) {
        val visual = CaptionStyleCatalog.resolve(segment.preset)
        val motion = TextMotion.frame(segment.animation, localMs, segment.durationMs)
        val maxWidth = (plan.width * 0.86f).roundToInt().coerceAtLeast(16)
        val paddingX = (10f * dpScale).roundToInt()
        val paddingY = (5f * dpScale).roundToInt()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = visual.textColorArgb
            textSize = visual.fontSizeSp * dpScale
            typeface = resolveTypeface(segment.fontFamily, visual.bold)
            alpha = (255f * motion.alphaMultiplier).roundToInt()
            if (visual.shadowEnabled) setShadowLayer(2f * dpScale, 0f, dpScale, 0xDD000000.toInt())
        }
        val layout = buildTextLayout(segment.text, textPaint, maxWidth - paddingX * 2, Layout.Alignment.ALIGN_CENTER)
        val contentWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) }?.roundToInt() ?: 1
        val boxWidth = (contentWidth + paddingX * 2).coerceIn(paddingX * 2 + 1, maxWidth)
        val boxHeight = layout.height + paddingY * 2
        val bottom = (visual.bottomMarginDp * dpScale) + stackIndex.coerceAtMost(3) * (42f * dpScale)
        val centerX = plan.width / 2f
        val centerY = plan.height - bottom - boxHeight / 2f + motion.translationYFraction * plan.height

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(motion.scaleMultiplier, motion.scaleMultiplier)
        val left = -boxWidth / 2f
        val top = -boxHeight / 2f
        if (Color.alpha(visual.backgroundColorArgb) > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = visual.backgroundColorArgb
                alpha = (Color.alpha(visual.backgroundColorArgb) * motion.alphaMultiplier).roundToInt()
            }
            canvas.drawRoundRect(RectF(left, top, left + boxWidth, top + boxHeight), 6f * dpScale, 6f * dpScale, bg)
        }
        canvas.translate(left + paddingX, top + paddingY)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildTextLayout(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, safeWidth, alignment, 1f, 0f, false)
        }
    }

    private fun resolveTypeface(family: TextFontFamily, bold: Boolean): Typeface {
        val name = when (family) {
            TextFontFamily.SANS -> "sans-serif"
            TextFontFamily.SERIF -> "serif"
            TextFontFamily.MONO -> "monospace"
            TextFontFamily.ROUNDED -> "sans-serif-rounded"
        }
        return Typeface.create(name, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (abs(edge1 - edge0) < 0.000001f) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
