package dev.hushyari.perception

import android.graphics.Bitmap
import android.graphics.Rect
import dev.hushyari.data.model.ElementQuery
import dev.hushyari.service.AccessibilityTreeParser
import dev.hushyari.service.HushyariAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-strategy element finding engine that resolves UI target descriptions
 * into screen coordinates with confidence scoring.
 *
 * Strategies are applied in order of speed until one matches above the threshold:
 * 1. Accessibility tree match (~1ms)
 * 2. OCR text location (~20ms)
 * 3. Template matching (~30ms)
 * 4. LLM-based visual finding (~1-3s, fallback only)
 *
 * **Mechanics:**
 * - ClickClickClick: Finder role — multi-strategy element resolution for
 *   reliable tool targeting even when the accessibility tree is sparse.
 * - PokeClaw: Strategy chaining with confidence gating — each strategy adds
 *   its own confidence score; the best match above threshold wins.
 * - Roubao: Structured [FindParams] supporting text, content desc, resource ID,
 *   template name, and coordinate hints for each strategy.
 */
@Singleton
class ElementFinder @Inject constructor(
    private val treeParser: AccessibilityTreeParser,
    private val ocrEngine: OcrEngine,
    private val templateMatcher: TemplateMatcher,
) {

    companion object {
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.5f
        private const val TREE_CONFIDENCE = 0.95f
        private const val OCR_CONFIDENCE_BOOST = 0.15f
        private const val TEMPLATE_CONFIDENCE = 0.85f
    }

    /**
     * Parameters for element finding across all strategies.
     */
    data class FindParams(
        val text: String? = null,
        val textContains: String? = null,
        val contentDesc: String? = null,
        val contentDescContains: String? = null,
        val resourceId: String? = null,
        val className: String? = null,
        val templateName: String? = null,
        val coordinateHint: Pair<Float, Float>? = null,
        val isClickable: Boolean? = null,
        val isScrollable: Boolean? = null,
        val isEditable: Boolean? = null,
        val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
        /** Optional screenshot bitmap for OCR and template strategies. */
        val screenshot: Bitmap? = null,
    )

    /**
     * Result from any finding strategy.
     */
    data class FoundElement(
        val x: Int,
        val y: Int,
        val width: Int = 0,
        val height: Int = 0,
        val confidence: Float,
        val strategy: Strategy,
        val text: String? = null,
        val contentDescription: String? = null,
        val resourceId: String? = null,
        val className: String? = null,
        val bounds: Rect? = null,
        val isClickable: Boolean = false,
        val isScrollable: Boolean = false,
        val isEditable: Boolean = false,
    ) {
        val centerX: Int get() = x + width / 2
        val centerY: Int get() = y + height / 2
    }

    enum class Strategy {
        ACCESSIBILITY_TREE,
        OCR,
        TEMPLATE,
        LLM,
    }

    // ------------------------------------------------------------------
    // Multi-strategy find
    // ------------------------------------------------------------------

    /**
     * Finds an element matching [params] using all available strategies.
     *
     * Returns the highest-confidence match above [FindParams.confidenceThreshold],
     * applying strategies in order and short-circuiting when a high-confidence
     * match is found.
     *
     * **ClickClickClick Finder mechanic:** The core entry point for element
     * resolution — called before any tap, swipe, or text input tool to resolve
     * target descriptions into actionable screen coordinates.
     */
    suspend fun find(params: FindParams): FoundElement? = coroutineScope {
        val candidates = mutableListOf<FoundElement>()

        // Strategy 1: Accessibility tree — fastest
        val treeResult = async { tryAccessibilityTree(params) }
        treeResult.await()?.let {
            if (it.confidence >= 0.9f) return@coroutineScope it
            candidates.add(it)
        }

        // Strategy 2: OCR text location
        if (params.text != null || params.textContains != null) {
            val ocrBitmap = params.screenshot
            val ocrResult = if (ocrBitmap != null && !ocrBitmap.isRecycled) {
                async { tryOcrOnBitmap(ocrBitmap, params) }
            } else {
                async { tryOcr(params) }
            }
            ocrResult.await()?.let {
                if (it.confidence >= 0.9f) return@coroutineScope it
                candidates.add(it)
            }
        }

        // Strategy 3: Template matching
        if (params.templateName != null) {
            val templateBitmap = params.screenshot
            val templateResult = if (templateBitmap != null && !templateBitmap.isRecycled) {
                async { tryTemplateOnBitmap(templateBitmap, params) }
            } else {
                async { tryTemplate(params) }
            }
            templateResult.await()?.let {
                if (it.confidence >= 0.9f) return@coroutineScope it
                candidates.add(it)
            }
        }

        // Strategy 4: LLM-based visual finding (fallback)
        if (candidates.isEmpty()) {
            val llmResult = async { tryLlm(params) }
            llmResult.await()?.let { candidates.add(it) }
        }

        val best = candidates
            .filter { it.confidence >= params.confidenceThreshold }
            .maxByOrNull { it.confidence }

        best?.let {
            Timber.d(
                "ElementFinder: found '%s' via %s (confidence=%.2f)",
                params.text ?: params.contentDesc ?: params.resourceId ?: "?",
                it.strategy.name,
                it.confidence,
            )
        }
        best
    }

    /**
     * Convenience: find and return coordinates directly.
     */
    suspend fun findCoordinates(params: FindParams): Pair<Int, Int>? {
        val found = find(params) ?: return null
        return found.centerX to found.centerY
    }

    /**
     * Convenience: find and return center coordinates, or fall back to
     * the [coordinateHint] if no element was found.
     */
    suspend fun findOrHint(
        params: FindParams,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val found = find(params)
        if (found != null) return found.centerX to found.centerY

        val hint = params.coordinateHint
        if (hint != null) {
            return (hint.first * screenWidth).toInt() to (hint.second * screenHeight).toInt()
        }

        return screenWidth / 2 to screenHeight / 2
    }

    /**
     * Public entry point for finding an element on a specific bitmap screenshot,
     * used when the caller already has a screenshot from ScreenCapture.
     *
     * **ClickClickClick Finder mechanic:** Called from [PerceptionPipeline] during
     * FULL capture mode when a screenshot is available.
     */
    suspend fun findOnBitmap(params: FindParams, bitmap: Bitmap): FoundElement? {
        return find(params.copy(screenshot = bitmap))
    }

    // ------------------------------------------------------------------
    // Strategy 1: Accessibility tree
    // ------------------------------------------------------------------

    private fun tryAccessibilityTree(params: FindParams): FoundElement? {
        val svc = HushyariAccessibilityService.instance ?: return null
        val root = svc.rootInActiveWindow ?: return null

        try {
            val query = toElementQuery(params)
            val matches = treeParser.findElements(root, query)

            if (matches.isEmpty()) return null

            val best = matches.first()
            return FoundElement(
                x = best.centerX.toInt(),
                y = best.centerY.toInt(),
                width = best.width,
                height = best.height,
                confidence = TREE_CONFIDENCE,
                strategy = Strategy.ACCESSIBILITY_TREE,
                text = best.text.ifEmpty { null },
                contentDescription = best.contentDescription.ifEmpty { null },
                resourceId = best.resourceId.ifEmpty { null },
                className = best.className,
                bounds = best.bounds,
                isClickable = best.isClickable,
                isScrollable = best.isScrollable,
                isEditable = best.isEditable,
            )
        } catch (e: Exception) {
            Timber.w(e, "ElementFinder: accessibility tree strategy failed")
            return null
        } finally {
            root.recycle()
        }
    }

    // ------------------------------------------------------------------
    // Strategy 2: OCR text location
    // ------------------------------------------------------------------

    /**
     * OCR strategy without a screenshot — attempts to extract text from the
     * accessibility tree to do a basic text search instead.
     */
    private suspend fun tryOcr(params: FindParams): FoundElement? {
        val svc = HushyariAccessibilityService.instance ?: return null
        val root = svc.rootInActiveWindow ?: return null

        try {
            val query = toElementQuery(params)
            val matches = treeParser.findElements(root, query)

            // Filter by text content in the accessibility tree
            val textMatch = matches.firstOrNull { element ->
                val searchText = params.text ?: params.textContains ?: return@firstOrNull false
                if (params.text != null) {
                    element.text.equals(searchText, ignoreCase = true) ||
                            element.contentDescription.equals(searchText, ignoreCase = true)
                } else {
                    element.text.contains(searchText, ignoreCase = true) ||
                            element.contentDescription.contains(searchText, ignoreCase = true)
                }
            }

            if (textMatch != null) {
                return FoundElement(
                    x = textMatch.centerX.toInt(),
                    y = textMatch.centerY.toInt(),
                    width = textMatch.width,
                    height = textMatch.height,
                    confidence = 0.6f + OCR_CONFIDENCE_BOOST,
                    strategy = Strategy.OCR,
                    text = textMatch.text.ifEmpty { null },
                    contentDescription = textMatch.contentDescription.ifEmpty { null },
                    resourceId = textMatch.resourceId.ifEmpty { null },
                    className = textMatch.className,
                    bounds = textMatch.bounds,
                    isClickable = textMatch.isClickable,
                    isScrollable = textMatch.isScrollable,
                    isEditable = textMatch.isEditable,
                )
            }

            return null
        } catch (e: Exception) {
            Timber.w(e, "ElementFinder: OCR strategy failed")
            return null
        } finally {
            root.recycle()
        }
    }

    private suspend fun tryOcrOnBitmap(
        bitmap: Bitmap,
        params: FindParams,
    ): FoundElement? {
        val query = params.text ?: params.textContains ?: return null
        val blocks = ocrEngine.recognizeText(bitmap)
        val matches = blocks.filter { block ->
            if (params.text != null) {
                block.text.equals(query, ignoreCase = true)
            } else {
                block.text.contains(query, ignoreCase = true)
            }
        }

        if (matches.isEmpty()) return null

        val best = matches.maxByOrNull { it.confidence } ?: return null
        val baseConfidence = 0.5f + best.confidence * OCR_CONFIDENCE_BOOST

        return FoundElement(
            x = best.bounds.centerX(),
            y = best.bounds.centerY(),
            width = best.bounds.width(),
            height = best.bounds.height(),
            confidence = baseConfidence.coerceAtMost(1f),
            strategy = Strategy.OCR,
            text = best.text,
            bounds = best.bounds,
        )
    }

    // ------------------------------------------------------------------
    // Strategy 3: Template matching
    // ------------------------------------------------------------------

    private suspend fun tryTemplate(params: FindParams): FoundElement? {
        // Without a screenshot, template matching cannot run; return null
        val templateName = params.templateName ?: return null
        Timber.d("ElementFinder: template '%s' skipped — no screenshot available", templateName)
        return null
    }

    private suspend fun tryTemplateOnBitmap(
        bitmap: Bitmap,
        params: FindParams,
    ): FoundElement? {
        val templateName = params.templateName ?: return null
        val matches = templateMatcher.matchActive(bitmap)
        val relevant = matches.filter { it.templateName == templateName }

        if (relevant.isEmpty()) return null

        val best = relevant.maxByOrNull { it.confidence } ?: return null

        return FoundElement(
            x = best.centerX,
            y = best.centerY,
            width = best.width,
            height = best.height,
            confidence = (best.confidence * 0.7f + TEMPLATE_CONFIDENCE * 0.3f).coerceAtMost(1f),
            strategy = Strategy.TEMPLATE,
            bounds = best.bounds,
        )
    }

    // ------------------------------------------------------------------
    // Strategy 4: LLM visual finding
    // ------------------------------------------------------------------

    private suspend fun tryLlm(params: FindParams): FoundElement? {
        Timber.d(
            "ElementFinder: LLM strategy invoked (fallback) for '%s'",
            params.text ?: params.resourceId ?: params.templateName ?: "?",
        )
        // LLM-based visual finding requires external LLM integration.
        // Return null here; concrete implementation wires up at integration layer.
        return withContext(Dispatchers.Default) { null }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun toElementQuery(params: FindParams): ElementQuery = ElementQuery(
        text = params.text,
        textContains = params.textContains,
        contentDesc = params.contentDesc,
        contentDescContains = params.contentDescContains,
        resourceId = params.resourceId,
        className = params.className,
        isClickable = params.isClickable,
        isScrollable = params.isScrollable,
        isEditable = params.isEditable,
    )
}
