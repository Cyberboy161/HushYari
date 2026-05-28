package dev.hushyari.perception

import android.graphics.Bitmap
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ultra-fast screen identification via pixel color sampling at pre-defined regions.
 *
 * Samples specific pixels at known screen positions and compares their colors
 * against per-game configuration to identify the current screen. Operates in
 * <10ms, making it suitable for real-time screen detection in the agent loop.
 *
 * **Mechanics:**
 * - 4x-game-agent Layer 1: Fast screen classification feeds the agent's world model
 *   updates without blocking the perception pipeline.
 * - PokeClaw: Config-driven pixel sampling — each game provides a JSON config mapping
 *   screen names to pixel check regions with expected RGB values.
 */
@Singleton
class PixelClassifier @Inject constructor() {

    companion object {
        private const val COLOR_TOLERANCE = 15
        private const val MIN_CONFIDENCE_FOR_MATCH = 0.7f
    }

    /**
     * Configuration for a pixel check region used to identify a screen.
     */
    data class PixelCheckRegion(
        val label: String,
        val x: Int,
        val y: Int,
        val expectedR: Int,
        val expectedG: Int,
        val expectedB: Int,
        val tolerance: Int = COLOR_TOLERANCE,
        val weight: Float = 1.0f,
    )

    /**
     * Configuration for a specific screen in a game.
     */
    data class ScreenConfig(
        val screenName: String,
        val checkRegions: List<PixelCheckRegion>,
        val minConfidence: Float = MIN_CONFIDENCE_FOR_MATCH,
    )

    /**
     * Full game configuration mapping screen names to their pixel check regions.
     */
    data class GameConfig(
        val gamePackage: String,
        val gameName: String,
        val screens: List<ScreenConfig>,
    )

    private var activeConfig: GameConfig? = null

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Loads a game configuration for pixel-based screen classification.
     */
    fun loadConfig(config: GameConfig) {
        activeConfig = config
        Timber.d("PixelClassifier: loaded config for ${config.gameName} with ${config.screens.size} screens")
    }

    /**
     * Clears the currently loaded configuration.
     */
    fun clearConfig() {
        activeConfig = null
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    /**
     * Classifies a [bitmap] screen capture against the currently loaded [gameConfig].
     *
     * Returns the best-matching screen name with confidence score, or null if no
     * screen passes the minimum confidence threshold.
     *
     * If [gameConfig] is null, returns null immediately (caller should load a config first).
     *
     * **Performance:** O(screens * checkRegions) with single-pass pixel reads.
     * Typical runtime <10ms for 10 screens with 5 check regions each.
     *
     * **4x-game-agent Layer 1 mechanic:** Used during FULL capture to quickly identify
     * the current game screen for world model updates and LLM context enrichment.
     */
    fun classifyScreen(bitmap: Bitmap, gameConfig: GameConfig?): ClassifyResult? {
        if (bitmap.isRecycled) return null

        val config = gameConfig ?: activeConfig
        if (config == null) {
            Timber.d("PixelClassifier: no config loaded")
            return null
        }

        if (config.screens.isEmpty()) return null

        // Pre-cache pixel colors to avoid redundant getPixel calls
        val pixelCache = mutableMapOf<Pair<Int, Int>, Int>()

        val results = config.screens.map { screen ->
            val (confidence, details) = evaluateScreen(bitmap, screen, pixelCache)
            ClassifyResult(
                screenName = screen.screenName,
                confidence = confidence,
                checkResults = details,
            )
        }

        val best = results
            .filter { it.confidence >= MIN_CONFIDENCE_FOR_MATCH }
            .maxByOrNull { it.confidence }

        if (best != null) {
            Timber.d(
                "PixelClassifier: classified as '%s' (confidence=%.2f)",
                best.screenName,
                best.confidence,
            )
        } else {
            Timber.d("PixelClassifier: no screen matched")
        }

        return best
    }

    /**
     * Convenience: returns just the screen name string or null.
     */
    fun classifyScreenName(bitmap: Bitmap, gameConfig: GameConfig? = null): String? {
        return classifyScreen(bitmap, gameConfig)?.screenName
    }

    /**
     * Convenience: returns screen name with confidence >= [minConfidence], or null.
     */
    fun classifyScreenWithMinConfidence(
        bitmap: Bitmap,
        minConfidence: Float = MIN_CONFIDENCE_FOR_MATCH,
        gameConfig: GameConfig? = null,
    ): String? {
        val result = classifyScreen(bitmap, gameConfig) ?: return null
        return if (result.confidence >= minConfidence) result.screenName else null
    }

    // ------------------------------------------------------------------
    // Result types
    // ------------------------------------------------------------------

    /**
     * Classification result with the best-matching screen and confidence.
     */
    data class ClassifyResult(
        val screenName: String,
        val confidence: Float,
        val checkResults: List<PixelCheckResult> = emptyList(),
    )

    data class PixelCheckResult(
        val label: String,
        val x: Int,
        val y: Int,
        val expectedR: Int,
        val expectedG: Int,
        val expectedB: Int,
        val actualR: Int,
        val actualG: Int,
        val actualB: Int,
        val matched: Boolean,
    )

    // ------------------------------------------------------------------
    // Internal evaluation
    // ------------------------------------------------------------------

    private fun evaluateScreen(
        bitmap: Bitmap,
        screenConfig: ScreenConfig,
        pixelCache: MutableMap<Pair<Int, Int>, Int>,
    ): Pair<Float, List<PixelCheckResult>> {
        if (screenConfig.checkRegions.isEmpty()) return 0f to emptyList()

        var totalWeight = 0f
        var weightedScore = 0f
        val details = mutableListOf<PixelCheckResult>()

        for (region in screenConfig.checkRegions) {
            val x = region.x.coerceIn(0, bitmap.width - 1)
            val y = region.y.coerceIn(0, bitmap.height - 1)

            val pixel = pixelCache.getOrPut(x to y) {
                bitmap.getPixel(x, y)
            }

            val actualR = (pixel shr 16) and 0xFF
            val actualG = (pixel shr 8) and 0xFF
            val actualB = pixel and 0xFF

            val matched = colorMatches(
                actualR, actualG, actualB,
                region.expectedR, region.expectedG, region.expectedB,
                region.tolerance,
            )

            details.add(
                PixelCheckResult(
                    label = region.label,
                    x = x,
                    y = y,
                    expectedR = region.expectedR,
                    expectedG = region.expectedG,
                    expectedB = region.expectedB,
                    actualR = actualR,
                    actualG = actualG,
                    actualB = actualB,
                    matched = matched,
                )
            )

            if (matched) {
                weightedScore += region.weight
            }
            totalWeight += region.weight
        }

        val confidence = if (totalWeight > 0f) weightedScore / totalWeight else 0f
        return confidence to details
    }

    private fun colorMatches(
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int,
        tolerance: Int,
    ): Boolean {
        return kotlin.math.abs(r1 - r2) <= tolerance &&
                kotlin.math.abs(g1 - g2) <= tolerance &&
                kotlin.math.abs(b1 - b2) <= tolerance
    }

    /**
     * Builds a simple [GameConfig] from name and screens list for testing.
     */
    fun buildConfig(
        gamePackage: String,
        gameName: String,
        screens: List<ScreenConfig>,
    ): GameConfig = GameConfig(
        gamePackage = gamePackage,
        gameName = gameName,
        screens = screens,
    )
}
