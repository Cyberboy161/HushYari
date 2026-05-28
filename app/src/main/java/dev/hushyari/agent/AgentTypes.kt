package dev.hushyari.agent

/**
 * Domain types shared across the agent system.
 *
 * **Mechanics:**
 * - ClickClickClick: Structured agent actions drive the execution ladder.
 * - PokeClaw: Plan, Reflection, UrgentAction, and StrategyAdjustment
 *   enable the full observe–plan–execute–reflect cycle.
 */
sealed class AgentAction {
    data class ToolAction(
        val toolName: String,
        val params: Map<String, String> = emptyMap(),
    ) : AgentAction()

    data class PlanAction(
        val steps: List<String>,
    ) : AgentAction()

    data class AskAction(
        val question: String,
    ) : AgentAction()

    data class DoneAction(
        val reason: String,
    ) : AgentAction()

    data class WaitAction(
        val durationMs: Long,
    ) : AgentAction()
}

data class Plan(
    val subTasks: List<SubTask>,
    val reasoning: String,
)

data class SubTask(
    val description: String,
    val priority: Int,
    val preconditions: List<String>,
)

data class Reflection(
    val success: Boolean,
    val confidence: Float,
    val summary: String,
)

data class UrgentAction(
    val description: String,
    val action: AgentAction,
    val reason: String,
)

data class StrategyAdjustment(
    val newGoal: String,
    val reason: String,
)
