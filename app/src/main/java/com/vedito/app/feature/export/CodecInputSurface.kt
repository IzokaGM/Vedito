package com.vedito.app.feature.export

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.vedito.app.core.export.GpuEffectSlot
import com.vedito.app.core.export.GpuPostProcessPlan
import com.vedito.app.core.export.GpuSourceGraphPlan
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.VideoEffectKind
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Patch 23 EGL/GLES encoder bridge.
 *
 * The main decoded source can now stay as a source texture while crop/fit/transform, chroma,
 * color grading, opacity and mask are evaluated in the encoder shader. CPU base-plane upload remains
 * as a correctness fallback for unsupported post stacks (currently Grain) and decoder failures.
 */
class CodecInputSurface(
    private val surface: Surface
) : Closeable {
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var sourceTexture = 0
    private var overlayTexture = 0
    private var sourceTextureWidth = 0
    private var sourceTextureHeight = 0
    private var overlayTextureWidth = 0
    private var overlayTextureHeight = 0

    private var positionHandle = 0
    private var texCoordHandle = 0
    private var sourceSamplerHandle = 0
    private var overlaySamplerHandle = 0
    private var frameSizeHandle = 0

    private var sourceGraphEnabledHandle = 0
    private var cropRectHandle = 0
    private var contentSizeHandle = 0
    private var sourceCenterHandle = 0
    private var rotationHandle = 0
    private var flipHandle = 0
    private var opacityHandle = 0
    private var chromaEnabledHandle = 0
    private var chromaKeyHandle = 0
    private var chromaToleranceHandle = 0
    private var chromaSoftnessHandle = 0
    private var chromaSpillHandle = 0
    private val colorRowHandles = IntArray(4)
    private var colorBiasHandle = 0
    private val curveAHandles = IntArray(4)
    private val curveBHandles = IntArray(4)
    private var hslHueHandle = 0
    private var hslSaturationHandle = 0
    private var hslLuminanceHandle = 0
    private var lutCodeHandle = 0
    private var lutIntensityHandle = 0
    private var maskShapeHandle = 0
    private var maskCenterHandle = 0
    private var maskSizeHandle = 0
    private var maskFeatherHandle = 0
    private var maskInvertedHandle = 0
    private var backgroundHandle = 0

    private val effectKindHandles = IntArray(EFFECT_SLOTS)
    private val effectIntensityHandles = IntArray(EFFECT_SLOTS)
    private var transitionKindHandle = 0
    private var transitionProgressHandle = 0
    private var transitionStrengthHandle = 0

    private val vertexBuffer: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
    )
    private val texBuffer: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    )

    init {
        setupEgl()
        setupGl()
    }

    fun draw(frame: HybridComposedFrame, presentationTimeNs: Long) {
        val source = if (frame.usesGpuSourceGraph) requireNotNull(frame.sourcePlane) else frame.basePlane
        val graph = if (frame.usesGpuSourceGraph) frame.sourceGraph else GpuSourceGraphPlan.disabled()
        drawInternal(
            sourceBitmap = source,
            overlayBitmap = frame.overlayPlane,
            outputWidth = frame.basePlane.width,
            outputHeight = frame.basePlane.height,
            sourceGraph = graph,
            postProcess = frame.gpuPostProcess,
            presentationTimeNs = presentationTimeNs
        )
    }

    fun draw(
        baseBitmap: Bitmap,
        overlayBitmap: Bitmap,
        postProcess: GpuPostProcessPlan,
        presentationTimeNs: Long
    ) {
        drawInternal(
            sourceBitmap = baseBitmap,
            overlayBitmap = overlayBitmap,
            outputWidth = baseBitmap.width,
            outputHeight = baseBitmap.height,
            sourceGraph = GpuSourceGraphPlan.disabled(),
            postProcess = postProcess,
            presentationTimeNs = presentationTimeNs
        )
    }

    private fun drawInternal(
        sourceBitmap: Bitmap,
        overlayBitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int,
        sourceGraph: GpuSourceGraphPlan,
        postProcess: GpuPostProcessPlan,
        presentationTimeNs: Long
    ) {
        require(outputWidth == overlayBitmap.width && outputHeight == overlayBitmap.height) {
            "Export overlay plane must match output dimensions"
        }
        makeCurrent()
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        uploadBitmap(
            texture = sourceTexture,
            textureUnit = GLES20.GL_TEXTURE0,
            bitmap = sourceBitmap,
            currentWidth = sourceTextureWidth,
            currentHeight = sourceTextureHeight
        ).also {
            sourceTextureWidth = it.first
            sourceTextureHeight = it.second
        }
        GLES20.glUniform1i(sourceSamplerHandle, 0)

        uploadBitmap(
            texture = overlayTexture,
            textureUnit = GLES20.GL_TEXTURE1,
            bitmap = overlayBitmap,
            currentWidth = overlayTextureWidth,
            currentHeight = overlayTextureHeight
        ).also {
            overlayTextureWidth = it.first
            overlayTextureHeight = it.second
        }
        GLES20.glUniform1i(overlaySamplerHandle, 1)
        GLES20.glUniform2f(frameSizeHandle, outputWidth.toFloat(), outputHeight.toFloat())
        applySourceUniforms(sourceGraph)
        applyPostUniforms(postProcess)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            error("eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    override fun close() {
        runCatching { makeCurrent() }
        if (sourceTexture != 0 || overlayTexture != 0) {
            GLES20.glDeleteTextures(2, intArrayOf(sourceTexture, overlayTexture), 0)
        }
        if (program != 0) GLES20.glDeleteProgram(program)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        surface.release()
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun uploadBitmap(
        texture: Int,
        textureUnit: Int,
        bitmap: Bitmap,
        currentWidth: Int,
        currentHeight: Int
    ): Pair<Int, Int> {
        GLES20.glActiveTexture(textureUnit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (currentWidth != bitmap.width || currentHeight != bitmap.height) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        return bitmap.width to bitmap.height
    }

    private fun applySourceUniforms(plan: GpuSourceGraphPlan) {
        GLES20.glUniform1f(sourceGraphEnabledHandle, if (plan.enabled) 1f else 0f)
        GLES20.glUniform4f(cropRectHandle, plan.cropLeft, plan.cropTop, plan.cropWidth, plan.cropHeight)
        GLES20.glUniform2f(contentSizeHandle, plan.contentWidthPx, plan.contentHeightPx)
        GLES20.glUniform2f(sourceCenterHandle, plan.centerXPx, plan.centerYPx)
        GLES20.glUniform1f(rotationHandle, plan.rotationDegrees)
        GLES20.glUniform2f(flipHandle, plan.flipX, plan.flipY)
        GLES20.glUniform1f(opacityHandle, plan.opacity)
        GLES20.glUniform1f(chromaEnabledHandle, if (plan.chromaEnabled) 1f else 0f)
        GLES20.glUniform3f(chromaKeyHandle, plan.chromaKeyR, plan.chromaKeyG, plan.chromaKeyB)
        GLES20.glUniform1f(chromaToleranceHandle, plan.chromaTolerance)
        GLES20.glUniform1f(chromaSoftnessHandle, plan.chromaSoftness)
        GLES20.glUniform1f(chromaSpillHandle, plan.chromaSpill)

        val m = plan.colorMatrix
        if (m.size >= 20) {
            GLES20.glUniform4f(colorRowHandles[0], m[0], m[1], m[2], m[3])
            GLES20.glUniform4f(colorRowHandles[1], m[5], m[6], m[7], m[8])
            GLES20.glUniform4f(colorRowHandles[2], m[10], m[11], m[12], m[13])
            GLES20.glUniform4f(colorRowHandles[3], m[15], m[16], m[17], m[18])
            GLES20.glUniform4f(colorBiasHandle, m[4], m[9], m[14], m[19])
        }
        setCurveUniform(0, plan.masterCurve)
        setCurveUniform(1, plan.redCurve)
        setCurveUniform(2, plan.greenCurve)
        setCurveUniform(3, plan.blueCurve)
        GLES20.glUniform1f(hslHueHandle, plan.hslHueDegrees)
        GLES20.glUniform1f(hslSaturationHandle, plan.hslSaturation)
        GLES20.glUniform1f(hslLuminanceHandle, plan.hslLuminance)
        GLES20.glUniform1f(lutCodeHandle, plan.lutCode.toFloat())
        GLES20.glUniform1f(lutIntensityHandle, plan.lutIntensity.coerceIn(0f, 1f))

        GLES20.glUniform1f(maskShapeHandle, plan.maskShapeCode.toFloat())
        GLES20.glUniform2f(maskCenterHandle, plan.maskCenterX, plan.maskCenterY)
        GLES20.glUniform2f(maskSizeHandle, plan.maskWidth, plan.maskHeight)
        GLES20.glUniform1f(maskFeatherHandle, plan.maskFeather)
        GLES20.glUniform1f(maskInvertedHandle, if (plan.maskInverted) 1f else 0f)
        GLES20.glUniform3f(backgroundHandle, plan.backgroundR, plan.backgroundG, plan.backgroundB)
    }


    private fun setCurveUniform(index: Int, values: FloatArray) {
        val v = if (values.size >= 5) values else floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        GLES20.glUniform4f(curveAHandles[index], v[0], v[1], v[2], v[3])
        GLES20.glUniform1f(curveBHandles[index], v[4])
    }

    private fun applyPostUniforms(planInput: GpuPostProcessPlan) {
        val plan = if (planInput.gpuEligible) planInput else GpuPostProcessPlan.NEUTRAL
        for (index in 0 until EFFECT_SLOTS) {
            val slot = plan.effects.getOrNull(index)
            GLES20.glUniform1f(effectKindHandles[index], effectCode(slot))
            GLES20.glUniform1f(effectIntensityHandles[index], slot?.intensity?.coerceIn(0f, 1f) ?: 0f)
        }
        GLES20.glUniform1f(transitionKindHandle, transitionCode(plan.transitionKind))
        GLES20.glUniform1f(transitionProgressHandle, plan.transitionProgress.coerceIn(0f, 1f))
        GLES20.glUniform1f(transitionStrengthHandle, plan.transitionStrength.coerceIn(0f, 1f))
    }

    private fun effectCode(slot: GpuEffectSlot?): Float = when (slot?.kind) {
        null -> 0f
        VideoEffectKind.WARM -> 1f
        VideoEffectKind.COOL -> 2f
        VideoEffectKind.DREAM -> 3f
        VideoEffectKind.VIGNETTE -> 4f
        VideoEffectKind.GRAIN -> 0f
    }

    private fun transitionCode(kind: TransitionKind): Float = when (kind) {
        TransitionKind.NONE -> 0f
        TransitionKind.FADE_BLACK -> 1f
        TransitionKind.FLASH_WHITE -> 2f
        TransitionKind.WIPE -> 3f
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "Unable to initialize EGL" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, configs.size, count, 0) && count[0] > 0) {
            "Unable to find recordable EGL config"
        }
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        val surfaceAttributes = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttributes, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
        makeCurrent()
    }

    private fun setupGl() {
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        sourceSamplerHandle = GLES20.glGetUniformLocation(program, "uSourceTexture")
        overlaySamplerHandle = GLES20.glGetUniformLocation(program, "uOverlayTexture")
        frameSizeHandle = GLES20.glGetUniformLocation(program, "uFrameSize")

        sourceGraphEnabledHandle = GLES20.glGetUniformLocation(program, "uSourceGraphEnabled")
        cropRectHandle = GLES20.glGetUniformLocation(program, "uCropRect")
        contentSizeHandle = GLES20.glGetUniformLocation(program, "uContentSize")
        sourceCenterHandle = GLES20.glGetUniformLocation(program, "uSourceCenter")
        rotationHandle = GLES20.glGetUniformLocation(program, "uRotationDegrees")
        flipHandle = GLES20.glGetUniformLocation(program, "uFlip")
        opacityHandle = GLES20.glGetUniformLocation(program, "uOpacity")
        chromaEnabledHandle = GLES20.glGetUniformLocation(program, "uChromaEnabled")
        chromaKeyHandle = GLES20.glGetUniformLocation(program, "uChromaKey")
        chromaToleranceHandle = GLES20.glGetUniformLocation(program, "uChromaTolerance")
        chromaSoftnessHandle = GLES20.glGetUniformLocation(program, "uChromaSoftness")
        chromaSpillHandle = GLES20.glGetUniformLocation(program, "uChromaSpill")
        for (index in 0..3) colorRowHandles[index] = GLES20.glGetUniformLocation(program, "uColorRow$index")
        colorBiasHandle = GLES20.glGetUniformLocation(program, "uColorBias")
        val curvePrefixes = arrayOf("Master", "Red", "Green", "Blue")
        for (index in curvePrefixes.indices) {
            curveAHandles[index] = GLES20.glGetUniformLocation(program, "u${curvePrefixes[index]}CurveA")
            curveBHandles[index] = GLES20.glGetUniformLocation(program, "u${curvePrefixes[index]}CurveB")
        }
        hslHueHandle = GLES20.glGetUniformLocation(program, "uHslHueDegrees")
        hslSaturationHandle = GLES20.glGetUniformLocation(program, "uHslSaturation")
        hslLuminanceHandle = GLES20.glGetUniformLocation(program, "uHslLuminance")
        lutCodeHandle = GLES20.glGetUniformLocation(program, "uLutCode")
        lutIntensityHandle = GLES20.glGetUniformLocation(program, "uLutIntensity")
        maskShapeHandle = GLES20.glGetUniformLocation(program, "uMaskShape")
        maskCenterHandle = GLES20.glGetUniformLocation(program, "uMaskCenter")
        maskSizeHandle = GLES20.glGetUniformLocation(program, "uMaskSize")
        maskFeatherHandle = GLES20.glGetUniformLocation(program, "uMaskFeather")
        maskInvertedHandle = GLES20.glGetUniformLocation(program, "uMaskInverted")
        backgroundHandle = GLES20.glGetUniformLocation(program, "uBackground")

        transitionKindHandle = GLES20.glGetUniformLocation(program, "uTransitionKind")
        transitionProgressHandle = GLES20.glGetUniformLocation(program, "uTransitionProgress")
        transitionStrengthHandle = GLES20.glGetUniformLocation(program, "uTransitionStrength")
        for (index in 0 until EFFECT_SLOTS) {
            effectKindHandles[index] = GLES20.glGetUniformLocation(program, "uEffectKind$index")
            effectIntensityHandles[index] = GLES20.glGetUniformLocation(program, "uEffectIntensity$index")
        }
        check(positionHandle >= 0 && texCoordHandle >= 0 && sourceSamplerHandle >= 0 && overlaySamplerHandle >= 0) {
            "Invalid GL shader handles"
        }

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        sourceTexture = textures[0]
        overlayTexture = textures[1]
        configureTexture(sourceTexture)
        configureTexture(overlayTexture)
    }

    private fun configureTexture(texture: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        check(result != 0) { "Unable to create GL program" }
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            error("GL program link failed: $log")
        }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create GL shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GL shader compile failed: $log")
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
        .allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); position(0) }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EFFECT_SLOTS = 4
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uSourceTexture;
            uniform sampler2D uOverlayTexture;
            uniform vec2 uFrameSize;

            uniform float uSourceGraphEnabled;
            uniform vec4 uCropRect;
            uniform vec2 uContentSize;
            uniform vec2 uSourceCenter;
            uniform float uRotationDegrees;
            uniform vec2 uFlip;
            uniform float uOpacity;
            uniform float uChromaEnabled;
            uniform vec3 uChromaKey;
            uniform float uChromaTolerance;
            uniform float uChromaSoftness;
            uniform float uChromaSpill;
            uniform vec4 uColorRow0;
            uniform vec4 uColorRow1;
            uniform vec4 uColorRow2;
            uniform vec4 uColorRow3;
            uniform vec4 uColorBias;
            uniform vec4 uMasterCurveA;
            uniform float uMasterCurveB;
            uniform vec4 uRedCurveA;
            uniform float uRedCurveB;
            uniform vec4 uGreenCurveA;
            uniform float uGreenCurveB;
            uniform vec4 uBlueCurveA;
            uniform float uBlueCurveB;
            uniform float uHslHueDegrees;
            uniform float uHslSaturation;
            uniform float uHslLuminance;
            uniform float uLutCode;
            uniform float uLutIntensity;
            uniform float uMaskShape;
            uniform vec2 uMaskCenter;
            uniform vec2 uMaskSize;
            uniform float uMaskFeather;
            uniform float uMaskInverted;
            uniform vec3 uBackground;

            uniform float uEffectKind0;
            uniform float uEffectKind1;
            uniform float uEffectKind2;
            uniform float uEffectKind3;
            uniform float uEffectIntensity0;
            uniform float uEffectIntensity1;
            uniform float uEffectIntensity2;
            uniform float uEffectIntensity3;
            uniform float uTransitionKind;
            uniform float uTransitionProgress;
            uniform float uTransitionStrength;
            varying vec2 vTexCoord;

            vec4 colorMatrix(vec4 color) {
                return vec4(
                    dot(uColorRow0, color) + uColorBias.r,
                    dot(uColorRow1, color) + uColorBias.g,
                    dot(uColorRow2, color) + uColorBias.b,
                    dot(uColorRow3, color) + uColorBias.a
                );
            }

            float curveValue(float value, vec4 a, float b) {
                float x = clamp(value, 0.0, 1.0) * 4.0;
                if (x < 1.0) return mix(a.x, a.y, x);
                if (x < 2.0) return mix(a.y, a.z, x - 1.0);
                if (x < 3.0) return mix(a.z, a.w, x - 2.0);
                return mix(a.w, b, x - 3.0);
            }

            vec3 applyCurves(vec3 color) {
                color.r = curveValue(curveValue(color.r, uMasterCurveA, uMasterCurveB), uRedCurveA, uRedCurveB);
                color.g = curveValue(curveValue(color.g, uMasterCurveA, uMasterCurveB), uGreenCurveA, uGreenCurveB);
                color.b = curveValue(curveValue(color.b, uMasterCurveA, uMasterCurveB), uBlueCurveA, uBlueCurveB);
                return clamp(color, 0.0, 1.0);
            }

            vec3 rgbToHsl(vec3 c) {
                float maxC = max(c.r, max(c.g, c.b));
                float minC = min(c.r, min(c.g, c.b));
                float l = (maxC + minC) * 0.5;
                float d = maxC - minC;
                if (d <= 0.00001) return vec3(0.0, 0.0, l);
                float s = d / max(0.00001, 1.0 - abs(2.0 * l - 1.0));
                float h;
                if (maxC == c.r) h = mod((c.g - c.b) / d, 6.0);
                else if (maxC == c.g) h = (c.b - c.r) / d + 2.0;
                else h = (c.r - c.g) / d + 4.0;
                h /= 6.0;
                if (h < 0.0) h += 1.0;
                return vec3(h, clamp(s, 0.0, 1.0), clamp(l, 0.0, 1.0));
            }

            float hueToRgb(float p, float q, float t) {
                t = mod(t, 1.0);
                if (t < 0.0) t += 1.0;
                if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
                if (t < 0.5) return q;
                if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
                return p;
            }

            vec3 hslToRgb(vec3 hsl) {
                float h = mod(hsl.x, 1.0);
                if (h < 0.0) h += 1.0;
                float s = clamp(hsl.y, 0.0, 1.0);
                float l = clamp(hsl.z, 0.0, 1.0);
                if (s <= 0.00001) return vec3(l);
                float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
                float p = 2.0 * l - q;
                return vec3(
                    hueToRgb(p, q, h + 1.0 / 3.0),
                    hueToRgb(p, q, h),
                    hueToRgb(p, q, h - 1.0 / 3.0)
                );
            }

            vec3 applyHsl(vec3 color) {
                vec3 hsl = rgbToHsl(color);
                hsl.x += uHslHueDegrees / 360.0;
                hsl.y = clamp(hsl.y + uHslSaturation, 0.0, 1.0);
                hsl.z = clamp(hsl.z + uHslLuminance * 0.5, 0.0, 1.0);
                return clamp(hslToRgb(hsl), 0.0, 1.0);
            }

            vec3 applyLut(vec3 color) {
                float intensity = clamp(uLutIntensity, 0.0, 1.0);
                if (uLutCode < 0.5 || intensity <= 0.0) return color;
                vec3 target = color;
                float luma = dot(color, vec3(0.299, 0.587, 0.114));
                if (uLutCode < 1.5) {
                    target = vec3(
                        1.06 * color.r + 0.01 * color.g - 0.02 * color.b - 0.005,
                        -0.01 * color.r + 1.01 * color.g,
                        -0.03 * color.r + 0.02 * color.g + 1.07 * color.b + 0.01
                    );
                } else if (uLutCode < 2.5) {
                    float shadow = 1.0 - luma;
                    float highlight = luma;
                    target = vec3(
                        color.r + 0.10 * highlight - 0.03 * shadow,
                        color.g + 0.025 * shadow,
                        color.b + 0.08 * shadow - 0.06 * highlight
                    );
                } else if (uLutCode < 3.5) {
                    target = vec3(
                        color.r * 0.90 + 0.075,
                        color.g * 0.90 + 0.055,
                        color.b * 0.88 + 0.045
                    );
                } else if (uLutCode < 4.5) {
                    vec3 pop = (color - vec3(0.5)) * 1.12 + vec3(0.5);
                    float popLuma = dot(pop, vec3(0.299, 0.587, 0.114));
                    target = vec3(popLuma) + (pop - vec3(popLuma)) * 1.08;
                }
                return clamp(mix(color, target, intensity), 0.0, 1.0);
            }

            vec3 advancedColor(vec3 color) {
                return applyLut(applyHsl(applyCurves(color)));
            }

            vec4 sourceGraph(vec2 uv) {
                if (uSourceGraphEnabled < 0.5) return texture2D(uSourceTexture, uv);

                vec2 p = uv * uFrameSize - uSourceCenter;
                float angle = radians(uRotationDegrees);
                float c = cos(angle);
                float s = sin(angle);
                // Inverse Android Canvas rotation in y-down output coordinates.
                vec2 q = vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
                q /= max(uContentSize, vec2(1.0));
                q /= uFlip;
                vec2 localUv = q + vec2(0.5);
                if (localUv.x < 0.0 || localUv.x > 1.0 || localUv.y < 0.0 || localUv.y > 1.0) {
                    return vec4(uBackground, 1.0);
                }

                vec2 sampleUv = uCropRect.xy + localUv * uCropRect.zw;
                vec4 color = texture2D(uSourceTexture, sampleUv);
                if (uChromaEnabled > 0.5) {
                    float distanceToKey = distance(color.rgb, uChromaKey);
                    float alphaFactor = smoothstep(
                        uChromaTolerance,
                        uChromaTolerance + max(uChromaSoftness, 0.001),
                        distanceToKey
                    );
                    float proximity = 1.0 - smoothstep(
                        uChromaTolerance,
                        uChromaTolerance + 0.25,
                        distanceToKey
                    );
                    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                    float spillMix = clamp(proximity * uChromaSpill, 0.0, 1.0);
                    color.rgb = mix(color.rgb, vec3(luma), spillMix);
                    color.a *= alphaFactor;
                }
                color = colorMatrix(color);
                color = clamp(color, 0.0, 1.0);
                color.rgb = advancedColor(color.rgb);
                color.a *= clamp(uOpacity, 0.0, 1.0);
                vec3 rgb = mix(uBackground, color.rgb, color.a);
                return vec4(rgb, 1.0);
            }

            vec4 applyEffect(vec4 color, float kind, float intensity, vec2 uv) {
                if (kind < 0.5 || intensity <= 0.0) return color;
                if (kind < 1.5) {
                    vec3 tint = vec3(1.0, 0.5098, 0.2157);
                    color.rgb = mix(color.rgb, tint, 0.20 * intensity);
                } else if (kind < 2.5) {
                    vec3 tint = vec3(0.2745, 0.5686, 1.0);
                    color.rgb = mix(color.rgb, tint, 0.18 * intensity);
                } else if (kind < 3.5) {
                    float t = clamp((uv.x + uv.y) * 0.5, 0.0, 1.0);
                    vec3 startColor = vec3(1.0, 0.8235, 1.0);
                    vec3 endColor = vec3(0.4706, 0.3529, 1.0);
                    vec3 tint = mix(startColor, endColor, t);
                    float alpha = mix(80.0 / 255.0, 65.0 / 255.0, t) * intensity;
                    color.rgb = mix(color.rgb, tint, alpha);
                } else if (kind < 4.5) {
                    vec2 px = (uv - vec2(0.5)) * uFrameSize;
                    float radius = max(uFrameSize.x, uFrameSize.y) * 0.72;
                    float radial = length(px) / max(radius, 1.0);
                    float shade = smoothstep(0.52, 1.0, radial) * (210.0 / 255.0) * intensity;
                    color.rgb = mix(color.rgb, vec3(0.0), clamp(shade, 0.0, 1.0));
                }
                return color;
            }

            vec4 applyTransition(vec4 color, vec2 uv) {
                float strength = clamp(uTransitionStrength, 0.0, 1.0);
                if (uTransitionKind < 0.5 || strength <= 0.0) return color;
                if (uTransitionKind < 1.5) {
                    color.rgb = mix(color.rgb, vec3(0.0), strength);
                } else if (uTransitionKind < 2.5) {
                    color.rgb = mix(color.rgb, vec3(1.0), strength * 0.95);
                } else if (uTransitionKind < 3.5) {
                    float center = clamp(uTransitionProgress, 0.0, 1.0);
                    float band = 0.16;
                    float pulse = max(0.0, 1.0 - abs(uv.x - center) / band);
                    float shade = pulse * (190.0 / 255.0) * strength;
                    color.rgb = mix(color.rgb, vec3(0.0), shade);
                }
                return color;
            }

            float maskVisibility(vec2 uv) {
                if (uSourceGraphEnabled < 0.5 || uMaskShape < 0.5) return 1.0;
                vec2 halfSize = max(uMaskSize * 0.5, vec2(0.0001));
                vec2 normalized = (uv - uMaskCenter) / halfSize;
                float feather = clamp(uMaskFeather, 0.0, 0.30);
                float edge;
                if (uMaskShape < 1.5) {
                    edge = max(abs(normalized.x), abs(normalized.y));
                } else {
                    edge = length(normalized);
                }
                float inside = 1.0 - smoothstep(max(0.0, 1.0 - feather), 1.0 + feather, edge);
                if (feather <= 0.0001) inside = edge <= 1.0 ? 1.0 : 0.0;
                return uMaskInverted > 0.5 ? 1.0 - inside : inside;
            }

            void main() {
                vec4 base = sourceGraph(vTexCoord);
                base = applyEffect(base, uEffectKind0, uEffectIntensity0, vTexCoord);
                base = applyEffect(base, uEffectKind1, uEffectIntensity1, vTexCoord);
                base = applyEffect(base, uEffectKind2, uEffectIntensity2, vTexCoord);
                base = applyEffect(base, uEffectKind3, uEffectIntensity3, vTexCoord);
                base = applyTransition(base, vTexCoord);
                float visible = maskVisibility(vTexCoord);
                if (uSourceGraphEnabled > 0.5 && uMaskShape > 0.5) {
                    base.rgb = mix(uBackground, base.rgb, visible);
                }
                vec4 overlay = texture2D(uOverlayTexture, vTexCoord);
                // Android Canvas overlay bitmaps are uploaded premultiplied: RGB already contains alpha.
                vec3 rgb = overlay.rgb + base.rgb * (1.0 - overlay.a);
                gl_FragColor = vec4(rgb, 1.0);
            }
        """
    }
}
