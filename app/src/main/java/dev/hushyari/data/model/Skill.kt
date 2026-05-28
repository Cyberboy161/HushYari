package dev.hushyari.data.model

import kotlinx.serialization.Serializable

/**
 * Skill definition — a reusable game workflow.
 * 🧠 PokeClaw mechanic: Skills built from atomic tools.
 * 🧠 4x-game-agent mechanic: Pre-scripted workflows with verification.
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val gameType: List<String> = emptyList(),
    val gamePackage: String? = null,
    val description: String = "",
    val category: SkillCategory = SkillCategory.GENERIC,
    val prerequisites: List<String> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val completionCheck: CompletionCheck? = null,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val isBuiltIn: Boolean = false,
    val version: Int = 1,
    val author: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class SkillStep(
    val id: String,
    val description: String = "",
    val tool: String,
    val target: TargetSpec? = null,
    val fallback: TargetSpec? = null,
    val params: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 5000,
    val waitAfterMs: Long = 500,
    val retriesOnFailure: Int = 1,
    val continueOnFailure: Boolean = false,
    val verifyAfter: VerificationCheck? = null,
)

@Serializable
data class TargetSpec(
    val type: TargetType = TargetType.COORDINATES,
    val text: String? = null,
    val textContains: String? = null,
    val contentDesc: String? = null,
    val contentDescContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val templateImage: String? = null,
    val xFraction: Float? = null,
    val yFraction: Float? = null,
    val x: Int? = null,
    val y: Int? = null,
    val scrollDirection: String? = null,
    val scrollAmount: Int? = null,
)

@Serializable
data class VerificationCheck(
    val type: String = "element_exists",
    val target: TargetSpec? = null,
    val textContains: String? = null,
    val screenName: String? = null,
    val timeoutMs: Long = 3000,
)

@Serializable
data class CompletionCheck(
    val type: String = "world_model",
    val path: String? = null,
    val screenName: String? = null,
    val condition: String? = null,
)

@Serializable
data class RetryPolicy(
    val maxRetries: Int = 3,
    val onFailure: FailureAction = FailureAction.ESCALATE_TO_LLM,
    val backoffMs: Long = 1000,
    val resetOnScreenChange: Boolean = true,
)

@Serializable
enum class TargetType {
    COORDINATES,
    TEXT,
    CONTENT_DESC,
    RESOURCE_ID,
    CLASS_NAME,
    TEMPLATE_IMAGE,
    SCROLL,
}

@Serializable
enum class SkillCategory {
    GENERIC,
    NAVIGATION,
    COMBAT,
    RESOURCE,
    BUILDING,
    QUEST,
    SOCIAL,
    CUSTOM,
}

@Serializable
enum class FailureAction {
    RETRY,
    SKIP_STEP,
    ESCALATE_TO_LLM,
    STOP_SKILL,
}
