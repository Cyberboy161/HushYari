package dev.hushyari.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hushyari.data.model.AgentEvent
import dev.hushyari.data.model.GameTask
import dev.hushyari.data.model.LogLevel
import dev.hushyari.data.model.Skill
import dev.hushyari.data.model.TaskStatus
import dev.hushyari.data.repository.SessionLog
import dev.hushyari.data.repository.SessionRepository
import dev.hushyari.data.repository.SessionStatus
import dev.hushyari.data.repository.SkillRepository
import dev.hushyari.skills.SkillEngine
import dev.hushyari.ui.components.LogEntry
import dev.hushyari.worldmodel.WorldStateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class GameUiState(
    val agentEvents: List<AgentEvent> = emptyList(),
    val logEntries: List<LogEntry> = emptyList(),
    val agentRunning: Boolean = false,
    val agentPaused: Boolean = false,
    val statusText: String = "Stopped",
    val stepCount: Int = 0,
    val elapsedMs: Long = 0,
    val activeSkillName: String? = null,
    val skillProgress: Float = 0f,
    val availableSkills: List<Skill> = emptyList(),
    val showSkillsPanel: Boolean = false,
    val sessionId: String? = null,
    val taskDescription: String = "",
    val gamePackage: String = "",
    val error: String? = null,
)

