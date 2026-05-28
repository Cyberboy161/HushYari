package dev.hushyari.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screenshot capture using [MediaProjection] and [ImageReader] to acquire
 * device screen contents as [Bitmap] instances.
 *
 * Creates a [VirtualDisplay] bound to an [ImageReader] surface, then acquires
 * the latest frame on demand. Manages projection lifecycle across orientation
 * changes and display metric updates.
 *
 * **Mechanics:**
 * - PokeClaw: Robust projection lifecycle — handles disconnection, reconnection,
 *   and orientation changes gracefully.
 * - 4x-game-agent Layer 1: Screenshot capture feeds visual perception for
 *   [PixelClassifier] and [TemplateMatcher].
 */
@Singleton
class ScreenCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val MAX_IMAGES = 2
        private const val DEFAULT_JPEG_QUALITY = 80
        private const val VIRTUAL_DISPLAY_NAME = "HushYariScreenCapture"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionWidth: Int = 0
    private var projectionHeight: Int = 0
    private var projectionDensity: Int = 0
    private var isProjectionActive: Boolean = false

    private val backgroundHandler: Handler by lazy {
        val thread = HandlerThread("ScreenCapture")
        thread.start()
        Handler(thread.looper)
    }

    @Volatile
    private var latestImage: Image? = null

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        latestImage?.close()
        latestImage = image
    }

    // ------------------------------------------------------------------
    // Projection lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the media projection with the given [intent] result data
     * from the system projection permission dialog.
     *
     * Must be called after the user grants screen capture permission.
     */
    fun startProjection(resultCode: Int, data: android.content.Intent) {
        stopProjection()

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        projectionWidth = metrics.widthPixels
        projectionHeight = metrics.heightPixels
        projectionDensity = metrics.densityDpi

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(
            projectionWidth,
            projectionHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        ).apply {
            setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            projectionWidth,
            projectionHeight,
            projectionDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler,
        )

        isProjectionActive = virtualDisplay != null
        Timber.d("ScreenCapture: projection started ${projectionWidth}x${projectionHeight} @${projectionDensity}dpi")
    }

    /**
     * Stops the current media projection, releasing all associated resources.
     */
    fun stopProjection() {
        isProjectionActive = false

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        latestImage?.close()
        latestImage = null

        mediaProjection?.stop()
        mediaProjection = null

        Timber.d("ScreenCapture: projection stopped")
    }

    // ------------------------------------------------------------------
    // Frame capture
    // ------------------------------------------------------------------

    /**
     * Captures the latest available frame from the [VirtualDisplay] image queue.
     *
     * Returns null if the projection is not active or no frame is available.
     * Blocks briefly to acquire the latest image from the [ImageReader].
     *
     * **4x-game-agent Layer 1 mechanic:** Called during VISUAL and FULL capture modes
     * to feed [PixelClassifier], [TemplateMatcher], and [OcrEngine].
     */
    fun capture(): Bitmap? {
        if (!isProjectionActive) {
            Timber.w("ScreenCapture: projection not active")
            return null
        }

        val image = latestImage ?: run {
            Timber.w("ScreenCapture: no image available")
            return null
        }

        return try {
            imageToBitmap(image)
        } catch (e: Exception) {
            Timber.e(e, "ScreenCapture: failed to convert image to bitmap")
            null
        }
    }

    /**
     * Captures and immediately resizes to fit within [maxWidth] and [maxHeight]
     * while preserving aspect ratio. Useful for LLM uploads where full-resolution
     * screenshots waste tokens.
     */
    fun captureResized(maxWidth: Int = 720, maxHeight: Int = 1280, quality: Int = DEFAULT_JPEG_QUALITY): Bitmap? {
        val raw = capture() ?: return null
        return resize(raw, maxWidth, maxHeight)
    }

    /**
     * Captures and compresses to JPEG byte array for network/API transport.
     */
    fun captureToJpeg(quality: Int = DEFAULT_JPEG_QUALITY): ByteArray? {
        val bitmap = capture() ?: return null
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    /**
     * Resizes [bitmap] to fit within [maxWidth] x [maxHeight] preserving
     * aspect ratio. Returns the original if already within bounds.
     */
    fun resize(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        if (w <= maxWidth && h <= maxHeight) return bitmap

        val ratio = minOf(
            maxWidth.toFloat() / w.toFloat(),
            maxHeight.toFloat() / h.toFloat(),
        )

        val newWidth = (w * ratio).toInt()
        val newHeight = (h * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Compresses [bitmap] to JPEG [ByteArray] at the given quality level.
     */
    fun compressToJpeg(bitmap: Bitmap, quality: Int = DEFAULT_JPEG_QUALITY): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Returns the current projection dimensions.
     */
    fun getProjectionSize(): Pair<Int, Int> = projectionWidth to projectionHeight

    /**
     * Returns true if the projection is active and capturing frames.
     */
    fun isActive(): Boolean = isProjectionActive

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /**
     * Converts an [Image] (RGBA_8888 Y plane) into an ARGB_8888 [Bitmap].
     *
     * Supports both single-plane and multi-plane image layouts for compatibility
     * across Android versions and device OEMs.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer
        val pixelStride: Int
        val rowStride: Int
        val rowPadding: Int

        if (planes.size == 1) {
            buffer = planes[0].buffer
            pixelStride = planes[0].pixelStride
            rowStride = planes[0].rowStride
            rowPadding = rowStride - image.width * pixelStride
        } else {
            buffer = planes[0].buffer
            pixelStride = planes[0].pixelStride
            rowStride = planes[0].rowStride
            rowPadding = 0
        }

        val width = image.width
        val height = image.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        var offset = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixelOffset = offset + col * pixelStride
                val r = buffer.get(pixelOffset).toInt() and 0xFF
                val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                val b = buffer.get(pixelOffset + 2).toInt() and 0xFF
                val a = buffer.get(pixelOffset + 3).toInt() and 0xFF
                bitmap.setPixel(col, row, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
            offset += rowStride
        }

        return bitmap
    }

    /**
     * Returns the device display metrics for dimension queries.
     */
    fun getDisplayMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        return metrics
    }
}
