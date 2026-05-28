package dev.hushyari.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.UIElement
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import javax.inject.Inject

/**
 * Core accessibility service for capturing screen state and dispatching gestures.
 *
 * Monitors window state/content changes, parses the accessibility tree into structured
 * [ScreenState] emissions, and provides low-level gesture dispatch methods used by
 * higher-level gesture APIs.
 *
 * **Mechanics:**
 * - PokeClaw: Recursive tree parsing with depth limit and node recycling safety.
 * - Roubao: Structured [UIElement] extraction enabling targeted tool interactions.
 * - 4x-game-agent Layer 1: Fast UI tree capture as primary perception input.
 */
@AndroidEntryPoint
class HushyariAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HushyariAccessibilityService? = null
            private set
    }

    @Inject
    lateinit var treeParser: AccessibilityTreeParser

    private val _screenStateFlow = MutableSharedFlow<ScreenState>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Hot flow of parsed screen states emitted on relevant accessibility events. */
    val screenStateFlow: SharedFlow<ScreenState> = _screenStateFlow

    @Volatile
    private var interrupted: Boolean = false
    private var lastPackageName: String = ""
    private var lastActivityName: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        interrupted = false
        Timber.d("HushyariAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (interrupted) return
        if (event == null) return

        val eventType = event.eventType

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            event.windowChanges
        }

        val relevant = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> true
            else -> false
        }

        if (!relevant) return

        val root = rootInActiveWindow ?: return

        try {
            val screenState = parseScreenState(root, event)
            _screenStateFlow.tryEmit(screenState)

            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                @Suppress("DEPRECATION")
                lastPackageName = event.packageName?.toString() ?: ""
                lastActivityName = event.className?.toString() ?: ""
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse screen state from event type 0x%x", eventType)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        interrupted = true
        Timber.w("HushyariAccessibilityService interrupted")
    }

    override fun onDestroy() {
        interrupted = true
        instance = null
        Timber.d("HushyariAccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------------------
    // Screen parsing
    // ---------------------------------------------------------------------------

    /**
     * Parses the root [AccessibilityNodeInfo] into a [ScreenState] using the tree parser.
     * Extracts package/activity name, recursively walks the node tree, and captures
     * display metrics for coordinate translation.
     *
     * **PokeClaw mechanic:** Recursive tree walk with depth limit 50 + node recycling safety.
     */
    private fun parseScreenState(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent,
    ): ScreenState {
        val elements = extractElements(root)

        @Suppress("DEPRECATION")
        val pkg = event.packageName?.toString() ?: lastPackageName
        @Suppress("DEPRECATION")
        val activity = event.className?.toString() ?: lastActivityName

        val dm = resources.displayMetrics
        val wm = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm?.currentWindowMetrics?.bounds
        } else {
            val display = wm?.defaultDisplay
            val out = android.graphics.Point()
            @Suppress("DEPRECATION")
            display?.getRealSize(out)
            Rect(0, 0, out.x, out.y)
        } ?: Rect(0, 0, dm.widthPixels, dm.heightPixels)

        return ScreenState(
            packageName = pkg,
            activityName = activity,
            windowTitle = root.windowId.toString(),
            elements = elements,
            timestamp = System.currentTimeMillis(),
            screenWidth = bounds.width(),
            screenHeight = bounds.height(),
        )
    }

    /**
     * Recursively extracts [UIElement] entries from the accessibility node tree.
     *
     * **PokeClaw mechanic:** Unicode normalization fallback path — if direct text
     * extraction misses, a normalized tree walk is attempted by [AccessibilityTreeParser].
     * Depth limited to 50 to prevent stack overflow on deeply-nested UIs.
     */
    private fun extractElements(root: AccessibilityNodeInfo, maxDepth: Int = 50): List<UIElement> {
        val elements = mutableListOf<UIElement>()
        var drawingOrder = 0
        extractElementsRecursive(root, 0, maxDepth, elements, drawingOrder)
        return elements
    }

    private fun extractElementsRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        elements: MutableList<UIElement>,
        drawingOrder: Int,
    ): Int {
        if (depth > maxDepth) return drawingOrder

        var currentOrder = drawingOrder

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val element = UIElement(
            className = node.className?.toString() ?: "android.view.View",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            resourceId = node.viewIdResourceName ?: "",
            bounds = bounds,
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isChecked = node.isChecked,
            isFocused = node.isFocused,
            isEnabled = node.isEnabled,
            depth = depth,
            childCount = node.childCount,
            drawingOrder = currentOrder,
            isVisible = node.isVisibleToUser,
        )

        elements.add(element)
        currentOrder++

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                currentOrder = extractElementsRecursive(child, depth + 1, maxDepth, elements, currentOrder)
            } finally {
                child.recycle()
            }
        }

        return currentOrder
    }

    // ---------------------------------------------------------------------------
    // Gesture dispatch
    // ---------------------------------------------------------------------------

    /**
     * Dispatches a tap at absolute screen coordinates using [GestureDescription].
     *
     * **PokeClaw mechanic:** Used as the final execution step after element finding
     * resolves coordinates.
     */
    fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a swipe from (startX, startY) to (endX, endY) over [durationMs].
     */
    fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a long press at (x, y) for [durationMs].
     */
    fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 600): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a drag gesture along [path] points over [durationMs].
     */
    fun dispatchDrag(pathPoints: List<Pair<Float, Float>>, durationMs: Long = 500): Boolean {
        if (pathPoints.size < 2) return false
        val path = Path().apply {
            moveTo(pathPoints.first().first, pathPoints.first().second)
            pathPoints.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a multi-touch gesture with simultaneous strokes.
     *
     * **PokeClaw mechanic:** Advanced gesture for pinch-zoom, two-finger scroll.
     */
    fun dispatchMultiTouch(
        strokes: List<Triple<List<Pair<Float, Float>>, Long, Long>>,
    ): Boolean {
        val builder = GestureDescription.Builder()
        strokes.forEach { (points, startMs, durationMs) ->
            val path = Path().apply {
                moveTo(points.first().first, points.first().second)
                points.drop(1).forEach { (x, y) -> lineTo(x, y) }
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, startMs, durationMs))
        }
        return dispatchGesture(builder.build(), null, null)
    }

    /**
     * Synchronous gesture dispatch with a completion callback bridged via latch.
     * Returns true when the gesture was accepted by the system.
     */
    fun dispatchGestureBlocking(
        gesture: GestureDescription,
        timeoutMs: Long = 5000,
    ): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }
        val dispatched = dispatchGesture(gesture, callback, null)
        if (dispatched) {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        return success
    }
}
