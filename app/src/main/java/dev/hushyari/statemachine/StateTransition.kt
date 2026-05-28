package dev.hushyari.statemachine

import dev.hushyari.data.model.SkillStep

/**
 * Represents a single directed edge in the screen state machine.
 *
 * Defines a valid navigation from [fromScreen] to [toScreen] via an [action],
 * optionally guarded by [conditions] that must be satisfied in the world model.
 *
 * 🧠 4x-game-agent mechanic: Layer 2 — Screen transition rule encoding.
 */
data class StateTransition(
    val fromScreen: String,
    val toScreen: String,
    val action: SkillStep,
    val conditions: List<TransitionCondition> = emptyList(),
    val priority: Int = 0,
    val description: String = "",
    val cost: Int = 1,
) {
    fun isSatisfied(resources: Map<String, Long>, currentScreen: String): Boolean {
        if (currentScreen != fromScreen) return false
        return conditions.all { it.evaluate(resources) }
    }
}

/**
 * A condition that must be met for a transition to be valid.
 *
 * Evaluates against the current world model resources map.
 *
 * 🧠 4x-game-agent mechanic: Guard conditions on state transitions.
 */
data class TransitionCondition(
    val key: String,
    val operator: ConditionOperator,
    val value: Long,
) {
    fun evaluate(resources: Map<String, Long>): Boolean {
        val actual = resources[key] ?: 0L
        return when (operator) {
            ConditionOperator.EQUALS -> actual == value
            ConditionOperator.NOT_EQUALS -> actual != value
            ConditionOperator.GREATER_THAN -> actual > value
            ConditionOperator.LESS_THAN -> actual < value
            ConditionOperator.GREATER_OR_EQUAL -> actual >= value
            ConditionOperator.LESS_OR_EQUAL -> actual <= value
            ConditionOperator.EXISTS -> resources.containsKey(key)
            ConditionOperator.NOT_EXISTS -> !resources.containsKey(key)
        }
    }
}

enum class ConditionOperator {
    EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN,
    GREATER_OR_EQUAL, LESS_OR_EQUAL, EXISTS, NOT_EXISTS,
}
