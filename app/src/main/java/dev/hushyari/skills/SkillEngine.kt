package dev.hushyari.skills

import dev.hushyari.data.model.FailureAction
import dev.hushyari.data.model.RetryPolicy
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.Skill
import dev.hushyari.data.model.SkillStep
import dev.hushyari.data.model.TargetSpec
import dev.hushyari.data.model.TargetType
import dev.hushyari.data.model.ToolResult
import dev.hushyari.data.model.VerificationCheck
import dev.hushyari.statemachine.GameFSM
import dev.hushyari.statemachine.PopupHandler
import dev.hushyari.statemachine.ScreenClassifier
import dev.hushyari.tools.ToolManager
import dev.hushyari.worldmodel.WorldStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes skill workflows by iterating through [SkillStep]s, dispatching
 * tool commands via [ToolManager], verifying results via [SkillVerifier],
 * and handling retries and escalations.
 *
 * Execution loop per skill:
 * 1. Check prerequisites (are we on the right screen?)
 * 2. Execute current step via ToolManager
 * 3. Verify step succeeded (via SkillVerifier or re-perception)
 * 4. If failed: retry per RetryPolicy
 * 5. If all retries exhausted: escalate to LLM or stop
 * 6. After step: wait per waitAfterMs
 * 7. Check completion condition after each step
 *
 * Supports concurrent watcher skills that run alongside main skills.
 *
 * 🧠 4x-game-agent mechanic: Layer 4 — Skill execution orchestrator.
 * 🧠 PokeClaw mechanic: Retry + escalate + watcher skill pattern.
 */
