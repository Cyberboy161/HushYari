package dev.hushyari.agent

import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.ToolResult
import dev.hushyari.perception.PerceptionPipeline
import dev.hushyari.statemachine.ClassificationResult
import dev.hushyari.statemachine.ScreenClassifier
import dev.hushyari.statemachine.GameConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Success/failure evaluation agent that compares pre- and post-action
 * screen states to determine whether an action achieved its intended effect.
 *
 * Uses multiple signals for evaluation:
 * - Screen classification change (did we navigate?)
 * - Element count change (did UI update?)
 * - OCR text change (did a dialog or label appear?)
 * - Tool result status (was the tool successful?)
 *
 * **Mechanics:**
 * - Roubao: Pre/post state comparison with confidence scoring.
 * - 4x-game-agent Layer 4: Reflection step driving the agent's learning loop.
 */
@Singleton
class Reflector @Inject constructor(
    private val perception: PerceptionPipeline,
    private val screenClassifier: ScreenClassifier,
) {

    /**
     * Evaluate whether an action succeeded by comparing screen states before
     * and after execution, plus the tool result.
     *
     * @param action The action that was performed.
     * @param beforeScreen Screen state before the action.
     * @param afterScreen Screen state after the action.
     * @param result The tool execution result.
     * @return [Reflection] with success flag, confidence, and summary.
     */
    suspend fun evaluate(
        action: AgentAction,
        beforeScreen: ScreenState,
        afterScreen: ScreenState,
        result: ToolResult,
    ): Reflection {
        var confidence = 0.5f
        var success = result.isSuccess
        val reasons = mutableListOf<String>()

        // Signal 1: Tool result status
        if (result.isSuccess) {
            confidence += 0.2f
            reasons.add("Tool reported success")
        } else {
            confidence -= 0.1f
            reasons.add("Tool reported failure: ${(result as? ToolResult.Failure)?.error ?: "unknown"}")
        }

        // Signal 2: Screen classification change
        if (beforeScreen.packageName != afterScreen.packageName ||
            beforeScreen.activityName != afterScreen.activityName
        ) {
            confidence += 0.15f
            reasons.add("Screen package/activity changed")
        } else {
            confidence += 0.03f
        }

        // Signal 3: Element count change
        val elementDelta = afterScreen.elementCount - beforeScreen.elementCount
        if (elementDelta != 0) {
            confidence += 0.1f.coerceAtMost(kotlin.math.abs(elementDelta) * 0.01f)
            reasons.add("Element count changed by $elementDelta")
        }

        // Signal 4: OCR text change
        if (beforeScreen.ocrText != afterScreen.ocrText) {
            confidence += 0.1f
            reasons.add("OCR text changed")
        }

        // Signal 5: Clickable elements change
        if (beforeScreen.clickableElements.size != afterScreen.clickableElements.size) {
            confidence += 0.05f
            reasons.add("Clickable elements count changed")
        }

        // Normalize confidence
        confidence = confidence.coerceIn(0f, 1f)

        // A successful action should have > 0.6 confidence
        if (success && confidence < 0.4f) {
            success = false
            reasons.add("Low confidence despite tool success: $confidence")
        }

        if (!success && confidence > 0.7f) {
            success = true
            reasons.add("High confidence despite tool failure: $confidence")
        }

        val actionDesc = when (action) {
            is AgentAction.ToolAction -> action.toolName
            is AgentAction.PlanAction -> "plan(${action.steps.size} steps)"
            is AgentAction.WaitAction -> "wait(${action.durationMs}ms)"
            is AgentAction.DoneAction -> "done"
            is AgentAction.AskAction -> "ask"
        }

        val summary = buildString {
            appendLine("Action: $actionDesc")
            appendLine("Success: $success | Confidence: ${"%.2f".format(confidence)}")
            appendLine("Reasons: ${reasons.joinToString("; ")}")
            append("Before: ${beforeScreen.elementCount} elements | After: ${afterScreen.elementCount} elements")
        }

        Timber.d("Reflector: $summary")
        return Reflection(success = success, confidence = confidence, summary = summary)
    }
}
