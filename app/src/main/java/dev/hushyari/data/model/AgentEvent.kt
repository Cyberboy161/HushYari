package dev.hushyari.data.model

/**
 * Events emitted by the agent loop for UI observation.
 * 🧠 PokeClaw + Roubao mechanic: Typed events flowing from agent to UI.
 */
sealed class AgentEvent {
    data object AgentStarted : AgentEvent()
    data object AgentStopped : AgentEvent()
    data object AgentPaused : AgentEvent()
    data object AgentResumed : AgentEvent()

    data class ScreenCaptured(
        val screen: ScreenState,
        val captureTimeMs: Long = 0,
    ) : AgentEvent()

    data class ScreenClassified(
        val screenName: String,
        val confidence: Float = 1f,
    ) : AgentEvent()

    data class ActionStarted(
        val actionDescription: String,
        val step: Int,
    ) : AgentEvent()

    data class ActionCompleted(
        val result: ToolResult,
        val step: Int,
    ) : AgentEvent()

    data class SkillStarted(
        val skillName: String,
    ) : AgentEvent()

    data class SkillCompleted(
        val skillName: String,
        val success: Boolean,
    ) : AgentEvent()

    data class WorldModelUpdated(
        val changes: List<String>,
    ) : AgentEvent()

    data class PopupDetected(
        val popupType: String,
        val autoHandled: Boolean = true,
    ) : AgentEvent()

    data class LLMCallStarted(
        val layer: Int,
        val purpose: String,
    ) : AgentEvent()

    data class LLMCallCompleted(
        val layer: Int,
        val latencyMs: Long,
        val tokensUsed: Int = 0,
    ) : AgentEvent()

    data class Log(
        val level: LogLevel,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent()

    data class Error(
        val error: String,
        val recoverable: Boolean = true,
    ) : AgentEvent()

    data class SafetyTriggered(
        val rule: String,
        val reason: String,
    ) : AgentEvent()

    data class TaskCompleted(
        val task: GameTask,
        val totalSteps: Int,
        val totalTimeMs: Long,
    ) : AgentEvent()

    data class TaskFailed(
        val task: GameTask,
        val reason: String,
    ) : AgentEvent()
}

enum class LogLevel { DEBUG, INFO, WARNING, ERROR }