@Singleton
class SkillEngine @Inject constructor(
    private val toolManager: ToolManager,
    private val verifier: SkillVerifier,
    private val gameFSM: GameFSM,
    private val worldStateManager: WorldStateManager,
    private val popupHandler: PopupHandler,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeSkill: Skill? = null
    private var currentStepIndex: Int = 0
    private var isPaused: Boolean = false
    private var totalStepsExecuted: Int = 0
    private val watcherSkills = mutableListOf<Skill>()

    private val _events = MutableSharedFlow<SkillExecutionEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<SkillExecutionEvent> = _events.asSharedFlow()

    /**
     * Execute a skill workflow, emitting events as progress is made.
     *
     * @param skill The skill to execute.
     * @param screenStateFn Function to capture current screen state (from perception pipeline).
     * @return Flow of SkillExecutionEvent.
     */
    fun executeSkill(
        skill: Skill,
        screenStateFn: suspend () -> ScreenState,
    ): Flow<SkillExecutionEvent> = flow {
        activeSkill = skill
        currentStepIndex = 0
        isPaused = false
        totalStepsExecuted = 0
        val startTime = System.currentTimeMillis()

        val totalSteps = skill.steps.size

        emitEvent(SkillExecutionEvent.Log("Starting skill: ${skill.name} ($totalSteps steps)"))

        for (stepIndex in skill.steps.indices) {
            if (!scope.isActive || isPaused) break

            currentStepIndex = stepIndex
            val step = skill.steps[stepIndex]

            emitEvent(SkillExecutionEvent.StepStarted(stepIndex, step.description.ifEmpty { step.tool }))

            var attemptCount = 0
            var stepSucceeded = false

            while (attemptCount <= step.retriesOnFailure && !stepSucceeded && scope.isActive) {
                if (isPaused) break

                val before = try {
                    screenStateFn()
                } catch (e: Exception) {
                    emitEvent(SkillExecutionEvent.StepFailed(
                        stepIndex, "Failed to capture screen: ${e.message}", attemptCount < step.retriesOnFailure
                    ))
                    attemptCount++
                    delay(skill.retryPolicy.backoffMs)
                    continue
                }

                val params = buildParams(step, before)
                val worldState = worldStateManager.getState()

                val toolResult = try {
                    withTimeout(step.timeoutMs) {
                        toolManager.execute(step.tool, params, before, worldState)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult.Failure(
                        toolName = step.tool,
                        error = "Timeout or exception: ${e.message}",
                    )
                }

                val after = try {
                    screenStateFn()
                } catch (e: Exception) {
                    before
                }

                val verification = verifier.verify(step, before, after, toolResult)

                emitEvent(SkillExecutionEvent.VerificationResult(
                    stepIndex, verification.success, verification.details
                ))

                if (verification.success) {
                    emitEvent(SkillExecutionEvent.StepCompleted(stepIndex, toolResult))
                    totalStepsExecuted++
                    stepSucceeded = true
                } else {
                    attemptCount++
                    val retrying = attemptCount <= step.retriesOnFailure
                    emitEvent(SkillExecutionEvent.StepFailed(
                        stepIndex,
                        verification.details,
                        retrying,
                    ))

                    if (retrying) {
                        delay(skill.retryPolicy.backoffMs)
                    }
                }
            }

            if (!stepSucceeded && !step.continueOnFailure) {
                when (skill.retryPolicy.onFailure) {
                    FailureAction.ESCALATE_TO_LLM -> {
                        emitEvent(SkillExecutionEvent.EscalatedToLLM(
                            "Step $stepIndex failed after ${attemptCount} attempts: ${step.tool}"
                        ))
                        break
                    }
                    FailureAction.STOP_SKILL -> {
                        emitEvent(SkillExecutionEvent.Completed(
                            success = false,
                            totalSteps = totalStepsExecuted,
                            durationMs = System.currentTimeMillis() - startTime,
                        ))
                        return@flow
                    }
                    FailureAction.RETRY -> {
                        if (skill.retryPolicy.resetOnScreenChange) {
                            emitEvent(SkillExecutionEvent.Log("Resetting to retry from screen change"))
                        }
                        continue
                    }
                    FailureAction.SKIP_STEP -> continue
                }
            }

            if (step.waitAfterMs > 0) {
                delay(step.waitAfterMs)
            }

            emitEvent(SkillExecutionEvent.Log("Progress: ${((stepIndex + 1).toFloat() / totalSteps * 100).toInt()}%"))
        }

        activeSkill = null
        isPaused = false

        emitEvent(SkillExecutionEvent.Completed(
            success = currentStepIndex >= skill.steps.size - 1,
            totalSteps = totalStepsExecuted,
            durationMs = System.currentTimeMillis() - startTime,
        ))
    }

    /**
     * Execute the next step in the active skill.
     * Used for step-by-step manual advancement.
     */
    suspend fun executeNextStep(screenStateFn: suspend () -> ScreenState): SkillExecutionEvent {
        val skill = activeSkill ?: return SkillExecutionEvent.Log("No active skill", "WARN")
        if (currentStepIndex >= skill.steps.size) {
            return SkillExecutionEvent.Completed(true, totalStepsExecuted, 0)
        }

        val step = skill.steps[currentStepIndex]
        emitEvent(SkillExecutionEvent.StepStarted(currentStepIndex, step.description.ifEmpty { step.tool }))

        val before = screenStateFn()
        val params = buildParams(step, before)
        val worldState = worldStateManager.getState()

        val result = try {
            withTimeout(step.timeoutMs) {
                toolManager.execute(step.tool, params, before, worldState)
            }
        } catch (e: Exception) {
            ToolResult.Failure(toolName = step.tool, error = "${e.message}")
        }

        val after = screenStateFn()
        val verification = verifier.verify(step, before, after, result)

        if (verification.success) {
            currentStepIndex++
            totalStepsExecuted++
            emitEvent(SkillExecutionEvent.StepCompleted(currentStepIndex - 1, result))
        } else {
            emitEvent(SkillExecutionEvent.StepFailed(currentStepIndex, verification.details, false))
        }

        if (step.waitAfterMs > 0) {
            delay(step.waitAfterMs)
        }

        return SkillExecutionEvent.StepCompleted(currentStepIndex - 1, result)
    }

    /**
     * Register a watcher skill that runs concurrently with the main skill.
     *
     * 🧠 PokeClaw mechanic: Watcher skills observe state and react (e.g., dismiss popups).
     */
    fun addWatcherSkill(skill: Skill) {
        watcherSkills.add(skill)
    }

    /**
     * Remove a watcher skill.
     */
    fun removeWatcherSkill(skillId: String) {
        watcherSkills.removeAll { it.id == skillId }
    }

    /**
     * Execute all watcher skills against the current screen.
     */
    suspend fun executeWatchers(screenStateFn: suspend () -> ScreenState) {
        if (watcherSkills.isEmpty()) return

        for (watcher in watcherSkills.toList()) {
            val screen = screenStateFn()
            for (step in watcher.steps) {
                val params = buildParams(step, screen)
                val worldState = worldStateManager.getState()
                toolManager.execute(step.tool, params, screen, worldState)
                delay(100)
            }
        }
    }

    /**
     * Pause the currently executing skill.
     */
    fun pause() {
        isPaused = true
        scope.launch {
            _events.emit(SkillExecutionEvent.SkillPaused("Paused by user"))
        }
    }

    /**
     * Resume a paused skill.
     */
    fun resume() {
        isPaused = false
        scope.launch {
            _events.emit(SkillExecutionEvent.SkillResumed(currentStepIndex))
        }
    }

    /**
     * Stop the currently executing skill and cancel watchers.
     */
    fun stop() {
        activeSkill = null
        isPaused = false
        watcherSkills.clear()
        scope.coroutineContext.cancel()
    }

    /**
     * Get execution progress as a fraction (0.0 to 1.0).
     */
    fun getProgress(): Float {
        val skill = activeSkill ?: return 0f
        if (skill.steps.isEmpty()) return 1f
        return (currentStepIndex.toFloat() / skill.steps.size).coerceIn(0f, 1f)
    }

    /**
     * Get the currently active skill.
     */
    fun getActiveSkill(): Skill? = activeSkill

    /**
     * Get the current step index.
     */
    fun getCurrentStepIndex(): Int = currentStepIndex

    /**
     * Check if a skill is currently executing.
     */
    fun isRunning(): Boolean = activeSkill != null

    /**
     * Check if execution is paused.
     */
    fun isPaused(): Boolean = isPaused

    // ── Private helpers ─────────────────────────────────────────────────

    private fun buildParams(step: SkillStep, screen: ScreenState): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>()
        params.putAll(step.params)

        val target = step.target
        if (target != null) {
            params["x"] = target.x
            params["y"] = target.y
            params["xFraction"] = target.xFraction
            params["yFraction"] = target.yFraction
            params["text"] = target.text
            params["textContains"] = target.textContains
            params["contentDesc"] = target.contentDesc
            params["resourceId"] = target.resourceId
            params["className"] = target.className

            if (target.x == null && target.y == null && target.xFraction == null) {
                val element = resolveTarget(target, screen)
                if (element != null) {
                    params["x"] = element.centerX.toInt()
                    params["y"] = element.centerY.toInt()
                }
            }
        }

        if (step.fallback != null && target != null) {
            params["fallbackX"] = step.fallback.x
            params["fallbackY"] = step.fallback.y
        }

        return params
    }

    private fun resolveTarget(
        target: TargetSpec,
        screen: ScreenState,
    ): dev.hushyari.data.model.UIElement? {
        return screen.findElement(
            dev.hushyari.data.model.ElementQuery(
                text = target.text,
                textContains = target.textContains,
                contentDesc = target.contentDesc,
                contentDescContains = target.contentDescContains,
                resourceId = target.resourceId,
                className = target.className,
            )
        )
    }

    private suspend fun emitEvent(event: SkillExecutionEvent) {
        _events.emit(event)
    }
}
