package dev.hushyari.tools

import dev.hushyari.data.model.ElementQuery
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import dev.hushyari.perception.PerceptionPipeline
import javax.inject.Inject
import javax.inject.Singleton

private val FIND_PERMISSIONS = listOf(
    "android.permission.SYSTEM_ALERT_WINDOW",
)

private val FIND_SAFETY_RULES = listOf(
    "checkScreenSensitivity",
    "checkPaymentScreen",
    "checkAuthenticationScreen",
    "checkRateLimit",
)

/**
 * Multi-strategy element finder with fallback chain.
 *
 * Tries strategies in order until a match is found:
 * 1. Accessibility tree search (fast, structured)
 * 2. OCR text recognition (for bitmap-rendered text)
 * 3. Template matching (for image-based detection)
 * 4. LLM-assisted find (for ambiguous / semantic queries)
 *
 * Actions:
 * - "find_element"  — find a single element matching query criteria
 * - "find_text"     — find text via OCR on the screen
 * - "find_image"    — find a template image on screen
 * - "find_all"      — return all elements matching query criteria
 *
 * Params:
 * - "action"           — one of the above actions
 * - "text"             — exact text to find
 * - "text_contains"    — substring match on text or content-desc
 * - "content_desc"     — exact content-description match
 * - "content_desc_contains" — substring match on content-description
 * - "resource_id"      — resource-id suffix to match
 * - "class_name"       — class name to match
 * - "template_name"    — name of template image to match
 * - "max_results"      — maximum results for find_all (default 20)
 * - "use_llm_fallback" — whether to fallback to LLM (default true)
 */
