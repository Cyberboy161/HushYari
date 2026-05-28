package dev.hushyari.worldmodel

import com.tencent.mmkv.MMKV
import dev.hushyari.data.model.Position
import dev.hushyari.data.model.TimerState
import dev.hushyari.data.model.WorldState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live world state holder providing reactive observation of all game state.
 *
 * Wraps [WorldState] in a [MutableStateFlow] so that consumers can observe
 * changes in real time. All mutations create a new immutable [WorldState]
 * snapshot to ensure thread-safe reactive updates.
 *
 * Persists critical state to MMKV for crash recovery.
 *
 * 🧠 4x-game-agent mechanic: Layer 3 — Central world model state holder.
 * 🧠 PokeClaw mechanic: Crash-resilient state persistence via MMKV.
 */
@Singleton
class WorldStateManager @Inject constructor() {

    private val _state = MutableStateFlow(WorldState())
    val state: StateFlow<WorldState> = _state.asStateFlow()

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID("world_state") }

    init {
        restoreFromMmkv()
    }

    fun getState(): WorldState = _state.value

    fun toSnapshot(): WorldState = _state.value.copy(
        resources = LinkedHashMap(_state.value.resources),
        items = LinkedHashMap(_state.value.items),
        activeTimers = LinkedHashMap(_state.value.activeTimers),
    )

    // ── Screen tracking ─────────────────────────────────────────────────

    fun updateScreen(name: String) {
        _state.update { current ->
            val history = current.screenHistory.toMutableList()
            history.add(current.currentScreen)
            if (history.size > 100) history.removeAt(0)
            current.copy(
                currentScreen = name,
                previousScreen = current.currentScreen,
                screenHistory = history,
                lastUpdated = System.currentTimeMillis(),
            )
        }
        persistToMmkv()
    }

    fun getCurrentScreen(): String = _state.value.currentScreen

    fun getPreviousScreen(): String = _state.value.previousScreen

    fun getScreenHistory(): List<String> = _state.value.screenHistory.toList()

    // ── Resource tracking ───────────────────────────────────────────────

    fun updateResource(key: String, value: Long) {
        _state.update { current ->
            val resources = LinkedHashMap(current.resources)
            resources[key] = value
            current.copy(
                resources = resources,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun addResource(key: String, amount: Long) {
        _state.update { current ->
            val resources = LinkedHashMap(current.resources)
            resources[key] = (resources[key] ?: 0L) + amount
            current.copy(
                resources = resources,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun updateResources(resourceMap: Map<String, Long>) {
        _state.update { current ->
            val resources = LinkedHashMap(current.resources)
            resources.putAll(resourceMap)
            current.copy(
                resources = resources,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun getResource(key: String): Long = _state.value.resources[key] ?: 0L

    fun getAllResources(): Map<String, Long> = _state.value.resources.toMap()

    // ── Position tracking ───────────────────────────────────────────────

    fun updatePosition(x: Int, y: Int, label: String) {
        _state.update { current ->
            current.copy(
                mapPosition = Position(x, y, label),
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun updateBasePosition(x: Int, y: Int, label: String) {
        _state.update { current ->
            current.copy(
                basePosition = Position(x, y, label),
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun getMapPosition(): Position? = _state.value.mapPosition

    fun getBasePosition(): Position? = _state.value.basePosition

    // ── Combat tracking ─────────────────────────────────────────────────

    fun enterCombat(hp: Int, maxHp: Int) {
        _state.update { current ->
            current.copy(
                isInCombat = true,
                currentHP = hp,
                maxHP = maxHp,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun exitCombat() {
        _state.update { current ->
            current.copy(
                isInCombat = false,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun updateHP(current: Int, max: Int) {
        _state.update { state ->
            state.copy(
                currentHP = current,
                maxHP = max,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun isInCombat(): Boolean = _state.value.isInCombat

    fun getHP(): Int = _state.value.currentHP

    fun getMaxHP(): Int = _state.value.maxHP

    // ── Timer tracking ──────────────────────────────────────────────────

    fun setTimer(name: String, durationMs: Long) {
        _state.update { current ->
            val timers = LinkedHashMap(current.activeTimers)
            timers[name] = TimerState(
                name = name,
                startedAt = System.currentTimeMillis(),
                durationMs = durationMs,
                remainingMs = durationMs,
            )
            current.copy(
                activeTimers = timers,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun updateTimer(name: String, remainingMs: Long) {
        _state.update { current ->
            val timers = LinkedHashMap(current.activeTimers)
            val existing = timers[name]
            if (existing != null) {
                timers[name] = existing.copy(remainingMs = remainingMs)
            }
            current.copy(
                activeTimers = timers,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun removeTimer(name: String) {
        _state.update { current ->
            val timers = LinkedHashMap(current.activeTimers)
            timers.remove(name)
            current.copy(
                activeTimers = timers,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun getActiveTimers(): Map<String, TimerState> = _state.value.activeTimers.toMap()

    fun getTimer(name: String): TimerState? = _state.value.activeTimers[name]

    // ── Inventory tracking ──────────────────────────────────────────────

    fun updateItem(item: String, count: Int) {
        _state.update { current ->
            val items = LinkedHashMap(current.items)
            items[item] = count
            current.copy(
                items = items,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun addItem(item: String, amount: Int = 1) {
        _state.update { current ->
            val items = LinkedHashMap(current.items)
            items[item] = (items[item] ?: 0) + amount
            current.copy(
                items = items,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun removeItem(item: String, amount: Int = 1) {
        _state.update { current ->
            val items = LinkedHashMap(current.items)
            val currentCount = items[item] ?: 0
            val newCount = (currentCount - amount).coerceAtLeast(0)
            if (newCount <= 0) items.remove(item) else items[item] = newCount
            current.copy(
                items = items,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    // ── Action / error / popup counters ─────────────────────────────────

    fun recordAction() {
        _state.update { current ->
            current.copy(
                actionsPerformed = current.actionsPerformed + 1,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun recordError() {
        _state.update { current ->
            current.copy(
                errorsEncountered = current.errorsEncountered + 1,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun recordPopupDismissed() {
        _state.update { current ->
            current.copy(
                popupsDismissed = current.popupsDismissed + 1,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun getActionsPerformed(): Int = _state.value.actionsPerformed

    fun getErrorsEncountered(): Int = _state.value.errorsEncountered

    fun getPopupsDismissed(): Int = _state.value.popupsDismissed

    // ── Game package ────────────────────────────────────────────────────

    fun setGamePackage(pkg: String) {
        _state.update { current ->
            current.copy(
                gamePackage = pkg,
                lastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun getGamePackage(): String = _state.value.gamePackage

    // ── Persistence ─────────────────────────────────────────────────────

    /**
     * 🧠 PokeClaw mechanic: Persist critical state to MMKV for crash recovery.
     */
    private fun persistToMmkv() {
        try {
            val s = _state.value
            mmkv.encode("game_package", s.gamePackage)
            mmkv.encode("current_screen", s.currentScreen)
            mmkv.encode("previous_screen", s.previousScreen)
            mmkv.encode("actions_performed", s.actionsPerformed)
            mmkv.encode("errors_encountered", s.errorsEncountered)
            mmkv.encode("popups_dismissed", s.popupsDismissed)
            mmkv.encode("session_start", s.sessionStartTime)
        } catch (e: Exception) {
            // MMKV persistence failure is non-critical, state remains in memory
        }
    }

    private fun restoreFromMmkv() {
        try {
            val pkg = mmkv.decodeString("game_package", "") ?: ""
            val screen = mmkv.decodeString("current_screen", "unknown") ?: "unknown"
            val prev = mmkv.decodeString("previous_screen", "") ?: ""
            val actions = mmkv.decodeInt("actions_performed", 0)
            val errors = mmkv.decodeInt("errors_encountered", 0)
            val popups = mmkv.decodeInt("popups_dismissed", 0)
            val sessionStart = mmkv.decodeLong("session_start", System.currentTimeMillis())

            _state.value = _state.value.copy(
                gamePackage = pkg,
                currentScreen = screen,
                previousScreen = prev,
                actionsPerformed = actions,
                errorsEncountered = errors,
                popupsDismissed = popups,
                sessionStartTime = sessionStart,
            )
        } catch (e: Exception) {
            // Recovery failure is non-critical; start with fresh state
        }
    }

    /**
     * Reset all state for a new game session.
     */
    fun reset() {
        _state.value = WorldState()
        mmkv.clearAll()
    }
}
