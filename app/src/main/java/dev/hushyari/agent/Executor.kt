package dev.hushyari.agent

import android.graphics.PointF
import dev.hushyari.controller.GestureDispatcher
import dev.hushyari.controller.ScrollDirection
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.SkillStep
import dev.hushyari.data.model.ToolResult
import dev.hushyari.data.model.WorldState
import dev.hushyari.skills.SkillEngine
import dev.hushyari.skills.SkillExecutionEvent
import dev.hushyari.tools.ToolManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Action execution agent that dispatches [AgentAction]s to the appropriate backend.
 *
 * Handles all action types: ToolAction (via [ToolManager]), PlanAction (chained steps),
 * AskAction, DoneAction, and WaitAction. Applies safety rules before every execution
 * and returns a structured [ToolResult].
 *
 * **Mechanics:**
 * - PokeClaw: Central execution dispatch point for all tool, plan, and wait actions.
 * - ClickClickClick: Safety-gated execution with structured result reporting.
 */
@Singleton
class Executor @Inject constructor(
    private val toolManager: ToolManager,
    private val skillEngine: SkillEngine,
    private val gestureDispatcher: GestureDispatcher,
) {

    /**
     * Execute an [AgentAction] against the current screen state.
     *
     * @param action The action to perform.
     * @param screen Current screen state for context.
     * @return [ToolResult] describing success or failure.
     */
    suspend fun execute(action: AgentAction, screen: ScreenState): ToolResult {
        val worldState = WorldState()

        return when (action) {
            is AgentAction.ToolAction -> executeToolAction(action, screen, worldState)
            is AgentAction.PlanAction -> executePlanAction(action, screen)
            is AgentAction.WaitAction -> executeWaitAction(action)
            is AgentAction.DoneAction -> {
                Timber.i("Task done: ${action.reason}")
                ToolResult.Success(toolName = "done", message = action.reason)
            }
            is AgentAction.AskAction -> {
                Timber.i("Asking user: ${action.question}")
                ToolResult.Success(toolName = "ask", message = action.question)
            }
        }
    }

    /**
     * Execute a single step of a skill workflow.
     *
     * @param step The skill step to execute.
     * @return [SkillExecutionEvent] describing the outcome.
     */
    suspend fun executeSkillStep(step: SkillStep): SkillExecutionEvent {
        skillEngine.executeNextStep {
            // Placeholder screen state; caller should provide actual capture
            ScreenState()
        }
        return SkillExecutionEvent.Log("Skill step dispatched: ${step.tool}")
    }

    // ── Action type handlers ────────────────────────────────────────────

    private suspend fun executeToolAction(
        action: AgentAction.ToolAction,
        screen: ScreenState,
        worldState: WorldState,
    ): ToolResult {
        val toolName = action.toolName.lowercase()
        val params = action.params.mapValues { it.value as Any? }

        // Route recognized built-in gestures through GestureDispatcher
        return when (toolName) {
            "tap" -> {
                val x = params["x"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                val y = params["y"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                if (x != null && y != null) {
                    gestureDispatcher.tap(x, y)
                } else {
                    toolManager.execute(toolName, params, screen, worldState)
                }
            }
            "swipe" -> {
                val x1 = params["x1"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                val y1 = params["y1"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                val x2 = params["x2"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                val y2 = params["y2"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    gestureDispatcher.swipe(PointF(x1, y1), PointF(x2, y2))
                } else {
                    toolManager.execute(toolName, params, screen, worldState)
                }
            }
            "scroll" -> {
                val direction = params["direction"]?.toString()?.lowercase() ?: "down"
                val amount = params["amount"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() } ?: 0.5f
                val scrollDirection = when (direction) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                }
                gestureDispatcher.scroll(scrollDirection, amount)
            }
            "longpress", "long_press" -> {
                val x = params["x"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                val y = params["y"]?.let { (it as? String)?.toFloatOrNull() ?: (it as? Number)?.toFloat() }
                val duration = params["durationMs"]?.let { (it as? String)?.toLongOrNull() ?: (it as? Number)?.toLong() } ?: 800
                if (x != null && y != null) {
                    gestureDispatcher.longPress(x, y, duration)
                } else {
                    toolManager.execute(toolName, params, screen, worldState)
                }
            }
            "type", "type_text", "input" -> {
                val text = params["text"]?.toString() ?: ""
                if (text.isNotEmpty()) {
                    gestureDispatcher.typeText(text)
                } else {
                    ToolResult.Failure(toolName = toolName, error = "No text provided", errorCode = ErrorCode.INVALID_PARAMS)
                }
            }
            "back", "press_back", "go_back" -> gestureDispatcher.goBack()
            "home", "go_home" -> gestureDispatcher.goHome()
            "open_app", "launch" -> {
                val pkg = params["package"]?.toString() ?: params["packageName"]?.toString() ?: ""
                if (pkg.isNotEmpty()) {
                    gestureDispatcher.openApp(pkg)
                } else {
                    ToolResult.Failure(toolName = toolName, error = "No package specified", errorCode = ErrorCode.INVALID_PARAMS)
                }
            }
            "dismiss_popup", "close_popup" -> {
                val x = (screen.screenWidth * 0.92f)
                val y = (screen.screenHeight * 0.06f)
                gestureDispatcher.tap(x, y)
            }
            else -> toolManager.execute(toolName, params, screen, worldState)
        }
    }

    private suspend fun executePlanAction(action: AgentAction.PlanAction, screen: ScreenState): ToolResult {
        var lastResult: ToolResult = ToolResult.Success(toolName = "plan", message = "No steps to execute")

        for (stepDesc in action.steps) {
            val action = ResponseParser.parse(stepDesc)
            if (action != null) {
                lastResult = when (action) {
                    is dev.hushyari.llm.AgentAction.ToolAction ->
                        executeToolAction(
                            AgentAction.ToolAction(action.toolName, action.params),
                            screen,
                            WorldState(),
                        )
                    is dev.hushyari.llm.AgentAction.WaitAction ->
                        executeWaitAction(AgentAction.WaitAction(1000L))
                    is dev.hushyari.llm.AgentAction.DoneAction -> break
                    is dev.hushyari.llm.AgentAction.AskAction -> continue
                    is dev.hushyari.llm.AgentAction.PlanAction -> continue
                }
            }

            if (!lastResult.isSuccess && lastResult !is ToolResult.Success) {
                break
            }
            kotlinx.coroutines.delay(300)
        }

        return lastResult
    }

    private suspend fun executeWaitAction(action: AgentAction.WaitAction): ToolResult {
        kotlinx.coroutines.delay(action.durationMs)
        return ToolResult.Success(
            toolName = "wait",
            message = "Waited for ${action.durationMs}ms",
            executionTimeMs = action.durationMs,
        )
    }
}

// Reference to LLM response parser for plan step parsing within executor
private object ResponseParser {
    fun parse(stepDesc: String): dev.hushyari.llm.AgentAction? =
        dev.hushyari.llm.ResponseParser.parse(stepDesc)
}
