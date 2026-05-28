package dev.hushyari.statemachine

import android.graphics.Rect
import dev.hushyari.data.model.ElementQuery
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.TargetSpec
import dev.hushyari.data.model.TargetType
import dev.hushyari.data.model.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.toSet

/**
 * Detects and dismisses in-game popups, ads, offers, and overlays.
 *
 * Detection strategies are layered from most reliable to heuristic:
 * 1. Known close button patterns from [GameConfig] (resource IDs, positions)
 * 2. Full-screen overlays (large element taking most screen with a close button)
 * 3. Ads (elements containing "ad" or "sponsor" in class name)
 * 4. "X" button near top-right corner
 * 5. Text containing "dismiss", "close", "no thanks", "later"
 *
 * Tracks already-tried popups to avoid dismiss loops.
 * Popups are dismissed in priority order (highest first).
 *
 * 🧠 PokeClaw mechanic: Popup dismissal loop prevention.
 * 🧠 4x-game-agent mechanic: Layer 2 — Pre-action screen sanity check.
 */
@Singleton
class PopupHandler @Inject constructor() {

    private val triedTargets = mutableSetOf<PopupTarget>()

    /**
     * Detect all popups on the current screen.
     *
     * @param screenState Current screen state.
     * @param gameConfig Game-specific popup configuration.
     * @return List of detected popups sorted by priority (most important first).
     */
    fun detectPopups(
        screenState: ScreenState,
        gameConfig: GameConfig,
    ): List<DetectedPopup> {
        val popups = mutableListOf<DetectedPopup>()

        popups.addAll(detectFromConfig(screenState, gameConfig))
        popups.addAll(detectFullScreenOverlays(screenState))
        popups.addAll(detectAds(screenState))
        popups.addAll(detectCloseButtons(screenState))
        popups.addAll(detectDismissText(screenState))

        val unique = popups
            .filter { !hasBeenTried(it.dismissTarget) }
            .distinctBy { it.type }
            .sortedByDescending { it.confidence }

        return unique
    }

    /**
     * Dismiss a single detected popup by tapping its close target.
     *
     * @param popup The detected popup to dismiss.
     * @param dimissFn Function that executes a tap on a target spec and returns ToolResult.
     * @return ToolResult of the tap operation.
     */
    suspend fun dismiss(
        popup: DetectedPopup,
        dismissFn: suspend (TargetSpec) -> ToolResult,
    ): ToolResult {
        triedTargets.add(PopupTarget(popup.dismissTarget, System.currentTimeMillis()))
        return dismissFn(popup.dismissTarget)
    }

    /**
     * Dismiss all detected popups in priority order.
     * Stops early if any dismiss fails.
     *
     * @param screenState Current screen state.
     * @param gameConfig Game-specific popup configuration.
     * @param dismissFn Function that executes a tap on a target spec.
     * @return Number of popups successfully dismissed.
     */
    suspend fun dismissAll(
        screenState: ScreenState,
        gameConfig: GameConfig,
        dismissFn: suspend (TargetSpec) -> ToolResult,
    ): Int {
        val popups = detectPopups(screenState, gameConfig)
        var dismissed = 0

        for (popup in popups) {
            val result = dismiss(popup, dismissFn)
            if (result.isSuccess) {
                dismissed++
            } else if (popup.priority > 3) {
                break
            }
            kotlinx.coroutines.delay(300)
        }

        return dismissed
    }

    /**
     * Clear the tried-targets tracking to allow retrying previously-failed dismiss targets.
     */
    fun resetTriedTargets() {
        triedTargets.clear()
    }

    /**
     * Expire old tried-targets entries beyond [maxAgeMs].
     */
    fun expireOldTargets(maxAgeMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        triedTargets.removeAll { now - it.timestamp > maxAgeMs }
    }

    fun getTriedCount(): Int = triedTargets.size

    private fun hasBeenTried(target: TargetSpec): Boolean {
        return triedTargets.any { it.target == target }
    }

