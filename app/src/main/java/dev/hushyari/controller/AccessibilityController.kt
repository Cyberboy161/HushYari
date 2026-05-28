package dev.hushyari.controller

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hushyari.service.HushyariAccessibilityService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class AccessibilityController @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceController {

    private val service: HushyariAccessibilityService?
        get() = HushyariAccessibilityService.instance

    override fun isAvailable(): Boolean =
        service != null

    // ── Gestures ────────────────────────────────────────────────

    override suspend fun tap(x: Float, y: Float, delayMs: Long) {
        requireService().dispatchTap(x, y)
        if (delayMs > 0) delay(delayMs)
    }

    override suspend fun swipe(from: PointF, to: PointF, durationMs: Long) {
        requireService().dispatchSwipe(from.x, from.y, to.x, to.y, durationMs)
        delay(50)
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
        requireService().dispatchLongPress(x, y, durationMs)
        delay(50)
    }

    override suspend fun drag(points: List<PointF>, durationMs: Long) {
        require(points.size >= 2) { "Drag requires at least 2 points" }
        val pathPoints = points.map { Pair(it.x, it.y) }
        requireService().dispatchDrag(pathPoints, durationMs)
        delay(50)
    }

    // ── Typing ──────────────────────────────────────────────────

    override suspend fun typeText(text: String) {
        val svc = requireService()
        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
            if (success) {
                Timber.d("Typed via ACTION_SET_TEXT")
                return
            }
        }
        focused?.recycle()
        Timber.d("Falling back to clipboard paste for typing")
        typeViaClipboard(text, svc)
    }

    private fun typeViaClipboard(text: String, service: AccessibilityService) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("hushyari_input", text)
        clipboard.setPrimaryClip(clip)
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        focused?.recycle()
    }

    // ── Keys ────────────────────────────────────────────────────

    override suspend fun pressKey(keyCode: Int) {
        val svc = requireService()
        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            focused.performAction(keyCode)
            focused.recycle()
        }
    }

    // ── Scroll ──────────────────────────────────────────────────

    override suspend fun scroll(direction: ScrollDirection, amount: Float) {
        val svc = requireService()
        val action = when (direction) {
            ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val root = svc.rootInActiveWindow ?: return
        try {
            val scrollable = findFirstScrollable(root) ?: root
            scrollable.performAction(action)
        } finally {
            root.recycle()
        }
    }

    // ── Navigation ──────────────────────────────────────────────

    override suspend fun openApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("No launch intent for $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        delay(1000)
    }

    override suspend fun goHome() {
        requireService().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(300)
    }

    override suspend fun goBack() {
        requireService().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(200)
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun requireService(): HushyariAccessibilityService {
        return service ?: throw IllegalStateException("AccessibilityService not connected")
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstScrollable(child)
            if (result != null) return result
        }
        return null
    }
}
