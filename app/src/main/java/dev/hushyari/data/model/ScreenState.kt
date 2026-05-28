package dev.hushyari.data.model

/**
 * Complete state of the screen at a point in time.
 * 🧠 4x-game-agent mechanic: Multi-source perception combining UI tree + visual.
 */
data class ScreenState(
    val packageName: String = "",
    val activityName: String = "",
    val windowTitle: String = "",
    val elements: List<UIElement> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val screenshot: ByteArray? = null,
    val ocrText: String = "",
    val ocrBlocks: List<OcrBlock> = emptyList(),
    val templateMatches: List<TemplateMatch> = emptyList(),
    val classifiedScreen: String? = null,
    val isGameScreen: Boolean = false,
    val hasPopup: Boolean = false,
) {
    val elementCount: Int get() = elements.size
    val clickableElements: List<UIElement> get() = elements.filter { it.isClickable }
    val textElements: List<UIElement> get() = elements.filter { it.hasText }
    val inputElements: List<UIElement> get() = elements.filter { it.isEditable }

    fun findElement(query: ElementQuery): UIElement? =
        elements.firstOrNull { it.matches(query) }

    fun findElements(query: ElementQuery): List<UIElement> =
        elements.filter { it.matches(query) }

    /**
     * 🧠 AccessibilityReader pattern: Return a text summary for LLM prompts.
     */
    fun toTextSummary(maxElements: Int = 30): String = buildString {
        appendLine("Package: $packageName")
        appendLine("Activity: $activityName")
        appendLine("Title: $windowTitle")
        appendLine("Size: ${screenWidth}x${screenHeight}")
        appendLine("Elements: ${elements.size} total, ${clickableElements.size} clickable")
        if (ocrText.isNotEmpty()) {
            appendLine("OCR Text: $ocrText")
        }
        appendLine("---")
        elements.take(maxElements).forEachIndexed { i, e ->
            val label = when {
                e.text.isNotEmpty() -> e.text
                e.contentDescription.isNotEmpty() -> e.contentDescription
                e.resourceId.isNotEmpty() -> e.resourceId.substringAfterLast('/')
                else -> e.className.substringAfterLast('.')
            }
            val clickable = if (e.isClickable) "[C]" else ""
            val scrollable = if (e.isScrollable) "[S]" else ""
            val editable = if (e.isEditable) "[E]" else ""
            val flags = listOfNotNull(
                clickable.ifEmpty { null },
                scrollable.ifEmpty { null },
                editable.ifEmpty { null }
            ).joinToString("")
            appendLine("  [$i] $label ${if (flags.isNotEmpty()) "$flags " else ""}@ (${e.centerX.toInt()}, ${e.centerY.toInt()})")
        }
        if (elements.size > maxElements) {
            appendLine("  ... ${elements.size - maxElements} more elements")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenState) return false
        if (packageName != other.packageName) return false
        if (activityName != other.activityName) return false
        if (windowTitle != other.windowTitle) return false
        if (elements.size != other.elements.size) return false
        return true
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + activityName.hashCode()
        result = 31 * result + elements.size
        return result
    }
}

data class OcrBlock(
    val text: String,
    val bounds: android.graphics.Rect,
    val confidence: Float,
)

data class TemplateMatch(
    val templateName: String,
    val x: Int,
    val y: Int,
    val confidence: Float,
)
