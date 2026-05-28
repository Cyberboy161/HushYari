package dev.hushyari.worldmodel

import dev.hushyari.data.model.OcrBlock
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.UIElement
import dev.hushyari.statemachine.GameConfig
import dev.hushyari.statemachine.ResourceConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Extracts and tracks in-game resource values from OCR text, UI elements,
 * and screen state. Maintains history for trend analysis.
 *
 * Pattern matching examples:
 * - "Gold: 12,345"
 * - "Gems: 56"
 * - "Food: 1.2M"
 * - "Wood 9.5K/h"
 *
 * 🧠 4x-game-agent mechanic: Layer 3 — Resource economy tracking.
 */
@Singleton
class ResourceTracker @Inject constructor() {

    private val resourceConfigs = mutableListOf<ResourceConfig>()
    private val history = mutableMapOf<String, MutableList<ResourceSnapshot>>()
    private val maxHistorySize = 30

    /**
     * Load resource definitions from game config.
     */
    fun loadConfig(config: GameConfig) {
        resourceConfigs.clear()
        resourceConfigs.addAll(config.getResourceConfigs())
    }

    /**
     * Extract resource values from OCR text blocks.
     *
     * @param ocrBlocks List of OCR text blocks from the screen.
     * @return Map of resource key to extracted value.
     */
    fun updateFromOcr(ocrBlocks: List<OcrBlock>): Map<String, Long> {
        val extracted = mutableMapOf<String, Long>()
        val fullText = ocrBlocks.joinToString(" ") { it.text }

        for (config in resourceConfigs) {
            val value = extractResourceValue(fullText, config)
            if (value != null) {
                extracted[config.key] = value
                recordSnapshot(config.key, value)
            }
        }

        return extracted
    }

    /**
     * Extract resource values from UI elements (accessibility nodes).
     *
     * @param elements List of UI elements from the accessibility tree.
     * @return Map of resource key to extracted value.
     */
    fun updateFromUI(elements: List<UIElement>): Map<String, Long> {
        val extracted = mutableMapOf<String, Long>()

        val displayElements = elements.filter {
            it.hasText && (
                    it.className.contains("Text", ignoreCase = true) ||
                            it.className.contains("Label", ignoreCase = true) ||
                            it.className.contains("Resource", ignoreCase = true)
                    )
        }

        val fullText = displayElements.joinToString(" ") { "${it.text} ${it.contentDescription}" }

        for (config in resourceConfigs) {
            val value = extractResourceValue(fullText, config)
            if (value != null) {
                extracted[config.key] = value
                recordSnapshot(config.key, value)
            }
        }

        return extracted
    }

    /**
     * Get the trend for a resource over recent history.
     *
     * @param key Resource key.
     * @return "increasing", "decreasing", or "stable".
     */
    fun getTrend(key: String): String {
        val snapshots = history[key] ?: return "stable"
        if (snapshots.size < 2) return "stable"

        val recent = snapshots.takeLast(5)
        val values = recent.map { it.value }
        val first = values.first()
        val last = values.last()

        val change = last - first
        val threshold = abs(first * 0.01).toLong().coerceAtLeast(1)

        return when {
            change > threshold -> "increasing"
            change < -threshold -> "decreasing"
            else -> "stable"
        }
    }

    /**
     * Estimate resource per-minute rate based on recent changes.
     *
     * @param key Resource key.
     * @return Estimated rate in resource units per minute, or 0 if insufficient data.
     */
    fun getRate(key: String): Long {
        val snapshots = history[key] ?: return 0L
        if (snapshots.size < 3) return 0L

        val recent = snapshots.takeLast(5)
        if (recent.size < 2) return 0L

        val first = recent.first()
        val last = recent.last()
        val deltaValue = last.value - first.value
        val deltaTimeMs = last.timestamp - first.timestamp

        if (deltaTimeMs <= 0) return 0L

        return deltaValue * 60_000L / deltaTimeMs
    }

    /**
     * Get the last known value for a resource.
     */
    fun getValue(key: String): Long {
        return history[key]?.lastOrNull()?.value ?: 0L
    }

    /**
     * Get all tracked resource values.
     */
    fun getAllValues(): Map<String, Long> {
        return history.mapValues { (_, snapshots) ->
            snapshots.lastOrNull()?.value ?: 0L
        }
    }

    /**
     * Clear all resource history.
     */
    fun reset() {
        history.clear()
        resourceConfigs.clear()
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun extractResourceValue(text: String, config: ResourceConfig): Long? {
        for (pattern in config.patterns) {
            val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(text) ?: continue

            val valueGroup = match.groups[1] ?: match.groups["value"] ?: continue
            val rawValue = valueGroup.value.replace(",", "").trim()

            val parsed = parseNumeric(rawValue)
            if (parsed != null) return parsed
        }

        val genericPatterns = listOf(
            Regex("""${Regex.escape(config.displayName)}[:\s]*([\d.,]+[KkMmBb]?)""", RegexOption.IGNORE_CASE),
            Regex("""${Regex.escape(config.key)}[:\s]*([\d.,]+[KkMmBb]?)""", RegexOption.IGNORE_CASE),
        )

        for (pattern in genericPatterns) {
            val match = pattern.find(text) ?: continue
            val rawValue = match.groupValues[1].replace(",", "").trim()
            val parsed = parseNumeric(rawValue)
            if (parsed != null) return parsed
        }

        return null
    }

    private fun parseNumeric(raw: String): Long? {
        return try {
            when {
                raw.endsWith("B", ignoreCase = true) -> {
                    (raw.dropLast(1).toDouble() * 1_000_000_000).roundToLong()
                }
                raw.endsWith("M", ignoreCase = true) -> {
                    (raw.dropLast(1).toDouble() * 1_000_000).roundToLong()
                }
                raw.endsWith("K", ignoreCase = true) -> {
                    (raw.dropLast(1).toDouble() * 1_000).roundToLong()
                }
                raw.contains('.') -> {
                    raw.toDouble().roundToLong()
                }
                else -> raw.toLong()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun recordSnapshot(key: String, value: Long) {
        val snapshots = history.getOrPut(key) { mutableListOf() }
        snapshots.add(ResourceSnapshot(value, System.currentTimeMillis()))
        if (snapshots.size > maxHistorySize) {
            snapshots.removeAt(0)
        }
    }
}

/**
 * A recorded resource value at a point in time for trend analysis.
 */
private data class ResourceSnapshot(
    val value: Long,
    val timestamp: Long,
)
