package dev.hushyari.tools

import dev.hushyari.data.model.ScreenState
import dev.hushyari.data.model.WorldState

/**
 * Central safety gate enforcing 13 PokeClaw-derived safety rules.
 *
 * Every rule returns a [Pair] of (isBlocked: Boolean, reason: String).
 * Rules are checked by [ToolManager] before any tool execution.
 * Additional rules beyond the core 13 can be registered dynamically.
 */
class ToolSafety(
    private val allowedPlayTimeMs: Long = 4 * 60 * 60 * 1000L,
    private val maxConsecutiveFailures: Int = 3,
    private var playSessionStartMs: Long = System.currentTimeMillis(),
) {

    private val failureCounts = mutableMapOf<String, Int>()

    // ── 13 core safety rules ──────────────────────────────────────────

    fun checkPaymentScreen(screen: ScreenState): Pair<Boolean, String> {
        val lower = screen.toTextSummary().lowercase()
        val paymentKeywords = listOf(
            "purchase", "payment", "checkout", "buy", "price", "$", "€", "£",
            "confirm purchase", "billing", "order total", "pay now", "subscribe",
            "credit card", "debit card", "wallet", "in-app purchase",
        )
        val matched = paymentKeywords.any { lower.contains(it) }
        return if (matched) Pair(true, "Payment/purchase screen detected") else Pair(false, "")
    }

    fun checkAuthenticationScreen(screen: ScreenState): Pair<Boolean, String> {
        val lower = screen.toTextSummary().lowercase()
        val authKeywords = listOf(
            "password", "passcode", "pin", "sign in", "log in", "login",
            "verify your identity", "two-factor", "2fa", "authenticate",
            "biometric", "fingerprint", "face id", "enter your password",
        )
        val matched = authKeywords.any { lower.contains(it) }
        return if (matched) Pair(true, "Authentication/password screen detected") else Pair(false, "")
    }

    fun checkPremiumCurrency(params: Map<String, Any?>): Pair<Boolean, String> {
        val lower = params.entries.joinToString(" ") { "${it.key}=${it.value}" }.lowercase()
        val premiumKeywords = listOf(
            "gem", "gems", "diamond", "diamonds", "crystal", "crystals",
            "premium currency", "gold bar", "voucher", "ticket", "spend gem",
            "use gem", "buy with gem", "purchase with",
        )
        val matched = premiumKeywords.any { lower.contains(it) }
        return if (matched) Pair(true, "Action would spend premium currency (gems/coins)") else Pair(false, "")
    }

    fun checkAllyTarget(params: Map<String, Any?>, worldState: WorldState): Pair<Boolean, String> {
        val targetText = (params["text"] as? String ?: params["target"] as? String ?: "").lowercase()
        val allies = worldState.heroes.map { it.lowercase() }.toSet() +
                listOf("ally", "friend", "teammate", "guild member", "clan mate", "partner")
        if (targetText.isBlank()) return Pair(false, "")
        val isAlly = allies.any { targetText.contains(it) || it.contains(targetText) }
        return if (isAlly) Pair(true, "Action would target an ally: $targetText") else Pair(false, "")
    }

    fun checkDestructiveAction(params: Map<String, Any?>): Pair<Boolean, String> {
        val lower = params.entries.joinToString(" ") { "${it.key}=${it.value}" }.lowercase()
        val destructiveKeywords = listOf(
            "sell", "delete", "discard", "dismantle", "destroy", "scrap",
            "remove", "trash", "sacrifice", "consume",
        )
        val rareKeywords = listOf(
            "rare", "epic", "legendary", "mythic", "unique", "special",
            "limited", "event item", "exclusive",
        )
        val hasDestructive = destructiveKeywords.any { lower.contains(it) }
        val hasRare = rareKeywords.any { lower.contains(it) }
        return if (hasDestructive && hasRare) {
            Pair(true, "Destructive action on rare/valuable item detected")
        } else {
            Pair(false, "")
        }
    }

    fun checkRateLimit(toolName: String): Pair<Boolean, String> {
        val failures = failureCounts[toolName] ?: 0
        return if (failures >= maxConsecutiveFailures) {
            Pair(true, "Rate limit exceeded: $failures consecutive failures for $toolName")
        } else {
            Pair(false, "")
        }
    }

    fun recordFailure(toolName: String) {
        failureCounts[toolName] = (failureCounts[toolName] ?: 0) + 1
    }

    fun recordSuccess(toolName: String) {
        failureCounts.remove(toolName)
    }

    fun checkPlayTimeLimit(startTimeMs: Long = playSessionStartMs): Pair<Boolean, String> {
        val elapsed = System.currentTimeMillis() - startTimeMs
        return if (elapsed >= allowedPlayTimeMs) {
            Pair(true, "Play time limit exceeded: ${elapsed / 60000} minutes played")
        } else {
            Pair(false, "")
        }
    }

    fun checkAppIntegrity(packageName: String): Pair<Boolean, String> {
        if (packageName.isBlank()) return Pair(true, "No game package specified")
        val blockedPatterns = listOf(
            "com.android.settings", "com.google.android.gm",
            "com.android.vending", "com.google.android.apps.docs",
        )
        return if (packageName in blockedPatterns) {
            Pair(true, "Blocked package: $packageName")
        } else {
            Pair(false, "")
        }
    }

    fun checkScreenSensitivity(screen: ScreenState): Pair<Boolean, String> {
        val lower = screen.toTextSummary().lowercase()
        val sensitiveKeywords = listOf(
            "privacy", "private message", "direct message", "dm",
            "personal information", "phone number", "email address",
            "home address", "location sharing",
        )
        val matched = sensitiveKeywords.any { lower.contains(it) }
        return if (matched) Pair(true, "Privacy-sensitive screen detected") else Pair(false, "")
    }

    fun checkAccountSettings(screen: ScreenState): Pair<Boolean, String> {
        val lower = screen.toTextSummary().lowercase()
        val accountKeywords = listOf(
            "account settings", "delete account", "reset progress",
            "clear data", "factory reset", "wipe data", "unlink account",
        )
        val matched = accountKeywords.any { lower.contains(it) }
        return if (matched) Pair(true, "Account settings / data wipe screen detected") else Pair(false, "")
    }

    fun checkNetworkIntegrity(screen: ScreenState): Pair<Boolean, String> {
        val lower = screen.toTextSummary().lowercase()
        val networkKeywords = listOf(
            "no connection", "network error", "reconnect", "offline",
        )
        return if (networkKeywords.any { lower.contains(it) }) {
            Pair(true, "Network connectivity issue detected on screen")
        } else {
            Pair(false, "")
        }
    }

    fun checkPopupSpam(screen: ScreenState, consecutivePopups: Int): Pair<Boolean, String> {
        return if (consecutivePopups >= 10) {
            Pair(true, "Excessive popup spam: $consecutivePopups consecutive popups detected")
        } else {
            Pair(false, "")
        }
    }

    fun checkExternalAppLaunch(params: Map<String, Any?>): Pair<Boolean, String> {
        val target = (params["package_name"] as? String ?: params["uri"] as? String ?: "").lowercase()
        if (target.isBlank()) return Pair(false, "")
        val blockedExternalApps = listOf(
            "com.android.settings", "com.android.chrome", "com.android.browser",
            "com.google.android.youtube", "com.instagram.android",
        )
        val matched = blockedExternalApps.any { target.contains(it) }
        return if (matched) Pair(true, "Blocked external app launch: $target") else Pair(false, "")
    }

    fun checkAll(
        screen: ScreenState,
        worldState: WorldState,
        params: Map<String, Any?>,
        toolName: String,
        gamePackage: String = "",
        consecutivePopups: Int = 0,
    ): List<Pair<Boolean, String>> {
        return listOf(
            checkPaymentScreen(screen),
            checkAuthenticationScreen(screen),
            checkPremiumCurrency(params),
            checkAllyTarget(params, worldState),
            checkDestructiveAction(params),
            checkRateLimit(toolName),
            checkPlayTimeLimit(),
            checkAppIntegrity(gamePackage),
            checkScreenSensitivity(screen),
            checkAccountSettings(screen),
            checkNetworkIntegrity(screen),
            checkPopupSpam(screen, consecutivePopups),
            checkExternalAppLaunch(params),
        )
    }

    fun resetRateLimit(toolName: String) {
        failureCounts.remove(toolName)
    }

    fun resetPlaySession() {
        playSessionStartMs = System.currentTimeMillis()
        failureCounts.clear()
    }
}
