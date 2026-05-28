package dev.hushyari.agent

import dev.hushyari.data.model.GameTask
import dev.hushyari.data.model.WorldState
import dev.hushyari.llm.ChatMessage
import dev.hushyari.llm.CloudLlmClient
import dev.hushyari.llm.LlmGenerationConfig
import dev.hushyari.llm.PromptEngine
import dev.hushyari.llm.Role
import dev.hushyari.worldmodel.WorldStateManager
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-term strategy reviewer that periodically evaluates whether the current
 * plan is still optimal given elapsed time, resource changes, and progress.
 *
 * Called every 30–120 seconds (not every frame) to minimize LLM cost while
 * enabling course correction when the situation changes significantly.
 * Returns null when no adjustment is needed.
 *
 * **Mechanics:**
 * - PokeClaw: Periodic strategy review pattern — cost-effective LLM usage.
 * - 4x-game-agent Layer 4: Meta-cognition — "is my plan still good?"
 */
@Singleton
class Strategist @Inject constructor(
    private val cloudLlm: CloudLlmClient,
    private val promptEngine: PromptEngine,
    private val worldStateManager: WorldStateManager,
) {

    private var lastReviewTime: Long = 0L
    private var reviewIntervalMs: Long = 60_000L // 60 sec default
    private var lastKnownGoal: String = ""

    /**
     * Set the review interval. Must be between 30 and 120 seconds.
     */
    fun setReviewInterval(seconds: Int) {
        reviewIntervalMs = (seconds * 1000L).coerceIn(30_000L, 120_000L)
    }

    /**
     * Review the current strategy and return an adjustment if needed.
     *
     * @param task The current game task.
     * @param state Current world model state.
     * @param sessionDuration Total session duration in milliseconds.
     * @return [StrategyAdjustment] if strategy should change, null otherwise.
     */
    suspend fun reviewStrategy(
        task: GameTask,
        state: WorldState,
        sessionDuration: Long,
    ): StrategyAdjustment? {
        val now = System.currentTimeMillis()

        // Enforce minimum review interval
        if (now - lastReviewTime < reviewIntervalMs) return null
        lastReviewTime = now

        // Only review if LLM is available
        if (!cloudLlm.isAvailable()) return null

        try {
            val prompt = buildReviewPrompt(task, state, sessionDuration)
            val messages = listOf(
                ChatMessage(Role.SYSTEM, promptEngine.buildSystemPrompt(task)),
                ChatMessage(Role.USER, prompt),
            )

            val response = cloudLlm.chat(messages, LlmGenerationConfig(
                temperature = 0.4f,
                maxTokens = 1024,
            ))

            return parseAdjustment(response.content)
        } catch (e: Exception) {
            Timber.w(e, "Strategy review failed")
            return null
        }
    }

    private fun buildReviewPrompt(
        task: GameTask,
        state: WorldState,
        sessionDuration: Long,
    ): String = buildString {
        appendLine("## Strategy Review")
        appendLine("You are reviewing whether the current strategy is still optimal.")
        appendLine()
        appendLine("### Task")
        appendLine("Goal: ${task.description}")
        appendLine("Steps taken: ${task.currentStep}")
        appendLine("Session duration: ${sessionDuration / 1000}s")
        appendLine()
        appendLine("### Current State")
        appendLine("Screen: ${state.currentScreen}")
        appendLine("HP: ${state.currentHP}/${state.maxHP}")
        appendLine("In combat: ${state.isInCombat}")
        if (state.resources.isNotEmpty()) {
            appendLine("Resources: ${state.resources.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        }
        if (state.activeTimers.isNotEmpty()) {
            val timers = state.activeTimers.values.joinToString(", ") {
                "${it.name}: ${it.remainingSeconds}s remaining"
            }
            appendLine("Timers: $timers")
        }
        if (lastKnownGoal.isNotEmpty()) {
            appendLine("Current goal: $lastKnownGoal")
        }
        appendLine()
        appendLine("### Instructions")
        appendLine("Based on the current state and progress, decide if the strategy should change.")
        appendLine("Respond with a JSON object:")
        appendLine("{")
        appendLine("  \"change_needed\": true,")
        appendLine("  \"new_goal\": \"updated strategy goal\",")
        appendLine("  \"reason\": \"why the change is needed\"")
        appendLine("}")
        appendLine()
        appendLine("If no change is needed, respond with:")
        appendLine("{")
        appendLine("  \"change_needed\": false")
        appendLine("}")
    }

    private fun parseAdjustment(response: String): StrategyAdjustment? {
        try {
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null

            val jsonStr = response.substring(jsonStart, jsonEnd + 1)
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            val changeNeeded = obj["change_needed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

            if (!changeNeeded) return null

            val newGoal = obj["new_goal"]?.jsonPrimitive?.content
                ?: obj["newGoal"]?.jsonPrimitive?.content
                ?: return null
            val reason = obj["reason"]?.jsonPrimitive?.content ?: "Unspecified reason"

            lastKnownGoal = newGoal
            Timber.i("Strategy adjusted: $newGoal — $reason")
            return StrategyAdjustment(newGoal = newGoal, reason = reason)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse strategy adjustment")
            return null
        }
    }

    /**
     * Update the last known goal for context in future reviews.
     */
    fun setCurrentGoal(goal: String) {
        lastKnownGoal = goal
    }

    /**
     * Reset the strategy review timer.
     */
    fun reset() {
        lastReviewTime = 0L
        lastKnownGoal = ""
    }
}
