package dev.hushyari.tools

import android.graphics.Bitmap
import dev.hushyari.data.model.ElementQuery
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.ToolResult
import dev.hushyari.data.model.UIElement
import dev.hushyari.perception.PerceptionPipeline
import javax.inject.Inject
import javax.inject.Singleton

private val SCREEN_PERMISSIONS = listOf(
    "android.permission.SYSTEM_ALERT_WINDOW",
)

private val SCREEN_SAFETY_RULES = listOf(
    "checkScreenSensitivity",
    "checkPaymentScreen",
    "checkAuthenticationScreen",
    "checkRateLimit",
)

/**
 * Screen tool: capture screenshots, retrieve screen state summaries,
 * and query element counts on the current screen.
 *
 * Uses [PerceptionPipeline] for accessibility-tree traversal and
 * screenshot capture.
 *
 * Actions:
 * - "take_screenshot"   — capture and return a screenshot as Bitmap bytes
 * - "get_screen_info"   — return a text summary of the current UI tree
 * - "get_element_count" — count elements matching optional query filters
 *
 * Params:
 * - "action"             — one of the above actions
 * - "max_elements"       — max elements in summary (default 30)
 * - "filter_clickable"   — count only clickable elements
 * - "filter_text"        — count only elements with text
 * - "filter_editable"    — count only editable elements
 * - "filter_text_contains" — count only elements whose text contains this
 */
@Singleton
class ScreenTool @Inject constructor(
    private val perception: PerceptionPipeline,
) : Tool {

    override val name = "screen"
    override val description = "Take screenshots and retrieve screen state information"
    override val category = ToolCategory.SCREEN
    override val requiredPermissions = SCREEN_PERMISSIONS
    override val safetyRules = SCREEN_SAFETY_RULES

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        return action in setOf("take_screenshot", "get_screen_info", "get_element_count")
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return invalidParams("action is required (take_screenshot/get_screen_info/get_element_count)")

        return try {
            when (action) {
                "take_screenshot" -> executeTakeScreenshot()
                "get_screen_info" -> executeGetScreenInfo(params)
                "get_element_count" -> executeGetElementCount(params)
                else -> invalidParams("Unknown action: $action")
            }
        } catch (e: Exception) {
            ToolResult.Failure(
                toolName = name,
                error = "Screen tool error: ${e.message}",
                errorCode = ErrorCode.SCREEN_CAPTURE_FAILED,
            )
        }
    }

    private suspend fun executeTakeScreenshot(): ToolResult {
        val bitmap = perception.captureScreenshot()
        if (bitmap == null) {
            return ToolResult.Failure(
                toolName = name,
                error = "Screenshot capture returned null",
                errorCode = ErrorCode.SCREEN_CAPTURE_FAILED,
            )
        }

        return ToolResult.Success(
            toolName = name,
            message = "Screenshot captured: ${bitmap.width}x${bitmap.height}",
            data = mapOf(
                "width" to bitmap.width,
                "height" to bitmap.height,
                "size_bytes" to bitmap.byteCount,
            ),
        )
    }

    private suspend fun executeGetScreenInfo(params: Map<String, Any?>): ToolResult {
        val maxElements = (params["max_elements"] as? Number)?.toInt() ?: 30
        val screen = perception.capture(PerceptionPipeline.CaptureMode.FULL)
        val summary = screen.toTextSummary(maxElements)

        return ToolResult.Success(
            toolName = name,
            message = "Screen info captured",
            data = mapOf(
                "package_name" to screen.packageName,
                "activity_name" to screen.activityName,
                "window_title" to screen.windowTitle,
                "screen_width" to screen.screenWidth,
                "screen_height" to screen.screenHeight,
                "element_count" to screen.elementCount,
                "clickable_count" to screen.clickableElements.size,
                "text_element_count" to screen.textElements.size,
                "input_element_count" to screen.inputElements.size,
                "is_game_screen" to screen.isGameScreen,
                "has_popup" to screen.hasPopup,
                "summary" to summary,
            ),
        )
    }

    private suspend fun executeGetElementCount(params: Map<String, Any?>): ToolResult {
        val screen = perception.capture(PerceptionPipeline.CaptureMode.FULL)
        var elements: List<UIElement> = screen.elements

        val filterClickable = params["filter_clickable"] as? Boolean
        val filterEditable = params["filter_editable"] as? Boolean
        val filterTextContains = params["filter_text_contains"]?.toString()

        if (filterClickable == true) elements = elements.filter { it.isClickable }
        if (filterEditable == true) elements = elements.filter { it.isEditable }
        if (filterTextContains != null) {
            elements = elements.filter {
                it.text.contains(filterTextContains, ignoreCase = true) ||
                    it.contentDescription.contains(filterTextContains, ignoreCase = true)
            }
        }

        return ToolResult.Success(
            toolName = name,
            message = "Found ${elements.size} matching elements",
            data = mapOf(
                "total_elements" to screen.elementCount,
                "filtered_count" to elements.size,
                "clickable_count" to screen.clickableElements.size,
                "editable_count" to screen.inputElements.size,
            ),
        )
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
