package dev.hushyari.service

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * MediaProjection-based screen capture service for obtaining game screenshots
 * without root or accessibility constraints.
 *
 * Creates a VirtualDisplay backed by an ImageReader to capture frames.
 * Must be started with an Intent containing the MediaProjection data
 * obtained via the Activity Result API.
 *
 * **Mechanics:**
 * - 4x-game-agent Layer 1: Screenshot capture for visual perception (pixel checks, OCR, templates).
 * - PokeClaw: VirtualDisplay + ImageReader pattern for continuous screen capture.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    @Volatile
    private var latestBitmap: Bitmap? = null

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("ScreenCaptureThread")
        captureThread?.start()
        captureHandler = Handler(captureThread!!.looper)
        Timber.d("ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == -1 || data == null) {
            Timber.w("ScreenCaptureService: missing MediaProjection data")
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.w("MediaProjection stopped externally")
                cleanupCapture()
            }
        }, captureHandler)

        startCapture()
        Timber.d("ScreenCaptureService started: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanupCapture()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        Timber.d("ScreenCaptureService destroyed")
        super.onDestroy()
    }

    /**
     * Capture the current screen as a [Bitmap].
     *
     * @return The most recent screenshot, or null if capture is not active.
     */
    fun capture(): Bitmap? {
        try {
            if (imageReader == null) return null

            val image = imageReader?.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                latestBitmap = cropped
                return cropped
            }

            latestBitmap = bitmap
            return bitmap
        } catch (e: Exception) {
            Timber.w(e, "Screen capture failed")
            return latestBitmap
        }
    }

    /**
     * Start the VirtualDisplay and ImageReader for continuous capture.
     */
    private fun startCapture() {
        val mp = mediaProjection ?: return

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            android.graphics.PixelFormat.RGBA_8888, 2,
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            // Image is read on-demand via capture() method
        }, captureHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "HushYariCapture",
            screenWidth, screenHeight, screenDensity,
            VIRTUAL_DISPLAY_FLAGS,
            imageReader?.surface,
            null,
            captureHandler,
        )

        Timber.d("VirtualDisplay created for screen capture")
    }

    /**
     * Release all capture resources.
     */
    private fun cleanupCapture() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        latestBitmap?.recycle()
        latestBitmap = null

        Timber.d("Screen capture resources released")
    }
}
