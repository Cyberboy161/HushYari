package dev.hushyari.statemachine

import dev.hushyari.data.model.SkillStep
import dev.hushyari.data.model.TargetSpec
import dev.hushyari.data.model.TargetType
import kotlinx.serialization.Serializable

/**
 * JSON-driven game configuration that powers screen classification,
 * popup handling, and state machine transitions.
 *
 * Loaded from assets/games/{gamePackage}/config.json.
 *
 * 🧠 4x-game-agent mechanic: Layer 2 — Data-driven game-specific configuration.
 */
@Serializable
data class GameConfig(
    val gamePackage: String,
    val gameName: String = "",
    val version: Int = 1,
    val screens: List<ScreenConfig> = emptyList(),
    val transitions: List<TransitionConfig> = emptyList(),
    val popups: List<PopupConfig> = emptyList(),
    val resources: List<ResourceConfig> = emptyList(),
    val homeScreen: String = "home",
    val timeoutMs: Long = 30_000,
) {
    fun getScreen(name: String): ScreenConfig? = screens.firstOrNull { it.name == name }
    fun getTransitionsFrom(from: String): List<TransitionConfig> =
        transitions.filter { it.fromScreen == from }
    fun getPopupConfigs(): List<PopupConfig> = popups
    fun getResourceConfigs(): List<ResourceConfig> = resources
}

@Serializable
data class ScreenConfig(
    val name: String,
    val aliases: List<String> = emptyList(),
    val classification: ClassificationConfig = ClassificationConfig(),
    val isPopup: Boolean = false,
    val isTransition: Boolean = false,
)

@Serializable
data class ClassificationConfig(
    val pixelChecks: List<PixelCheckConfig> = emptyList(),
    val elementChecks: List<ElementCheckConfig> = emptyList(),
    val textChecks: List<TextCheckConfig> = emptyList(),
)

@Serializable
data class PixelCheckConfig(
    val x: Int,
    val y: Int,
    val expectedColor: Int,
    val tolerance: Int = 10,
    val weight: Float = 1.0f,
)

@Serializable
data class ElementCheckConfig(
    val resourceId: String? = null,
    val text: String? = null,
    val textContains: String? = null,
    val contentDesc: String? = null,
    val className: String? = null,
    val mustExist: Boolean = true,
    val weight: Float = 1.0f,
) {
    fun toTargetSpec(): TargetSpec = TargetSpec(
        type = when {
            resourceId != null -> TargetType.RESOURCE_ID
            text != null -> TargetType.TEXT
            textContains != null -> TargetType.TEXT
            contentDesc != null -> TargetType.CONTENT_DESC
            className != null -> TargetType.CLASS_NAME
            else -> TargetType.COORDINATES
        },
        text = text,
        textContains = textContains,
        contentDesc = contentDesc,
        resourceId = resourceId,
        className = className,
    )
}

@Serializable
data class TextCheckConfig(
    val text: String,
    val exact: Boolean = true,
    val weight: Float = 1.0f,
)

@Serializable
data class TransitionConfig(
    val fromScreen: String,
    val toScreen: String,
    val tool: String,
    val target: TargetSpec? = null,
    val description: String = "",
    val conditions: List<ConditionConfig> = emptyList(),
    val priority: Int = 0,
    val cost: Int = 1,
) {
    fun toStateTransition(): StateTransition = StateTransition(
        fromScreen = fromScreen,
        toScreen = toScreen,
        action = SkillStep(
            id = "transition_${fromScreen}_to_$toScreen",
            description = description.ifEmpty { "Navigate from $fromScreen to $toScreen" },
            tool = tool,
            target = target,
        ),
        conditions = conditions.map { it.toTransitionCondition() },
        priority = priority,
        description = description,
        cost = cost,
    )
}

@Serializable
data class ConditionConfig(
    val key: String,
    val operator: String = ">=",
    val value: Long = 0,
) {
    fun toTransitionCondition(): TransitionCondition = TransitionCondition(
        key = key,
        operator = when (operator) {
            "==" -> ConditionOperator.EQUALS
            "!=" -> ConditionOperator.NOT_EQUALS
            ">" -> ConditionOperator.GREATER_THAN
            "<" -> ConditionOperator.LESS_THAN
            ">=" -> ConditionOperator.GREATER_OR_EQUAL
            "<=" -> ConditionOperator.LESS_OR_EQUAL
            "exists" -> ConditionOperator.EXISTS
            "not_exists" -> ConditionOperator.NOT_EXISTS
            else -> ConditionOperator.GREATER_OR_EQUAL
        },
        value = value,
    )
}

@Serializable
data class PopupConfig(
    val type: PopupType = PopupType.GENERIC,
    val classNames: List<String> = emptyList(),
    val textPatterns: List<String> = emptyList(),
    val closeTarget: TargetSpec? = null,
    val closeResourceIds: List<String> = emptyList(),
    val priority: Int = 0,
)

enum class PopupType {
    AD, OFFER, UPDATE, ERROR, REWARD, CONNECTION, PERMISSION, GENERIC,
}

@Serializable
data class ResourceConfig(
    val key: String,
    val displayName: String = "",
    val patterns: List<String> = emptyList(),
    val isCurrency: Boolean = false,
    val isPremium: Boolean = false,
    val iconTemplate: String? = null,
)
