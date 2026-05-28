package dev.hushyari.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.hushyari.data.model.ElementQuery
import dev.hushyari.data.model.UIElement
import timber.log.Timber
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility for inspecting and querying the accessibility node tree.
 *
 * Provides static-style helper methods that wrap safe node traversal with recycling
 * guards and Unicode normalization fallback paths.
 *
 * **Mechanics:**
 * - PokeClaw: Unicode-normalized tree walk — when direct text lookup misses,
 *   re-walk the tree with NFKD normalization to catch composed/decomposed glyph
 *   mismatches (common in CJK and emoji-heavy game UIs).
 * - Roubao: Structured [UIElement] conversion for tool target resolution.
 */
@Singleton
class AccessibilityTreeParser @Inject constructor() {

    companion object {
        private const val MAX_DEPTH = 50
        private const val MAX_TEXT_SUMMARY_ELEMENTS = 50
    }

    // ------------------------------------------------------------------
    // Text summary
    // ------------------------------------------------------------------

    /**
     * Builds a human-readable text summary from the accessibility tree rooted at [rootNode].
     * Respects [maxElements] to keep LLM prompts under token limits.
     *
     * **AccessibilityReader pattern:** Text summaries feed Layer 1 LLM prompts for quick
     * agent decisions without screenshot analysis.
     */
    fun getTextSummary(rootNode: AccessibilityNodeInfo, maxElements: Int = MAX_TEXT_SUMMARY_ELEMENTS): String {
        val elements = flattenTree(rootNode, maxElements)
        return buildString {
            elements.forEachIndexed { i, el ->
                val label = when {
                    el.text.isNotEmpty() -> el.text
                    el.contentDescription.isNotEmpty() -> el.contentDescription
                    el.resourceId.isNotEmpty() -> el.resourceId.substringAfterLast('/')
                    else -> el.className.substringAfterLast('.')
                }
                appendLine("[$i] $label @ (${el.centerX.toInt()}, ${el.centerY.toInt()})")
            }
        }
    }

    // ------------------------------------------------------------------
    // Element queries
    // ------------------------------------------------------------------

    /**
     * Finds all [UIElement] entries matching [query] in the tree rooted at [rootNode].
     *
     * **PokeClaw mechanic:** Falls back to Unicode-normalized tree walk when direct
     * filtering yields zero results, catching CJK/emoji normalization mismatches.
     */
    fun findElements(rootNode: AccessibilityNodeInfo, query: ElementQuery): List<UIElement> {
        val elements = flattenTree(rootNode, Int.MAX_VALUE)
        val direct = elements.filter { it.matches(query) }
        if (direct.isNotEmpty() || !query.hasTextCriteria()) return direct

        return unicodeNormalizedFallback(rootNode, query)
    }

    /**
     * Returns the first [UIElement] whose bounds contain absolute coordinates (x, y).
     * Traverses deepest-first so the most specific (leaf) node wins.
     *
     * **ClickClickClick mechanic (Finder role):** Used by [ElementFinder] strategy 1
     * to resolve coordinates from accessibility tree when text/ID matching fails.
     */
    fun getElementAt(rootNode: AccessibilityNodeInfo, x: Int, y: Int): UIElement? {
        return findByCoordinates(rootNode, x.toFloat(), y.toFloat())
    }

    /**
     * Relative coordinate variant — finds the element at fractional screen position.
     */
    fun getElementAtFractional(
        rootNode: AccessibilityNodeInfo,
        xFraction: Float,
        yFraction: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): UIElement? {
        val x = (xFraction * screenWidth).toInt()
        val y = (yFraction * screenHeight).toInt()
        return findByCoordinates(rootNode, x.toFloat(), y.toFloat())
    }

    // ------------------------------------------------------------------
    // Tree flattening
    // ------------------------------------------------------------------

    /**
     * Recursively flattens the accessibility tree into a list of [UIElement].
     * Each child node is [recycle]d after extraction to prevent memory pressure.
     */
    fun flattenTree(rootNode: AccessibilityNodeInfo, maxElements: Int): List<UIElement> {
        val elements = mutableListOf<UIElement>()
        flattenRecursive(rootNode, 0, maxElements, elements, 0)
        return elements
    }

