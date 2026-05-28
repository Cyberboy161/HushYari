package dev.hushyari.llm

/**
 * Supported LLM backends and model configurations.
 */
enum class LlmProvider {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    OLLAMA,
    CUSTOM,
    LOCAL,
}

/**
 * Configuration for an LLM client instance.
 * 🧠 Roubao mechanic: Single config object wiring provider, model, keys, and tuning params.
 */
data class LlmConfig(
    val provider: LlmProvider,
    val modelName: String,
    val apiKey: String = "",
    val baseUrl: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val topP: Float = 1.0f,
    val useLocal: Boolean = false,
    val localModelPath: String = "",
) {
    val isLocal: Boolean get() = provider == LlmProvider.LOCAL || useLocal

    companion object {
        val DEFAULT_OPENAI = LlmConfig(
            provider = LlmProvider.OPENAI,
            modelName = "gpt-4o",
            baseUrl = "https://api.openai.com/v1",
        )

        val DEFAULT_ANTHROPIC = LlmConfig(
            provider = LlmProvider.ANTHROPIC,
            modelName = "claude-sonnet-4-20250514",
            baseUrl = "https://api.anthropic.com/v1",
        )

        val DEFAULT_GOOGLE = LlmConfig(
            provider = LlmProvider.GOOGLE,
            modelName = "gemini-2.5-flash",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        )

        val DEFAULT_LOCAL = LlmConfig(
            provider = LlmProvider.LOCAL,
            modelName = "gemma4_e2b",
            useLocal = true,
        )
    }
}
