package dev.hushyari.worldmodel

import dev.hushyari.data.model.OcrBlock
import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.TimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks in-game timers (building, research, training, etc.) by detecting
 * timer text on screen and predicting completion times.
 *
 * Supports multiple time formats:
 * - "02:34:12" (HH:MM:SS)
 * - "1d 2h" or "1d 2h 30m"
 * - "Ready in 5m 30s"
 * - "00:45" (MM:SS)
 *
 * Uses coroutines for periodic checking of timer expiry.
 *
 * 🧠 4x-game-agent mechanic: Layer 3 — Timer scheduling for optimal action timing.
 */
@Singleton
class TimerEngine @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicJob: Job? = null

    private val timers = mutableMapOf<String, MutableTimerState>()
    private val _events = MutableSharedFlow<TimerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TimerEvent> = _events.asSharedFlow()

    /**
     * Scan the screen state for timer text and update tracked timers.
     *
     * @param screenState Current screen state.
     * @return List of detected timer names.
     */
    fun detectTimers(screenState: ScreenState): List<String> {
        val allText = buildString {
            if (screenState.ocrText.isNotEmpty()) {
                append(screenState.ocrText)
                append(' ')
            }
            screenState.ocrBlocks.forEach { block ->
                append(block.text)
                append(' ')
            }
            screenState.textElements.forEach { el ->
                append(el.text)
                append(' ')
                append(el.contentDescription)
                append(' ')
            }
        }

        val detected = mutableListOf<String>()

        for (match in TIMER_PATTERN.findAll(allText)) {
            val fullMatch = match.value
            val name = match.groups["name"]?.value?.trim()
            val durationMs = parseDuration(fullMatch) ?: continue

            val key = name ?: fullMatch
            setTimer(key, durationMs)
            detected.add(key)
        }

        for (match in COUNTDOWN_PATTERN.findAll(allText)) {
            val fullMatch = match.value
            val name = match.groups["name"]?.value?.trim()
            val durationMs = parseCountdown(fullMatch) ?: continue

            val key = name ?: fullMatch
            setTimer(key, durationMs)
            detected.add(key)
        }

        return detected.distinct()
    }

    /**
     * Create or update a named timer with a duration.
     *
     * @param name Timer identifier.
     * @param durationMs Duration in milliseconds from now.
     */
    fun setTimer(name: String, durationMs: Long) {
        val existing = timers[name]
        if (existing != null && kotlin.math.abs(existing.remainingMs - durationMs) < 5000) {
            return
        }

        timers[name] = MutableTimerState(
            name = name,
            startedAt = System.currentTimeMillis(),
            durationMs = durationMs,
            remainingMs = durationMs,
        )
    }

    /**
     * Remove a timer by name.
     */
    fun clearTimer(name: String) {
        timers.remove(name)
    }

    /**
     * Get all timers that have completed (remainingMs <= 0).
     */
    fun getExpiredTimers(): List<TimerState> {
        return timers.values
            .filter { it.computeRemainingMs() <= 0 }
            .map { it.toTimerState() }
    }

    /**
     * Get all currently active (non-expired) timers.
     */
    fun getActiveTimers(): List<TimerState> {
        return timers.values
            .filter { it.computeRemainingMs() > 0 }
            .sortedBy { it.computeRemainingMs() }
            .map { it.toTimerState() }
    }

    /**
     * Get all tracked timers (both active and expired).
     */
    fun getAllTimers(): List<TimerState> {
        return timers.values.map { it.toTimerState() }
    }

    /**
     * Predict when a named timer will complete (epoch millis).
     *
     * @param name Timer identifier.
     * @return Predicted completion time in epoch millis, or null if timer not found.
     */
    fun predictCompletion(name: String): Long? {
        val timer = timers[name] ?: return null
        val remaining = timer.computeRemainingMs()
        return System.currentTimeMillis() + remaining
    }

    /**
     * Get the next timer that will expire.
     *
     * @return The timer with the earliest remaining time, or null if none active.
     */
    fun getNextExpiry(): TimerState? {
        return timers.values
            .filter { it.computeRemainingMs() > 0 }
            .minByOrNull { it.computeRemainingMs() }
            ?.toTimerState()
    }

    /**
     * Get estimated milliseconds until any timer expires.
     *
     * @return Milliseconds until next completion, or Long.MAX_VALUE if none active.
     */
    fun getTimeUntilNextExpiry(): Long {
        return getNextExpiry()?.remainingMs ?: Long.MAX_VALUE
    }

    /**
     * Start periodic timer checking. Emits [TimerEvent.Expired] events when timers complete.
     *
     * @param intervalMs Check interval in milliseconds.
     */
    fun startPeriodicCheck(intervalMs: Long = 5000) {
        stopPeriodicCheck()
        periodicJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                updateTimers()
                val expired = getExpiredTimers()
                for (timer in expired) {
                    _events.tryEmit(TimerEvent.Expired(timer))
                    timers.remove(timer.name)
                }
            }
        }
    }

    /**
     * Stop periodic timer checking.
     */
    fun stopPeriodicCheck() {
        periodicJob?.cancel()
        periodicJob = null
    }

    /**
     * Update remaining times on all timers based on elapsed wall-clock time.
     */
    fun updateTimers() {
        timers.values.forEach { it.computeRemainingMs() }
    }

    /**
     * Clear all tracked timers.
     */
    fun reset() {
        stopPeriodicCheck()
        timers.clear()
    }

    // ── Parsing ─────────────────────────────────────────────────────────

    private fun parseDuration(text: String): Long? {
        var totalMs = 0L
        var matched = false

        val dayPattern = Regex("""(\d+)\s*d""", RegexOption.IGNORE_CASE)
        val hourPattern = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE)
        val minutePattern = Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE)
        val secondPattern = Regex("""(\d+)\s*s""", RegexOption.IGNORE_CASE)

        dayPattern.find(text)?.let { m ->
            totalMs += (m.groupValues[1].toLongOrNull() ?: 0) * 86_400_000
            matched = true
        }
        hourPattern.find(text)?.let { m ->
            totalMs += (m.groupValues[1].toLongOrNull() ?: 0) * 3_600_000
            matched = true
        }
        minutePattern.find(text)?.let { m ->
            totalMs += (m.groupValues[1].toLongOrNull() ?: 0) * 60_000
            matched = true
        }
        secondPattern.find(text)?.let { m ->
            totalMs += (m.groupValues[1].toLongOrNull() ?: 0) * 1_000
            matched = true
        }

        return if (matched && totalMs >= 0) totalMs else null
    }

    private fun parseCountdown(text: String): Long? {
        val hhmmssPattern = Regex("""(\d{2}):(\d{2}):(\d{2})""")
        val mmssPattern = Regex("""(\d{1,2}):(\d{2})""")

        hhmmssPattern.find(text)?.let { m ->
            val hours = m.groupValues[1].toLongOrNull() ?: 0
            val minutes = m.groupValues[2].toLongOrNull() ?: 0
            val seconds = m.groupValues[3].toLongOrNull() ?: 0
            return hours * 3_600_000 + minutes * 60_000 + seconds * 1_000
        }

        mmssPattern.find(text)?.let { m ->
            val minutes = m.groupValues[1].toLongOrNull() ?: 0
            val seconds = m.groupValues[2].toLongOrNull() ?: 0
            return minutes * 60_000 + seconds * 1_000
        }

        return null
    }

    companion object {
        private val TIMER_PATTERN = Regex(
            """(?<name>\w+(?:\s+\w+)?)\s*(?:ready\s*in|completes?\s*in|finishes?\s*in|:)\s*[\d\sdhms]+""",
            RegexOption.IGNORE_CASE,
        )
        private val COUNTDOWN_PATTERN = Regex(
            """(?<name>\w+(?:\s+\w+)?)?\s*(\d{1,2}:\d{2}(?::\d{2})?)\s*(?:remaining|left)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * Events emitted by TimerEngine.
 */
sealed class TimerEvent {
    data class Expired(val timer: TimerState) : TimerEvent()
    data class Detected(val name: String, val durationMs: Long) : TimerEvent()
}

/**
 * Mutable internal timer state that computes elapsed time on access.
 */
private data class MutableTimerState(
    val name: String,
    val startedAt: Long,
    val durationMs: Long,
    var remainingMs: Long,
    val isPaused: Boolean = false,
) {
    fun computeRemainingMs(): Long {
        if (isPaused) return remainingMs
        val elapsed = System.currentTimeMillis() - startedAt
        remainingMs = (durationMs - elapsed).coerceAtLeast(0)
        return remainingMs
    }

    fun toTimerState(): TimerState = TimerState(
        name = name,
        startedAt = startedAt,
        durationMs = durationMs,
        remainingMs = computeRemainingMs(),
        isPaused = isPaused,
    )
}
