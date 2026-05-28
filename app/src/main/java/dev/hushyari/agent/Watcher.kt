package dev.hushyari.agent

import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.WorldState
import dev.hushyari.perception.PerceptionPipeline
import dev.hushyari.worldmodel.WorldStateManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Urgent condition monitor that checks every frame for high-priority events
 * requiring immediate action, bypassing normal planning.
 *
 * Detected conditions:
 * - Enemy attack / combat start
 * - Low HP requiring healing
 * - Expired timer (building complete, resource available)
 * - Popup blocking interaction
 * - App not in foreground (game minimized)
 * - Network error screen
 *
 * Checked every 200ms in the agent loop. Returns non-null [UrgentAction]
 * only when a condition requires immediate intervention.
 *
 * **Mechanics:**
 * - PokeClaw: Watcher pattern — continuous monitoring for urgent interrupts.
 * - 4x-game-agent Layer 3: Threshold-based alerting from world model.
 */
@Singleton
class Watcher @Inject constructor(
    private val perception: PerceptionPipeline,
    private val worldStateManager: WorldStateManager,
) {

    private var lastCheckTime: Long = 0L
    private val checkIntervalMs: Long = 200L

    // Configurable thresholds per game
    var lowHpThreshold: Int = 30
        @Synchronized set
    var maxHpThreshold: Int = 100
        @Synchronized set
    var enableCombatWatch: Boolean = true
        @Synchronized set
    var enableTimerWatch: Boolean = true
        @Synchronized set

    /**
     * Check the current screen and world state for urgent conditions.
     *
     * @param screen Current screen state.
     * @param worldState Current world model state.
     * @return [UrgentAction] if immediate action is needed, null otherwise.
     */
    fun check(screen: ScreenState, worldState: WorldState): UrgentAction? {
        val now = System.currentTimeMillis()

        // Rate-limited check: only run every [checkIntervalMs]
        if (now - lastCheckTime < checkIntervalMs) return null
        lastCheckTime = now

        // Check 1: Game is not the foreground app
        if (screen.packageName.isNotEmpty() &&
            worldState.gamePackage.isNotEmpty() &&
            screen.packageName != worldState.gamePackage
        ) {
            return UrgentAction(
                description = "Game app not in foreground",
                action = AgentAction.ToolAction("open_app", mapOf("package" to worldState.gamePackage)),
                reason = "Game package ${worldState.gamePackage} not active, current: ${screen.packageName}",
            )
        }

        // Check 2: Low HP — needs healing
        if (enableCombatWatch &&
            worldState.isInCombat &&
            worldState.currentHP in 1 until lowHpThreshold
        ) {
            return UrgentAction(
                description = "HP critical: ${worldState.currentHP}/${worldState.maxHP}",
                action = AgentAction.ToolAction("tap", mapOf(
                    "text" to "heal",
                    "textContains" to "heal",
                )),
                reason = "HP below threshold ($lowHpThreshold)",
            )
        }

        // Check 3: Combat detected via screen text
        if (enableCombatWatch && worldState.isInCombat) {
            return UrgentAction(
                description = "In combat — attacking",
                action = AgentAction.ToolAction("tap", mapOf(
                    "textContains" to "attack",
                )),
                reason = "Combat state active, must attack",
            )
        }

        // Check 4: Expired timers
        if (enableTimerWatch) {
            val expiredTimers = worldState.activeTimers
                .filter { it.value.isExpired }
                .keys.toList()
            if (expiredTimers.isNotEmpty()) {
                val timerNames = expiredTimers.joinToString(", ")
                return UrgentAction(
                    description = "Timer(s) expired: $timerNames",
                    action = AgentAction.ToolAction("tap", mapOf(
                        "textContains" to "collect",
                    )),
                    reason = "Timers expired, resources may be ready: $timerNames",
                )
            }
        }

        // Check 5: Error dialog detected
        val lowerOcr = screen.ocrText.lowercase()
        val lowerTitle = screen.windowTitle.lowercase()
        if (lowerOcr.contains("error") || lowerOcr.contains("an error occurred") ||
            lowerTitle.contains("error")
        ) {
            return UrgentAction(
                description = "Error dialog detected",
                action = AgentAction.ToolAction("tap", mapOf(
                    "textContains" to "ok",
                )),
                reason = "Error dialog on screen: ${screen.ocrText.take(100)}",
            )
        }

        // Check 6: Connection lost
        if (lowerOcr.contains("connection lost") || lowerOcr.contains("network error") ||
            lowerOcr.contains("reconnect")
        ) {
            return UrgentAction(
                description = "Connection lost",
                action = AgentAction.ToolAction("tap", mapOf(
                    "textContains" to "retry",
                )),
                reason = "Network connection lost",
            )
        }

        return null
    }
}
