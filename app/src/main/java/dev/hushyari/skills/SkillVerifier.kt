package dev.hushyari.skills

import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.SkillStep
import dev.hushyari.data.model.ToolResult
import dev.hushyari.data.model.VerificationCheck
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confirms whether a skill step succeeded by comparing pre- and post-action
 * screen states, tool results, and verification criteria.
 *
 * Each verification check type uses different signals:
 * - element_exists: target element must be present after action
 * - element_gone: target element must no longer exist
 * - text_appeared: expected text must be visible
 * - screen_changed: screen classification must have changed
 * - tool_success: the tool result itself indicates success
 *
 * 🧠 4x-game-agent mechanic: Layer 4 — Per-step verification ensuring reliable automation.
 */
@Singleton
class SkillVerifier @Inject constructor() {

    /**
     * Verify that a step executed successfully.
     *
     * @param step The skill step that was executed.
     * @param before Screen state before the step.
     * @param after Screen state after the step.
     * @param toolResult The result of the tool execution.
     * @return VerificationResult with success, confidence, and details.
     */
    fun verify(
        step: SkillStep,
        before: ScreenState,
        after: ScreenState,
        toolResult: ToolResult,
    ): VerificationResult {
        val check = step.verifyAfter ?: return defaultVerify(toolResult)

        return when (check.type.lowercase()) {
            "element_exists" -> verifyElementExists(check, after)
            "element_gone" -> verifyElementGone(check, after)
            "text_appeared" -> verifyTextAppeared(check, after)
            "screen_changed" -> verifyScreenChanged(before, after)
            "screen_is" -> verifyScreenIs(check, after)
            "tool_success" -> verifyToolSuccess(toolResult)
            "resource_changed" -> verifyResourceChanged(check, before, after)
            else -> defaultVerify(toolResult)
        }
    }

    /**
     * Default verification based solely on tool result status.
     */
    fun defaultVerify(toolResult: ToolResult): VerificationResult {
        return when {
            toolResult.isSuccess -> VerificationResult(
                success = true,
                confidence = 0.9f,
                details = "Tool executed successfully: ${toolResult.toolName}",
            )
            toolResult is ToolResult.Timeout -> VerificationResult(
                success = false,
                confidence = 0.4f,
                details = "Tool timed out after ${toolResult.timeoutMs}ms",
            )
            toolResult is ToolResult.SafetyBlocked -> VerificationResult(
                success = false,
                confidence = 0.1f,
                details = "Tool blocked by safety rule: ${toolResult.reason}",
            )
            else -> VerificationResult(
                success = false,
                confidence = 0.3f,
                details = "Tool failed: ${(toolResult as? ToolResult.Failure)?.error ?: "unknown"}",
            )
        }
    }

    private fun verifyElementExists(
        check: VerificationCheck,
        screenState: ScreenState,
    ): VerificationResult {
        if (check.target == null) {
            return VerificationResult(false, 0.5f, "No target specified for element_exists check")
        }

        val query = dev.hushyari.data.model.ElementQuery(
            text = check.target.text,
            textContains = check.target.textContains,
            contentDesc = check.target.contentDesc,
            contentDescContains = check.target.contentDescContains,
            resourceId = check.target.resourceId,
            className = check.target.className,
        )
        val found = screenState.findElement(query)

        return if (found != null) {
            VerificationResult(true, 0.85f, "Expected element found: ${check.target}")
        } else {
            VerificationResult(false, 0.3f, "Expected element not found: ${check.target}")
        }
    }

    private fun verifyElementGone(
        check: VerificationCheck,
        screenState: ScreenState,
    ): VerificationResult {
        if (check.target == null) {
            return VerificationResult(false, 0.5f, "No target specified for element_gone check")
        }

        val query = dev.hushyari.data.model.ElementQuery(
            text = check.target.text,
            textContains = check.target.textContains,
            contentDesc = check.target.contentDesc,
            contentDescContains = check.target.contentDescContains,
            resourceId = check.target.resourceId,
            className = check.target.className,
        )
        val found = screenState.findElement(query)

        return if (found == null) {
            VerificationResult(true, 0.85f, "Expected element no longer present: ${check.target}")
        } else {
            VerificationResult(false, 0.3f, "Element still present: ${check.target}")
        }
    }

    private fun verifyTextAppeared(
        check: VerificationCheck,
        screenState: ScreenState,
    ): VerificationResult {
        val expectedText = check.textContains ?: return VerificationResult(
            false, 0.5f, "No expected text specified for text_appeared check"
        )

        val allText = buildString {
            append(screenState.ocrText)
            append(' ')
            screenState.textElements.forEach { el ->
                append(el.text)
                append(' ')
                append(el.contentDescription)
                append(' ')
            }
        }

        val found = allText.contains(expectedText, ignoreCase = true)

        return if (found) {
            VerificationResult(true, 0.8f, "Expected text appeared: \"$expectedText\"")
        } else {
            VerificationResult(false, 0.3f, "Expected text not found: \"$expectedText\"")
        }
    }

    private fun verifyScreenChanged(
        before: ScreenState,
        after: ScreenState,
    ): VerificationResult {
        val changed = before.packageName != after.packageName ||
                before.activityName != after.activityName ||
                before.elementCount != after.elementCount

        if (changed) {
            return VerificationResult(true, 0.85f, "Screen state changed after action")
        }

        val beforeSummary = before.toTextSummary(maxElements = 5)
        val afterSummary = after.toTextSummary(maxElements = 5)

        val contentChanged = beforeSummary != afterSummary

        return if (contentChanged) {
            VerificationResult(true, 0.75f, "Screen content changed measurably")
        } else {
            VerificationResult(false, 0.3f, "No detectable screen change after action")
        }
    }

    private fun verifyScreenIs(
        check: VerificationCheck,
        screenState: ScreenState,
    ): VerificationResult {
        val expectedScreen = check.screenName ?: return VerificationResult(
            false, 0.5f, "No screen name specified for screen_is check"
        )

        val classified = screenState.classifiedScreen

        return if (classified != null && classified.equals(expectedScreen, ignoreCase = true)) {
            VerificationResult(true, 0.85f, "Screen matches expected: \"$expectedScreen\"")
        } else {
            VerificationResult(false, 0.2f, "Screen \"$classified\" does not match expected \"$expectedScreen\"")
        }
    }

    private fun verifyToolSuccess(toolResult: ToolResult): VerificationResult {
        return defaultVerify(toolResult)
    }

    private fun verifyResourceChanged(
        check: VerificationCheck,
        before: ScreenState,
        after: ScreenState,
    ): VerificationResult {
        val beforeText = before.ocrText.lowercase()
        val afterText = after.ocrText.lowercase()

        if (beforeText != afterText) {
            return VerificationResult(true, 0.7f, "Resource text changed on screen")
        }

        return VerificationResult(false, 0.3f, "No resource change detected on screen")
    }
}

/**
 * Result of a skill step verification.
 *
 * 🧠 4x-game-agent mechanic: Layer 4 output used by SkillEngine for retry decisions.
 */
data class VerificationResult(
    val success: Boolean,
    val confidence: Float,
    val details: String,
)
