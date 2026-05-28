package dev.hushyari.skills

import dev.hushyari.data.model.FailureAction
import dev.hushyari.data.model.RetryPolicy
import dev.hushyari.data.model.Skill
import dev.hushyari.data.model.SkillCategory
import dev.hushyari.data.model.SkillStep
import dev.hushyari.data.model.TargetSpec
import dev.hushyari.data.model.TargetType
import dev.hushyari.data.model.VerificationCheck

/**
 * Universal game-agnostic skills that work across all game types.
 *
 * These skills handle common patterns: popup dismissal, screen waiting,
 * app restart, connection recovery, reward collection, and navigation
 * via the state machine.
 *
 * 🧠 4x-game-agent mechanic: Layer 4 — Cross-game reusable skill library.
 * 🧠 PokeClaw mechanic: Watcher-style skills for popup and error handling.
 */
object GenericSkills {

    /**
     * Detects and dismisses any popup on screen.
     * Used as a watcher skill to run alongside other tasks.
     */
    val DISMISS_POPUPS = Skill(
        id = "generic.dismiss_popups",
        name = "Dismiss Popups",
        gameType = emptyList(),
        description = "Detects and dismisses any popup, ad, or overlay on screen",
        category = SkillCategory.GENERIC,
        steps = listOf(
            SkillStep(
                id = "detect_and_tap_close",
                description = "Find and tap close/dismiss button on popup",
                tool = "tap",
                target = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "close",
                ),
                fallback = TargetSpec(
                    type = TargetType.COORDINATES,
                    xFraction = 0.92f,
                    yFraction = 0.06f,
                ),
                continueOnFailure = true,
                retriesOnFailure = 0,
            ),
        ),
        retryPolicy = RetryPolicy(
            maxRetries = 1,
            onFailure = FailureAction.SKIP_STEP,
        ),
        isBuiltIn = true,
    )

    /**
     * Wait until a specific screen appears.
     *
     * @param screenName The screen to wait for.
     */
    fun WAIT_FOR_SCREEN(screenName: String) = Skill(
        id = "generic.wait_for_screen.$screenName",
        name = "Wait for $screenName",
        gameType = emptyList(),
        description = "Pauses until the $screenName screen is visible",
        category = SkillCategory.NAVIGATION,
        steps = listOf(
            SkillStep(
                id = "wait_for_screen",
                description = "Wait up to 30 seconds for $screenName screen",
                tool = "wait",
                params = mapOf(
                    "timeoutMs" to "30000",
                    "checkScreen" to screenName,
                ),
                verifyAfter = VerificationCheck(
                    type = "screen_is",
                    screenName = screenName,
                ),
            ),
        ),
        retryPolicy = RetryPolicy(
            maxRetries = 2,
            onFailure = FailureAction.ESCALATE_TO_LLM,
            backoffMs = 2000,
        ),
        isBuiltIn = true,
    )

    /**
     * Navigate to the game's home screen and restart the app.
     * Used as a recovery mechanism when the agent gets lost.
     */
    val GO_HOME_AND_RESTART = Skill(
        id = "generic.go_home_and_restart",
        name = "Go Home and Restart",
        gameType = emptyList(),
        description = "Returns to device home, re-launches the game to recover from unknown state",
        category = SkillCategory.NAVIGATION,
        steps = listOf(
            SkillStep(
                id = "press_home",
                description = "Press device home button",
                tool = "home",
                waitAfterMs = 1000,
            ),
            SkillStep(
                id = "launch_game",
                description = "Re-launch the game app",
                tool = "launch_app",
                waitAfterMs = 5000,
            ),
            SkillStep(
                id = "wait_load",
                description = "Wait for game to load",
                tool = "wait",
                params = mapOf("timeoutMs" to "15000"),
            ),
            SkillStep(
                id = "dismiss_any",
                description = "Dismiss any startup popups",
                tool = "tap",
                target = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "close",
                ),
                continueOnFailure = true,
                retriesOnFailure = 0,
                waitAfterMs = 500,
            ),
        ),
        retryPolicy = RetryPolicy(
            maxRetries = 1,
            onFailure = FailureAction.STOP_SKILL,
            backoffMs = 3000,
        ),
        isBuiltIn = true,
    )

    /**
     * Handles connection loss popups ("no internet", "reconnect").
     */
    val HANDLE_CONNECTION_LOSS = Skill(
        id = "generic.handle_connection_loss",
        name = "Handle Connection Loss",
        gameType = emptyList(),
        description = "Detects and handles network connection error popups",
        category = SkillCategory.GENERIC,
        steps = listOf(
            SkillStep(
                id = "tap_retry",
                description = "Tap retry/reconnect button",
                tool = "tap",
                target = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "retry",
                ),
                fallback = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "reconnect",
                ),
                retriesOnFailure = 1,
            ),
            SkillStep(
                id = "wait_reconnect",
                description = "Wait for reconnection",
                tool = "wait",
                params = mapOf("timeoutMs" to "10000"),
                waitAfterMs = 2000,
            ),
            SkillStep(
                id = "dismiss_error",
                description = "Dismiss error dialog if still present",
                tool = "tap",
                target = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "ok",
                ),
                continueOnFailure = true,
            ),
        ),
        retryPolicy = RetryPolicy(
            maxRetries = 3,
            onFailure = FailureAction.SKIP_STEP,
            backoffMs = 5000,
        ),
        isBuiltIn = true,
    )

    /**
     * Collects rewards by tapping any reward/claim/collect buttons on screen.
     */
    val COLLECT_REWARDS = Skill(
        id = "generic.collect_rewards",
        name = "Collect Rewards",
        gameType = emptyList(),
        description = "Taps on any visible reward/claim/collect buttons",
        category = SkillCategory.RESOURCE,
        steps = listOf(
            SkillStep(
                id = "tap_claim",
                description = "Tap claim/reward button",
                tool = "tap",
                target = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "claim",
                ),
                fallback = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "collect",
                ),
                continueOnFailure = true,
                waitAfterMs = 500,
            ),
            SkillStep(
                id = "tap_reward",
                description = "Tap reward button if claim not found",
                tool = "tap",
                target = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "reward",
                ),
                continueOnFailure = true,
                waitAfterMs = 500,
            ),
            SkillStep(
                id = "tap_confirm",
                description = "Tap confirm/ok on reward dialog",
                tool = "tap",
                target = TargetSpec(
                    type = TargetType.TEXT,
                    textContains = "ok",
                ),
                fallback = TargetSpec(
                    type = TargetType.COORDINATES,
                    xFraction = 0.5f,
                    yFraction = 0.7f,
                ),
                continueOnFailure = true,
            ),
        ),
        retryPolicy = RetryPolicy(
            maxRetries = 0,
            onFailure = FailureAction.SKIP_STEP,
        ),
        isBuiltIn = true,
    )

    /**
     * Navigate to a target screen using the state machine pathfinding.
     *
     * @param targetScreen The destination screen name.
     */
    fun NAVIGATE_TO(targetScreen: String) = Skill(
        id = "generic.navigate_to.$targetScreen",
        name = "Navigate to $targetScreen",
        gameType = emptyList(),
        description = "Navigates through the game's screen graph to reach $targetScreen",
        category = SkillCategory.NAVIGATION,
        steps = listOf(
            SkillStep(
                id = "navigate_step",
                description = "Execute next step in navigation path to $targetScreen",
                tool = "navigate",
                params = mapOf("targetScreen" to targetScreen),
                verifyAfter = VerificationCheck(
                    type = "screen_is",
                    screenName = targetScreen,
                ),
                retriesOnFailure = 2,
            ),
        ),
        retryPolicy = RetryPolicy(
            maxRetries = 3,
            onFailure = FailureAction.ESCALATE_TO_LLM,
            backoffMs = 1000,
        ),
        isBuiltIn = true,
    )

    /**
     * All generic skills.
     */
    fun all(): List<Skill> = listOf(
        DISMISS_POPUPS,
        GO_HOME_AND_RESTART,
        HANDLE_CONNECTION_LOSS,
        COLLECT_REWARDS,
        DISMISS_POPUPS,
    )

    /**
     * Generic watcher skills that should run alongside any main skill.
     */
    fun defaultWatchers(): List<Skill> = listOf(
        DISMISS_POPUPS,
    )
}
