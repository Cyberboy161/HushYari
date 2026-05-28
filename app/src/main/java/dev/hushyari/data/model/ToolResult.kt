package dev.hushyari.data.model

/**
 * Tool execution result returned after any tool invocation.
 * 🧠 PokeClaw mechanic: Structured result with success/failure + data.
 */
sealed class ToolResult {
    abstract val toolName: String
    abstract val executionTimeMs: Long

    data class Success(
        override val toolName: String,
        override val executionTimeMs: Long = 0,
        val message: String = "",
        val data: Map<String, Any> = emptyMap(),
    ) : ToolResult()

    data class Failure(
        override val toolName: String,
        override val executionTimeMs: Long = 0,
        val error: String,
        val errorCode: ErrorCode = ErrorCode.UNKNOWN,
        val recoverable: Boolean = true,
    ) : ToolResult()

    data class SafetyBlocked(
        override val toolName: String,
        override val executionTimeMs: Long = 0,
        val rule: String,
        val reason: String,
    ) : ToolResult()

    data class Timeout(
        override val toolName: String,
        override val executionTimeMs: Long = 0,
        val timeoutMs: Long,
    ) : ToolResult()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure || this is SafetyBlocked || this is Timeout
}

enum class ErrorCode {
    UNKNOWN,
    PERMISSION_DENIED,
    ELEMENT_NOT_FOUND,
    GESTURE_FAILED,
    NETWORK_ERROR,
    TIMEOUT,
    GAME_NOT_RUNNING,
    SCREEN_CAPTURE_FAILED,
    MODEL_ERROR,
    SAFETY_BLOCKED,
    INVALID_PARAMS,
    INTERRUPTED,
}
