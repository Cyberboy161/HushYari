package dev.hushyari.tools

import dev.hushyari.data.model.ToolResult

enum class ToolCategory {
    GESTURE,
    INPUT,
    SCREEN,
    APP,
    FIND,
    WAIT,
    CLIPBOARD,
    VOICE,
}

/**
 * Foundation interface for all game-agnostic tool primitives.
 *
 * Each tool is a single atomic action that can be composed into [Skill]s.
 * Safety rules from ToolSafety are enforced by [ToolManager] before execution.
 */
interface Tool {
    val name: String
    val description: String
    val category: ToolCategory
    val requiredPermissions: List<String>
    val safetyRules: List<String>

    suspend fun execute(params: Map<String, Any?>): ToolResult

    fun validateParams(params: Map<String, Any?>): Boolean
}
