package dev.hushyari.statemachine

import dev.hushyari.data.model.ElementQuery
import dev.hushyari.data.model.ScreenState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiered screen classifier that identifies which game screen we are currently on.
 *
 * Strategy hierarchy (fastest first):
 * 1. Pixel check — pre-sampled pixel colors at known positions from game config
 * 2. UI element check — unique element IDs or text from game config
 * 3. OCR text check — screen title or distinctive text
 * 4. LLM classification — last resort, slowest
 *
 * Results are cached for 500ms to avoid redundant processing.
 *
 * 🧠 4x-game-agent mechanic: Layer 2 — Deterministic screen identification before other layers.
 */
@Singleton
class ScreenClassifier @Inject constructor() {

    private data class CachedClassification(
        val packageName: String,
        val result: ClassificationResult,
        val timestamp: Long,
    )

    private var cached: CachedClassification? = null
    private val cacheDurationMs = 500L

    /**
     * Classify which screen we are on using the fastest available strategy.
     *
     * @param screenState Current screen state from perception pipeline.
     * @param gameConfig Game-specific configuration used for classification rules.
     * @return ClassificationResult with screen name, confidence, and metadata.
     */
    fun classify(screenState: ScreenState, gameConfig: GameConfig): ClassificationResult {
        synchronized(this) {
            cached?.let { cache ->
                if (cache.packageName == screenState.packageName &&
                    System.currentTimeMillis() - cache.timestamp < cacheDurationMs
                ) {
                    return cache.result
                }
            }
        }

        val result = classifyInternal(screenState, gameConfig)
        cached = CachedClassification(screenState.packageName, result, System.currentTimeMillis())
        return result
    }

    private fun classifyInternal(screenState: ScreenState, gameConfig: GameConfig): ClassificationResult {
        if (screenState.elements.isEmpty()) {
            return ClassificationResult(screenName = "unknown", confidence = 0.0f)
        }

        for (screenConfig in gameConfig.screens) {
            val config = screenConfig.classification

            val pixelConfidence = pixelCheck(screenState, config.pixelChecks)
            if (pixelConfidence >= 0.9f) {
                return ClassificationResult(
                    screenName = screenConfig.name,
                    confidence = pixelConfidence,
                    strategy = "pixel_check",
                    isPopup = screenConfig.isPopup,
                    isTransition = screenConfig.isTransition,
                )
            }

            val elementConfidence = elementCheck(screenState, config.elementChecks)
            if (elementConfidence >= 0.85f) {
                return ClassificationResult(
                    screenName = screenConfig.name,
                    confidence = elementConfidence,
                    strategy = "element_check",
                    isPopup = screenConfig.isPopup,
                    isTransition = screenConfig.isTransition,
                )
            }

            val textConfidence = textCheck(screenState, config.textChecks)
            if (textConfidence >= 0.8f) {
                return ClassificationResult(
                    screenName = screenConfig.name,
                    confidence = textConfidence,
                    strategy = "text_check",
                    isPopup = screenConfig.isPopup,
                    isTransition = screenConfig.isTransition,
                )
            }
        }

        val bestMatch = gameConfig.screens.maxByOrNull { screenConfig ->
            val config = screenConfig.classification
            val totalWeight = (config.pixelChecks.sumOf { it.weight.toDouble() } +
                    config.elementChecks.sumOf { it.weight.toDouble() } +
                    config.textChecks.sumOf { it.weight.toDouble() }).toFloat()

            if (totalWeight <= 0f) return@maxByOrNull -1f

            val pixelConf = pixelCheck(screenState, config.pixelChecks)
            val elementConf = elementCheck(screenState, config.elementChecks)
            val textConf = textCheck(screenState, config.textChecks)
            val avgConf = (pixelConf + elementConf + textConf) / 3f
            avgConf
        }

        return if (bestMatch != null) {
            val config = bestMatch.classification
            val avg = (pixelCheck(screenState, config.pixelChecks) +
                    elementCheck(screenState, config.elementChecks) +
                    textCheck(screenState, config.textChecks)) / 3f
            ClassificationResult(
                screenName = bestMatch.name,
                confidence = avg.coerceIn(0f, 1f),
                strategy = "aggregate",
                isPopup = bestMatch.isPopup,
                isTransition = bestMatch.isTransition,
            )
        } else {
            ClassificationResult(
                screenName = "unknown",
                confidence = 0.15f,
                strategy = "none",
                suggestions = emptyList(),
            )
        }
    }