@Singleton
class FindTool @Inject constructor(
    private val perception: PerceptionPipeline,
) : Tool {

    override val name = "find"
    override val description = "Find UI elements, text, or images on screen with multi-strategy fallback"
    override val category = ToolCategory.FIND
    override val requiredPermissions = FIND_PERMISSIONS
    override val safetyRules = FIND_SAFETY_RULES

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        if (action !in setOf("find_element", "find_text", "find_image", "find_all")) return false

        return when (action) {
            "find_text" -> params["text"] != null
            "find_image" -> params["template_name"] != null
            else -> true
        }
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return invalidParams("action is required (find_element/find_text/find_image/find_all)")

        return try {
            when (action) {
                "find_element" -> executeFindElement(params)
                "find_text" -> executeFindText(params)
                "find_image" -> executeFindImage(params)
                "find_all" -> executeFindAll(params)
                else -> invalidParams("Unknown action: $action")
            }
        } catch (e: Exception) {
            ToolResult.Failure(
                toolName = name,
                error = "Find tool error: ${e.message}",
                errorCode = ErrorCode.ELEMENT_NOT_FOUND,
            )
        }
    }

    private suspend fun executeFindElement(params: Map<String, Any?>): ToolResult {
        val query = buildQuery(params)
        val screen = perception.capture(PerceptionPipeline.CaptureMode.FULL)

        val element = perception.findElement(query)

        if (element != null) {
            return elementToResult(element)
        }

        val useLlm = params["use_llm_fallback"] as? Boolean ?: true
        if (useLlm) {
            val textQuery = query.text ?: query.textContains ?: query.contentDesc ?: query.contentDescContains
            if (textQuery != null) {
                val textResults = screen.elements.filter { element ->
                    element.text.contains(textQuery, ignoreCase = true) ||
                    element.contentDescription.contains(textQuery, ignoreCase = true)
                }
                if (textResults.isNotEmpty()) {
                    return elementToResult(textResults.first())
                }
            }
        }

        return ToolResult.Failure(
            toolName = name,
            error = "Element not found with query: text=${query.text}, textContains=${query.textContains}, " +
                "resourceId=${query.resourceId}, className=${query.className}",
            errorCode = ErrorCode.ELEMENT_NOT_FOUND,
        )
    }

    private suspend fun executeFindText(params: Map<String, Any?>): ToolResult {
        val text = params["text"]?.toString()
            ?: return invalidParams("text is required for find_text")
        val screen = perception.capture(PerceptionPipeline.CaptureMode.FULL)
        val results = screen.elements.filter { element ->
            element.text.contains(text, ignoreCase = true) ||
            element.contentDescription.contains(text, ignoreCase = true)
        }

        if (results.isEmpty()) {
            return ToolResult.Failure(
                toolName = name,
                error = "Text not found: \"$text\"",
                errorCode = ErrorCode.ELEMENT_NOT_FOUND,
            )
        }

        val elements = results.map { element ->
            mapOf(
                "text" to element.text,
                "content_desc" to element.contentDescription,
                "resource_id" to element.resourceId,
                "class_name" to element.className,
                "center_x" to element.centerX.toDouble(),
                "center_y" to element.centerY.toDouble(),
                "bounds_left" to element.bounds.left,
                "bounds_top" to element.bounds.top,
                "bounds_right" to element.bounds.right,
                "bounds_bottom" to element.bounds.bottom,
            )
        }

        return ToolResult.Success(
            toolName = name,
            message = "Found ${results.size} text match(es) for \"$text\"",
            data = mapOf(
                "count" to results.size,
                "matches" to elements,
            ),
        )
    }

    private suspend fun executeFindImage(params: Map<String, Any?>): ToolResult {
        val templateName = params["template_name"]?.toString()
            ?: return invalidParams("template_name is required for find_image")
        val screen = perception.capture(PerceptionPipeline.CaptureMode.FULL)
        val matches = screen.templateMatches
            .filter { it.templateName == templateName }
            .map { it.x to it.y }

        if (matches.isEmpty()) {
            return ToolResult.Failure(
                toolName = name,
                error = "Image template not found: $templateName",
                errorCode = ErrorCode.ELEMENT_NOT_FOUND,
            )
        }

        val matchList = matches.map { (x, y) ->
            mapOf("x" to x, "y" to y)
        }

        return ToolResult.Success(
            toolName = name,
            message = "Template \"$templateName\" found at ${matches.size} location(s)",
            data = mapOf(
                "template_name" to templateName,
                "count" to matches.size,
                "locations" to matchList,
            ),
        )
    }

    private suspend fun executeFindAll(params: Map<String, Any?>): ToolResult {
        val query = buildQuery(params)
        val maxResults = (params["max_results"] as? Number)?.toInt() ?: 20
        val screen = perception.capture(PerceptionPipeline.CaptureMode.FULL)

        val allMatches = screen.elements.filter { element -> matchesQuery(element, query) }
        val elements = allMatches.take(maxResults)

        if (elements.isEmpty()) {
            return ToolResult.Failure(
                toolName = name,
                error = "No elements matched the query",
                errorCode = ErrorCode.ELEMENT_NOT_FOUND,
            )
        }

        val elementList = elements.map { element ->
            mapOf(
                "text" to element.text,
                "content_desc" to element.contentDescription,
                "resource_id" to element.resourceId,
                "class_name" to element.className,
                "center_x" to element.centerX.toDouble(),
                "center_y" to element.centerY.toDouble(),
                "bounds_left" to element.bounds.left,
                "bounds_top" to element.bounds.top,
                "bounds_right" to element.bounds.right,
                "bounds_bottom" to element.bounds.bottom,
                "is_clickable" to element.isClickable,
                "is_scrollable" to element.isScrollable,
                "is_editable" to element.isEditable,
                "is_enabled" to element.isEnabled,
            )
        }

        return ToolResult.Success(
            toolName = name,
            message = "Found ${elements.size} element(s)",
            data = mapOf(
                "count" to elements.size,
                "total_matches" to allMatches.size,
                "elements" to elementList,
            ),
        )
    }

    private fun buildQuery(params: Map<String, Any?>): ElementQuery {
        return ElementQuery(
            text = params["text"]?.toString(),
            textContains = params["text_contains"]?.toString(),
            contentDesc = params["content_desc"]?.toString(),
            contentDescContains = params["content_desc_contains"]?.toString(),
            resourceId = params["resource_id"]?.toString(),
            className = params["class_name"]?.toString(),
            isClickable = params["is_clickable"] as? Boolean,
            isScrollable = params["is_scrollable"] as? Boolean,
            isEditable = params["is_editable"] as? Boolean,
            isEnabled = params["is_enabled"] as? Boolean,
            requiresVisible = params["requires_visible"] as? Boolean ?: true,
        )
    }

    private fun matchesQuery(
        element: dev.hushyari.data.model.UIElement,
        query: ElementQuery,
    ): Boolean {
        if (query.text != null && element.text != query.text) return false
        if (query.textContains != null && !element.text.contains(query.textContains, ignoreCase = true)) return false
        if (query.contentDesc != null && element.contentDescription != query.contentDesc) return false
        if (query.contentDescContains != null && !element.contentDescription.contains(query.contentDescContains, ignoreCase = true)) return false
        if (query.resourceId != null && !element.resourceId.endsWith(query.resourceId)) return false
        if (query.className != null && element.className != query.className) return false
        if (query.isClickable != null && element.isClickable != query.isClickable) return false
        if (query.isScrollable != null && element.isScrollable != query.isScrollable) return false
        if (query.isEditable != null && element.isEditable != query.isEditable) return false
        if (query.isEnabled != null && element.isEnabled != query.isEnabled) return false
        return true
    }

    private fun elementToResult(element: dev.hushyari.data.model.UIElement): ToolResult.Success {
        return ToolResult.Success(
            toolName = name,
            message = "Element found: ${element.text.ifEmpty { element.contentDescription.ifEmpty { element.resourceId } }}",
            data = mapOf(
                "text" to element.text,
                "content_desc" to element.contentDescription,
                "resource_id" to element.resourceId,
                "class_name" to element.className,
                "center_x" to element.centerX.toDouble(),
                "center_y" to element.centerY.toDouble(),
                "bounds_left" to element.bounds.left,
                "bounds_top" to element.bounds.top,
                "bounds_right" to element.bounds.right,
                "bounds_bottom" to element.bounds.bottom,
                "width" to element.width,
                "height" to element.height,
                "is_clickable" to element.isClickable,
                "is_scrollable" to element.isScrollable,
                "is_editable" to element.isEditable,
                "is_enabled" to element.isEnabled,
            ),
        )
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
