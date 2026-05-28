package dev.hushyari.llm

import dev.hushyari.data.model.GameTask
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.WorldState
import javax.inject.Inject

/**
 * Maximum number of recent actions to include in history for the prompt.
 */
private const val MAX_HISTORY_ACTIONS = 10

/**
 * Builds structured prompts for the LLM by combining system instructions, game context,
 * screen state, world model, and conversation history.
 * 🧠 PokeClaw mechanic: Combines PromptTemplates with runtime state to produce
 * token-efficient prompts that fit within the model's context window.
 */
class PromptEngine @Inject constructor() {

    /**
     * Build the system prompt with game-specific knowledge injected.
     *
     * @param gameTask The user's requested task.
     * @param gameConfig Optional game-specific config (package name, settings).
     * @param safetyRules Additional safety constraints beyond defaults.
     */
    fun buildSystemPrompt(
        gameTask: GameTask,
        gameConfig: Map<String, Any> = emptyMap(),
        safetyRules: List<String> = emptyList(),
    ): String {
        val gamePackage = gameTask.gamePackage.ifEmpty {
            gameConfig["packageName"] as? String ?: ""
        }

        val template = PromptTemplates.forGame(gamePackage)
        val basePrompt = template?.systemPrompt?.ifEmpty { null }
            ?: PromptTemplates.SYSTEM_PROMPT_BASE

        return buildString {
            appendLine(basePrompt)
            appendLine()

            if (template != null && template.uiKnowledge.isNotBlank()) {
                appendLine(template.uiKnowledge)
                appendLine()
            }

            // Strategy
            val strategies = template?.strategies ?: emptyList()
            if (strategies.isNotEmpty()) {
                appendLine("## Game-Specific Strategies")
                strategies.forEach { appendLine("- $it") }
                appendLine()
            }

            // Common elements
            val elements = template?.commonElements ?: emptyMap()
            if (elements.isNotEmpty()) {
                appendLine("## Common UI Elements")
                elements.forEach { (name, label) ->
                    appendLine("- $name = \"$label\"")
                }
                appendLine()
            }

            // Skills
            val skills = template?.skills ?: emptyMap()
            if (skills.isNotEmpty()) {
                appendLine("## Available Game Skills")
                skills.forEach { (name, desc) ->
                    appendLine("- $name: $desc")
                }
                appendLine()
            }

            // Task context
            appendLine("## Current Task")
            appendLine("Goal: ${gameTask.description}")
            if (gamePackage.isNotBlank()) {
                appendLine("Game: $gamePackage")
            }
            appendLine()

            // Safety
            val allSafetyRules = buildList {
                add("Do NOT make purchases unless explicitly allowed")
                add("Do NOT send chat messages unless explicitly allowed")
                add("Do NOT delete items or heroes")
                addAll(safetyRules)
            }
            if (gameTask.allowSpending) {
                appendLine("Purchases are ALLOWED for this task.")
            }
            if (gameTask.allowChatMessages) {
                appendLine("Chat messages are ALLOWED for this task.")
            }
            if (allSafetyRules.isNotEmpty()) {
                appendLine("## Additional Safety Rules")
                allSafetyRules.forEach { appendLine("- $it") }
            }
        }
    }

    /**
     * Build the action prompt that includes current screen state, world state,
     * task progress, and recent history.
     */
    fun buildActionPrompt(
        screen: ScreenState,
        worldState: WorldState,
        task: GameTask,
        history: List<String> = emptyList(),
    ): String {
        return buildString {
            appendLine("## Current Screen")
            append(screen.toTextSummary())
            appendLine()

            if (screen.ocrText.isNotBlank()) {
                appendLine("## OCR Text")
                appendLine(screen.ocrText.take(1000))
                appendLine()
            }

            appendLine("## World State")
            appendLine(worldState.toCompactString())
            appendLine()

            appendLine("## Task Progress")
            appendLine("Step: ${task.currentStep} / ${task.maxSteps}")
            appendLine()

            // Recent history
            if (history.isNotEmpty()) {
                appendLine("## Recent Actions")
                history.takeLast(MAX_HISTORY_ACTIONS).forEach { action ->
                    appendLine("- $action")
                }
                appendLine()
            }

            appendLine("What is the next action? Respond with exactly ONE JSON action object.")
        }
    }

    /**
     * Build the complete list of [ChatMessage] for an LLM call.
     *
     * @param conversationHistory Previous turns (user/assistant pairs).
     * @param screen Current screen state.
     * @param worldState Current world model state.
     * @param task The user's task.
     * @param actionHistory Recent action descriptions.
     * @param gameConfig Optional game-specific configuration.
     * @param safetyRules Additional safety constraints.
     * @param tokenBudget Maximum allowed tokens (trims history if needed).
     */
    fun buildMessages(
        conversationHistory: List<ChatMessage> = emptyList(),
        screen: ScreenState,
        worldState: WorldState,
        task: GameTask,
        actionHistory: List<String> = emptyList(),
        imageRequired: Boolean = false,
        gameConfig: Map<String, Any> = emptyMap(),
        safetyRules: List<String> = emptyList(),
        tokenBudget: Int = 128_000,
    ): List<ChatMessage> {
        val systemPrompt = buildSystemPrompt(task, gameConfig, safetyRules)
        val actionPrompt = buildActionPrompt(screen, worldState, task, actionHistory)

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage(Role.SYSTEM, systemPrompt))

        // Insert conversation history, trimmed to fit budget
        val historyTokens = conversationHistory.sumOf { estimateTokens(it.content) }
        val actionTokens = estimateTokens(actionPrompt)
        val systemTokens = estimateTokens(systemPrompt)
        val availableForHistory = tokenBudget - systemTokens - actionTokens - 1024

        if (historyTokens > availableForHistory && availableForHistory > 0) {
            val ratio = availableForHistory.toDouble() / historyTokens
            val trimmed = trimHistory(conversationHistory, ratio)
            messages.addAll(trimmed)
        } else if (availableForHistory > 0) {
            messages.addAll(conversationHistory)
        }

        messages.add(ChatMessage(Role.USER, actionPrompt))
        return messages
    }

    /**
     * Estimate token count using the 4 chars/token heuristic.
     */
    private fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)

    /**
     * Trim history to approximately [ratio] of original length,
     * keeping most recent messages.
     */
    private fun trimHistory(
        history: List<ChatMessage>,
        ratio: Double,
    ): List<ChatMessage> {
        if (ratio >= 1.0) return history
        val keepCount = maxOf(1, (history.size * ratio).toInt())
        return history.takeLast(keepCount)
    }
}
