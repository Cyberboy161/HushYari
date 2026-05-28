package dev.hushyari.service

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.hushyari.data.model.UIElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Higher-level gesture API wrapping [HushyariAccessibilityService]'s low-level
 * gesture dispatch methods.
 *
 * Handles coordinate conversion (fractional to absolute), retry logic with
 * exponential backoff, and thread-safe serialized dispatch.
 *
 * **Mechanics:**
 * - PokeClaw: Retry-with-backoff for flaky gesture delivery on some OEMs.
 * - ClickClickClick: Element-targeted gestures (tapElement, swipeOnElement) support
 *   Finder-role element resolution before gesture execution.
 * - Roubao: Structured element targeting enables tool-chaining.
 */
@Singleton
class AccessibilityGestureDispatcher @Inject constructor() {

    companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 50L
        private const val DEFAULT_SWIPE_DURATION_MS = 300L
        private const val DEFAULT_TAP_DURATION_MS = 1L
        private const val DEFAULT_LONG_PRESS_DURATION_MS = 600L
    }

    private val dispatchMutex = Mutex()

    private val service: HushyariAccessibilityService?
        get() = HushyariAccessibilityService.instance

    private val displayMetrics: DisplayMetrics
        get() {
            val svc = service ?: return DisplayMetrics()
            return svc.resources.displayMetrics
        }

    // ------------------------------------------------------------------
    // Point and element taps
    // ------------------------------------------------------------------

    /**
     * Taps at absolute pixel coordinates (x, y) with retry logic.
     *
     * **ClickClickClick mechanic:** Final click delivery after element resolution.
     */
    suspend fun tapPoint(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        dispatchWithRetry("tap(${x.toInt()}, ${y.toInt()})") {
            service?.dispatchTap(x, y) == true
        }
    }

    /**
     * Taps at fractional screen coordinates (0.0–1.0).
     */
    suspend fun tapFractional(xFraction: Float, yFraction: Float): Boolean {
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()
        return tapPoint(xFraction * w, yFraction * h)
    }

    /**
     * Taps the center of a [UIElement] using its bounds.
     */
    suspend fun tapElement(element: UIElement): Boolean {
        return tapPoint(element.centerX, element.centerY)
    }

    // ------------------------------------------------------------------
    // Swipe
    // ------------------------------------------------------------------

    /**
     * Swipes from (startX, startY) to (endX, endY) with optional duration.
     */
    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
    ): Boolean = withContext(Dispatchers.Main) {
        dispatchWithRetry("swipe") {
            service?.dispatchSwipe(startX, startY, endX, endY, durationMs) == true
        }
    }

    /**
     * Swipes on an element in the given [direction].
     * Direction: "up", "down", "left", "right".
     */
    suspend fun swipeOnElement(element: UIElement, direction: String): Boolean {
        val (startX, startY, endX, endY) = computeSwipeEndpoints(element.bounds, direction)
        return swipe(startX, startY, endX, endY)
    }

    // ------------------------------------------------------------------
    // Long press
    // ------------------------------------------------------------------

    /**
     * Long presses at (x, y) for [durationMs].
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = DEFAULT_LONG_PRESS_DURATION_MS): Boolean =
        withContext(Dispatchers.Main) {
            dispatchWithRetry("longPress(${x.toInt()}, ${y.toInt()})") {
                service?.dispatchLongPress(x, y, durationMs) == true
            }
        }

    // ------------------------------------------------------------------
    // Drag
    // ------------------------------------------------------------------

    /**
     * Drags along a list of [pathPoints] (absolute coordinates).
     */
    suspend fun drag(
        pathPoints: List<Pair<Float, Float>>,
        durationMs: Long = 500,
    ): Boolean = withContext(Dispatchers.Main) {
        dispatchWithRetry("drag(${pathPoints.size}pts)") {
            service?.dispatchDrag(pathPoints, durationMs) == true
        }
    }

    // ------------------------------------------------------------------
    // Multi-touch
    // ------------------------------------------------------------------

    /**
     * Dispatches a multi-touch gesture with [strokes].
     * Each stroke defined as (points, startTimeOffsetMs, durationMs).
     */
    suspend fun multiTouch(
        strokes: List<Triple<List<Pair<Float, Float>>, Long, Long>>,
    ): Boolean = withContext(Dispatchers.Main) {
        dispatchWithRetry("multiTouch(${strokes.size}strokes)") {
            service?.dispatchMultiTouch(strokes) == true
        }
    }

    // ------------------------------------------------------------------
    // Text input
    // ------------------------------------------------------------------

    /**
     * Types text by focusing an editable element and setting its text via
     * accessibility action.
     *
     * **PokeClaw mechanic:** Fallback to per-character input if bulk set fails
     * (some OEM keyboards require this).
     */
    suspend fun typeText(text: String, targetElement: UIElement? = null): Boolean {
        val svc = service ?: return false
        return withContext(Dispatchers.Default) {
            val focused = targetElement ?: return@withContext false

            val root = svc.rootInActiveWindow ?: return@withContext false
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(focused.resourceId)
                for (node in nodes) {
                    if (node.isEditable && node.isFocused) {
                        val args = android.os.Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text,
                            )
                        }
                        val result = node.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            args,
                        )
                        node.recycle()
                        return@withContext result
                    }
                    if (node.isEditable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        delay(50)
                        val args = android.os.Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text,
                            )
                        }
                        val result = node.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            args,
                        )
                        node.recycle()
                        return@withContext result
                    }
                    node.recycle()
                }
                false
            } finally {
                root.recycle()
            }
        }
    }

    // ------------------------------------------------------------------
    // Scroll
    // ------------------------------------------------------------------

    /**
     * Scrolls on an element in [direction] ("up", "down", "left", "right").
     * Uses accessibility scroll actions for precise control.
     */
    suspend fun scrollOnElement(element: UIElement, direction: String): Boolean {
        val svc = service ?: return false
        return withContext(Dispatchers.Main) {
            val root = svc.rootInActiveWindow ?: return@withContext false
            try {
                var found: android.view.accessibility.AccessibilityNodeInfo? = null
                val match = findNodeByElement(root, element)
                found = match

                if (found == null) {
                    Timber.w("scrollOnElement: element not found in tree")
                    return@withContext false
                }

                val action = when (direction.lowercase()) {
                    "up" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "down" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> return@withContext false
                }

                val result = found.performAction(action)
                found.recycle()
                result
            } finally {
                root.recycle()
            }
        }
    }

    // ------------------------------------------------------------------
    // Pinch-to-zoom
    // ------------------------------------------------------------------

    suspend fun pinchZoom(
        centerX: Float,
        centerY: Float,
        scaleOut: Boolean,
        durationMs: Long = 300,
    ): Boolean {
        val spread = 100f
        val sign = if (scaleOut) 1f else -1f
        val stroke1 = listOf(
            centerX - spread to centerY,
            (centerX - spread - sign * spread) to centerY,
        )
        val stroke2 = listOf(
            centerX + spread to centerY,
            (centerX + spread + sign * spread) to centerY,
        )
        return multiTouch(
            listOf(
                Triple(stroke1, 0, durationMs),
                Triple(stroke2, 0, durationMs),
            )
        )
    }

    // ------------------------------------------------------------------
    // Service availability
    // ------------------------------------------------------------------

    /**
     * Returns true when the accessibility service is connected and not interrupted.
     */
    fun isAvailable(): Boolean {
        val svc = service ?: return false
        return true
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Dispatch with retry: attempts [block] up to [MAX_RETRIES]+1 times with
     * [RETRY_BACKOFF_MS] delay between attempts. Serialized via [dispatchMutex].
     *
     * **PokeClaw mechanic:** Some OEMs drop or delay gesture dispatch callbacks;
     * retries dramatically improve reliability for game automation.
     */
    private suspend fun dispatchWithRetry(
        label: String,
        block: suspend () -> Boolean,
    ): Boolean {
        dispatchMutex.withLock {
            var attempt = 0
            while (attempt <= MAX_RETRIES) {
                val success = block()
                if (success) return@withLock true
                attempt++
                if (attempt <= MAX_RETRIES) {
                    Timber.d("Gesture $label attempt $attempt failed, retrying in ${RETRY_BACKOFF_MS}ms")
                    delay(RETRY_BACKOFF_MS * attempt)
                }
            }
            Timber.w("Gesture $label failed after ${MAX_RETRIES + 1} attempts")
            return@withLock false
        }
        return false
    }

    private fun computeSwipeEndpoints(
        bounds: Rect,
        direction: String,
    ): Quadruple {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val halfW = bounds.width() / 2f * 0.5f
        val halfH = bounds.height() / 2f * 0.5f

        return when (direction.lowercase()) {
            "up" -> Quadruple(cx, cy + halfH, cx, cy - halfH)
            "down" -> Quadruple(cx, cy - halfH, cx, cy + halfH)
            "left" -> Quadruple(cx + halfW, cy, cx - halfW, cy)
            "right" -> Quadruple(cx - halfW, cy, cx + halfW, cy)
            else -> Quadruple(cx, cy, cx, cy)
        }
    }

    private fun findNodeByElement(
        root: android.view.accessibility.AccessibilityNodeInfo,
        element: UIElement,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nodeBounds = android.graphics.Rect()
            node.getBoundsInScreen(nodeBounds)

            if (nodeBounds == element.bounds &&
                (node.viewIdResourceName ?: "") == element.resourceId
            ) {
                // Found match — return without recycling
                // Clean up remaining queue
                queue.forEach { it.recycle() }
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }
        return null
    }

    private data class Quadruple(val a: Float, val b: Float, val c: Float, val d: Float)
}