    private fun detectFromConfig(
        screenState: ScreenState,
        gameConfig: GameConfig,
    ): List<DetectedPopup> {
        val results = mutableListOf<DetectedPopup>()

        for (popupConfig in gameConfig.getPopupConfigs()) {
            val closeTarget = popupConfig.closeTarget

            for (className in popupConfig.classNames) {
                val query = ElementQuery(className = className)
                if (screenState.findElement(query) != null) {
                    results.add(
                        DetectedPopup(
                            type = popupConfig.type,
                            confidence = 0.85f,
                            dismissTarget = closeTarget ?: fallbackCloseTarget(screenState),
                            priority = popupConfig.priority,
                            source = "config_class_$className",
                        )
                    )
                    break
                }
            }

            for (textPattern in popupConfig.textPatterns) {
                val ocrLower = screenState.ocrText.lowercase()
                if (ocrLower.contains(textPattern.lowercase())) {
                    results.add(
                        DetectedPopup(
                            type = popupConfig.type,
                            confidence = 0.8f,
                            dismissTarget = closeTarget ?: fallbackCloseTarget(screenState),
                            priority = popupConfig.priority,
                            source = "config_text_$textPattern",
                        )
                    )
                    break
                }
            }

            for (resId in popupConfig.closeResourceIds) {
                val query = ElementQuery(resourceId = resId)
                val element = screenState.findElement(query)
                if (element != null) {
                    results.add(
                        DetectedPopup(
                            type = popupConfig.type,
                            confidence = 0.9f,
                            dismissTarget = TargetSpec(
                                type = TargetType.COORDINATES,
                                x = element.centerX.toInt(),
                                y = element.centerY.toInt(),
                            ),
                            priority = popupConfig.priority,
                            source = "config_resid_$resId",
                        )
                    )
                    break
                }
            }
        }

        return results
    }

    private fun detectFullScreenOverlays(screenState: ScreenState): List<DetectedPopup> {
        val results = mutableListOf<DetectedPopup>()
        val screenArea = screenState.screenWidth * screenState.screenHeight
        if (screenArea <= 0) return results

        val closeableElements = screenState.elements.filter { it.isClickable }
        val largeElements = screenState.elements.filter { e ->
            val area = e.width * e.height
            area.toFloat() / screenArea > 0.5f
        }

        for (large in largeElements) {
            if (large.className.contains("Dialog", ignoreCase = true) ||
                large.className.contains("Popup", ignoreCase = true) ||
                large.className.contains("Overlay", ignoreCase = true)
            ) {
                val closeBtn = closeableElements
                    .filter { it.bounds.top >= large.bounds.top && it.bounds.bottom <= large.bounds.bottom }
                    .minByOrNull {
                        val distToTopRight =
                            kotlin.math.abs(it.bounds.right - large.bounds.right) +
                                    kotlin.math.abs(it.bounds.top - large.bounds.top)
                        distToTopRight
                    }

                results.add(
                    DetectedPopup(
                        type = PopupType.GENERIC,
                        confidence = 0.75f,
                        dismissTarget = if (closeBtn != null) {
                            TargetSpec(
                                type = TargetType.COORDINATES,
                                x = closeBtn.centerX.toInt(),
                                y = closeBtn.centerY.toInt(),
                            )
                        } else {
                            fallbackCloseTarget(screenState)
                        },
                        priority = 4,
                        source = "fullscreen_overlay",
                    )
                )
            }
        }

        return results
    }

    private fun detectAds(screenState: ScreenState): List<DetectedPopup> {
        val results = mutableListOf<DetectedPopup>()

        for (element in screenState.elements) {
            val lowerClass = element.className.lowercase()
            val lowerText = element.text.lowercase()
            val lowerDesc = element.contentDescription.lowercase()

            if (lowerClass.contains("ad") || lowerClass.contains("sponsor") ||
                lowerText.contains("ad") || lowerDesc.contains("sponsored")
            ) {
                val closeBtn = screenState.clickableElements
                    .filter { it.isClickable }
                    .minByOrNull { btn ->
                        kotlin.math.abs(btn.bounds.right - screenState.screenWidth) +
                                kotlin.math.abs(btn.bounds.top)
                    }

                results.add(
                    DetectedPopup(
                        type = PopupType.AD,
                        confidence = 0.7f,
                        dismissTarget = if (closeBtn != null) {
                            TargetSpec(
                                type = TargetType.COORDINATES,
                                x = closeBtn.centerX.toInt(),
                                y = closeBtn.centerY.toInt(),
                            )
                        } else {
                            fallbackCloseTarget(screenState)
                        },
                        priority = 2,
                        source = "ad_detection",
                    )
                )
                break
            }
        }

        return results
    }

