package dev.hushyari.data.model

/**
 * World state tracks persistent game knowledge.
 * 🧠 4x-game-agent mechanic: Layer 3 — World Model tracking resources, timers, state.
 */
data class WorldState(
    val gamePackage: String = "",
    val currentScreen: String = "unknown",
    val previousScreen: String = "",
    val screenHistory: List<String> = emptyList(),

    // Resources
    val resources: MutableMap<String, Long> = mutableMapOf(),

    // Timers
    val activeTimers: MutableMap<String, TimerState> = mutableMapOf(),

    // Position
    val mapPosition: Position? = null,
    val basePosition: Position? = null,

    // Combat
    val isInCombat: Boolean = false,
    val currentHP: Int = 100,
    val maxHP: Int = 100,

    // Inventory
    val items: MutableMap<String, Int> = mutableMapOf(),
    val heroes: List<String> = emptyList(),

    // Meta
    val lastUpdated: Long = System.currentTimeMillis(),
    val sessionStartTime: Long = System.currentTimeMillis(),

    // Action tracking
    val actionsPerformed: Int = 0,
    val errorsEncountered: Int = 0,
    val popupsDismissed: Int = 0,
) {
    fun getResource(key: String): Long = resources[key] ?: 0L
    fun setResource(key: String, value: Long) { resources[key] = value }

    fun addResource(key: String, amount: Long) {
        resources[key] = getResource(key) + amount
    }

    fun getTimer(name: String): TimerState? = activeTimers[name]

    fun setTimer(name: String, durationMs: Long) {
        activeTimers[name] = TimerState(
            name = name,
            startedAt = System.currentTimeMillis(),
            durationMs = durationMs,
            remainingMs = durationMs,
        )
    }

    fun isTimerExpired(name: String): Boolean {
        return activeTimers[name]?.isExpired ?: true
    }

    fun getItemCount(item: String): Int = items[item] ?: 0

    val isAlive: Boolean get() = currentHP > 0
    fun needsHealing(threshold: Int = 30): Boolean = currentHP < threshold

    fun toCompactString(): String = buildString {
        append("Screen: $currentScreen")
        if (resources.isNotEmpty()) {
            append(" | Resources: ")
            append(resources.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
        if (isInCombat) {
            append(" | Combat: HP=${currentHP}/$maxHP")
        }
        activeTimers.values.filter { !it.isExpired }.forEach {
            append(" | ${it.name}: ${it.remainingSeconds}s left")
        }
    }
}

data class TimerState(
    val name: String,
    val startedAt: Long,
    val durationMs: Long,
    val remainingMs: Long,
    val isPaused: Boolean = false,
) {
    val isExpired: Boolean get() = remainingMs <= 0
    val remainingSeconds: Long get() = remainingMs / 1000
    val totalSeconds: Long get() = durationMs / 1000
    val progress: Float
        get() = if (durationMs > 0) {
            ((durationMs - remainingMs).toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f
}

data class Position(
    val x: Int,
    val y: Int,
    val label: String = "",
)
