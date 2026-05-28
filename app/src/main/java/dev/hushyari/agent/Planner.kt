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
 * Step-by-step action planner that generates a sequence of [AgentAction]s
 * to accomplish a sub-goal, using the LLM for reasoning.
 *
 * Uses cloud LLM for complex chains (3+ steps) and local LLM for simple ones
 * (1-2 steps). Returns at most 5 actions at a time — the agent loop re-plans
 * after executing them to account for changing game state.
 *
 * **Mechanics:**
 * - ClickClickClick: Planner role — multi-step action chain planning.
 * - PokeClaw: Don't over-plan — return ≤5 actions, re-plan after execution.
 */
@Singleton
class Planner @Inject constructor(
    private val cloudLlm: CloudLlmClient,
    private val localLlm: LocalLlmClient,
    private val promptEngine: PromptEngine,
) {

    /**
     * Plan the next sequence of actions to advance towards the task goal.
     *
     * @param task The current game task.
     * @param screen Current screen state.
     * @param worldState Current world model state.
     * @param history Recent conversation history for context.
     * @param llmConfig LLM configuration override.
     * @return List of [AgentAction]s to execute (max 5), or empty if nothing to do.
     */
    suspend fun planNextActions(
        task: GameTask,
        screen: ScreenState,
        worldState: WorldState,
        history: List<ChatMessage>,
        llmConfig: LlmConfig,
    ): List<AgentAction> {
        val systemPrompt = promptEngine.buildSystemPrompt(task)
        val actionPrompt = promptEngine.buildActionPrompt(screen, worldState, task)

        val messages = buildList {
            add(ChatMessage(Role.SYSTEM, systemPrompt))
            addAll(history.takeLast(8))
            add(ChatMessage(Role.USER, actionPrompt))
            add(ChatMessage(Role.USER, buildSequencePrompt()))
        }

        val response = if (llmConfig.isLocal || !cloudLlm.isAvailable()) {
            localLlm.chat(messages, LlmGenerationConfig(
                temperature = 0.5f,
                maxTokens = 2048,
            ))
        } else {
            cloudLlm.configure(llmConfig)
            cloudLlm.chat(messages, LlmGenerationConfig(
                temperature = llmConfig.temperature,
                maxTokens = llmConfig.maxTokens,
            ))
        }

        return parseActionSequence(response.content)
    }

    /**
     * Build the prompt instructing the LLM to output an action sequence.
     */
    private fun buildSequencePrompt(): String = buildString {
        appendLine("## Plan Next Actions")
        appendLine("Provide a sequence of up to 5 actions to accomplish the current goal.")
        appendLine("Each action must be a JSON object. Respond with a JSON array:")
        appendLine("[")
        appendLine("  {\"tool\": \"tap\", \"params\": {\"x\": \"100\", \"y\": \"200\"}},")
        appendLine("  {\"tool\": \"wait\", \"params\": {\"ms\": \"1000\"}},")
        appendLine("  {\"tool\": \"done\", \"params\": {\"reason\": \"task complete\"}}")
        appendLine("]")
        appendLine()
        appendLine("Available tools: tap, swipe, scroll, type_text, press_back, wait, done")
        appendLine("Do NOT output more than 5 actions. Prefer short sequences.")
    }

    /**
     * Parse the LLM's action sequence response into a list of [AgentAction]s.
     * Handles both JSON array format and individual line-by-line format.
     */
    private fun parseActionSequence(response: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()

        try {
            // Try parsing as JSON array
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd + 1)
                val json = kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
                val array = json.parseToJsonElement(jsonStr).jsonArray
                for (element in array) {
                    val obj = element.jsonObject
                    val tool = obj["tool"]?.jsonPrimitive?.content ?: continue
                    val params = buildMap {
                        obj["params"]?.jsonObject?.forEach { entry ->
                            put(entry.key, entry.value.jsonPrimitive.content)
                        }
                    }
                    val action = mapToAgentAction(tool, params)
                    if (action != null) {
                        actions.add(action)
                    }
                }
                // Cap at 5 actions
                return actions.take(5)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse action sequence as JSON array")
        }

        // Fallback: parse individual actions line by line
        for (line in response.lines()) {
            val parsed = ResponseParser.parse(line.trim())
            if (parsed != null) {
                val mapped = mapAgentAction(parsed)
                if (mapped != null) {
                    actions.add(mapped)
                }
            }
            if (actions.size >= 5) break
        }

        return actions
    }

    private fun mapToAgentAction(tool: String, params: Map<String, String>): AgentAction? {
        return when (tool.lowercase()) {
            "done", "complete", "finish" ->
                AgentAction.DoneAction(reason = params["reason"] ?: "Task complete")
            "wait" ->
                AgentAction.WaitAction(durationMs = params["ms"]?.toLongOrNull() ?: 1000L)
            else ->
                AgentAction.ToolAction(toolName = tool, params = params)
        }
    }

    private fun mapAgentAction(action: dev.hushyari.llm.AgentAction): AgentAction? {
        return when (action) {
            is dev.hushyari.llm.AgentAction.ToolAction ->
                AgentAction.ToolAction(action.toolName, action.params)
            is dev.hushyari.llm.AgentAction.PlanAction ->
                AgentAction.PlanAction(action.steps)
            is dev.hushyari.llm.AgentAction.AskAction ->
                AgentAction.AskAction(action.question)
            is dev.hushyari.llm.AgentAction.DoneAction ->
                AgentAction.DoneAction(action.reason)
            is dev.hushyari.llm.AgentAction.WaitAction ->
                AgentAction.WaitAction(1000L)
        }
    }
}