    private fun flattenRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxElements: Int,
        elements: MutableList<UIElement>,
        drawingOrder: Int,
    ): Int {
        if (depth > MAX_DEPTH || elements.size >= maxElements) return drawingOrder
        var order = drawingOrder

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        elements.add(
            UIElement(
                className = node.className?.toString() ?: "android.view.View",
                text = node.text?.toString() ?: "",
                contentDescription = node.contentDescription?.toString() ?: "",
                resourceId = node.viewIdResourceName ?: "",
                bounds = bounds,
                isClickable = node.isClickable,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable,
                isChecked = node.isChecked,
                isFocused = node.isFocused,
                isEnabled = node.isEnabled,
                depth = depth,
                childCount = node.childCount,
                drawingOrder = order,
                isVisible = node.isVisibleToUser,
            )
        )
        order++

        for (i in 0 until node.childCount) {
            if (elements.size >= maxElements) break
            val child = node.getChild(i) ?: continue
            try {
                order = flattenRecursive(child, depth + 1, maxElements, elements, order)
            } finally {
                child.recycle()
            }
        }

        return order
    }

    // ------------------------------------------------------------------
    // Coordinate-based lookup
    // ------------------------------------------------------------------

    private fun findByCoordinates(
        node: AccessibilityNodeInfo,
        x: Float,
        y: Float,
    ): UIElement? {
        var bestElement: UIElement? = null
        var bestDepth = -1

        findByCoordsRecursive(node, x, y, 0) { element, depth ->
            if (depth > bestDepth) {
                bestDepth = depth
                bestElement = element
            }
        }

        return bestElement
    }

    private fun findByCoordsRecursive(
        node: AccessibilityNodeInfo,
        x: Float,
        y: Float,
        depth: Int,
        onMatch: (UIElement, Int) -> Unit,
    ): Unit {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.contains(x.toInt(), y.toInt())) {
            val element = UIElement(
                className = node.className?.toString() ?: "",
                text = node.text?.toString() ?: "",
                contentDescription = node.contentDescription?.toString() ?: "",
                resourceId = node.viewIdResourceName ?: "",
                bounds = bounds,
                isClickable = node.isClickable,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable,
                isChecked = node.isChecked,
                isFocused = node.isFocused,
                isEnabled = node.isEnabled,
                depth = depth,
                childCount = node.childCount,
                drawingOrder = 0,
                isVisible = node.isVisibleToUser,
            )
            onMatch(element, depth)
        }

        if (depth > MAX_DEPTH) return

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                findByCoordsRecursive(child, x, y, depth + 1, onMatch)
            } finally {
                child.recycle()
            }
        }
    }

    // ------------------------------------------------------------------
    // Unicode normalization fallback (PokeClaw mechanic)
    // ------------------------------------------------------------------

    /**
     * **PokeClaw mechanic:** Re-walks the accessibility tree using Unicode NFKD
     * normalization to catch composed/decomposed glyph mismatches.
     *
     * Common in games with CJK text, special symbols, or emoji characters where
     * the rendered glyph differs from the composed form stored in content descriptions.
     */
    private fun unicodeNormalizedFallback(
        rootNode: AccessibilityNodeInfo,
        query: ElementQuery,
    ): List<UIElement> {
        Timber.d("PokeClaw: Unicode-normalized tree walk for query text=%s", query.text ?: query.textContains)
        val results = mutableListOf<UIElement>()
        unicodeWalkRecursive(rootNode, 0, null, results, 0)

        val normalizedQuery = ElementQuery(
            text = query.text?.let { safeNormalize(it) },
            textContains = query.textContains?.let { safeNormalize(it) },
            contentDesc = query.contentDesc?.let { safeNormalize(it) },
            contentDescContains = query.contentDescContains?.let { safeNormalize(it) },
            resourceId = query.resourceId,
            className = query.className,
            isClickable = query.isClickable,
            isScrollable = query.isScrollable,
            isEditable = query.isEditable,
            isEnabled = query.isEnabled,
            depthLimit = query.depthLimit,
            requiresVisible = query.requiresVisible,
        )

        return results.filter { it.matches(normalizedQuery) }
    }

    private fun unicodeWalkRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        buffer: MutableList<String>?,
        results: MutableList<UIElement>,
        drawingOrder: Int,
    ): Int {
        if (depth > MAX_DEPTH) return drawingOrder
        var order = drawingOrder

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val element = UIElement(
            className = node.className?.toString() ?: "",
            text = safeNormalize(node.text?.toString() ?: ""),
            contentDescription = safeNormalize(node.contentDescription?.toString() ?: ""),
            resourceId = node.viewIdResourceName ?: "",
            bounds = bounds,
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isChecked = node.isChecked,
            isFocused = node.isFocused,
            isEnabled = node.isEnabled,
            depth = depth,
            childCount = node.childCount,
            drawingOrder = order,
            isVisible = node.isVisibleToUser,
        )
        results.add(element)
        order++

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                order = unicodeWalkRecursive(child, depth + 1, buffer, results, order)
            } finally {
                child.recycle()
            }
        }
        return order
    }

    private fun safeNormalize(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun ElementQuery.hasTextCriteria(): Boolean {
        return text != null || textContains != null || contentDesc != null || contentDescContains != null
    }

}
