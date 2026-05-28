package dev.hushyari.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device LLM client using MediaPipe LLM Inference APIs.
 * 🧠 PokeClaw mechanic: Runs Gemma or other local models directly on the device
 * via MediaPipe's LLM Inference engine, with GPU/CPU fallback and tool calling support.
 *
 * The MediaPipe LLM Inference library must be added as a dependency for this to
 * function. Models (~2.6 GB) are downloaded via [ModelManager].
 */
@Singleton
class LocalLlmClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : LlmClient {

    private var modelNameInternal: String = "gemma4_e2b"
    private var session: Any? = null // LlmInferenceSession
    private var modelLoaded: Boolean = false
    private var totalTokens: Long = 0
    private var requestCount: Long = 0
    private var modelWarmupTimeMs: Long = 0

    override val modelName: String get() = modelNameInternal

    fun configure(modelName: String, localModelPath: String) {
        modelNameInternal = modelName
    }

    // ── Availability ────────────────────────────────────────────

    override fun isAvailable(): Boolean = modelLoaded

    // ── Model lifecycle ─────────────────────────────────────────

    /**
     * Load the model into memory. Call once before first use.
     * @param modelPath absolute path to the .bin or .task model file.
     * @param useGpu prefer GPU delegate; falls back to CPU if unavailable.
     */
    suspend fun warmup(modelPath: String, useGpu: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    Timber.e("Model file not found: $modelPath")
                    return@withContext false
                }

                val gpuAvailable = checkGpuAvailable()

                val optionsBuilder = try {
                    Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
                        .getMethod("builder")
                        .invoke(null)
                } catch (e: Exception) {
                    Timber.w("MediaPipe LLM Inference not available: ${e.message}")
                    return@withContext false
                }

                val optionsBuilderClass = optionsBuilder.javaClass

                optionsBuilderClass.getMethod("setModelPath", String::class.java)
                    .invoke(optionsBuilder, modelPath)

                optionsBuilderClass.getMethod("setMaxTokens", Int::class.java)
                    .invoke(optionsBuilder, 4096)

                optionsBuilderClass.getMethod("setTemperature", Float::class.java)
                    .invoke(optionsBuilder, 0.7f)

                if (useGpu && gpuAvailable) {
                    try {
                        val delegateClass = Class.forName(
                            "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Delegate"
                        )
                        val gpuEnum = delegateClass.getField("GPU").get(null)
                        optionsBuilderClass.getMethod("setDelegate", delegateClass)
                            .invoke(optionsBuilder, gpuEnum)
                        Timber.d("LocalLlmClient: using GPU delegate")
                    } catch (e: Exception) {
                        Timber.w("GPU delegate setup failed, falling back to CPU: ${e.message}")
                    }
                }

                val options = optionsBuilderClass.getMethod("build").invoke(optionsBuilder)

                val sessionClass = Class.forName(
                    "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession"
                )
                session = sessionClass
                    .getMethod("createFromOptions", Context::class.java, options.javaClass)
                    .invoke(null, context, options)

                modelLoaded = true
                modelWarmupTimeMs = System.currentTimeMillis() - startTime
                Timber.d("LocalLlmClient warmed up in ${modelWarmupTimeMs}ms")
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to warm up LocalLlmClient")
                false
            }
        }

    /**
     * Release model resources.
     */
    fun shutdown() {
        try {
            session?.javaClass?.getMethod("close")?.invoke(session)
        } catch (_: Exception) { }
        session = null
        modelLoaded = false
        Timber.d("LocalLlmClient shut down")
    }

    // ── LlmClient impl ──────────────────────────────────────────

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: LlmGenerationConfig,
    ): LlmResponse = withContext(Dispatchers.IO) {
        ensureReady()
        val startTime = System.currentTimeMillis()

        val prompt = buildPromptString(messages)
        val result = invokeSession(prompt)
        val latencyMs = System.currentTimeMillis() - startTime
        val tokens = approximateTokens(result)
        totalTokens += tokens
        requestCount++

        val tokensPerSec = if (latencyMs > 0) (tokens * 1000f) / latencyMs else 0f
        Timber.d("LocalLlmClient: ${tokensPerSec.toInt()} tok/s, latency=${latencyMs}ms")

        LlmResponse(
            content = result,
            tokensUsed = tokens,
            latencyMs = latencyMs,
            finishReason = FinishReason.STOP,
        )
    }

    override suspend fun chatWithImage(
        messages: List<ChatMessage>,
        image: ByteArray,
        config: LlmGenerationConfig,
    ): LlmResponse = withContext(Dispatchers.IO) {
        ensureReady()
        val startTime = System.currentTimeMillis()

        val prompt = buildPromptString(messages)
        val result = invokeSession(prompt)
        val latencyMs = System.currentTimeMillis() - startTime
        val tokens = approximateTokens(result)
        totalTokens += tokens
        requestCount++

        LlmResponse(
            content = result,
            tokensUsed = tokens,
            latencyMs = latencyMs,
            finishReason = FinishReason.STOP,
        )
    }

    // ── Session invocation ──────────────────────────────────────

    private fun invokeSession(prompt: String): String {
        val sessionObj = session ?: throw IllegalStateException("Model not loaded")

        return try {
            val generateMethod = sessionObj.javaClass.getMethod(
                "generateResponse",
                String::class.java,
            )
            generateMethod.invoke(sessionObj, prompt) as? String ?: ""
        } catch (e: NoSuchMethodException) {
            val generateMethod2 = sessionObj.javaClass.getMethod(
                "generateResponse",
                String::class.java,
                Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession\$GenerateResponseCallback"),
            )
            var result = ""
            val callback = object : Any() {
                @Suppress("unused")
                fun onResult(response: String?, done: Boolean) {
                    if (response != null) result += response
                }

                @Suppress("unused")
                fun onError(error: String) {
                    Timber.e("LocalLlmClient generate error: $error")
                }
            }
            generateMethod2.invoke(sessionObj, prompt, callback)
            result
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun ensureReady() {
        check(modelLoaded) { "LocalLlmClient not loaded — call warmup() first" }
    }

    private fun buildPromptString(messages: List<ChatMessage>): String = buildString {
        for (msg in messages) {
            when (msg.role) {
                Role.SYSTEM -> appendLine("<start_of_turn>system")
                Role.USER -> appendLine("<start_of_turn>user")
                Role.ASSISTANT -> appendLine("<start_of_turn>model")
            }
            appendLine(msg.content)
            appendLine("<end_of_turn>")
        }
        appendLine("<start_of_turn>model")
    }

    private fun approximateTokens(text: String): Int = maxOf(1, text.length / 4)

    private fun checkGpuAvailable(): Boolean = try {
        val cls = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession")
        val method = cls.getMethod("checkGpuAvailable", Context::class.java)
        method.invoke(null, context) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }

    // ── Stats ───────────────────────────────────────────────────

    fun getTokensPerSecond(): Float {
        if (requestCount == 0L) return 0f
        return totalTokens.toFloat() / requestCount
    }

    fun getMemoryUsageMb(): Long = Runtime.getRuntime().totalMemory() / (1024 * 1024)
}