    /**
     * 🧠 Fastest strategy: Check pre-sampled pixel colors at known positions.
     * Returns confidence 0.0-1.0 based on weighted match ratio.
     */
    private fun pixelCheck(
        screenState: ScreenState,
        checks: List<PixelCheckConfig>,
    ): Float {
        if (checks.isEmpty()) return 0f
        val screenshot = screenState.screenshot ?: return 0f

        var matchedWeight = 0f
        var totalWeight = 0f

        for (check in checks) {
            totalWeight += check.weight
            val pixelIndex = (check.y * screenState.screenWidth + check.x) * 4
            if (pixelIndex >= 0 && pixelIndex + 2 < screenshot.size) {
                val r = screenshot[pixelIndex].toInt() and 0xFF
                val g = screenshot[pixelIndex + 1].toInt() and 0xFF
                val b = screenshot[pixelIndex + 2].toInt() and 0xFF
                val pixelColor = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                if (colorWithinTolerance(pixelColor, check.expectedColor, check.tolerance)) {
                    matchedWeight += check.weight
                }
            }
        }

        return if (totalWeight > 0f) matchedWeight / totalWeight else 0f
    }

    /**
     * 🧠 Mid-speed strategy: Look for unique UI elements from the game config.
     * Returns confidence based on element presence/absence matching.
     */
    private fun elementCheck(
        screenState: ScreenState,
        checks: List<ElementCheckConfig>,
    ): Float {
        if (checks.isEmpty()) return 0f

        var matchedWeight = 0f
        var totalWeight = 0f

        for (check in checks) {
            totalWeight += check.weight
            val query = ElementQuery(
                resourceId = check.resourceId,
                text = check.text,
                textContains = check.textContains,
                contentDesc = check.contentDesc,
                className = check.className,
            )
            val found = screenState.findElement(query) != null
            if (found == check.mustExist) {
                matchedWeight += check.weight
            }
        }

        return if (totalWeight > 0f) matchedWeight / totalWeight else 0f
    }

    /**
     * 🧠 Slower strategy: Match OCR text on screen to known screen titles or distinctive text.
     * Returns confidence based on text presence.
     */
    private fun textCheck(
        screenState: ScreenState,
        checks: List<TextCheckConfig>,
    ): Float {
        if (checks.isEmpty()) return 0f
        val ocrText = screenState.ocrText.lowercase()
        val allTexts = buildString {
            append(ocrText)
            append(' ')
            screenState.textElements.forEach { el ->
                append(el.text.lowercase())
                append(' ')
                append(el.contentDescription.lowercase())
                append(' ')
            }
        }

        var matchedWeight = 0f
        var totalWeight = 0f

        for (check in checks) {
            totalWeight += check.weight
            val searchText = check.text.lowercase()
            val found = if (check.exact) {
                allTexts.contains(searchText)
            } else {
                allTexts.contains(searchText)
            }
            if (found) {
                matchedWeight += check.weight
            }
        }

        return if (totalWeight > 0f) matchedWeight / totalWeight else 0f
    }

    /**
     * 🧠 Slowest strategy (not executed inline): Delegate to LLM for complex classification.
     * The caller should invoke this separately when other strategies fail.
     */
    suspend fun llmClassify(
        screenState: ScreenState,
        gameConfig: GameConfig,
        llmClient: LlmScreenClassifier,
    ): ClassificationResult {
        val screenNames = gameConfig.screens.map { it.name }
        return llmClient.classifyScreen(screenState, screenNames)
    }

    private fun colorWithinTolerance(actual: Int, expected: Int, tolerance: Int): Boolean {
        val ar = (actual shr 16) and 0xFF
        val ag = (actual shr 8) and 0xFF
        val ab = actual and 0xFF
        val er = (expected shr 16) and 0xFF
        val eg = (expected shr 8) and 0xFF
        val eb = expected and 0xFF
        return kotlin.math.abs(ar - er) <= tolerance &&
                kotlin.math.abs(ag - eg) <= tolerance &&
                kotlin.math.abs(ab - eb) <= tolerance
    }

    fun invalidateCache() {
        synchronized(this) {
            cached = null
        }
    }
}

/**
 * Result of screen classification with metadata for downstream consumers.
 *
 * 🧠 4x-game-agent mechanic: Layer 2 output passed to GameFSM and SkillEngine.
 */
data class ClassificationResult(
    val screenName: String,
    val confidence: Float,
    val strategy: String = "none",
    val isPopup: Boolean = false,
    val isTransition: Boolean = false,
    val suggestions: List<String> = emptyList(),
) {
    val isKnown: Boolean get() = screenName != "unknown"
    val isHighConfidence: Boolean get() = confidence >= 0.8f
}

/**
 * Interface for LLM-based screen classification.
 * Implementations should use CloudLlmClient or LocalLlmClient.
 *
 * 🧠 4x-game-agent mechanic: Fallback classification when deterministic methods fail.
 */
interface LlmScreenClassifier {
    suspend fun classifyScreen(screenState: ScreenState, knownScreens: List<String>): ClassificationResult
}
