package dev.hushyari.tools

import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val WAIT_PERMISSIONS = emptyList<String>()

private val WAIT_SAFETY_RULES = listOf(
    "checkPlayTimeLimit",
    "checkRateLimit",
)

/**
 * Type alias for a condition check that returns true when satisfied.
 */
typealias ConditionCheck = suspend () -> Boolean

/**
 * Wait tool: pause execution until a condition is met or a timeout elapses.
 *
 * Actions:
 * - "wait_for"   — loop checking a condition until satisfied or timeout
 * - "wait_until" — wait until an external signal or time-based condition
 * - "sleep"      — simple delay for the given number of milliseconds
 *
 * Params:
 * - "action"        — "wait_for", "wait_until", "sleep"
 * - "timeout_ms"    — maximum wait time in ms (default 30000 for wait, 1000 for sleep)
 * - "poll_interval_ms" — interval between condition checks (default 500ms)
 * - "description"   — human-readable description of what is being waited for
 *
 * When used with [ToolManager], the condition can be checked
 * against the current [ScreenState] (e.g., waiting for an element to appear).
 */
@Singleton
class WaitTool @Inject constructor() : Tool {

    override val name = "wait"
    override val description = "Wait for a condition, element, screen, or delay execution"
    override val category = ToolCategory.WAIT
    override val requiredPermissions = WAIT_PERMISSIONS
    override val safetyRules = WAIT_SAFETY_RULES

    private var conditionChecker: ConditionCheck? = null

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        return action in setOf("wait_for", "wait_until", "sleep")
    }

    /**
     * Register a condition checker used by wait_for / wait_until actions.
     */
    fun setConditionChecker(checker: ConditionCheck?) {
        conditionChecker = checker
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return invalidParams("action is required (wait_for/wait_until/sleep)")
        val description = params["description"]?.toString() ?: action

        return try {
            when (action) {
                "wait_for" -> executeWaitFor(params, description)
                "wait_until" -> executeWaitUntil(params, description)
                "sleep" -> executeSleep(params, description)
                else -> invalidParams("Unknown action: $action")
            }
        } catch (e: Exception) {
            ToolResult.Failure(
                toolName = name,
                error = "Wait interrupted: ${e.message}",
                errorCode = ErrorCode.INTERRUPTED,
            )
        }
    }

    private suspend fun executeWaitFor(params: Map<String, Any?>, description: String): ToolResult {
        val timeoutMs = (params["timeout_ms"] as? Number)?.toLong() ?: 30_000L
        val pollIntervalMs = (params["poll_interval_ms"] as? Number)?.toLong() ?: 500L
        val checker = conditionChecker ?: return ToolResult.Failure(
            toolName = name,
            error = "No condition checker registered for wait_for",
            errorCode = ErrorCode.INVALID_PARAMS,
        )

        val result = withTimeoutOrNull(timeoutMs) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var iterations = 0
            while (System.currentTimeMillis() < deadline) {
                if (checker()) {
                    return@withTimeoutOrNull true
                }
                iterations++
                delay(pollIntervalMs.coerceIn(50L, 10_000L))
            }
            null
        }

        return if (result == true) {
            ToolResult.Success(
                toolName = name,
                message = "Condition satisfied: $description",
                data = mapOf("description" to description, "timeout_ms" to timeoutMs),
            )
        } else {
            ToolResult.Timeout(
                toolName = name,
                timeoutMs = timeoutMs,
            )
        }
    }

    private suspend fun executeWaitUntil(params: Map<String, Any?>, description: String): ToolResult {
        val timeoutMs = (params["timeout_ms"] as? Number)?.toLong() ?: 30_000L
        val pollIntervalMs = (params["poll_interval_ms"] as? Number)?.toLong() ?: 500L
        val checker = conditionChecker ?: return ToolResult.Failure(
            toolName = name,
            error = "No condition checker registered for wait_until",
            errorCode = ErrorCode.INVALID_PARAMS,
        )

        val result = withTimeoutOrNull(timeoutMs) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var iterations = 0
            while (System.currentTimeMillis() < deadline) {
                if (checker()) {
                    return@withTimeoutOrNull true
                }
                iterations++
                delay(pollIntervalMs.coerceIn(50L, 10_000L))
            }
            null
        }

        return if (result == true) {
            ToolResult.Success(
                toolName = name,
                message = "Condition met: $description",
                data = mapOf("description" to description, "timeout_ms" to timeoutMs),
            )
        } else {
            ToolResult.Timeout(
                toolName = name,
                timeoutMs = timeoutMs,
            )
        }
    }

    private suspend fun executeSleep(params: Map<String, Any?>, description: String): ToolResult {
        val durationMs = (params["timeout_ms"] as? Number)?.toLong()
            ?: (params["duration_ms"] as? Number)?.toLong()
            ?: (params["millis"] as? Number)?.toLong()
            ?: 1000L

        val clampedDuration = durationMs.coerceIn(0L, 300_000L)

        delay(clampedDuration)

        return ToolResult.Success(
            toolName = name,
            message = "Slept for ${clampedDuration}ms: $description",
            data = mapOf("duration_ms" to clampedDuration, "description" to description),
        )
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
