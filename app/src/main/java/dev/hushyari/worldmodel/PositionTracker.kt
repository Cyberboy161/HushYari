package dev.hushyari.worldmodel

import dev.hushyari.data.model.Position
import dev.hushyari.data.model.UIElement
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracks the agent's position in the game world map.
 *
 * Updates position from UI indicators (coordinates, region names)
 * and estimates position based on last known location plus actions
 * performed since.
 *
 * Supports:
 * - Proximity checks
 * - Path suggestion (swipe directions to navigate)
 * - Map zoom level awareness
 *
 * 🧠 4x-game-agent mechanic: Layer 3 — Spatial awareness for navigation planning.
 */
@Singleton
class PositionTracker @Inject constructor() {

    private var lastKnownPosition: Position? = null
    private var positionConfidence: Float = 0f
    private var zoomLevel: Float = 1.0f
    private var estimatedDx: Int = 0
    private var estimatedDy: Int = 0
    private var currentRegion: String = ""
    private var actionCount: Int = 0

    /**
     * Update position from UI elements (e.g., map coordinates display).
     *
     * @param elements List of UI elements from the accessibility tree.
     * @return The updated position if found, null otherwise.
     */
    fun updateFromUI(elements: List<UIElement>): Position? {
        var newPos: Position? = null

        for (element in elements) {
            val text = element.text + " " + element.contentDescription

            for (match in COORD_PATTERN.findAll(text)) {
                val x = match.groupValues[1].toIntOrNull()
                val y = match.groupValues[2].toIntOrNull()
                if (x != null && y != null) {
                    newPos = Position(x, y)
                    positionConfidence = 0.95f
                    break
                }
            }

            for (match in REGION_PATTERN.findAll(text)) {
                val region = match.groupValues[1]
                if (region.isNotBlank()) {
                    currentRegion = region
                    if (newPos == null && lastKnownPosition != null) {
                        newPos = lastKnownPosition!!.copy(label = region)
                    }
                    positionConfidence = (positionConfidence * 0.5f + 0.5f).coerceAtMost(0.8f)
                }
            }
        }

        if (newPos != null) {
            lastKnownPosition = newPos
            estimatedDx = 0
            estimatedDy = 0
            actionCount = 0
        }

        return newPos
    }

    /**
     * Estimate current position based on last known position plus
     * accumulated offsets from actions.
     *
     * @return Estimated position, or null if we have no position data.
     */
    fun estimatePosition(): Position? {
        val last = lastKnownPosition ?: return null
        return Position(
            x = last.x + estimatedDx,
            y = last.y + estimatedDy,
            label = last.label,
        )
    }

    /**
     * Record that a swipe was performed, adjusting the estimated offset.
     * Direction is normalized to game-world coordinates.
     *
     * @param direction Swipe direction on screen: "up", "down", "left", "right".
     * @param distance Pixels swiped on screen.
     */
    fun recordSwipe(direction: String, distance: Int) {
        val adjusted = (distance / zoomLevel).toInt()
        actionCount++

        when (direction.lowercase()) {
            "up" -> estimatedDy -= adjusted
            "down" -> estimatedDy += adjusted
            "left" -> estimatedDx -= adjusted
            "right" -> estimatedDx += adjusted
        }
    }

    /**
     * Record a tap at a map location.
     *
     * @param x Screen X coordinate.
     * @param y Screen Y coordinate.
     */
    fun recordTap(x: Int, y: Int) {
        actionCount++
    }

    /**
     * Set map zoom level to normalize swipe distances.
     *
     * @param zoom Current zoom level (1.0 = default).
     */
    fun setZoomLevel(zoom: Float) {
        zoomLevel = zoom.coerceIn(0.25f, 5.0f)
    }

    fun getZoomLevel(): Float = zoomLevel

    /**
     * Check if the estimated position is near a target position.
     *
     * @param target The target position to check against.
     * @param threshold Maximum pixel distance considered "near".
     * @return true if the estimated position is within threshold of target.
     */
    fun isNear(target: Position, threshold: Double = 100.0): Boolean {
        val current = estimatePosition() ?: return false
        val dx = (current.x - target.x).toDouble()
        val dy = (current.y - target.y).toDouble()
        return sqrt(dx * dx + dy * dy) <= threshold
    }

    /**
     * Suggest swipe directions to navigate from current position to target.
     *
     * @param target The target position to navigate to.
     * @return Ordered list of (direction, distance) pairs.
     */
    fun pathTo(target: Position): List<Pair<String, Float>> {
        val current = estimatePosition() ?: return emptyList()

        val dx = target.x - current.x
        val dy = target.y - current.y
        val steps = mutableListOf<Pair<String, Float>>()

        val absDx = abs(dx)
        val absDy = abs(dy)

        if (absDx > 0) {
            val xDir = if (dx > 0) "right" else "left"
            steps.add(xDir to absDx.toFloat())
        }
        if (absDy > 0) {
            val yDir = if (dy > 0) "down" else "up"
            steps.add(yDir to absDy.toFloat())
        }

        return steps
    }

    /**
     * Get the last known position label (region name).
     */
    fun getCurrentRegion(): String = currentRegion

    /**
     * Get the confidence in our current position estimate.
     * Decreases as actions accumulate without a confirmed position update.
     */
    fun getConfidence(): Float {
        val decay = 1.0f / (1 + actionCount * 0.1f)
        return (positionConfidence * decay).coerceIn(0f, 1f)
    }

    /**
     * Reset all position tracking state.
     */
    fun reset() {
        lastKnownPosition = null
        positionConfidence = 0f
        zoomLevel = 1.0f
        estimatedDx = 0
        estimatedDy = 0
        currentRegion = ""
        actionCount = 0
    }

    companion object {
        private val COORD_PATTERN = Regex(
            """X[:\s]*(\d+)[,\s]+Y[:\s]*(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        private val REGION_PATTERN = Regex(
            """(?:Region|Area|Zone|Location)[:\s]+(\w+(?:\s+\w+)?)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
