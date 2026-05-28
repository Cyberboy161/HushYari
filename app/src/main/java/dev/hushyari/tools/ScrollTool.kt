package dev.hushyari.tools

import android.content.res.Resources
import android.graphics.PointF
import dev.hushyari.controller.GestureDispatcher
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

private val SCROLL_PERMISSIONS = listOf("android.permission.SYSTEM_ALERT_WINDOW")

private val SCROLL_SAFETY_RULES = listOf(
    "checkPaymentScreen",
    "checkRateLimit",
    "checkPlayTimeLimit",
)

/**
 * Scroll tool: scroll up/down/left/right by a pixel amount or by "page".
 *
 * Can target a specific element (by text/content-desc/resource-id) or
 * scroll the entire screen. Uses [GestureDispatcher] to perform the
 * swipe-based scroll gesture.
 *
 * Params:
 * - "direction"    — "up", "down", "left", "right" (required)
 * - "amount"       — pixels as Int, or "page" for a full-page scroll
 * - "element_text" — optional text of element to scroll within
 * - "element_resource_id" — optional resource-id of element to scroll
 * - "duration_ms"  — swipe duration (default 200ms)
 * - "use_fractional" — if true, amount is interpreted as screen fraction
 */
@Singleton
class ScrollTool @Inject constructor(
    private val gestureDispatcher: GestureDispatcher,
) : Tool {

    override val name = "scroll"
    override val description = "Scroll the screen or a specific element by pixels or page"
    override val category = ToolCategory.GESTURE
    override val requiredPermissions = SCROLL_PERMISSIONS
    override val safetyRules = SCROLL_SAFETY_RULES

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val direction = params["direction"] as? String ?: return false
        return direction in setOf("up", "down", "left", "right")
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val direction = params["direction"] as? String
            ?: return invalidParams("direction is required (up/down/left/right)")

        val useFractional = params["use_fractional"] as? Boolean ?: false
        val amountRaw = params["amount"] ?: "page"
        val durationMs = (params["duration_ms"] as? Number)?.toLong() ?: 200L

        val metrics = Resources.getSystem().displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        if (params["element_text"] != null || params["element_resource_id"] != null) {
            startX = screenW / 2f
            startY = screenH / 2f
        } else {
            startX = screenW / 2f
            startY = screenH * 0.7f
        }

        val scrollAmount: Float = if (amountRaw == "page") {
            when (direction) {
                "up", "down" -> screenH * 0.6f
                "left", "right" -> screenW * 0.6f
                else -> screenH * 0.6f
            }
        } else {
            val amount = (amountRaw as? Number)?.toFloat()
                ?: return invalidParams("amount must be a number or \"page\"")
            if (useFractional) {
                when (direction) {
                    "up", "down" -> amount * screenH
                    "left", "right" -> amount * screenW
                    else -> amount * screenH
                }
            } else {
                amount
            }
        }

        when (direction) {
            "up" -> {
                endX = startX
                endY = startY - scrollAmount
            }
            "down" -> {
                endX = startX
                endY = startY + scrollAmount
            }
            "left" -> {
                endX = startX - scrollAmount
                endY = startY
            }
            "right" -> {
                endX = startX + scrollAmount
                endY = startY
            }
            else -> return invalidParams("Unknown direction: $direction")
        }

        val clampedEndX = endX.coerceIn(0f, screenW)
        val clampedEndY = endY.coerceIn(0f, screenH)

        return gestureDispatcher.swipe(
            PointF(startX, startY),
            PointF(clampedEndX, clampedEndY),
            durationMs,
        )
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
