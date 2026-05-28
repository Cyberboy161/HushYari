package dev.hushyari.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

// ── Moshi request / response shapes ─────────────────────────────

private data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Float = 0.7f,
    val max_tokens: Int = 4096,
    val top_p: Float = 1.0f,
    val stop: List<String>? = null,
)

private data class OpenAiMessage(val role: String, val content: Any)

private data class OpenAiContentPart(val type: String, val text: String? = null, val image_url: Map<String, String>? = null)

private data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

private data class OpenAiChoice(val message: OpenAiMessage, val finish_reason: String? = null)

private data class OpenAiUsage(val total_tokens: Int = 0)

private data class AnthropicRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<AnthropicMessage>,
    val system: Any? = null,
    val temperature: Float = 0.7f,
    val top_p: Float = 1.0f,
    val stop_sequences: List<String>? = null,
)

private data class AnthropicMessage(val role: String, val content: Any)

private data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val source: Map<String, Any>? = null,
)

private data class AnthropicResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
    val usage: AnthropicUsage? = null,
    val stop_reason: String? = null,
)

private data class AnthropicUsage(val input_tokens: Int = 0, val output_tokens: Int = 0)

private data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

private data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>,
)

private data class GeminiPart(val text: String? = null, val inlineData: GeminiInlineData? = null)

private data class GeminiInlineData(val mimeType: String, val data: String)

private data class GeminiGenerationConfig(
    val temperature: Float = 0.7f,
    val maxOutputTokens: Int = 4096,
    val topP: Float = 1.0f,
    val stopSequences: List<String>? = null,
)

private data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsage? = null,
)

private data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

private data class GeminiUsage(val totalTokenCount: Int = 0)

/**
 * Cloud LLM client supporting OpenAI, Anthropic Claude, and Google Gemini.
 * 🧠 Roubao + ClickClickClick mechanic: Multi-provider LLM client with different
 * request formats per provider, image handling, retry with exponential backoff,
 * and unified [LlmResponse] output.
 */
@Singleton
class CloudLlmClient @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) : LlmClient {

    private val moshi: Moshi = Moshi.Builder().build()
    private val jsonAdapter = moshi.adapter(Map::class.java)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var config: LlmConfig = LlmConfig.DEFAULT_GOOGLE

    override val modelName: String get() = config.modelName

    fun configure(newConfig: LlmConfig) {
        config = newConfig
    }

    override fun isAvailable(): Boolean = config.apiKey.isNotBlank() || config.provider == LlmProvider.OLLAMA

    // ── Public API ──────────────────────────────────────────────

    override suspend fun chat(
        messages: List<ChatMessage>,
        configOverride: LlmGenerationConfig,
    ): LlmResponse = executeWithRetry {
        val request = when (config.provider) {
            LlmProvider.OPENAI -> buildOpenAiRequest(messages, null, configOverride)
            LlmProvider.ANTHROPIC -> buildAnthropicRequest(messages, null, configOverride)
            LlmProvider.GOOGLE -> buildGeminiRequest(messages, null, configOverride)
            LlmProvider.OLLAMA, LlmProvider.CUSTOM -> buildOpenAiCompatibleRequest(messages, null, configOverride)
            LlmProvider.LOCAL -> throw IllegalStateException("Use LocalLlmClient for local models")
        }
        executeRequest(request, configOverride)
    }

    override suspend fun chatWithImage(
        messages: List<ChatMessage>,
        image: ByteArray,
        configOverride: LlmGenerationConfig,
    ): LlmResponse = executeWithRetry {
        val processedImage = processImage(image)
        val request = when (config.provider) {
            LlmProvider.OPENAI -> buildOpenAiRequest(messages, processedImage, configOverride)
            LlmProvider.ANTHROPIC -> buildAnthropicRequest(messages, processedImage, configOverride)
            LlmProvider.GOOGLE -> buildGeminiRequest(messages, processedImage, configOverride)
            LlmProvider.OLLAMA, LlmProvider.CUSTOM -> buildOpenAiCompatibleRequest(messages, processedImage, configOverride)
            LlmProvider.LOCAL -> throw IllegalStateException("Use LocalLlmClient for local models")
        }
        executeRequest(request, configOverride)
    }

    // ── Request construction per provider ───────────────────────

