package dev.hushyari.controller

import android.graphics.PointF
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backends available for gesture dispatch.
 */
enum class Provider { ACCESSIBILITY, SHIZUKU }

/**
 * Priority-ordered gesture dispatcher that tries AccessibilityService first,
 * then falls back to Shizuku if available.
 * 🧠 PokeClaw + Roubao mechanic: Dual-backend dispatch with automatic failover.
 * If both backends fail, returns a structured [ToolResult.Failure].
 */
@Singleton
class GestureDispatcher @Inject constructor(
    private val accessibilityController: AccessibilityController,
) {

    private var shizukuController: ShizukuController? = null

    @Volatile
    var activeProvider: Provider = Provider.ACCESSIBILITY
        private set

    fun setShizukuController(controller: ShizukuController?) {
        shizukuController = controller
    }

    fun setProvider(provider: Provider) {
        activeProvider = provider
        Timber.i("GestureDispatcher provider switched to $provider")
    }

    fun getPreferredProvider(): Provider {
        return when {
            shizukuController?.isAvailable() == true -> Provider.SHIZUKU
            accessibilityController.isAvailable() -> Provider.ACCESSIBILITY
            else -> Provider.ACCESSIBILITY
        }
    }

    // ── Public API ──────────────────────────────────────────────

    suspend fun tap(x: Float, y: Float, delayMs: Long = 50): ToolResult = runCatching {
        withAccessibility { it.tap(x, y, delayMs) }
            ?: withShizuku { it.tap(x, y, delayMs) }
            ?: throw NoAvailableControllerException("tap")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "tap", message = "Tap at ($x, $y)")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "tap",
                error = e.message ?: "Tap failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun swipe(from: PointF, to: PointF, durationMs: Long = 300): ToolResult = runCatching {
        withAccessibility { it.swipe(from, to, durationMs) }
            ?: withShizuku { it.swipe(from, to, durationMs) }
            ?: throw NoAvailableControllerException("swipe")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "swipe", message = "Swipe from $from to $to")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "swipe",
                error = e.message ?: "Swipe failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 800): ToolResult = runCatching {
        withAccessibility { it.longPress(x, y, durationMs) }
            ?: withShizuku { it.longPress(x, y, durationMs) }
            ?: throw NoAvailableControllerException("longPress")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "longPress", message = "Long press at ($x, $y)")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "longPress",
                error = e.message ?: "Long press failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun drag(points: List<PointF>, durationMs: Long = 500): ToolResult = runCatching {
        withAccessibility { it.drag(points, durationMs) }
            ?: withShizuku { it.drag(points, durationMs) }
            ?: throw NoAvailableControllerException("drag")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "drag", message = "Drag ${points.size} points")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "drag",
                error = e.message ?: "Drag failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun typeText(text: String): ToolResult = runCatching {
        withAccessibility { it.typeText(text) }
            ?: withShizuku { it.typeText(text) }
            ?: throw NoAvailableControllerException("typeText")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "typeText", message = "Typed ${text.length} chars")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "typeText",
                error = e.message ?: "Type text failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun pressKey(keyCode: Int): ToolResult = runCatching {
        withAccessibility { it.pressKey(keyCode) }
            ?: withShizuku { it.pressKey(keyCode) }
            ?: throw NoAvailableControllerException("pressKey")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "pressKey", message = "Key $keyCode pressed")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "pressKey",
                error = e.message ?: "Key press failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun scroll(direction: ScrollDirection, amount: Float): ToolResult = runCatching {
        withAccessibility { it.scroll(direction, amount) }
            ?: withShizuku { it.scroll(direction, amount) }
            ?: throw NoAvailableControllerException("scroll")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "scroll", message = "Scroll $direction")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "scroll",
                error = e.message ?: "Scroll failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun openApp(packageName: String): ToolResult = runCatching {
        withAccessibility { it.openApp(packageName) }
            ?: withShizuku { it.openApp(packageName) }
            ?: throw NoAvailableControllerException("openApp")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "openApp", message = "Opened $packageName")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "openApp",
                error = e.message ?: "Open app failed",
                errorCode = ErrorCode.PERMISSION_DENIED,
            )
        },
    )

    suspend fun goHome(): ToolResult = runCatching {
        withAccessibility { it.goHome() }
            ?: withShizuku { it.goHome() }
            ?: throw NoAvailableControllerException("goHome")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "goHome", message = "Went home")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "goHome",
                error = e.message ?: "Go home failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    suspend fun goBack(): ToolResult = runCatching {
        withAccessibility { it.goBack() }
            ?: withShizuku { it.goBack() }
            ?: throw NoAvailableControllerException("goBack")
    }.fold(
        onSuccess = {
            ToolResult.Success(toolName = "goBack", message = "Went back")
        },
        onFailure = { e ->
            ToolResult.Failure(
                toolName = "goBack",
                error = e.message ?: "Go back failed",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        },
    )

    // ── Helpers ─────────────────────────────────────────────────

    /**
     * Execute [block] on the AccessibilityController if available.
     * Returns `null` if the AccessibilityService is not connected.
     */
    private suspend fun <T> withAccessibility(block: suspend (DeviceController) -> T): T? {
        if (!accessibilityController.isAvailable()) {
            Timber.d("AccessibilityController not available, skipping")
            return null
        }
        return try {
            block(accessibilityController)
        } catch (e: Exception) {
            Timber.w(e, "AccessibilityController failed, will try Shizuku")
            null
        }
    }

    /**
     * Execute [block] on the ShizukuController if available.
     * Returns `null` if Shizuku is not installed/authorized.
     */
    private suspend fun <T> withShizuku(block: suspend (DeviceController) -> T): T? {
        val ctrl = shizukuController ?: return null
        if (!ctrl.isAvailable()) return null
        return try {
            block(ctrl)
        } catch (e: Exception) {
            Timber.w(e, "ShizukuController failed")
            null
        }
    }

    private class NoAvailableControllerException(action: String) :
        Exception("No controller available for action: $action")
}
