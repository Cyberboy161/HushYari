package dev.hushyari.agent

import dev.hushyari.data.model.AgentEvent
import dev.hushyari.data.model.LogLevel
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.WorldState
import dev.hushyari.worldmodel.WorldStateManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Important observation recorder that tracks notable events and state changes
 * for summarization in future LLM prompts and context history.
 *
 * Records: resource changes, timer updates, screen transitions, enemy positions,
 * combat state changes, and error conditions. Summarizes into a compact format
 * suitable for injecting as context into the next LLM call.
 *
 * **Mechanics:**
 * - PokeClaw: Chat history enrichment with structured observations.
 * - 4x-game-agent Layer 4: Observation recording for the agent's memory system.
 */
@Singleton
class Notetaker @Inject constructor(
    private val worldStateManager: WorldStateManager,
) {

    private val observations = mutableListOf<Observation>()
    private var lastScreen: String = ""
    private var lastResourceSnapshot: Map<String, Long> = emptyMap()

    companion object {
        private const val MAX_OBSERVATIONS = 50
    }

    /**
     * Record an observation from the current frame.
     *
     * @param screen Current screen state.
     * @param worldState Current world model state.
     * @param event The agent event to record observations from.
     */
    fun record(screen: ScreenState, worldState: WorldState, event: AgentEvent) {
        val notes = mutableListOf<String>()

        // Track screen transitions
        if (screen.classifiedScreen != null && screen.classifiedScreen != lastScreen && lastScreen.isNotEmpty()) {
            notes.add("Screen: $lastScreen → ${screen.classifiedScreen}")
        }
        if (screen.classifiedScreen != null) {
            lastScreen = screen.classifiedScreen!!
        }

        // Track resource changes
        val currentResources = worldState.resources.toMap()
        if (lastResourceSnapshot.isNotEmpty()) {
            for ((key, value) in currentResources) {
                val previous = lastResourceSnapshot[key] ?: 0L
                if (previous != value) {
                    val delta = value - previous
                    val direction = if (delta > 0) "+" else ""
                    notes.add("Resource $key: $previous → $value ($direction$delta)")
                }
            }
        }
        lastResourceSnapshot = currentResources

        // Track combat state
        if (worldState.isInCombat) {
            notes.add("Combat: HP ${worldState.currentHP}/${worldState.maxHP}")
        }

        // Track active timers
        val activeTimers = worldState.activeTimers.filter { !it.value.isExpired }
        if (activeTimers.isNotEmpty()) {
            val timerSummary = activeTimers.values.joinToString(", ") {
                "${it.name}: ${it.remainingSeconds}s"
            }
            notes.add("Active timers: $timerSummary")
        }

        // Track event-specific notes
        when (event) {
            is AgentEvent.ActionCompleted -> {
                val result = event.result
                if (result.isSuccess) {
                    notes.add("Action succeeded: ${result.toolName}")
                } else {
                    notes.add("Action failed: ${result.toolName} — ${(result as? dev.hushyari.data.model.ToolResult.Failure)?.error ?: "unknown"}")
                }
            }
            is AgentEvent.PopupDetected -> {
                notes.add("Popup detected: ${event.popupType} (auto-handled: ${event.autoHandled})")
            }
            is AgentEvent.Error -> {
                notes.add("Error: ${event.error}")
            }
            is AgentEvent.SafetyTriggered -> {
                notes.add("Safety triggered: ${event.rule} — ${event.reason}")
            }
            else -> { /* other events logged at lower detail */ }
        }

        if (notes.isNotEmpty()) {
            observations.add(Observation(System.currentTimeMillis(), notes))
            if (observations.size > MAX_OBSERVATIONS) {
                observations.removeAt(0)
            }
            Timber.d("Notetaker: recorded ${notes.size} observations")
        }
    }

    /**
     * Get all recorded observations.
     */
    fun getObservations(): List<Observation> = observations.toList()

    /**
     * Get a compact summary suitable for LLM prompt injection.
     *
     * @param maxLines Maximum number of observation lines to include.
     * @return A formatted summary string of recent observations.
     */
    fun getSummary(maxLines: Int = 20): String {
        if (observations.isEmpty()) return "No significant observations yet."

        return buildString {
            appendLine("## Recent Observations")
            observations.takeLast(maxLines).forEach { obs ->
                obs.notes.forEach { note ->
                    appendLine("- $note")
                }
            }
        }
    }

    /**
     * Clear all recorded observations.
     */
    fun clear() {
        observations.clear()
        lastScreen = ""
        lastResourceSnapshot = emptyMap()
    }
}

/**
 * A single observation event with timestamp and notes.
 */
data class Observation(
    val timestamp: Long,
    val notes: List<String>,
)
