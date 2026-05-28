package dev.hushyari.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hushyari.agent.AgentLoop
import dev.hushyari.data.model.AgentEvent
import dev.hushyari.data.model.GameTask
import dev.hushyari.data.model.LogLevel
import dev.hushyari.data.model.Skill
import dev.hushyari.data.model.TaskStatus
import dev.hushyari.data.repository.GameRepository
import dev.hushyari.data.repository.SessionLog
import dev.hushyari.data.repository.SessionRepository
import dev.hushyari.data.repository.SessionStatus
import dev.hushyari.data.repository.SkillRepository
import dev.hushyari.skills.SkillEngine
import dev.hushyari.statemachine.GameConfig
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
    val statusText: String = "Ready",
    val stepCount: Int = 0,
    val elapsedMs: Long = 0,
    val activeSkillName: String? = null,
    val skillProgress: Float = 0f,
    val availableSkills: List<Skill> = emptyList(),
    val showSkillsPanel: Boolean = false,
    val sessionId: String? = null,
    val taskDescription: String = "",
    val gamePackage: String = "",
    val gameConfig: GameConfig? = null,
    val error: String? = null,
)

@HiltViewModel
class GameViewModel @Inject constructor(
    application: Application,
    private val agentLoop: AgentLoop,
    private val skillEngine: SkillEngine,
    private val skillRepository: SkillRepository,
    private val gameRepository: GameRepository,
    private val worldStateManager: WorldStateManager,
    private val sessionRepository: SessionRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var agentJob: Job? = null
    private var timerJob: Job? = null

    fun initialize(gamePackage: String) {
        if (_uiState.value.gamePackage == gamePackage && _uiState.value.gameConfig != null) return

        _uiState.update {
            it.copy(
                gamePackage = gamePackage,
                logEntries = listOf(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = LogLevel.INFO,
                        message = "Initializing for ${gamePackage}",
                    )
                ),
            )
        }
        loadGameConfig(gamePackage)
        loadSkills(gamePackage)
    }

    private fun loadGameConfig(gamePackage: String) {
        viewModelScope.launch {
            val config = gameRepository.getGameConfig(gamePackage)
            _uiState.update { it.copy(gameConfig = config) }
        }
    }

    fun startAgent(taskDescription: String = "Play the game automatically") {
        val state = _uiState.value
        if (state.agentRunning) return

        val gamePkg = state.gamePackage
        if (gamePkg.isBlank()) return

        val sessionId = UUID.randomUUID().toString()

        _uiState.update {
            it.copy(
                agentRunning = true,
                agentPaused = false,
                statusText = "Launching game...",
                stepCount = 0,
                elapsedMs = 0,
                sessionId = sessionId,
                taskDescription = taskDescription,
            )
        }

        addLog(LogLevel.INFO, "Launching game: $gamePkg")

        viewModelScope.launch {
            val context = getApplication<Application>()

            // 1. Launch the game
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(gamePkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    context.startActivity(launchIntent)
                    addLog(LogLevel.INFO, "Game launched: $gamePkg")
                } else {
                    addLog(LogLevel.ERROR, "Cannot launch $gamePkg — no launch intent found")
                    _uiState.update { it.copy(agentRunning = false, statusText = "Error") }
                    return@launch
                }
            } catch (e: Exception) {
                addLog(LogLevel.ERROR, "Failed to launch game: ${e.message}")
                _uiState.update { it.copy(agentRunning = false, statusText = "Error") }
                return@launch
            }

            // 2. Wait for game to open and accessibility service to see it
            delay(3000)
            _uiState.update { it.copy(statusText = "Running") }
            addLog(LogLevel.INFO, "Agent starting...")

            // 3. Create the task
            val task = GameTask(
                description = taskDescription,
                gamePackage = gamePkg,
                gameName = state.gameConfig?.gameName ?: gamePkg,
                maxSteps = 10_000,
                maxTimeMs = 3_600_000, // 1 hour
            )

            // 4. Persist session
            val session = SessionLog(
                id = sessionId,
                gamePackage = gamePkg,
                taskDescription = taskDescription,
                status = SessionStatus.RUNNING,
            )
            sessionRepository.saveSession(session)

            // 5. Start the timer
            timerJob = launch {
                while (true) {
                    delay(1000)
                    _uiState.update { it.copy(elapsedMs = it.elapsedMs + 1000) }
                }
            }

            // 6. Start the agent loop — collect its events
            agentJob = launch {
                try {
                    agentLoop.run(task, state.gameConfig).collect { event ->
                        onAgentEvent(event)
                    }
                    _uiState.update { it.copy(agentRunning = false, statusText = "Done") }
                    sessionRepository.updateSessionStatus(
                        id = sessionId,
                        status = SessionStatus.COMPLETED,
                        totalSteps = _uiState.value.stepCount,
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    addLog(LogLevel.ERROR, "Agent crashed: ${e.message}")
                    _uiState.update { it.copy(agentRunning = false, statusText = "Error", error = e.message) }
                    sessionRepository.updateSessionStatus(
                        id = sessionId,
                        status = SessionStatus.FAILED,
                        totalSteps = _uiState.value.stepCount,
                    )
                }
            }
        }
    }

    fun stopAgent() {
        skillEngine.stop()
        agentLoop.stop()
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
        agentLoop.pause()
        skillEngine.pause()
        _uiState.update { it.copy(agentPaused = true, statusText = "Paused") }
        addLog(LogLevel.INFO, "Agent paused")
    }

    fun resumeAgent() {
        agentLoop.resume()
        skillEngine.resume()
        _uiState.update { it.copy(agentPaused = false, statusText = "Running") }
        addLog(LogLevel.INFO, "Agent resumed")
    }

    fun onAgentEvent(event: AgentEvent) {
        _uiState.update { state ->
            val entry = eventToLogEntry(event)
            state.copy(logEntries = (state.logEntries + entry).takeLast(1000))
        }

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
            it.copy(logEntries = (it.logEntries + LogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                message = message,
            )).takeLast(1000))
        }
    }

    private fun eventToLogEntry(event: AgentEvent): LogEntry = when (event) {
        is AgentEvent.AgentStarted -> LogEntry(System.currentTimeMillis(), LogLevel.INFO, "Agent started")
        is AgentEvent.AgentStopped -> LogEntry(System.currentTimeMillis(), LogLevel.INFO, "Agent stopped")
        is AgentEvent.AgentPaused -> LogEntry(System.currentTimeMillis(), LogLevel.INFO, "Agent paused")
        is AgentEvent.AgentResumed -> LogEntry(System.currentTimeMillis(), LogLevel.INFO, "Agent resumed")
        is AgentEvent.ScreenCaptured -> LogEntry(System.currentTimeMillis(), LogLevel.DEBUG, "Screen: ${event.screen.packageName} (${event.captureTimeMs}ms)")
        is AgentEvent.ScreenClassified -> LogEntry(System.currentTimeMillis(), LogLevel.DEBUG, "Classified: ${event.screenName} (${(event.confidence * 100).toInt()}%)")
        is AgentEvent.ActionStarted -> LogEntry(System.currentTimeMillis(), LogLevel.INFO, "[${event.step}] ${event.actionDescription}")
        is AgentEvent.ActionCompleted -> LogEntry(
            System.currentTimeMillis(),
            if (event.result.isSuccess) LogLevel.INFO else LogLevel.WARNING,
            "[${event.step}] ${event.result}"
        )
        is AgentEvent.SkillStarted -> LogEntry(System.currentTimeMillis(), LogLevel.INFO, "Skill: ${event.skillName}")
        is AgentEvent.SkillCompleted -> LogEntry(
            System.currentTimeMillis(),
            if (event.success) LogLevel.INFO else LogLevel.ERROR,
            "Skill ${event.skillName} ${if (event.success) "done" else "failed"}"
        )
        is AgentEvent.PopupDetected -> LogEntry(System.currentTimeMillis(), LogLevel.WARNING, "Popup: ${event.popupType}")
        is AgentEvent.LLMCallStarted -> LogEntry(System.currentTimeMillis(), LogLevel.DEBUG, "LLM L${event.layer}: ${event.purpose}")
        is AgentEvent.LLMCallCompleted -> LogEntry(System.currentTimeMillis(), LogLevel.DEBUG, "LLM: ${event.latencyMs}ms")
        is AgentEvent.Log -> LogEntry(event.timestamp, event.level, event.message)
        is AgentEvent.Error -> LogEntry(System.currentTimeMillis(), LogLevel.ERROR, event.error)
        is AgentEvent.SafetyTriggered -> LogEntry(System.currentTimeMillis(), LogLevel.WARNING, "Safety: ${event.rule}")
        is AgentEvent.TaskCompleted -> LogEntry(System.currentTimeMillis(), LogLevel.INFO, "Task done: ${event.totalSteps} steps")
        is AgentEvent.TaskFailed -> LogEntry(System.currentTimeMillis(), LogLevel.ERROR, "Task failed: ${event.reason}")
        is AgentEvent.WorldModelUpdated -> LogEntry(System.currentTimeMillis(), LogLevel.DEBUG, "WM: ${event.changes.joinToString()}")
    }
}
