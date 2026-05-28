package dev.hushyari.skills

import dev.hushyari.data.model.ToolResult

/**
 * Events emitted during skill execution for reactive UI observation.
 *
 * Each event represents a milestone in the skill execution lifecycle,
 * from step start through to completion or failure.
 *
 * 🧠 4x-game-agent mechanic: Layer 4 — Observable skill execution progress.
 */
sealed class SkillExecutionEvent {
    data class StepStarted(
        val step: Int,
        val description: String,
    ) : SkillExecutionEvent()

    data class StepCompleted(
        val step: Int,
        val result: ToolResult,
    ) : SkillExecutionEvent()

    data class StepFailed(
        val step: Int,
        val error: String,
        val retrying: Boolean,
    ) : SkillExecutionEvent()

    data class EscalatedToLLM(
        val reason: String,
    ) : SkillExecutionEvent()

    data class SkillPaused(
        val reason: String,
    ) : SkillExecutionEvent()

    data class SkillResumed(
        val step: Int,
    ) : SkillExecutionEvent()

    data class Completed(
        val success: Boolean,
        val totalSteps: Int,
        val durationMs: Long,
    ) : SkillExecutionEvent()

    data class PrerequisiteCheck(
        val satisfied: Boolean,
        val currentScreen: String,
        val requiredScreen: String,
    ) : SkillExecutionEvent()

    data class VerificationResult(
        val step: Int,
        val passed: Boolean,
        val details: String,
    ) : SkillExecutionEvent()

    data class Log(
        val message: String,
        val level: String = "INFO",
    ) : SkillExecutionEvent()
}