@HiltViewModel
class GameViewModel @Inject constructor(
    private val skillEngine: SkillEngine,
    private val skillRepository: SkillRepository,
    private val worldStateManager: WorldStateManager,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var agentJob: Job? = null
    private var timerJob: Job? = null

    fun initialize(gamePackage: String, taskDescription: String) {
        _uiState.update {
            it.copy(
                gamePackage = gamePackage,
                taskDescription = taskDescription,
                logEntries = listOf(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = LogLevel.INFO,
                        message = "Session initialized for $gamePackage",
                    )
                ),
            )
        }
        loadSkills(gamePackage)
    }

    fun startAgent() {
        val state = _uiState.value
        if (state.agentRunning) return

        val sessionId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                agentRunning = true,
                agentPaused = false,
                statusText = "Running",
                stepCount = 0,
                elapsedMs = 0,
                sessionId = sessionId,
            )
        }

        addLog(LogLevel.INFO, "Agent started")

        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.update { it.copy(elapsedMs = it.elapsedMs + 1000) }
            }
        }

        viewModelScope.launch {
            val session = SessionLog(
                id = sessionId,
                gamePackage = state.gamePackage,
                taskDescription = state.taskDescription,
                status = SessionStatus.RUNNING,
            )
            sessionRepository.saveSession(session)
        }
    }

    fun stopAgent() {
        skillEngine.stop()
        agentJob?.cancel()
        timerJob?.cancel()

        _uiState.update {
            it.copy(
                agentRunning = false,
                agentPaused = false,
                statusText = "Stopped",
            )
        }

        addLog(LogLevel.INFO, "Agent stopped")

        viewModelScope.launch {
            _uiState.value.sessionId?.let { id ->
                sessionRepository.updateSessionStatus(
                    id = id,
                    status = SessionStatus.CANCELLED,
                    totalSteps = _uiState.value.stepCount,
                )
            }
        }
    }

    fun pauseAgent() {
        skillEngine.pause()
        _uiState.update { it.copy(agentPaused = true, statusText = "Paused") }
        addLog(LogLevel.INFO, "Agent paused")
    }

    fun resumeAgent() {
        skillEngine.resume()
        _uiState.update { it.copy(agentPaused = false, statusText = "Running") }
        addLog(LogLevel.INFO, "Agent resumed")
    }

    fun onAgentEvent(event: AgentEvent) {
        val entry = when (event) {
            is AgentEvent.AgentStarted -> LogEntry(
                System.currentTimeMillis(), LogLevel.INFO, "Agent started"
            )
            is AgentEvent.AgentStopped -> LogEntry(
                System.currentTimeMillis(), LogLevel.INFO, "Agent stopped"
            )
            is AgentEvent.AgentPaused -> LogEntry(
                System.currentTimeMillis(), LogLevel.INFO, "Agent paused"
            )
            is AgentEvent.AgentResumed -> LogEntry(
                System.currentTimeMillis(), LogLevel.INFO, "Agent resumed"
            )
            is AgentEvent.ScreenCaptured -> LogEntry(
                System.currentTimeMillis(), LogLevel.DEBUG, "Screen captured in ${event.captureTimeMs}ms"
            )
            is AgentEvent.ScreenClassified -> LogEntry(
                System.currentTimeMillis(), LogLevel.DEBUG, "Screen: ${event.screenName} (${(event.confidence * 100).toInt()}%)"
            )
            is AgentEvent.ActionStarted -> LogEntry(
                System.currentTimeMillis(), LogLevel.INFO, "[Step ${event.step}] ${event.actionDescription}"
            )
            is AgentEvent.ActionCompleted -> LogEntry(
                System.currentTimeMillis(),
                if (event.result.isSuccess) LogLevel.INFO else LogLevel.WARNING,
                "[Step ${event.step}] ${event.result}"
            )
            is AgentEvent.SkillStarted -> LogEntry(
                System.currentTimeMillis(), LogLevel.INFO, "Skill: ${event.skillName}"
            )
            is AgentEvent.SkillCompleted -> LogEntry(
                System.currentTimeMillis(),
                if (event.success) LogLevel.INFO else LogLevel.ERROR,
                "Skill ${event.skillName} ${if (event.success) "completed" else "failed"}"
            )
            is AgentEvent.PopupDetected -> LogEntry(
                System.currentTimeMillis(), LogLevel.WARNING, "Popup: ${event.popupType} (${if (event.autoHandled) "handled" else "manual"})"
            )
            is AgentEvent.LLMCallStarted -> LogEntry(
                System.currentTimeMillis(), LogLevel.DEBUG, "LLM L${event.layer}: ${event.purpose}"
            )
            is AgentEvent.LLMCallCompleted -> LogEntry(
                System.currentTimeMillis(), LogLevel.DEBUG, "LLM done: ${event.latencyMs}ms, ${event.tokensUsed} tokens"
            )
            is AgentEvent.Log -> LogEntry(event.timestamp, event.level, event.message)
            is AgentEvent.Error -> LogEntry(
                System.currentTimeMillis(), LogLevel.ERROR, event.error
            )
            is AgentEvent.SafetyTriggered -> LogEntry(
                System.currentTimeMillis(), LogLevel.WARNING, "Safety: ${event.rule} — ${event.reason}"
            )
            is AgentEvent.TaskCompleted -> LogEntry(
                System.currentTimeMillis(), LogLevel.INFO, "Task completed: ${event.totalSteps} steps in ${event.totalTimeMs}ms"
            )
            is AgentEvent.TaskFailed -> LogEntry(
                System.currentTimeMillis(), LogLevel.ERROR, "Task failed: ${event.reason}"
            )
            is AgentEvent.WorldModelUpdated -> LogEntry(
                System.currentTimeMillis(), LogLevel.DEBUG, "WM: ${event.changes.joinToString(", ")}"
            )
        }

        _uiState.update { it.copy(logEntries = it.logEntries + entry) }

        when (event) {
            is AgentEvent.ActionStarted -> _uiState.update { it.copy(stepCount = it.stepCount + 1) }
            is AgentEvent.SkillStarted -> _uiState.update { it.copy(activeSkillName = event.skillName) }
            is AgentEvent.TaskCompleted -> stopAgent()
            is AgentEvent.TaskFailed -> _uiState.update { it.copy(error = event.reason) }
            else -> {}
        }
    }

    fun toggleSkillsPanel() {
        _uiState.update { it.copy(showSkillsPanel = !it.showSkillsPanel) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun loadSkills(gamePackage: String) {
        viewModelScope.launch {
            skillRepository.getSkills(gamePackage).catch { }.collect { skills ->
                _uiState.update { it.copy(availableSkills = skills) }
            }
        }
    }

    private fun addLog(level: LogLevel, message: String) {
        _uiState.update {
            it.copy(logEntries = it.logEntries + LogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                message = message,
            ))
        }
    }
}
