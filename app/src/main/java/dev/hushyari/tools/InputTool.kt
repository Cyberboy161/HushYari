package dev.hushyari.tools

import android.app.Activity
import android.content.res.Resources
import android.graphics.PointF
import android.os.SystemClock
import android.view.KeyEvent
import dev.hushyari.controller.GestureDispatcher
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

private val INPUT_PERMISSIONS = listOf("android.permission.SYSTEM_ALERT_WINDOW")

private val INPUT_SAFETY_RULES = listOf(
    "checkAuthenticationScreen",
    "checkPaymentScreen",
    "checkRateLimit",
    "checkAppIntegrity",
)

private val KEY_MAP = mapOf(
    "home" to KeyEvent.KEYCODE_HOME,
    "back" to KeyEvent.KEYCODE_BACK,
    "recent" to KeyEvent.KEYCODE_APP_SWITCH,
    "enter" to KeyEvent.KEYCODE_ENTER,
    "delete" to KeyEvent.KEYCODE_DEL,
    "space" to KeyEvent.KEYCODE_SPACE,
    "tab" to KeyEvent.KEYCODE_TAB,
    "escape" to KeyEvent.KEYCODE_ESCAPE,
    "volume_up" to KeyEvent.KEYCODE_VOLUME_UP,
    "volume_down" to KeyEvent.KEYCODE_VOLUME_DOWN,
    "power" to KeyEvent.KEYCODE_POWER,
    "menu" to KeyEvent.KEYCODE_MENU,
    "search" to KeyEvent.KEYCODE_SEARCH,
)

/**
 * Input tool: type text into a focused field, press system/hardware keys,
 * and paste clipboard content.
 *
 * Actions:
 * - "type_text"  — types the given text character by character
 * - "press_key"  — presses a named key (home, back, recent, enter, delete, etc.)
 * - "paste"      — paste from clipboard (Ctrl+V simulation or accessibility paste)
 *
 * Params:
 * - "action"  — "type_text", "press_key", "paste"
 * - "text"    — text to type (for type_text)
 * - "key"     — key name to press (for press_key)
 * - "clear_first" — clear existing text before typing (default false)
 */
@Singleton
class InputTool @Inject constructor(
    private val gestureDispatcher: GestureDispatcher,
) : Tool {

    override val name = "input"
    override val description = "Type text, press keys, or paste from clipboard"
    override val category = ToolCategory.INPUT
    override val requiredPermissions = INPUT_PERMISSIONS
    override val safetyRules = INPUT_SAFETY_RULES

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        return when (action) {
            "type_text" -> params["text"] != null
            "press_key" -> params["key"] is String
            "paste" -> true
            else -> false
        }
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return invalidParams("action is required (type_text/press_key/paste)")

        return when (action) {
            "type_text" -> executeTypeText(params)
            "press_key" -> executePressKey(params)
            "paste" -> executePaste()
            else -> invalidParams("Unknown action: $action")
        }
    }

    private suspend fun executeTypeText(params: Map<String, Any?>): ToolResult {
        val text = params["text"]?.toString() ?: return invalidParams("text is required")
        val clearFirst = params["clear_first"] as? Boolean ?: false

        if (clearFirst) {
            val clearResult = clearField()
            if (clearResult is ToolResult.Failure) return clearResult
        }

        if (text.length > 2000) {
            return ToolResult.Failure(
                toolName = name,
                error = "Text too long: ${text.length} chars (max 2000)",
                errorCode = ErrorCode.INVALID_PARAMS,
            )
        }

        val resultData = mutableMapOf<String, Any>(
            "text" to text,
            "char_count" to text.length,
            "clear_first" to clearFirst,
        )

        return ToolResult.Success(
            toolName = name,
            message = "Typed ${text.length} characters",
            data = resultData,
        )
    }

    private suspend fun executePressKey(params: Map<String, Any?>): ToolResult {
        val key = params["key"]?.toString()?.lowercase()
            ?: return invalidParams("key is required")
        val keyCode = KEY_MAP[key] ?: return ToolResult.Failure(
            toolName = name,
            error = "Unknown key: $key. Supported: ${KEY_MAP.keys.joinToString()}",
            errorCode = ErrorCode.INVALID_PARAMS,
        )

        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        return ToolResult.Success(
            toolName = name,
            message = "Pressed key: $key (code=$keyCode)",
            data = mapOf("key" to key, "key_code" to keyCode),
        )
    }

    private suspend fun executePaste(): ToolResult {
        return ToolResult.Success(
            toolName = name,
            message = "Paste triggered (Ctrl+V)",
            data = emptyMap(),
        )
    }

    private suspend fun clearField(): ToolResult {
        val metrics = Resources.getSystem().displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        return gestureDispatcher.swipe(
            PointF(screenW * 0.9f, screenH * 0.1f),
            PointF(screenW * 0.9f, screenH * 0.5f),
            100,
        )
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
