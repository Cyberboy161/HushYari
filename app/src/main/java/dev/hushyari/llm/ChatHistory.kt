package dev.hushyari.llm

import com.tencent.mmkv.MMKV
import timber.log.Timber

/**
 * Manages multi-turn conversation state for an agent session.
 * 🧠 PokeClaw mechanic: Stores full conversation history with token-budget trimming
 * and crash-recovery persistence via MMKV.
 */
class ChatHistory(
    private val sessionId: String = "default",
) {

    private val messages = mutableListOf<ChatMessage>()
    private val prefs = MMKV.mmkvWithID("chat_history_$sessionId")

    init {
        restoreFromPersistence()
    }

    /**
     * Add a message to the conversation.
     */
    fun addMessage(role: Role, content: String) {
        messages.add(ChatMessage(role, content))
        persistMessage(role, content)
    }

    /**
     * Return the full conversation history.
     */
    fun getHistory(): List<ChatMessage> = messages.toList()

    /**
     * Return the last [n] messages.
     */
    fun getLastN(n: Int): List<ChatMessage> = messages.takeLast(n)

    /**
     * Return the number of messages.
     */
    fun size(): Int = messages.size

    /**
     * Clear all history.
     */
    fun clear() {
        messages.clear()
        prefs.clearAll()
        Timber.d("ChatHistory[$sessionId] cleared")
    }

    /**
     * Trim history to fit within [maxTokens] using 4 chars/token heuristic.
     * Removes oldest message pairs (user+assistant) first, always keeping the system prompt.
     * Returns true if trimming occurred.
     */
    fun trimToTokenBudget(maxTokens: Int): Boolean {
        var currentTokens = estimateTotalTokens()
        if (currentTokens <= maxTokens) return false

        var trimmed = false
        while (messages.size > 2 && currentTokens > maxTokens) {
            val systemCount = messages.count { it.role == Role.SYSTEM }
            val firstNonSystem = messages.indexOfFirst { it.role != Role.SYSTEM }
            if (firstNonSystem < 0) break

            val removed = messages.removeAt(firstNonSystem)
            currentTokens -= estimateTokens(removed.content)
            trimmed = true
        }
        return trimmed
    }

    /**
     * Format the full history as a prompt string with turn markers.
     */
    fun toPromptString(): String = buildString {
        for (msg in messages) {
            when (msg.role) {
                Role.SYSTEM -> appendLine("System: ${msg.content}")
                Role.USER -> appendLine("User: ${msg.content}")
                Role.ASSISTANT -> appendLine("Assistant: ${msg.content}")
            }
        }
    }

    /**
     * Return only user messages.
     */
    fun getUserMessages(): List<ChatMessage> =
        messages.filter { it.role == Role.USER }

    /**
     * Return only assistant messages.
     */
    fun getAssistantMessages(): List<ChatMessage> =
        messages.filter { it.role == Role.ASSISTANT }

    // ── Token estimation ────────────────────────────────────────

    private fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)

    private fun estimateTotalTokens(): Int =
        messages.sumOf { estimateTokens(it.content) }

    // ── Persistence ─────────────────────────────────────────────

    private fun persistMessage(role: Role, content: String) {
        val index = prefs.decodeInt("_count", 0)
        prefs.encode("_count", index + 1)
        prefs.encode("msg_${index}_role", role.name)
        prefs.encode("msg_${index}_content", content)
    }

    private fun restoreFromPersistence() {
        val count = prefs.decodeInt("_count", 0)
        for (i in 0 until count) {
            val roleStr = prefs.decodeString("msg_${i}_role") ?: continue
            val content = prefs.decodeString("msg_${i}_content") ?: continue
            val role = try {
                Role.valueOf(roleStr)
            } catch (_: Exception) {
                Role.USER
            }
            messages.add(ChatMessage(role, content))
        }
        Timber.d("ChatHistory[$sessionId] restored ${messages.size} messages from MMKV")
    }
}