    private fun detectCloseButtons(screenState: ScreenState): List<DetectedPopup> {
        val closeTargets = listOf(
            RECT_PATTERN_TOP_RIGHT to 0.9f,
            RECT_PATTERN_TOP_LEFT to 0.7f,
        )

        for ((zone, confidence) in closeTargets) {
            val buttonsInZone = screenState.clickableElements.filter { btn ->
                zone.contains(btn.bounds.centerX().toInt(), btn.bounds.centerY().toInt()) &&
                        btn.width < screenState.screenWidth / 4 &&
                        btn.height < screenState.screenHeight / 4
            }

            for (btn in buttonsInZone) {
                val combined = btn.text.lowercase() + btn.contentDescription.lowercase()
                if (combined.contains("close") || combined.contains("x") || combined.contains("dismiss") ||
                    combined.contains("cancel") || combined.isEmpty() || btn.width < 100 && btn.height < 100
                ) {
                    return listOf(
                        DetectedPopup(
                            type = PopupType.GENERIC,
                            confidence = confidence,
                            dismissTarget = TargetSpec(
                                type = TargetType.COORDINATES,
                                x = btn.centerX.toInt(),
                                y = btn.centerY.toInt(),
                            ),
                            priority = 3,
                            source = "close_button_topright",
                        )
                    )
                }
            }
        }

        return emptyList()
    }

    private fun detectDismissText(screenState: ScreenState): List<DetectedPopup> {
        val dismissKeywords = listOf(
            "dismiss", "close", "no thanks", "later", "maybe later",
            "skip", "cancel", "not now", "remind me later", "continue",
        )

        for (element in screenState.elements) {
            val text = element.text.lowercase()
            val desc = element.contentDescription.lowercase()
            val combined = "$text $desc"

            for (keyword in dismissKeywords) {
                if (combined.contains(keyword) && element.isClickable) {
                    return listOf(
                        DetectedPopup(
                            type = PopupType.GENERIC,
                            confidence = 0.65f,
                            dismissTarget = TargetSpec(
                                type = TargetType.COORDINATES,
                                x = element.centerX.toInt(),
                                y = element.centerY.toInt(),
                            ),
                            priority = 1,
                            source = "dismiss_text_$keyword",
                        )
                    )
                }
            }
        }

        return emptyList()
    }

    private fun fallbackCloseTarget(screenState: ScreenState): TargetSpec {
        val x = (screenState.screenWidth * 0.92f).toInt()
        val y = (screenState.screenHeight * 0.06f).toInt()
        return TargetSpec(
            type = TargetType.COORDINATES,
            x = x,
            y = y,
        )
    }

    companion object {
        private val RECT_PATTERN_TOP_RIGHT = Rect(
            (1080 * 0.75f).toInt(), 0, 1080, (1920 * 0.15f).toInt()
        )
        private val RECT_PATTERN_TOP_LEFT = Rect(
            0, 0, (1080 * 0.25f).toInt(), (1920 * 0.15f).toInt()
        )
    }
}

/**
 * A detected popup with type, confidence, and the target to tap to dismiss it.
 *
 * 🧠 PokeClaw mechanic: Structured popup representation for dismissal logic.
 */
data class DetectedPopup(
    val type: PopupType,
    val confidence: Float,
    val dismissTarget: TargetSpec,
    val priority: Int = 0,
    val source: String = "",
)

/**
 * Record of a popup dismiss action already attempted.
 * Used to prevent dismiss loops.
 *
 * 🧠 PokeClaw mechanic: Anti-loop tracking for popup dismissal.
 */
private data class PopupTarget(
    val target: TargetSpec,
    val timestamp: Long,
)
