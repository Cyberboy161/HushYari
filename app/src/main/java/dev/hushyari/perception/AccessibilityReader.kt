package dev.hushyari.perception

import dev.hushyari.data.model.ScreenState
import dev.hushyari.service.HushyariAccessibilityService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the accessibility service's [ScreenState] flow into synchronous reads
 * used by the perception pipeline and agent loop.
 *
 * Observes [HushyariAccessibilityService.screenStateFlow] and provides
 * non-suspending cached access for hot-path reads.
 *
 * **Mechanics:**
 * - Roubao: Decouples service lifecycle from perception consumers.
 * - AccessibilityReader pattern: Text summary generation for LLM prompts directly
 *   from the cached UI tree without requiring the service on the main thread.
 */
@Singleton
class AccessibilityReader @Inject constructor() {

    companion object {
        private const val READ_TIMEOUT_MS = 3000L
    }

    @Volatile
    private var latestState: ScreenState = ScreenState()

    @Volatile
    private var serviceAvailable: Boolean = false

    /**
     * The raw [SharedFlow] from the accessibility service. Can be collected
     * for reactive screen state observation.
     */
    val screenStateFlow: SharedFlow<ScreenState>?
        get() = HushyariAccessibilityService.instance?.screenStateFlow

    /**
     * Returns the latest parsed [ScreenState] from the accessibility service.
     *
     * If the service is unavailable, falls back to the last cached state.
     * Waits up to [READ_TIMEOUT_MS] for a fresh emission before returning stale data.
     *
     * **AccessibilityReader pattern:** Typically called from a background dispatcher
     * so the timeout doesn't block the main thread.
     */
    suspend fun readUITree(): ScreenState {
        val svc = HushyariAccessibilityService.instance
        serviceAvailable = svc != null

        if (!serviceAvailable) {
            Timber.w("AccessibilityReader: service unavailable, returning cached state")
            return latestState
        }

        val flow = svc?.screenStateFlow ?: return latestState

        val fresh = withTimeoutOrNull(READ_TIMEOUT_MS) {
            flow.first()
        }

        if (fresh != null) {
            latestState = fresh
        }

        return latestState
    }

    /**
     * Non-suspending read of the most recently cached [ScreenState].
     * Returns an empty state if nothing has been captured yet.
     */
    fun readCached(): ScreenState = latestState

    /**
     * Returns true when the accessibility service is connected and not interrupted.
     */
    fun isServiceAvailable(): Boolean {
        val svc = HushyariAccessibilityService.instance
        serviceAvailable = svc != null
        return serviceAvailable
    }

    /**
     * Generates a text summary of the current screen for LLM prompts.
     *
     * **AccessibilityReader pattern:** LLM prompt injection point — transforms
     * structured [ScreenState] into a compact text representation suitable for
     * Layer 1 agent decision making.
     */
    fun getPromptSummary(maxElements: Int = 30): String {
        val state = latestState
        if (state.elementCount == 0) return "No UI elements available."

        return buildString {
            appendLine("[Screen: ${state.packageName}/${state.activityName}]")
            appendLine("Dimensions: ${state.screenWidth}x${state.screenHeight}")
            appendLine("Elements: ${state.elementCount} total, ${state.clickableElements.size} clickable")
            appendLine()

            state.clickableElements.take(maxElements).forEachIndexed { i, element ->
                val label = when {
                    element.text.isNotEmpty() -> element.text
                    element.contentDescription.isNotEmpty() -> "\"${element.contentDescription}\""
                    element.resourceId.isNotEmpty() -> element.resourceId.substringAfterLast('/')
                    else -> element.className.substringAfterLast('.')
                }
                appendLine("  [$i] $label @ (${element.centerX.toInt()},${element.centerY.toInt()})")
            }

            if (state.inputElements.isNotEmpty()) {
                appendLine()
                appendLine("Input fields (${state.inputElements.size}):")
                state.inputElements.take(5).forEach { element ->
                    val label = element.text.ifEmpty {
                        element.contentDescription.ifEmpty { element.resourceId.substringAfterLast('/') }
                    }
                    appendLine("  [-] $label @ (${element.centerX.toInt()},${element.centerY.toInt()})")
                }
            }

            if (state.ocrText.isNotEmpty()) {
                appendLine()
                appendLine("OCR Text: ${state.ocrText}")
            }
        }
    }

    /**
     * Forces a refresh by invalidating any internal caches.
     */
    fun refresh() {
        latestState = ScreenState()
    }

    /**
     * Returns the display metrics from the accessibility service context
     * for coordinate conversion.
     */
    fun getScreenWidth(): Int = latestState.screenWidth
    fun getScreenHeight(): Int = latestState.screenHeight
}
