package dev.hushyari.statemachine

import dev.hushyari.data.model.WorldState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finite state machine representing a game's screen-to-screen navigation graph.
 *
 * Loads transitions from [GameConfig] at initialization. Manages the current
 * screen state and validates/proposes transitions. Supports pathfinding between
 * screens via BFS on the state graph.
 *
 * 🧠 4x-game-agent mechanic: Layer 2 — Screen-state graph for navigation planning.
 * 🧠 PokeClaw mechanic: Popup response mapping and unknown-transition graceful fallback.
 */
@Singleton
class GameFSM @Inject constructor() {

    private var gameConfig: GameConfig? = null
    private var currentState: String = "unknown"
    private val transitions = mutableListOf<StateTransition>()
    private val adjacencyList = mutableMapOf<String, MutableList<StateTransition>>()
    private val transitionIndex = mutableMapOf<String, StateTransition>()

    /**
     * Load transition rules from a [GameConfig].
     * Call this at the start of each playground session.
     */
    fun loadConfig(config: GameConfig) {
        gameConfig = config
        currentState = config.homeScreen
        transitions.clear()
        adjacencyList.clear()
        transitionIndex.clear()

        for (tc in config.transitions) {
            val transition = tc.toStateTransition()
            transitions.add(transition)
            adjacencyList.getOrPut(transition.fromScreen) { mutableListOf() }.add(transition)
            transitionIndex["${transition.fromScreen}->${transition.toScreen}"] = transition
        }
    }

    /**
     * Get the current screen name.
     */
    fun getCurrentState(): String = currentState

    /**
     * Transition to a new state. Validates that the transition exists in the config,
     * or allows it gracefully for unknown screens.
     *
     * @param toState The target screen name.
     * @return true if the transition was accepted, false if invalid.
     */
    fun transition(toState: String): Boolean {
        if (toState == currentState) return true

        val validTransitions = getValidTransitions()
        val valid = validTransitions.any { it.toScreen == toState }

        if (valid) {
            currentState = toState
            return true
        }

        val isKnownScreen = gameConfig?.getScreen(toState) != null
        if (!isKnownScreen) {
            currentState = toState
            return true
        }

        return false
    }

    /**
     * Force-set the current state without validation.
     * Used after external classification determines the screen.
     */
    fun setCurrentState(state: String) {
        currentState = state
    }

    /**
     * Get all valid transition edges from the current state.
     */
    fun getValidTransitions(): List<StateTransition> {
        return adjacencyList[currentState]?.toList() ?: emptyList()
    }

    /**
     * Get all valid transition edges from the current state that are
     * satisfied by the given world model resources.
     */
    fun getSatisfiedTransitions(resources: Map<String, Long>): List<StateTransition> {
        return getValidTransitions().filter { it.isSatisfied(resources, currentState) }
    }

    /**
     * Find the shortest path (BFS) from the current state to [toState].
     *
     * @param toState Target screen name.
     * @return Ordered list of transitions forming the shortest path, or empty if unreachable.
     */
    fun getPath(toState: String): List<StateTransition> {
        if (toState == currentState) return emptyList()

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        val predecessors = mutableMapOf<String, StateTransition?>()

        queue.add(currentState)
        predecessors[currentState] = null

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == toState) break
            if (current in visited) continue
            visited.add(current)

            val neighbors = adjacencyList[current] ?: continue
            for (transition in neighbors) {
                val next = transition.toScreen
                if (next !in visited && next !in queue && next !in predecessors) {
                    predecessors[next] = transition
                    queue.add(next)
                }
            }
        }

        if (toState !in predecessors) return emptyList()

        val path = mutableListOf<StateTransition>()
        var step = predecessors[toState]
        while (step != null) {
            path.add(0, step)
            step = predecessors[step.fromScreen]
        }

        return path
    }

    /**
     * Check if the current state matches [target].
     * Handles aliases defined in the game config.
     */
    fun isAtScreen(target: String): Boolean {
        if (currentState == target) return true
        val screenConfig = gameConfig?.getScreen(currentState) ?: return false
        return screenConfig.aliases.contains(target) || screenConfig.name == target
    }

    /**
     * Check if we are currently on a known popup screen.
     */
    fun isPopup(): Boolean {
        val screenConfig = gameConfig?.getScreen(currentState) ?: return false
        return screenConfig.isPopup
    }

    /**
     * Get the appropriate response action for an unexpected popup type.
     * Returns a recommended tool name and target for the dismiss action.
     *
     * 🧠 PokeClaw mechanic: Popup type to action mapping.
     */
    fun getUnexpectedPopupResponse(popupType: PopupType): PopupResponseAction {
        return when (popupType) {
            PopupType.AD -> PopupResponseAction("tap", "close_ad")
            PopupType.OFFER -> PopupResponseAction("tap", "no_thanks")
            PopupType.UPDATE -> PopupResponseAction("tap", "later")
            PopupType.ERROR -> PopupResponseAction("tap", "ok")
            PopupType.REWARD -> PopupResponseAction("tap", "claim")
            PopupType.CONNECTION -> PopupResponseAction("tap", "retry")
            PopupType.PERMISSION -> PopupResponseAction("tap", "allow")
            PopupType.GENERIC -> PopupResponseAction("tap", "close")
        }
    }

    /**
     * Get all screens that are reachable from the current state in one transition.
     */
    fun getReachableScreens(): List<String> {
        return getValidTransitions().map { it.toScreen }.distinct()
    }

    /**
     * Reset to a fresh state. Typically called at the start of a new session.
     */
    fun reset() {
        currentState = gameConfig?.homeScreen ?: "unknown"
    }

    /**
     * Reconstruct navigation history by replaying transitions on screen changes.
     * Called when [ScreenClassifier] detects a screen change.
     */
    fun onScreenChanged(newScreen: String) {
        transition(newScreen)
    }
}

/**
 * Suggested action for responding to an unexpected popup.
 *
 * 🧠 PokeClaw mechanic: Popup response mapping.
 */
data class PopupResponseAction(
    val tool: String,
    val target: String,
    val params: Map<String, Any?> = emptyMap(),
)
