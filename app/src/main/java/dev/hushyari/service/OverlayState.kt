package dev.hushyari.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state bridge between the AgentLoop (background) and the floating OverlayService.
 * Singleton — readable from anywhere without DI.
 */
object OverlayState {

    data class State(
        val gameName: String = "",
        val agentStatus: String = "Idle",
        val stepCount: Int = 0,
        val currentScreen: String = "—",
        val screenDescription: String = "No data yet",
        val lastAction: String = "—",
        val currentPlan: String = "—",
        val activeSkill: String = "—",
        val worldSummary: String = "—",
        val popupDetected: Boolean = false,
        val llmThinking: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(block: State.() -> State) {
        _state.value = _state.value.block()
    }

    fun setScreen(name: String, description: String) {
        update { copy(currentScreen = name, screenDescription = description) }
    }

    fun setAction(description: String) {
        update { copy(lastAction = description) }
    }

    fun setPlan(plan: String) {
        update { copy(currentPlan = plan) }
    }

    fun setThinking(thinking: Boolean) {
        update { copy(llmThinking = thinking) }
    }

    fun setStatus(status: String) {
        update { copy(agentStatus = status) }
    }

    fun setSkill(name: String) {
        update { copy(activeSkill = name) }
    }

    fun setError(error: String?) {
        update { copy(error = error) }
    }

    fun reset() {
        _state.value = State()
    }
}
