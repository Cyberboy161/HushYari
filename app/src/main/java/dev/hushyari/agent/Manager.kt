package dev.hushyari.agent

import dev.hushyari.data.model.GameTask
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.WorldState
import dev.hushyari.llm.ChatMessage
import dev.hushyari.llm.CloudLlmClient
import dev.hushyari.llm.LlmConfig
import dev.hushyari.llm.LlmGenerationConfig
import dev.hushyari.llm.LocalLlmClient
import dev.hushyari.llm.PromptEngine
import dev.hushyari.llm.ResponseParser
import dev.hushyari.llm.Role
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task decomposition agent that analyzes the current game state and
 * breaks the user's high-level task into a structured [Plan] of prioritized [SubTask]s.
 *
 * Uses LLM reasoning to determine the optimal sequence of sub-goals.
 * Prioritization order: survival > objectives > resource gathering > exploration.
 *
 * **Mechanics:**
 * - 4x-game-agent Layer 4: Task decomposition into executable sub-goals.
 * - PokeClaw: Structured plan with preconditions for reliable execution.
 */
@Singleton
class Manager @Inject constructor(
    private val promptEngine: PromptEngine,
    private val cloudLlm: CloudLlmClient,
    private val localLlm: LocalLlmClient,
) {

    /**
     * Generate a structured [Plan] from a [GameTask] and current game state.
     *
     * @param task The user's high-level task.
     * @param screen Current screen state for context.
     * @param worldState Current world model state.
     * @param config LLM configuration for the planning call.
     * @return A [Plan] with ordered list of [SubTask]s and reasoning.
     */
    suspend fun plan(
        task: GameTask,
        screen: ScreenState,
        worldState: WorldState,
        config: LlmConfig,
    ): Plan {
        val systemPrompt = promptEngine.buildSystemPrompt(task)
        val actionPrompt = buildPlanPrompt(screen, worldState, task)

        val messages = listOf(
            ChatMessage(Role.SYSTEM, systemPrompt),
            ChatMessage(Role.USER, actionPrompt),
        )

        val response = if (cloudLlm.isAvailable()) {
            cloudLlm.configure(config)
            cloudLlm.chat(messages, LlmGenerationConfig(
                temperature = config.temperature,
                maxTokens = config.maxTokens,
            ))
        } else {
            localLlm.chat(messages, LlmGenerationConfig(
                temperature = 0.5f,
                maxTokens = config.maxTokens,
            ))
        }

        return parsePlan(response.content, task)
    }

    /**
     * Build the prompt for plan generation.
     */
    private fun buildPlanPrompt(
        screen: ScreenState,
        worldState: WorldState,
        task: GameTask,
    ): String = buildString {
        appendLine("## Planning Request")
        appendLine("Break down the following task into prioritized sub-tasks:")
        appendLine("Task: ${task.description}")
        appendLine()
        appendLine("## Current State")
        appendLine(screen.toTextSummary(maxElements = 15))
        appendLine()
        appendLine("## World State")
        appendLine(worldState.toCompactString())
        appendLine()
        appendLine("## Priority Rules")
        appendLine("1. SURVIVAL: healing, defense, avoiding threats")
        appendLine("2. OBJECTIVES: mission goals, quests, main task")
        appendLine("3. RESOURCE GATHERING: collecting, farming, earning")
        appendLine("4. EXPLORATION: scouting, map discovery")
        appendLine()
        appendLine("Respond with a JSON object:")
        appendLine("{")
        appendLine("  \"reasoning\": \"explain your strategy\",")
        appendLine("  \"subTasks\": [")
        appendLine("    {")
        appendLine("      \"description\": \"what to do\",")
        appendLine("      \"priority\": 1,")
        appendLine("      \"preconditions\": [\"must be on home screen\"]")
        appendLine("    }")
        appendLine("  ]")
        appendLine("}")
    }

    /**
     * Parse the LLM response into a structured [Plan].
     */
    private fun parsePlan(response: String, task: GameTask): Plan {
        try {
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd + 1)
                val json = kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                val reasoning = obj["reasoning"]?.jsonPrimitive?.content ?: "Plan for: ${task.description}"
                val subTasks = obj["subTasks"]?.jsonArray?.mapNotNull { element ->
                    val t = element.jsonObject
                    val desc = t["description"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val priority = t["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5
                    val preconditions = t["preconditions"]?.jsonArray?.mapNotNull {
                        it.jsonPrimitive?.content
                    } ?: emptyList()
                    SubTask(
                        description = desc,
                        priority = priority,
                        preconditions = preconditions,
                    )
                }?.sortedBy { it.priority } ?: emptyList()

                return Plan(subTasks = subTasks, reasoning = reasoning)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse plan JSON, using heuristic fallback")
        }

        // Heuristic fallback: single sub-task
        return Plan(
            subTasks = listOf(
                SubTask(
                    description = task.description,
                    priority = 1,
                    preconditions = emptyList(),
                )
            ),
            reasoning = "Fallback: execute task directly",
        )
    }
}
