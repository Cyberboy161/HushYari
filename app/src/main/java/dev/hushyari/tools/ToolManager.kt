package dev.hushyari.tools

import android.util.Log
import dev.hushyari.data.model.AgentEvent
import dev.hushyari.data.model.LogLevel
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.ToolResult
import dev.hushyari.data.model.WorldState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry and execution orchestrator for all [Tool] instances.
 *
 * Validates safety rules via [ToolSafety] before every execution,
 * routes to the correct tool by name, logs every call,
 * and emits structured [AgentEvent]s via a [SharedFlow].
 */
@Singleton
class ToolManager @Inject constructor() {

    private val tools = mutableMapOf<String, Tool>()
    val safety = ToolSafety()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private var consecutivePopups = 0

    fun register(tool: Tool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name} (${tool.category})")
    }

    fun registerAll(vararg toolList: Tool) {
        toolList.forEach { register(it) }
    }

    fun getTool(name: String): Tool? = tools[name]

    fun listTools(): List<Tool> = tools.values.toList()

    fun listToolsByCategory(category: ToolCategory): List<Tool> =
        tools.values.filter { it.category == category }

    /**
     * Execute a tool by name with the given parameters.
     *
     * Flow:
     * 1. Look up the tool
     * 2. Run safety checks against the current [screen] and [worldState]
     * 3. Validate parameters
     * 4. Execute the tool
     * 5. Record success/failure for rate limiting
     * 6. Emit an event
     */
    suspend fun execute(
        toolName: String,
        params: Map<String, Any?>,
        screen: ScreenState,
        worldState: WorldState,
    ): ToolResult {
        val startTime = System.currentTimeMillis()
        val tool = tools[toolName]

        if (tool == null) {
            val result = ToolResult.Failure(
                toolName = toolName,
                error = "Unknown tool: $toolName",
                recoverable = false,
            )
            Log.w(TAG, "Tool not found: $toolName")
            emitEvent(AgentEvent.Log(LogLevel.WARNING, "Tool not found: $toolName"))
            return result
        }

        val gamePackage = screen.packageName.ifEmpty { worldState.gamePackage }

        val safetyChecks = safety.checkAll(
            screen = screen,
            worldState = worldState,
            params = params,
            toolName = toolName,
            gamePackage = gamePackage,
            consecutivePopups = consecutivePopups,
        )

        val blockedRule = safetyChecks.firstOrNull { it.first }
        if (blockedRule != null) {
            val (_, reason) = blockedRule
            val result = ToolResult.SafetyBlocked(
                toolName = toolName,
                executionTimeMs = System.currentTimeMillis() - startTime,
                rule = "safety_check",
                reason = reason,
            )
            Log.w(TAG, "Safety blocked $toolName: $reason")
            emitEvent(AgentEvent.SafetyTriggered(rule = "safety_check", reason = reason))
            emitEvent(AgentEvent.Log(LogLevel.WARNING, "Safety blocked $toolName: $reason"))
            return result
        }

        if (!tool.validateParams(params)) {
            val result = ToolResult.Failure(
                toolName = toolName,
                error = "Invalid parameters for $toolName",
                errorCode = dev.hushyari.data.model.ErrorCode.INVALID_PARAMS,
                recoverable = true,
            )
            Log.w(TAG, "Invalid params for $toolName: $params")
            emitEvent(AgentEvent.Log(LogLevel.WARNING, "Invalid params for $toolName"))
            return result
        }

        try {
            val result = tool.execute(params)
            val executionTime = System.currentTimeMillis() - startTime

            when (result) {
                is ToolResult.Success -> {
                    safety.recordSuccess(toolName)
                    consecutivePopups = 0
                }
                is ToolResult.Failure -> {
                    safety.recordFailure(toolName)
                }
                is ToolResult.SafetyBlocked,
                is ToolResult.Timeout -> {
                    safety.recordFailure(toolName)
                }
            }

            Log.d(TAG, "Executed $toolName in ${executionTime}ms: ${result.isSuccess}")
            emitEvent(
                AgentEvent.ActionCompleted(
                    result = result.copyWithTime(executionTime),
                    step = 0,
                )
            )

            return result.copyWithTime(executionTime)
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            safety.recordFailure(toolName)
            val result = ToolResult.Failure(
                toolName = toolName,
                executionTimeMs = executionTime,
                error = "${e.javaClass.simpleName}: ${e.message}",
                recoverable = true,
            )
            Log.e(TAG, "Tool $toolName failed", e)
            emitEvent(AgentEvent.ActionCompleted(result = result, step = 0))
            return result
        }
    }

    /**
     * Suspend the caller until the tool reports a non-pending result.
     */
    suspend fun executeAndWait(
        toolName: String,
        params: Map<String, Any?>,
        screen: ScreenState,
        worldState: WorldState,
    ): ToolResult = execute(toolName, params, screen, worldState)

    fun onPopupDetected() {
        consecutivePopups++
    }

    fun resetPopupCount() {
        consecutivePopups = 0
    }

    private suspend fun emitEvent(event: AgentEvent) {
        _events.emit(event)
    }

    /**
     * Emit a log event for external observers.
     */
    suspend fun log(level: LogLevel, message: String) {
        _events.emit(AgentEvent.Log(level, message))
    }

    /**
     * Reset internal state (rate limits, popup counters).
     */
    fun reset() {
        safety.resetPlaySession()
        resetPopupCount()
        Log.d(TAG, "ToolManager reset")
    }

    private fun ToolResult.copyWithTime(timeMs: Long): ToolResult = when (this) {
        is ToolResult.Success -> copy(executionTimeMs = timeMs)
        is ToolResult.Failure -> copy(executionTimeMs = timeMs)
        is ToolResult.SafetyBlocked -> copy(executionTimeMs = timeMs)
        is ToolResult.Timeout -> copy(executionTimeMs = timeMs)
    }

    companion object {
        private const val TAG = "ToolManager"
    }
}
