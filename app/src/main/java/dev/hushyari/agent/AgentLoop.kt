package dev.hushyari.agent

import dev.hushyari.data.model.AgentEvent
import dev.hushyari.data.model.GameTask
import dev.hushyari.data.model.LogLevel
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.WorldState
import dev.hushyari.llm.ChatHistory
import dev.hushyari.llm.ChatMessage
import dev.hushyari.llm.CloudLlmClient
import dev.hushyari.llm.LlmConfig
import dev.hushyari.llm.LlmGenerationConfig
import dev.hushyari.llm.LocalLlmClient
import dev.hushyari.llm.PromptEngine
import dev.hushyari.llm.ResponseParser
import dev.hushyari.llm.Role
import dev.hushyari.perception.PerceptionPipeline
import dev.hushyari.skills.SkillEngine
import dev.hushyari.statemachine.GameConfig
import dev.hushyari.statemachine.GameFSM
import dev.hushyari.statemachine.PopupHandler
import dev.hushyari.statemachine.ScreenClassifier
import dev.hushyari.tools.ToolManager
import dev.hushyari.worldmodel.WorldStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentLoop @Inject constructor(
    private val perception: PerceptionPipeline,
    private val skillEngine: SkillEngine,
    private val screenClassifier: ScreenClassifier,
    private val popupHandler: PopupHandler,
    private val worldStateManager: WorldStateManager,
    private val toolManager: ToolManager,
    private val promptEngine: PromptEngine,
    private val cloudLlm: CloudLlmClient,
    private val localLlm: LocalLlmClient,
    private val gameFsm: GameFSM,
    private val watcher: Watcher,
    private val manager: Manager,
    private val executor: Executor,
    private val reflector: Reflector,
    private val notetaker: Notetaker,
    private val strategist: Strategist,
    private val planner: Planner,
) {

    @Volatile
    var currentTask: GameTask? = null
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var stepCount: Int = 0
        private set

    @Volatile
    private var isPaused: Boolean = false

    private var lastActionTime: Long = 0L
    private var lastAction: AgentAction? = null
    private var consecutiveErrors: Int = 0
    private var currentLlmConfig: LlmConfig = LlmConfig.DEFAULT_GOOGLE
    private var gameConfig: GameConfig? = null

    private val actionCacheWindowMs = 100L

    private val chatHistory: ChatHistory = ChatHistory()

    fun run(
        task: GameTask,
        config: GameConfig? = null,
        llmConfig: LlmConfig? = null,
    ): Flow<AgentEvent> = flow {
        currentTask = task
        gameConfig = config
        if (llmConfig != null) {
            currentLlmConfig = llmConfig
            cloudLlm.configure(llmConfig)
        }
        isRunning = true
        isPaused = false
        stepCount = 0
        consecutiveErrors = 0
        lastAction = null
        lastActionTime = 0L

        if (config != null) {
            gameFsm.loadConfig(config)
        }

        emit(AgentEvent.AgentStarted)
        emit(AgentEvent.Log(LogLevel.INFO, "AgentLoop started for task: ${task.description}"))

        while (isRunning && !task.isTerminal) {
            if (isPaused) {
                delay(100)
                continue
            }

            if (stepCount >= task.maxSteps) {
                emit(AgentEvent.TaskFailed(task, "Exceeded max steps (${task.maxSteps})"))
                isRunning = false
                break
            }

            val runtime = task.startedAt?.let { System.currentTimeMillis() - it } ?: 0L
            if (task.startedAt != null && runtime >= task.maxTimeMs) {
                emit(AgentEvent.TaskFailed(task, "Exceeded max time (${task.maxTimeMs}ms)"))
                isRunning = false
                break
            }

            try {
                val stepStart = System.currentTimeMillis()
                stepCount++

                // ── 1. PERCEIVE ────────────────────────────────────
                val captureMode = if (stepCount % 10 == 0) {
                    PerceptionPipeline.CaptureMode.FULL
                } else {
                    PerceptionPipeline.CaptureMode.FAST
                }
                val screenState = perception.capture(captureMode)
                val captureTime = System.currentTimeMillis() - stepStart
                emit(AgentEvent.ScreenCaptured(screenState, captureTime))

                val worldState = worldStateManager.getState()

                // ── 2. HANDLE POPUPS ───────────────────────────────
                val gc = gameConfig
                if (gc != null) {
                    val popups = popupHandler.detectPopups(screenState, gc)
                    if (popups.isNotEmpty()) {
                        emit(AgentEvent.PopupDetected(popups.first().type.name, autoHandled = true))
                        val dismissed = popupHandler.dismissAll(screenState, gc) { target ->
                            val params = mutableMapOf<String, Any?>()
                            if (target.x != null) params["x"] = target.x
                            if (target.y != null) params["y"] = target.y
                            if (target.xFraction != null) params["xFraction"] = target.xFraction
                            if (target.yFraction != null) params["yFraction"] = target.yFraction
                            if (target.text != null) params["text"] = target.text
                            if (target.resourceId != null) params["resourceId"] = target.resourceId
                            toolManager.execute("tap", params, screenState, worldStateManager.getState())
                        }
                        if (dismissed > 0) {
                            emit(AgentEvent.Log(LogLevel.INFO, "Dismissed $dismissed popup(s)"))
                        }
                    }
                }

                // ── 3. CLASSIFY SCREEN ─────────────────────────────
                val classification = if (gc != null) {
                    screenClassifier.classify(screenState, gc)
                } else {
                    null
                }
                if (classification != null) {
                    emit(AgentEvent.ScreenClassified(classification.screenName, classification.confidence))
                    worldStateManager.updateScreen(classification.screenName)
                    gameFsm.onScreenChanged(classification.screenName)
                }

                // ── 4. CHECK WATCHER ───────────────────────────────
                val urgent = watcher.check(screenState, worldState)
                if (urgent != null) {
                    emit(AgentEvent.Log(LogLevel.WARNING, "Urgent: ${urgent.reason}"))
                    val urgentResult = executor.execute(urgent.action, screenState)
                    emit(AgentEvent.ActionCompleted(urgentResult, stepCount))
                    notetaker.record(screenState, worldState, AgentEvent.ActionCompleted(urgentResult, stepCount))
                    delay(200)
                    continue
                }

                // ── 5. TRY SKILL ───────────────────────────────────
                if (skillEngine.isRunning()) {
                    emit(AgentEvent.SkillStarted(skillEngine.getActiveSkill()?.name ?: "active"))
                    val skillEvent = skillEngine.executeNextStep { perception.capture(PerceptionPipeline.CaptureMode.FAST) }
                    when (skillEvent) {
                        is dev.hushyari.skills.SkillExecutionEvent.StepCompleted -> {
                            emit(AgentEvent.ActionCompleted(skillEvent.result, stepCount))
                            notetaker.record(screenState, worldState, AgentEvent.ActionCompleted(skillEvent.result, stepCount))
                        }
                        is dev.hushyari.skills.SkillExecutionEvent.StepFailed -> {
                            emit(AgentEvent.Log(LogLevel.WARNING, "Skill step failed: ${skillEvent.error}"))
                        }
                        is dev.hushyari.skills.SkillExecutionEvent.Completed -> {
                            emit(AgentEvent.SkillCompleted("skill", skillEvent.success))
                        }
                        is dev.hushyari.skills.SkillExecutionEvent.EscalatedToLLM -> {
                            emit(AgentEvent.Log(LogLevel.WARNING, "Skill escalated to LLM: ${skillEvent.reason}"))
                        }
                        else -> {}
                    }
                    delay(100)
                    continue
                }

                // ── 6. TRY FSM ─────────────────────────────────────
                if (gc != null) {
                    val transitions = gameFsm.getSatisfiedTransitions(worldStateManager.getAllResources())
                    if (transitions.isNotEmpty()) {
                        val bestTransition = transitions.first()
                        val fsmAction = AgentAction.ToolAction(
                            toolName = bestTransition.action.tool,
                            params = bestTransition.action.params,
                        )
                        emit(AgentEvent.ActionStarted(
                            "FSM: ${bestTransition.fromScreen} → ${bestTransition.toScreen}",
                            stepCount,
                        ))
                        val fsmResult = executor.execute(fsmAction, screenState)
                        emit(AgentEvent.ActionCompleted(fsmResult, stepCount))
                        notetaker.record(screenState, worldState, AgentEvent.ActionCompleted(fsmResult, stepCount))
                        delay(200)
                        continue
                    }
                }

                // ── 7. ESCALATION LADDER ───────────────────────────
                val action = escalate(screenState, worldState, task, this)
                if (action != null) {
                    emit(AgentEvent.ActionStarted(actionDescription(action), stepCount))
                    val result = executor.execute(action, screenState)
                    emit(AgentEvent.ActionCompleted(result, stepCount))
                    lastAction = action
                    lastActionTime = System.currentTimeMillis()

                    // ── 8. REFLECT ──────────────────────────────────
                    try {
                        val afterScreen = perception.capture(PerceptionPipeline.CaptureMode.FAST)
                        val reflection = reflector.evaluate(action, screenState, afterScreen, result)
                        chatHistory.addMessage(Role.USER, "Action: ${actionDescription(action)}")
                        chatHistory.addMessage(Role.ASSISTANT, "Result: ${reflection.summary}")

                        if (reflection.success) {
                            consecutiveErrors = 0
                        } else {
                            consecutiveErrors++
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Reflection failed")
                    }

                    emit(AgentEvent.Log(LogLevel.DEBUG, "Step $stepCount complete in ${System.currentTimeMillis() - stepStart}ms"))
                } else {
                    emit(AgentEvent.Log(LogLevel.DEBUG, "No action determined for step $stepCount, waiting..."))
                    delay(500)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "AgentLoop error at step $stepCount")
                emit(AgentEvent.Error("Step $stepCount: ${e.message}", recoverable = true))
                consecutiveErrors++
                if (consecutiveErrors >= 5) {
                    emit(AgentEvent.TaskFailed(task, "Too many consecutive errors: ${e.message}"))
                    isRunning = false
                    break
                }
                delay(1000)
            }
        }

        isRunning = false
        emit(AgentEvent.TaskCompleted(task, stepCount, System.currentTimeMillis() - (task.startedAt ?: System.currentTimeMillis())))
    }

    // ── Escalation ladder ──────────────────────────────────────────────

    private suspend fun escalate(
        screen: ScreenState,
        worldState: WorldState,
        task: GameTask,
        collector: FlowCollector<AgentEvent>,
    ): AgentAction? {
        // Level 0: Cached action (reuse same action within 100ms)
        if (lastAction != null && (System.currentTimeMillis() - lastActionTime) < actionCacheWindowMs) {
            if (lastAction !is AgentAction.DoneAction && lastAction !is AgentAction.AskAction) {
                return lastAction
            }
        }

        // Level 3: Template/OCR find via perception
        try {
            val foundElement = perception.findElement(
                dev.hushyari.data.model.ElementQuery(textContains = "collect")
            )
            if (foundElement != null) {
                return AgentAction.ToolAction("tap", mapOf(
                    "x" to foundElement.centerX.toInt().toString(),
                    "y" to foundElement.centerY.toInt().toString(),
                ))
            }
        } catch (_: Exception) {}

        // Level 4: Local LLM
        if (localLlm.isAvailable()) {
            try {
                val systemPrompt = promptEngine.buildSystemPrompt(task)
                val actionPrompt = promptEngine.buildActionPrompt(screen, worldState, task)
                val messages = listOf(
                    ChatMessage(Role.SYSTEM, systemPrompt),
                    ChatMessage(Role.USER, actionPrompt),
                )

                collector.emit(AgentEvent.LLMCallStarted(4, "local_reasoning"))
                val localResponse = localLlm.chat(messages, LlmGenerationConfig(
                    temperature = currentLlmConfig.temperature,
                    maxTokens = currentLlmConfig.maxTokens,
                ))
                collector.emit(AgentEvent.LLMCallCompleted(4, localResponse.latencyMs, localResponse.tokensUsed))

                val localAction = ResponseParser.parse(localResponse.content)
                if (localAction != null) {
                    return mapAgentAction(localAction)
                }
            } catch (e: Exception) {
                Timber.w(e, "Local LLM call failed, escalating to cloud")
            }
        }

        // Level 5: Cloud LLM
        if (cloudLlm.isAvailable()) {
            try {
                val conversationHistory = chatHistory.getLastN(10)
                val messages = promptEngine.buildMessages(
                    conversationHistory = conversationHistory,
                    screen = screen,
                    worldState = worldState,
                    task = task,
                )

                collector.emit(AgentEvent.LLMCallStarted(5, "cloud_reasoning"))
                val cloudResponse = if (screen.screenshot != null) {
                    cloudLlm.chatWithImage(messages, screen.screenshot!!, LlmGenerationConfig(
                        temperature = currentLlmConfig.temperature,
                        maxTokens = currentLlmConfig.maxTokens,
                    ))
                } else {
                    cloudLlm.chat(messages, LlmGenerationConfig(
                        temperature = currentLlmConfig.temperature,
                        maxTokens = currentLlmConfig.maxTokens,
                    ))
                }
                collector.emit(AgentEvent.LLMCallCompleted(5, cloudResponse.latencyMs, cloudResponse.tokensUsed))

                val cloudAction = ResponseParser.parse(cloudResponse.content)
                if (cloudAction != null) {
                    chatHistory.addMessage(Role.ASSISTANT, cloudResponse.content)
                    return mapAgentAction(cloudAction)
                }
            } catch (e: Exception) {
                Timber.e(e, "Cloud LLM call failed")
                collector.emit(AgentEvent.Error("Cloud LLM: ${e.message}", recoverable = true))
            }
        }

        return AgentAction.WaitAction(durationMs = 1000)
    }

    private fun mapAgentAction(action: dev.hushyari.llm.AgentAction): AgentAction {
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

    private fun actionDescription(action: AgentAction): String = when (action) {
        is AgentAction.ToolAction -> "${action.toolName}(${action.params})"
        is AgentAction.PlanAction -> "Plan(${action.steps.size} steps)"
        is AgentAction.AskAction -> "Ask: ${action.question.take(50)}"
        is AgentAction.DoneAction -> "Done: ${action.reason.take(50)}"
        is AgentAction.WaitAction -> "Wait(${action.durationMs}ms)"
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun pause() {
        isPaused = true
        Timber.d("AgentLoop paused")
    }

    fun resume() {
        isPaused = false
        Timber.d("AgentLoop resumed")
    }

    fun stop() {
        isRunning = false
        isPaused = false
        Timber.d("AgentLoop stopped")
    }
}
