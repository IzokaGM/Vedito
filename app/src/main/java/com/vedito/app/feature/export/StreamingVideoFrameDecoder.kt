package com.vedito.app.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.vedito.app.core.export.FrameAccessPlanner
import java.io.Closeable

/**
 * Sequential MediaExtractor + MediaCodec decoder used by export.
 * Frames are rendered to a bounded YUV ImageReader and converted into one reusable Bitmap.
 * If a device cannot negotiate the surface path, callers fall back to random-access extraction.
 */
class StreamingVideoFrameDecoder(
    private val context: Context,
    private val uriString: String,
    requestedWidth: Int,
    requestedHeight: Int,
    private val nominalFrameRate: Float,
    private val cancelCheck: () -> Unit = {}
) : Closeable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private var selectedTrack = -1
    private var started = false
    private var inputEos = false
    private var outputEos = false
    private var lastTargetUs = -1L
    private var lastOutputUs = Long.MIN_VALUE
    private var reusableBitmap: Bitmap? = null
    private var pixelBuffer = IntArray(0)

    val width: Int
    val height: Int

    init {
        extractor.setDataSource(context, Uri.parse(uriString), null)
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) {
                selectedTrack = index
                format = candidate
                break
            }
        }
        require(selectedTrack >= 0 && format != null) { "No video track" }
        val trackFormat = requireNotNull(format)
        extractor.selectTrack(selectedTrack)
        val sourceWidth = trackFormat.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(2)
        val sourceHeight = trackFormat.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(2)
        val target = FrameAccessPlanner.decodeTarget(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            outputWidth = requestedWidth.coerceAtLeast(2),
            outputHeight = requestedHeight.coerceAtLeast(2)
        )
        width = target.first
        height = target.second

        val activeReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES)
        reader = activeReader
        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing video MIME")
        val activeCodec = MediaCodec.createDecoderByType(mime)
        codec = activeCodec
        activeCodec.configure(trackFormat, activeReader.surface, null, 0)
        activeCodec.start()
        started = true
    }

    /**
     * Returns a reusable Bitmap owned by this decoder. The caller must consume it before the next call
     * and must not recycle it.
     */
    fun frameAt(sourcePositionMs: Int): Bitmap? {
        cancelCheck()
        val targetUs = sourcePositionMs.coerceAtLeast(0).toLong() * 1_000L
        if (FrameAccessPlanner.shouldRestartStream(lastTargetUs, targetUs)) {
            seek(targetUs)
        }
        lastTargetUs = targetUs

        val reuseWindowUs = frameReuseWindowUs()
        if (lastOutputUs != Long.MIN_VALUE && reusableBitmap != null) {
            val delta = targetUs - lastOutputUs
            if (delta in -reuseWindowUs..reuseWindowUs) return reusableBitmap
        }

        val activeCodec = codec ?: return null
        var loops = 0
        while (loops++ < MAX_DECODE_LOOPS) {
            cancelCheck()
            feedInput(activeCodec)

            val info = MediaCodec.BufferInfo()
            val index = activeCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputEos && outputEos) return if (lastOutputUs != Long.MIN_VALUE) reusableBitmap else null
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val ptsUs = info.presentationTimeUs
                    val shouldRender = !eos && ptsUs + reuseWindowUs >= targetUs
                    activeCodec.releaseOutputBuffer(index, shouldRender)
                    if (eos) outputEos = true
                    if (shouldRender) {
                        val image = awaitImage() ?: continue
                        try {
                            convertImageIntoReusableBitmap(image)
                        } finally {
                            image.close()
                        }
                        lastOutputUs = ptsUs
                        return reusableBitmap
                    }
                    if (eos) return if (lastOutputUs != Long.MIN_VALUE) reusableBitmap else null
                }
            }
        }
        return if (lastOutputUs != Long.MIN_VALUE) reusableBitmap else null
    }

    override fun close() {
        if (started) runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { reader?.close() }
        runCatching { extractor.release() }
        reusableBitmap?.let { if (!it.isRecycled) it.recycle() }
        reusableBitmap = null
        pixelBuffer = IntArray(0)
        codec = null
        reader = null
        started = false
    }

    private fun seek(targetUs: Long) {
        cancelCheck()
        codec?.flush()
        drainImages()
        extractor.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputEos = false
        outputEos = false
        lastOutputUs = Long.MIN_VALUE
    }

    private fun feedInput(activeCodec: MediaCodec) {
        if (inputEos) return
        val inputIndex = activeCodec.dequeueInputBuffer(0L)
        if (inputIndex < 0) return
        val input = activeCodec.getInputBuffer(inputIndex) ?: return
        input.clear()
        val size = extractor.readSampleData(input, 0)
        if (size < 0) {
            activeCodec.queueInputBuffer(
                inputIndex,
                0,
                0,
                extractor.sampleTime.coerceAtLeast(0L),
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            inputEos = true
            return
        }
        val pts = extractor.sampleTime.coerceAtLeast(0L)
        val flags = extractor.sampleFlags
        activeCodec.queueInputBuffer(inputIndex, 0, size, pts, flags)
        extractor.advance()
    }

    private fun awaitImage(): Image? {
        val activeReader = reader ?: return null
        var attempts = 0
        while (attempts++ < IMAGE_WAIT_ATTEMPTS) {
            cancelCheck()
            activeReader.acquireLatestImage()?.let { return it }
            try {
                Thread.sleep(IMAGE_WAIT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancelCheck()
                return null
            }
        }
        return activeReader.acquireLatestImage()
    }

    private fun drainImages() {
        val activeReader = reader ?: return
        while (true) {
            val image = activeReader.acquireLatestImage() ?: break
            image.close()
        }
    }

    private fun convertImageIntoReusableBitmap(image: Image) {
        require(image.format == ImageFormat.YUV_420_888) { "Unsupported decoder image format ${image.format}" }
        val crop = image.cropRect
        val outW = crop.width().coerceAtLeast(1)
        val outH = crop.height().coerceAtLeast(1)
        val required = outW * outH
        if (pixelBuffer.size != required) pixelBuffer = IntArray(required)

        val planes = image.planes
        require(planes.size >= 3) { "YUV decoder returned too few planes" }
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()

        var out = 0
        for (row in 0 until outH) {
            val sourceY = crop.top + row
            val chromaY = sourceY / 2
            for (col in 0 until outW) {
                val sourceX = crop.left + col
                val chromaX = sourceX / 2
                val yIndex = yBase + sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride
                val uIndex = uBase + chromaY * uPlane.rowStride + chromaX * uPlane.pixelStride
                val vIndex = vBase + chromaY * vPlane.rowStride + chromaX * vPlane.pixelStride
                if (yIndex >= yBuffer.limit() || uIndex >= uBuffer.limit() || vIndex >= vBuffer.limit()) {
                    pixelBuffer[out++] = Color.BLACK
                    continue
                }
                val y = yBuffer.get(yIndex).toInt() and 0xFF
                val u = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vIndex).toInt() and 0xFF) - 128
                val c = (y - 16).coerceAtLeast(0)
                val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
                pixelBuffer[out++] = Color.rgb(r, g, b)
            }
        }

        val bitmap = reusableBitmap
        if (bitmap == null || bitmap.isRecycled || bitmap.width != outW || bitmap.height != outH) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            reusableBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        }
        reusableBitmap?.setPixels(pixelBuffer, 0, outW, 0, 0, outW, outH)
    }

    private fun frameReuseWindowUs(): Long {
        val fps = nominalFrameRate.takeIf { it.isFinite() && it > 1f } ?: 30f
        return (550_000f / fps).toLong().coerceIn(8_000L, 40_000L)
    }

    companion object {
        private const val MAX_IMAGES = 2
        private const val CODEC_TIMEOUT_US = 8_000L
        private const val MAX_DECODE_LOOPS = 480
        private const val IMAGE_WAIT_ATTEMPTS = 24
        private const val IMAGE_WAIT_MS = 1L
    }
}
