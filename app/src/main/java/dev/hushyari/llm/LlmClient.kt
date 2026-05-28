package dev.hushyari.llm

/**
 * Role of a participant in a chat conversation.
 */
enum class Role { USER, ASSISTANT, SYSTEM }

/**
 * A single message in a chat conversation.
 * 🧠 PokeClaw mechanic: Standardized message format decoupled from any specific LLM API.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
)

/**
 * Controls how the LLM generates output.
 */
data class LlmGenerationConfig(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val topP: Float = 1.0f,
    val stopSequences: List<String> = emptyList(),
)

/**
 * Reason the LLM stopped generating.
 */
enum class FinishReason { STOP, LENGTH, CONTENT_FILTER, ERROR, UNKNOWN }

/**
 * Unified response from any LLM backend.
 */
data class LlmResponse(
    val content: String,
    val tokensUsed: Int = 0,
    val latencyMs: Long = 0,
    val finishReason: FinishReason = FinishReason.UNKNOWN,
)

/**
 * Interface for interacting with any LLM — cloud or local.
 * 🧠 Roubao + ClickClickClick mechanic: Multi-backend LLM abstraction
 * supporting OpenAI, Anthropic, Google Gemini, and on-device models.
 */
interface LlmClient {
    /**
     * Display name for the model (e.g. "Gemini 2.5 Flash").
     */
    val modelName: String

    /**
     * Send a text-only conversation to the LLM.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        config: LlmGenerationConfig = LlmGenerationConfig(),
    ): LlmResponse

    /**
     * Send a conversation with an image attachment to the LLM.
     * [image] is JPEG or PNG raw bytes.
     */
    suspend fun chatWithImage(
        messages: List<ChatMessage>,
        image: ByteArray,
        config: LlmGenerationConfig = LlmGenerationConfig(),
    ): LlmResponse

    /**
     * Whether this client is ready to accept requests.
     */
    fun isAvailable(): Boolean
}
