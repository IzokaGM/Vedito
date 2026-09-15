package com.vedito.app.feature.export

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.vedito.app.core.export.GpuEffectSlot
import com.vedito.app.core.export.GpuPostProcessPlan
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.VideoEffectKind
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Patch 21 EGL/GLES encoder bridge.
 * Reuses texture storage, applies supported timed post effects/transitions on GPU, then alpha-composites
 * the overlay plane before presenting the frame to MediaCodec.
 */
class CodecInputSurface(
    private val surface: Surface
) : Closeable {
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var baseTexture = 0
    private var overlayTexture = 0
    private var baseTextureWidth = 0
    private var baseTextureHeight = 0
    private var overlayTextureWidth = 0
    private var overlayTextureHeight = 0

    private var positionHandle = 0
    private var texCoordHandle = 0
    private var baseSamplerHandle = 0
    private var overlaySamplerHandle = 0
    private var frameSizeHandle = 0
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
        draw(frame.basePlane, frame.overlayPlane, frame.gpuPostProcess, presentationTimeNs)
    }

    fun draw(
        baseBitmap: Bitmap,
        overlayBitmap: Bitmap,
        postProcess: GpuPostProcessPlan,
        presentationTimeNs: Long
    ) {
        require(baseBitmap.width == overlayBitmap.width && baseBitmap.height == overlayBitmap.height) {
            "Export planes must have matching dimensions"
        }
        makeCurrent()
        GLES20.glViewport(0, 0, baseBitmap.width, baseBitmap.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        uploadBitmap(
            texture = baseTexture,
            textureUnit = GLES20.GL_TEXTURE0,
            bitmap = baseBitmap,
            currentWidth = baseTextureWidth,
            currentHeight = baseTextureHeight
        ).also {
            baseTextureWidth = it.first
            baseTextureHeight = it.second
        }
        GLES20.glUniform1i(baseSamplerHandle, 0)

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
        GLES20.glUniform2f(frameSizeHandle, baseBitmap.width.toFloat(), baseBitmap.height.toFloat())
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
        if (baseTexture != 0 || overlayTexture != 0) {
            GLES20.glDeleteTextures(2, intArrayOf(baseTexture, overlayTexture), 0)
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
        baseSamplerHandle = GLES20.glGetUniformLocation(program, "uBaseTexture")
        overlaySamplerHandle = GLES20.glGetUniformLocation(program, "uOverlayTexture")
        frameSizeHandle = GLES20.glGetUniformLocation(program, "uFrameSize")
        transitionKindHandle = GLES20.glGetUniformLocation(program, "uTransitionKind")
        transitionProgressHandle = GLES20.glGetUniformLocation(program, "uTransitionProgress")
        transitionStrengthHandle = GLES20.glGetUniformLocation(program, "uTransitionStrength")
        for (index in 0 until EFFECT_SLOTS) {
            effectKindHandles[index] = GLES20.glGetUniformLocation(program, "uEffectKind$index")
            effectIntensityHandles[index] = GLES20.glGetUniformLocation(program, "uEffectIntensity$index")
        }
        check(positionHandle >= 0 && texCoordHandle >= 0 && baseSamplerHandle >= 0 && overlaySamplerHandle >= 0) {
            "Invalid GL shader handles"
        }

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        baseTexture = textures[0]
        overlayTexture = textures[1]
        configureTexture(baseTexture)
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
            uniform sampler2D uBaseTexture;
            uniform sampler2D uOverlayTexture;
            uniform vec2 uFrameSize;
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

            void main() {
                vec4 base = texture2D(uBaseTexture, vTexCoord);
                base = applyEffect(base, uEffectKind0, uEffectIntensity0, vTexCoord);
                base = applyEffect(base, uEffectKind1, uEffectIntensity1, vTexCoord);
                base = applyEffect(base, uEffectKind2, uEffectIntensity2, vTexCoord);
                base = applyEffect(base, uEffectKind3, uEffectIntensity3, vTexCoord);
                base = applyTransition(base, vTexCoord);
                vec4 overlay = texture2D(uOverlayTexture, vTexCoord);
                // Android Canvas bitmaps are uploaded premultiplied: RGB already contains alpha.
                vec3 rgb = overlay.rgb + base.rgb * (1.0 - overlay.a);
                gl_FragColor = vec4(rgb, 1.0);
            }
        """
    }
}
