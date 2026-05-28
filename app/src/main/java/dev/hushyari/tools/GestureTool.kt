package dev.hushyari.tools

import android.content.res.Resources
import android.graphics.PointF
import dev.hushyari.controller.GestureDispatcher
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

private val GESTURE_PERMISSIONS = listOf("android.permission.SYSTEM_ALERT_WINDOW")

private val GESTURE_SAFETY_RULES = listOf(
    "checkPaymentScreen",
    "checkAuthenticationScreen",
    "checkDestructiveAction",
    "checkRateLimit",
)

/**
 * Atomic gesture tool: tap, swipe, long_press, drag, pinch.
 *
 * Coordinates can be given as absolute pixel values or as fractional
 * values in the range 0.0–1.0 (relative to screen dimensions).
 *
 * Actions:
 * - "tap"         — single tap at (x, y)
 * - "swipe"       — swipe from (x1, y1) to (x2, y2)
 * - "long_press"  — long press at (x, y)
 * - "drag"        — drag from (x1, y1) to (x2, y2) with duration control
 * - "pinch_open"  — two-finger spread
 * - "pinch_close" — two-finger pinch
 */
@Singleton
class GestureTool @Inject constructor(
    private val gestureDispatcher: GestureDispatcher,
) : Tool {

    override val name = "gesture"
    override val description = "Perform tap, swipe, long_press, drag, and pinch gestures"
    override val category = ToolCategory.GESTURE
    override val requiredPermissions = GESTURE_PERMISSIONS
    override val safetyRules = GESTURE_SAFETY_RULES

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        return action in setOf("tap", "swipe", "long_press", "drag", "pinch_open", "pinch_close")
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String ?: return invalidParams("action is required")
        val useFractional = params["use_fractional"] as? Boolean ?: false

        return when (action) {
            "tap" -> executeTap(params, useFractional)
            "swipe" -> executeSwipe(params, useFractional)
            "long_press" -> executeLongPress(params, useFractional)
            "drag" -> executeDrag(params, useFractional)
            "pinch_open" -> executePinchOpen(params, useFractional)
            "pinch_close" -> executePinchClose(params, useFractional)
            else -> invalidParams("Unknown action: $action")
        }
    }

    private suspend fun executeTap(params: Map<String, Any?>, fractional: Boolean): ToolResult {
        val (x, y) = resolvePoint(params, "x", "y", fractional) ?: return missingCoords()
        val delayMs = (params["duration_ms"] as? Number)?.toLong() ?: 50L
        return gestureDispatcher.tap(x, y, delayMs)
    }

    private suspend fun executeSwipe(params: Map<String, Any?>, fractional: Boolean): ToolResult {
        val start = resolvePoint(params, "x1", "y1", fractional) ?: return missingCoords("x1,y1")
        val end = resolvePoint(params, "x2", "y2", fractional) ?: return missingCoords("x2,y2")
        val durationMs = (params["duration_ms"] as? Number)?.toLong() ?: 300L
        return gestureDispatcher.swipe(
            PointF(start.first, start.second),
            PointF(end.first, end.second),
            durationMs,
        )
    }

    private suspend fun executeLongPress(params: Map<String, Any?>, fractional: Boolean): ToolResult {
        val (x, y) = resolvePoint(params, "x", "y", fractional) ?: return missingCoords()
        val durationMs = (params["duration_ms"] as? Number)?.toLong() ?: 800L
        return gestureDispatcher.longPress(x, y, durationMs)
    }

    private suspend fun executeDrag(params: Map<String, Any?>, fractional: Boolean): ToolResult {
        val start = resolvePoint(params, "x1", "y1", fractional) ?: return missingCoords("x1,y1")
        val end = resolvePoint(params, "x2", "y2", fractional) ?: return missingCoords("x2,y2")
        val durationMs = (params["duration_ms"] as? Number)?.toLong() ?: 500L
        val steps = (params["steps"] as? Number)?.toInt() ?: 20
        val points = interpolate(start, end, steps).map { PointF(it.first, it.second) }
        return gestureDispatcher.drag(points, durationMs)
    }

    private suspend fun executePinchOpen(params: Map<String, Any?>, fractional: Boolean): ToolResult {
        val (cx, cy) = resolvePoint(params, "x", "y", fractional)
            ?: resolveCenter()
        val spreadPx = (params["spread_px"] as? Number)?.toFloat()
            ?: (Resources.getSystem().displayMetrics.widthPixels * 0.15f)
        val steps = (params["steps"] as? Number)?.toInt() ?: 8
        val durationMs = (params["duration_ms"] as? Number)?.toLong() ?: 300L

        val path1 = interpolate(
            cx - spreadPx / 2 to cy,
            cx - spreadPx to cy,
            steps,
        ).map { PointF(it.first, it.second) }
        val path2 = interpolate(
            cx + spreadPx / 2 to cy,
            cx + spreadPx to cy,
            steps,
        ).map { PointF(it.first, it.second) }

        return coroutineScope {
            val r1 = async { gestureDispatcher.drag(path1, durationMs) }
            val r2 = async { gestureDispatcher.drag(path2, durationMs) }
            r1.await()
            r2.await()
            ToolResult.Success(
                toolName = name,
                message = "Pinch open at ($cx, $cy) spread ${spreadPx.toInt()}px",
            )
        }
    }

    private suspend fun executePinchClose(params: Map<String, Any?>, fractional: Boolean): ToolResult {
        val (cx, cy) = resolvePoint(params, "x", "y", fractional)
            ?: resolveCenter()
        val spreadPx = (params["spread_px"] as? Number)?.toFloat()
            ?: (Resources.getSystem().displayMetrics.widthPixels * 0.15f)
        val steps = (params["steps"] as? Number)?.toInt() ?: 8
        val durationMs = (params["duration_ms"] as? Number)?.toLong() ?: 300L

        val path1 = interpolate(
            cx - spreadPx to cy,
            cx - spreadPx / 2 to cy,
            steps,
        ).map { PointF(it.first, it.second) }
        val path2 = interpolate(
            cx + spreadPx to cy,
            cx + spreadPx / 2 to cy,
            steps,
        ).map { PointF(it.first, it.second) }

        return coroutineScope {
            val r1 = async { gestureDispatcher.drag(path1, durationMs) }
            val r2 = async { gestureDispatcher.drag(path2, durationMs) }
            r1.await()
            r2.await()
            ToolResult.Success(
                toolName = name,
                message = "Pinch close at ($cx, $cy) spread ${spreadPx.toInt()}px",
            )
        }
    }

    private fun screenWidth(): Int = Resources.getSystem().displayMetrics.widthPixels

    private fun screenHeight(): Int = Resources.getSystem().displayMetrics.heightPixels

    private fun resolvePoint(
        params: Map<String, Any?>,
        keyX: String,
        keyY: String,
        fractional: Boolean,
    ): Pair<Float, Float>? {
        val rawX = params[keyX] as? Number ?: return null
        val rawY = params[keyY] as? Number ?: return null
        return if (fractional) {
            (rawX.toFloat() * screenWidth()) to
                (rawY.toFloat() * screenHeight())
        } else {
            rawX.toFloat() to rawY.toFloat()
        }
    }

    private fun resolveCenter(): Pair<Float, Float> =
        (screenWidth() / 2f) to (screenHeight() / 2f)

    private fun interpolate(
        start: Pair<Float, Float>,
        end: Pair<Float, Float>,
        steps: Int,
    ): List<Pair<Float, Float>> {
        if (steps <= 1) return listOf(start, end)
        return (0..steps).map { i ->
            val t = i.toFloat() / steps
            val x = start.first + (end.first - start.first) * t
            val y = start.second + (end.second - start.second) * t
            x to y
        }
    }

    private fun missingCoords(label: String = "x,y"): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = "Missing coordinates: $label",
            errorCode = ErrorCode.INVALID_PARAMS,
        )

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
