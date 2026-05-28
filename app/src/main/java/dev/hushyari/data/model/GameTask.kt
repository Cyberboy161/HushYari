package dev.hushyari.data.model

import java.util.UUID

/**
 * A task given by the user to accomplish in a game.
 * 🧠 PokeClaw mechanic: Structured task with channels and history.
 */
data class GameTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val gamePackage: String = "",
    val gameName: String = "",
    val maxSteps: Int = 1000,
    val maxTimeMs: Long = 3_600_000, // 1 hour default
    val maxAttempts: Int = 10,
    val allowSpending: Boolean = false,
    val allowChatMessages: Boolean = false,
    val safetyLevel: SafetyLevel = SafetyLevel.STANDARD,
    val createdAt: Long = System.currentTimeMillis(),
    var status: TaskStatus = TaskStatus.PENDING,
    var currentStep: Int = 0,
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var lastError: String? = null,
) {
    val isActive: Boolean get() = status == TaskStatus.RUNNING
    val isTerminal: Boolean
        get() = status == TaskStatus.COMPLETED ||
                status == TaskStatus.FAILED ||
                status == TaskStatus.CANCELLED

    val runtimeMs: Long
        get() = if (startedAt != null) {
            (completedAt ?: System.currentTimeMillis()) - startedAt!!
        } else 0L
}

enum class TaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class SafetyLevel {
    LENIENT,
    STANDARD,
    STRICT,
}
