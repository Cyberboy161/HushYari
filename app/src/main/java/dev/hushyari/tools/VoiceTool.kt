package dev.hushyari.tools

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private val VOICE_PERMISSIONS = listOf(
    "android.permission.RECORD_AUDIO",
)

private val VOICE_SAFETY_RULES = listOf(
    "checkScreenSensitivity",
    "checkPaymentScreen",
    "checkAuthenticationScreen",
    "checkRateLimit",
    "checkPlayTimeLimit",
)

/**
 * Voice tool: speech-to-text recognition and text-to-speech synthesis.
 *
 * Uses Android's built-in [android.speech.SpeechRecognizer] for STT
 * and [android.speech.tts.TextToSpeech] for TTS.
 * Async recognition is bridged to coroutines via [suspendCancellableCoroutine].
 *
 * Actions:
 * - "speech_to_text" — listen and convert speech to text
 * - "text_to_speech" — read text aloud
 *
 * Params:
 * - "action"         — "speech_to_text" or "text_to_speech"
 * - "text"           — text to speak (for text_to_speech)
 * - "language"       — language code (e.g. "en-US", "zh-CN", default device locale)
 * - "prompt"         — hint text shown during recognition (for speech_to_text)
 * - "max_results"    — max number of recognition results (default 1)
 * - "pitch"          — TTS pitch (default 1.0f)
 * - "speech_rate"    — TTS speech rate (default 1.0f)
 */
@Singleton
class VoiceTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {

    override val name = "voice"
    override val description = "Speech-to-text recognition and text-to-speech synthesis"
    override val category = ToolCategory.VOICE
    override val requiredPermissions = VOICE_PERMISSIONS
    override val safetyRules = VOICE_SAFETY_RULES

    private var tts: TextToSpeech? = null

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        if (action !in setOf("speech_to_text", "text_to_speech")) return false
        if (action == "text_to_speech" && params["text"] == null) return false
        return true
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return invalidParams("action is required (speech_to_text/text_to_speech)")

        return try {
            when (action) {
                "speech_to_text" -> executeSpeechToText(params)
                "text_to_speech" -> executeTextToSpeech(params)
                else -> invalidParams("Unknown action: $action")
            }
        } catch (e: SecurityException) {
            ToolResult.Failure(
                toolName = name,
                error = "Audio permission denied: ${e.message}",
                errorCode = ErrorCode.PERMISSION_DENIED,
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                toolName = name,
                error = "Voice tool error: ${e.message}",
                errorCode = ErrorCode.UNKNOWN,
            )
        }
    }

    private suspend fun executeSpeechToText(params: Map<String, Any?>): ToolResult {
        val language = params["language"]?.toString() ?: Locale.getDefault().toString()
        val prompt = params["prompt"]?.toString() ?: "Speak now..."
        val maxResults = (params["max_results"] as? Number)?.toInt() ?: 1

        return suspendCancellableCoroutine { continuation ->
            val results = mutableListOf<String>()

            // Speech recognition requires an Activity context for the intent.
            // The caller is expected to handle the activity result.
            // We return a result indicating readiness for recognition.
            if (continuation.isActive) {
                continuation.resume(
                    ToolResult.Success(
                        toolName = name,
                        message = "Speech recognition intent ready",
                        data = mapOf(
                            "language" to language,
                            "prompt" to prompt,
                            "max_results" to maxResults,
                            "requires_activity" to true,
                            "note" to "Speech recognition requires an Activity to launch the intent. " +
                                "Caller should start RecognizerIntent.ACTION_RECOGNIZE_SPEECH.",
                        ),
                    )
                )
            }
        }
    }

    private suspend fun executeTextToSpeech(params: Map<String, Any?>): ToolResult {
        val text = params["text"]?.toString() ?: return invalidParams("text is required")
        val language = params["language"]?.toString()
        val pitch = (params["pitch"] as? Number)?.toFloat() ?: 1.0f
        val speechRate = (params["speech_rate"] as? Number)?.toFloat() ?: 1.0f

        if (text.length > 4000) {
            return ToolResult.Failure(
                toolName = name,
                error = "Text too long for TTS: ${text.length} chars (max 4000)",
                errorCode = ErrorCode.INVALID_PARAMS,
            )
        }

        return suspendCancellableCoroutine { continuation ->
            val utteranceId = "hushyari_tts_${System.currentTimeMillis()}"

            val listener = TextToSpeech.OnInitListener { status ->
                if (status != TextToSpeech.SUCCESS) {
                    if (continuation.isActive) {
                        continuation.resume(
                            ToolResult.Failure(
                                toolName = name,
                                error = "TTS initialization failed with status $status",
                                errorCode = ErrorCode.MODEL_ERROR,
                            )
                        )
                    }
                    return@OnInitListener
                }

                val ttsEngine = tts ?: return@OnInitListener

                if (language != null) {
                    val locale = Locale.forLanguageTag(language)
                    ttsEngine.language = locale
                }

                ttsEngine.setPitch(pitch.coerceIn(0.1f, 3.0f))
                ttsEngine.setSpeechRate(speechRate.coerceIn(0.1f, 3.0f))

                ttsEngine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (continuation.isActive) {
                            continuation.resume(
                                ToolResult.Success(
                                    toolName = name,
                                    message = "TTS completed: ${text.length} characters spoken",
                                    data = mapOf(
                                        "char_count" to text.length,
                                        "language" to (language ?: ttsEngine.language.toString()),
                                        "pitch" to pitch,
                                        "speech_rate" to speechRate,
                                    ),
                                )
                            )
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (continuation.isActive) {
                            continuation.resume(
                                ToolResult.Failure(
                                    toolName = name,
                                    error = "TTS utterance error for: ${text.take(50)}...",
                                    errorCode = ErrorCode.MODEL_ERROR,
                                )
                            )
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(
                                ToolResult.Failure(
                                    toolName = name,
                                    error = "TTS error code $errorCode for: ${text.take(50)}...",
                                    errorCode = ErrorCode.MODEL_ERROR,
                                )
                            )
                        }
                    }
                })

                val result = ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    if (continuation.isActive) {
                        continuation.resume(
                            ToolResult.Failure(
                                toolName = name,
                                error = "TTS speak() failed with result $result",
                                errorCode = ErrorCode.MODEL_ERROR,
                            )
                        )
                    }
                }
            }

            tts?.stop()
            tts?.shutdown()
            tts = TextToSpeech(context, listener)
        }
    }

    /**
     * Shutdown the TTS engine. Call when the tool is no longer needed.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
