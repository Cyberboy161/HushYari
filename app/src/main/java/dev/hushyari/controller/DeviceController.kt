package dev.hushyari.controller

import android.graphics.PointF

/**
 * Direction for scroll operations.
 */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Abstraction over device-level input: taps, swipes, typing, navigation.
 * 🧠 PokeClaw mechanic: Unified interface so gesture tools work across
 * AccessibilityService and Shizuku without knowing the backend.
 */
interface DeviceController {

    /**
     * Tap at pixel coordinates.
     * @param delayMs wait after the tap for UI to settle.
     */
    suspend fun tap(x: Float, y: Float, delayMs: Long = 50)

    /**
     * Swipe from [from] to [to] over [durationMs].
     */
    suspend fun swipe(from: PointF, to: PointF, durationMs: Long = 300)

    /**
     * Long-press at coordinates.
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 800)

    /**
     * Drag along a path defined by the ordered list of points.
     */
    suspend fun drag(points: List<PointF>, durationMs: Long = 500)

    /**
     * Type text into the currently focused input field.
     */
    suspend fun typeText(text: String)

    /**
     * Press a raw Android key code (e.g. KEYCODE_ENTER).
     */
    suspend fun pressKey(keyCode: Int)

    /**
     * Perform a directional scroll.
     * [amount] is a fraction of screen size (0..1) or pixel amount depending on backend.
     */
    suspend fun scroll(direction: ScrollDirection, amount: Float)

    /**
     * Launch an installed app by package name.
     */
    suspend fun openApp(packageName: String)

    /**
     * Navigate to the home screen.
     */
    suspend fun goHome()

    /**
     * Navigate back.
     */
    suspend fun goBack()

    /**
     * Whether this controller is currently usable.
     */
    fun isAvailable(): Boolean
}