    private fun buildOpenAiRequest(
        messages: List<ChatMessage>,
        imageBase64: String?,
        configOverride: LlmGenerationConfig,
    ): Request {
        val openAiMessages = messages.map { msg ->
            if (imageBase64 != null && msg.role == Role.USER) {
                val parts = mutableListOf<OpenAiContentPart>()
                parts.add(OpenAiContentPart(type = "text", text = msg.content))
                parts.add(
                    OpenAiContentPart(
                        type = "image_url",
                        image_url = mapOf("url" to "data:image/jpeg;base64,$imageBase64"),
                    )
                )
                OpenAiMessage(role = mapRoleToOpenAi(msg.role), content = parts)
            } else {
                OpenAiMessage(role = mapRoleToOpenAi(msg.role), content = msg.content)
            }
        }

        val body = OpenAiRequest(
            model = config.modelName,
            messages = openAiMessages,
            temperature = configOverride.temperature,
            max_tokens = configOverride.maxTokens,
            top_p = configOverride.topP,
            stop = configOverride.stopSequences.ifEmpty { null },
        )

        val json = moshi.adapter(OpenAiRequest::class.java).toJson(body)
        val url = "${config.baseUrl.trimEnd('/')}/chat/completions"

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildAnthropicRequest(
        messages: List<ChatMessage>,
        imageBase64: String?,
        configOverride: LlmGenerationConfig,
    ): Request {
        val systemMessages = messages.filter { it.role == Role.SYSTEM }
        val systemPrompt = systemMessages.joinToString("\n") { it.content }.ifEmpty { null }

        val chatMessages = messages.filter { it.role != Role.SYSTEM }.map { msg ->
            if (imageBase64 != null && msg.role == Role.USER) {
                val blocks = listOf(
                    AnthropicContentBlock(type = "text", text = msg.content),
                    AnthropicContentBlock(
                        type = "image",
                        source = mapOf(
                            "type" to "base64",
                            "media_type" to "image/jpeg",
                            "data" to imageBase64,
                        ),
                    ),
                )
                AnthropicMessage(role = "user", content = blocks)
            } else {
                AnthropicMessage(
                    role = mapRoleToAnthropic(msg.role),
                    content = msg.content,
                )
            }
        }

        val body = AnthropicRequest(
            model = config.modelName,
            max_tokens = configOverride.maxTokens,
            messages = chatMessages,
            system = if (systemPrompt != null) listOf(mapOf("type" to "text", "text" to systemPrompt)) else null,
            temperature = configOverride.temperature,
            top_p = configOverride.topP,
            stop_sequences = configOverride.stopSequences.ifEmpty { null },
        )

        val json = moshi.adapter(AnthropicRequest::class.java).toJson(body)
        val url = "${config.baseUrl.trimEnd('/')}/messages"

        return Request.Builder()
            .url(url)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildGeminiRequest(
        messages: List<ChatMessage>,
        imageBase64: String?,
        configOverride: LlmGenerationConfig,
    ): Request {
        val systemPrompt = messages.filter { it.role == Role.SYSTEM }
            .joinToString("\n") { it.content }

        val contents = messages.filter { it.role != Role.SYSTEM }.map { msg ->
            val parts = mutableListOf<GeminiPart>()
            parts.add(GeminiPart(text = msg.content))
            if (imageBase64 != null && msg.role == Role.USER) {
                parts.add(GeminiPart(inlineData = GeminiInlineData("image/jpeg", imageBase64)))
            }
            GeminiContent(role = mapRoleToGemini(msg.role), parts = parts)
        }

        val genConfig = GeminiGenerationConfig(
            temperature = configOverride.temperature,
            maxOutputTokens = configOverride.maxTokens,
            topP = configOverride.topP,
            stopSequences = configOverride.stopSequences.ifEmpty { null },
        )

        val body = GeminiRequest(contents = contents, generationConfig = genConfig)
        val json = moshi.adapter(GeminiRequest::class.java).toJson(body)

        val url = "${config.baseUrl.trimEnd('/')}/models/${config.modelName}:generateContent"

        val urlBuilder = url.toBuilder()
        if (systemPrompt.isNotBlank()) {
            urlBuilder.addQueryParameter("system_instruction", systemPrompt)
        }
        urlBuilder.addQueryParameter("key", config.apiKey)

        return Request.Builder()
            .url(urlBuilder.build())
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildOpenAiCompatibleRequest(
        messages: List<ChatMessage>,
        imageBase64: String?,
        configOverride: LlmGenerationConfig,
    ): Request {
        val body = buildOpenAiRequest(messages, imageBase64, configOverride)
        return body.newBuilder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .removeHeader("Authorization")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .build()
    }

    // ── Execution ───────────────────────────────────────────────

    private suspend fun executeRequest(
        request: Request,
        configOverride: LlmGenerationConfig,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw LlmApiException(response.code, errorBody)
        }

        val json = response.body?.string()
            ?: throw LlmApiException(-1, "Empty response body")
        val latencyMs = System.currentTimeMillis() - startTime

        parseResponse(json, config.maxTokens, latencyMs)
    }

    private suspend fun executeWithRetry(
        block: suspend () -> LlmResponse,
    ): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0..3) {
            try {
                return block()
            } catch (e: LlmApiException) {
                if (e.httpCode in 500..599 && attempt < 3) {
                    lastException = e
                    val delayMs = 1000L * (1 shl attempt)
                    Timber.w("LLM API error (attempt ${attempt + 1}/4), retrying in ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                    continue
                }
                throw e
            } catch (e: Exception) {
                if (attempt < 3) {
                    lastException = e
                    val delayMs = 1000L * (1 shl attempt)
                    Timber.w("LLM error (attempt ${attempt + 1}/4), retrying in ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                    continue
                }
                throw e
            }
        }
        throw lastException ?: LlmApiException(-1, "Max retries exceeded")
    }

    // ── Response parsing ────────────────────────────────────────

    private fun parseResponse(
        json: String,
        maxTokens: Int,
        latencyMs: Long,
    ): LlmResponse {
        return when (config.provider) {
            LlmProvider.OPENAI, LlmProvider.OLLAMA, LlmProvider.CUSTOM -> parseOpenAiResponse(json, latencyMs)
            LlmProvider.ANTHROPIC -> parseAnthropicResponse(json, maxTokens, latencyMs)
            LlmProvider.GOOGLE -> parseGeminiResponse(json, latencyMs)
            LlmProvider.LOCAL -> throw IllegalStateException("Use LocalLlmClient")
        }
    }

    private fun parseOpenAiResponse(json: String, latencyMs: Long): LlmResponse {
        val resp = moshi.adapter(OpenAiResponse::class.java).fromJson(json)
            ?: return LlmResponse("", 0, latencyMs, FinishReason.ERROR)

        val content = when (val msg = resp.choices.firstOrNull()?.message?.content) {
            is String -> msg
            is List<*> -> msg.joinToString("") { it.toString() }
            else -> ""
        }

        val finishReason = when (resp.choices.firstOrNull()?.finish_reason) {
            "stop" -> FinishReason.STOP
            "length" -> FinishReason.LENGTH
            "content_filter" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.UNKNOWN
        }

        return LlmResponse(
            content = content,
            tokensUsed = resp.usage?.total_tokens ?: approximateTokens(content),
            latencyMs = latencyMs,
            finishReason = finishReason,
        )
    }

    private fun parseAnthropicResponse(json: String, maxTokens: Int, latencyMs: Long): LlmResponse {
        val resp = moshi.adapter(AnthropicResponse::class.java).fromJson(json)
            ?: return LlmResponse("", 0, latencyMs, FinishReason.ERROR)

        val content = resp.content.joinToString("") {
            it.text ?: ""
        }

        val finishReason = when (resp.stop_reason) {
            "end_turn" -> FinishReason.STOP
            "max_tokens" -> FinishReason.LENGTH
            "stop_sequence" -> FinishReason.STOP
            else -> FinishReason.UNKNOWN
        }

        val tokens = (resp.usage?.input_tokens ?: 0) + (resp.usage?.output_tokens ?: 0)

        return LlmResponse(
            content = content,
            tokensUsed = if (tokens > 0) tokens else approximateTokens(content),
            latencyMs = latencyMs,
            finishReason = finishReason,
        )
    }

    private fun parseGeminiResponse(json: String, latencyMs: Long): LlmResponse {
        val resp = moshi.adapter(GeminiResponse::class.java).fromJson(json)
            ?: return LlmResponse("", 0, latencyMs, FinishReason.ERROR)

        val content = resp.candidates.firstOrNull()?.content?.parts
            ?.joinToString("") { it.text ?: "" } ?: ""

        val finishReason = when (resp.candidates.firstOrNull()?.finishReason) {
            "STOP" -> FinishReason.STOP
            "MAX_TOKENS" -> FinishReason.LENGTH
            "SAFETY" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.UNKNOWN
        }

        return LlmResponse(
            content = content,
            tokensUsed = resp.usageMetadata?.totalTokenCount ?: approximateTokens(content),
            latencyMs = latencyMs,
            finishReason = finishReason,
        )
    }

    // ── Image processing ────────────────────────────────────────

    private fun processImage(image: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
            ?: return Base64.encodeToString(image, Base64.NO_WRAP)

        val maxDim = 2048
        val (width, height) = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            (bitmap.width * scale).toInt() to (bitmap.height * scale).toInt()
        } else {
            bitmap.width to bitmap.height
        }

        val resized = if (width != bitmap.width || height != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val output = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 70, output)

        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()

        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    // ── Role mapping ────────────────────────────────────────────

    private fun mapRoleToOpenAi(role: Role): String = when (role) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.SYSTEM -> "system"
    }

    private fun mapRoleToAnthropic(role: Role): String = when (role) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.SYSTEM -> "user"
    }

    private fun mapRoleToGemini(role: Role): String = when (role) {
        Role.USER -> "user"
        Role.ASSISTANT -> "model"
        Role.SYSTEM -> "user"
    }

    // ── Token approximation ─────────────────────────────────────

    private fun approximateTokens(text: String): Int = maxOf(1, text.length / 4)
}

// ── Exception ───────────────────────────────────────────────────

class LlmApiException(
    val httpCode: Int,
    message: String,
) : Exception("LLM API error $httpCode: $message")

private fun String.toBuilder(): okhttp3.HttpUrl.Builder =
    okhttp3.HttpUrl.Builder().scheme("https").let { builder ->
        val u = this.toHttpUrlOrNull() ?: return@let builder
        builder.host(u.host).apply {
            u.pathSegments.forEach { addPathSegment(it) }
            u.queryParameterNames.forEach { name ->
                u.queryParameterValues(name).forEach { value ->
                    addQueryParameter(name, value)
                }
            }
        }
    }
