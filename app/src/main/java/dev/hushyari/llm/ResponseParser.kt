package dev.hushyari.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * A structured action produced by the LLM response parser.
 * 🧠 PokeClaw mechanic: Typed action hierarchy so the agent loop
 * can route tool calls, plans, questions, and completion signals.
 */
sealed class AgentAction {

    /**
     * Execute a named tool with parameters.
     */
    data class ToolAction(
        val toolName: String,
        val params: Map<String, String> = emptyMap(),
    ) : AgentAction()

    /**
     * A multi-step plan to execute sequentially.
     */
    data class PlanAction(
        val steps: List<String>,
    ) : AgentAction()

    /**
     * The agent needs to ask the user a question.
     */
    data class AskAction(
        val question: String,
    ) : AgentAction()

    /**
     * The agent considers the task complete.
     */
    data class DoneAction(
        val reason: String,
    ) : AgentAction()

    /**
     * No action needed at this moment (e.g., waiting for animation).
     */
    data object WaitAction : AgentAction()
}

/**
 * Parses LLM text responses into structured [AgentAction] objects.
 * 🧠 PokeClaw mechanic: Robust multi-format parser that handles JSON mode,
 * function-calling format, markdown code fences, and plain-text regex fallback.
 */
object ResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse the LLM response string into an [AgentAction], or null if unparseable.
     */
    fun parse(response: String): AgentAction? {
        val cleaned = response.trim()

        // 1. Try JSON mode: {"tool": "...", "params": {...}}
        tryParseJsonAction(cleaned)?.let { return it }

        // 2. Try function-calling format: <function_call>{"name":"...","arguments":{...}}</function_call>
        tryParseFunctionCall(cleaned)?.let { return it }

        // 3. Try markdown code fence with JSON
        tryParseMarkdownFence(cleaned)?.let { return it }

        // 4. Try "done" / "task complete" detection
        tryParseDoneAction(cleaned)?.let { return it }

        // 5. Try "ask" / question detection
        tryParseAskAction(cleaned)?.let { return it }

        // 6. Regex fallback for plain text action descriptions
        tryParsePlainTextAction(cleaned)?.let { return it }

        Timber.w("Could not parse agent action from response: ${cleaned.take(200)}")
        return null
    }

    // ── Format-specific parsers ─────────────────────────────────

    private fun tryParseJsonAction(text: String): AgentAction? {
        return try {
            val obj = json.parseToJsonElement(text).jsonObject

            val tool = obj["tool"]?.jsonPrimitive?.content
                ?: obj["function"]?.jsonPrimitive?.content
                ?: obj["action"]?.jsonPrimitive?.content
                ?: return null

            val params = extractParams(obj)
            mapToolAction(tool, params)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryParseFunctionCall(text: String): AgentAction? {
        val regex = Regex(
            """<function_call>\s*(\{.*?\})\s*</function_call>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = regex.find(text) ?: return null
        return try {
            val obj = json.parseToJsonElement(match.groupValues[1]).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return null
            val arguments = obj["arguments"]?.jsonObject ?: JsonObject(emptyMap())
            val params = extractParams(arguments)
            mapToolAction(name, params)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryParseMarkdownFence(text: String): AgentAction? {
        val regex = Regex("""```(?:json)?\s*(\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text) ?: return null
        return tryParseJsonAction(match.groupValues[1])
    }

    private fun tryParseDoneAction(text: String): AgentAction? {
        val lower = text.lowercase()
        val donePatterns = listOf(
            "task complete", "task completed", "done", "finished",
            "objective complete", "goal achieved", "all done",
        )
        if (donePatterns.any { lower.contains(it) } && text.length < 200) {
            return AgentAction.DoneAction(reason = text.take(500))
        }
        return null
    }

    private fun tryParseAskAction(text: String): AgentAction? {
        val lower = text.lowercase()
        if (lower.contains("question:") || lower.contains("ask:")) {
            return AgentAction.AskAction(question = text.take(500))
        }
        val askRegex = Regex(
            """(?i)(?:question|ask)(?:\s*[:：]\s*)(.+?)(?:$|\n)""",
        )
        val match = askRegex.find(text) ?: return null
        return AgentAction.AskAction(question = match.groupValues[1].trim())
    }

    private fun tryParsePlainTextAction(text: String): AgentAction? {
        val lower = text.lowercase().trim()

        // Tap
        val tapRegex = Regex(
            """tap\s*(?:at)?\s*[\[(]?\s*(\d+)\s*[,，]\s*(\d+)\s*[)\]]?""",
            RegexOption.IGNORE_CASE,
        )
        tapRegex.find(text)?.let { match ->
            val x = match.groupValues[1]
            val y = match.groupValues[2]
            return AgentAction.ToolAction("tap", mapOf("x" to x, "y" to y))
        }

        // Swipe
        val swipeRegex = Regex(
            """swipe\s*(?:from)?\s*[\[(]?\s*(\d+)\s*[,，]\s*(\d+)\s*[)\]]?\s*(?:to)?\s*[\[(]?\s*(\d+)\s*[,，]\s*(\d+)\s*[)\]]?""",
            RegexOption.IGNORE_CASE,
        )
        swipeRegex.find(text)?.let { match ->
            return AgentAction.ToolAction("swipe", mapOf(
                "x1" to match.groupValues[1],
                "y1" to match.groupValues[2],
                "x2" to match.groupValues[3],
                "y2" to match.groupValues[4],
            ))
        }

        // Scroll
        if (lower.contains("scroll up")) {
            return AgentAction.ToolAction("scroll", mapOf("direction" to "up"))
        }
        if (lower.contains("scroll down")) {
            return AgentAction.ToolAction("scroll", mapOf("direction" to "down"))
        }
        if (lower.contains("scroll left")) {
            return AgentAction.ToolAction("scroll", mapOf("direction" to "left"))
        }
        if (lower.contains("scroll right")) {
            return AgentAction.ToolAction("scroll", mapOf("direction" to "right"))
        }

        // Back
        if (lower.contains("press back") || lower.contains("go back") || lower == "back") {
            return AgentAction.ToolAction("press_back", emptyMap())
        }

        // Wait
        val waitRegex = Regex(
            """wait\s*(\d+)\s*(?:ms|milliseconds?|s|seconds?)?""",
            RegexOption.IGNORE_CASE,
        )
        waitRegex.find(text)?.let { match ->
            val ms = match.groupValues[1]
            return AgentAction.ToolAction("wait", mapOf("ms" to ms))
        }

        // Dismiss popup
        if (lower.contains("dismiss popup") || lower.contains("close popup") ||
            lower.contains("dismiss ad") || lower.contains("close ad")
        ) {
            return AgentAction.ToolAction("dismiss_popup", emptyMap())
        }

        // Type text
        val typeRegex = Regex("""type\s*[：:]\s*(.+?)(?:$|\n)""", RegexOption.IGNORE_CASE)
        typeRegex.find(text)?.let { match ->
            return AgentAction.ToolAction("type_text", mapOf("text" to match.groupValues[1].trim()))
        }

        // Done with reason
        if ((lower.contains("done") || lower.contains("finished") ||
                lower.contains("complete")) && text.length < 300
        ) {
            return AgentAction.DoneAction(reason = text.take(500))
        }

        return null
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun extractParams(obj: JsonObject): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val paramsObj = obj["params"]?.jsonObject
            ?: obj["arguments"]?.jsonObject
            ?: obj

        for ((key, value) in paramsObj) {
            if (key == "tool" || key == "function" || key == "action" || key == "reasoning") continue
            val prim = value.jsonPrimitive
            params[key] = prim.content
        }
        return params
    }

    private fun mapToolAction(tool: String, params: Map<String, String>): AgentAction {
        return when (tool.lowercase()) {
            "done", "complete", "finish", "stop" ->
                AgentAction.DoneAction(reason = params["reason"] ?: "Task completed")

            "wait" ->
                AgentAction.ToolAction("wait", params)

            "plan" -> {
                val stepsStr = params["steps"] ?: ""
                val steps = stepsStr.split("\n").filter { it.isNotBlank() }
                AgentAction.PlanAction(steps = steps.ifEmpty { listOf(tool) })
            }

            "ask", "question" ->
                AgentAction.AskAction(question = params["question"] ?: "Question about the task")

            else -> AgentAction.ToolAction(tool, params)
        }
    }
}
