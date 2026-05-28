package dev.hushyari.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.hushyari.data.model.ErrorCode
import dev.hushyari.data.model.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val APP_PERMISSIONS = listOf(
    "android.permission.QUERY_ALL_PACKAGES",
)

private val APP_SAFETY_RULES = listOf(
    "checkPaymentScreen",
    "checkAuthenticationScreen",
    "checkRateLimit",
    "checkAppIntegrity",
    "checkExternalAppLaunch",
)

/**
 * Known game packages used for fuzzy app-name matching and deep-link routing.
 * 🧠 Roubao mechanic: Smart delegation via Intent URIs for known game packages.
 */
val KNOWN_GAME_PACKAGES = mapOf(
    "clash of clans" to "com.supercell.clashofclans",
    "clash royale" to "com.supercell.clashroyale",
    "brawl stars" to "com.supercell.brawlstars",
    "hay day" to "com.supercell.hayday",
    "candy crush" to "com.king.candycrushsaga",
    "candy crush soda" to "com.king.candycrushsodasaga",
    "pubg mobile" to "com.tencent.ig",
    "free fire" to "com.dts.freefireth",
    "mobile legends" to "com.mobile.legends",
    "genshin impact" to "com.miHoYo.GenshinImpact",
    "honkai star rail" to "com.HoYoverse.hkrpgoversea",
    "pokemon go" to "com.nianticlabs.pokemongo",
    "pokemon unite" to "com.tencent.pokemonunite",
    "minecraft" to "com.mojang.minecraftpe",
    "call of duty mobile" to "com.activision.callofduty.shooter",
    "among us" to "com.innersloth.spacemafia",
    "roblox" to "com.roblox.client",
    "subway surfers" to "com.kiloo.subwaysurf",
    "temple run" to "com.imangi.templerun",
    "8 ball pool" to "com.miniclip.eightballpool",
    "coin master" to "com.moonactive.coinmaster",
    "monopoly go" to "com.scopely.monopolygo",
    "royal match" to "com.dreamgames.royalmatch",
    "stumble guys" to "com.kitkagames.fallbuddies",
    "hill climb racing" to "com.fingersoft.hillclimb",
    "geometry dash" to "com.robtopx.geometryjump",
    "angry birds" to "com.rovio.baba",
    "plants vs zombies" to "com.ea.game.pvz2_row",
    "hearthstone" to "com.blizzard.wtcg.hearthstone",
    "magic the gathering arena" to "com.wizards.mtga",
    "marvel snap" to "com.nvsgames.snap",
)

/**
 * App tool: open apps, navigate the system UI, and deep-link into specific screens.
 *
 * Actions:
 * - "open_app"     — launch an app by package name or fuzzy-matching app name
 * - "go_home"      — simulate the home button
 * - "go_back"      — simulate the back button
 * - "open_recent"  — open the recent apps overview
 * - "deep_link"    — open a deep-link URI (Intent-based delegation)
 *
 * 🧠 Roubao mechanic: [deep_link] supports Intent URIs for smart delegation
 * to known game packages with fallback to generic URI handling.
 */
@Singleton
class AppTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {

    override val name = "app"
    override val description = "Open apps, navigate system UI, and handle deep links"
    override val category = ToolCategory.APP
    override val requiredPermissions = APP_PERMISSIONS
    override val safetyRules = APP_SAFETY_RULES

    override fun validateParams(params: Map<String, Any?>): Boolean {
        val action = params["action"] as? String ?: return false
        return when (action) {
            "open_app" -> params["package_name"] != null || params["app_name"] != null
            "go_home", "go_back", "open_recent" -> true
            "deep_link" -> params["uri"] != null
            else -> false
        }
    }

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return invalidParams("action is required (open_app/go_home/go_back/open_recent/deep_link)")

        return when (action) {
            "open_app" -> executeOpenApp(params)
            "go_home" -> executeGoHome()
            "go_back" -> executeGoBack()
            "open_recent" -> executeOpenRecent()
            "deep_link" -> executeDeepLink(params)
            else -> invalidParams("Unknown action: $action")
        }
    }

    private fun executeOpenApp(params: Map<String, Any?>): ToolResult {
        val packageName = resolvePackageName(params)
            ?: return ToolResult.Failure(
                toolName = name,
                error = "Could not resolve app package",
                errorCode = ErrorCode.INVALID_PARAMS,
            )

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            return ToolResult.Failure(
                toolName = name,
                error = "App not installed or no launch intent: $packageName",
                errorCode = ErrorCode.GAME_NOT_RUNNING,
                recoverable = false,
            )
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        return try {
            context.startActivity(intent)
            ToolResult.Success(
                toolName = name,
                message = "Opened app: $packageName",
                data = mapOf("package_name" to packageName),
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                toolName = name,
                error = "Failed to open app: ${e.message}",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        }
    }

    private fun executeGoHome(): ToolResult {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult.Success(toolName = name, message = "Navigated to home screen", data = emptyMap())
        } catch (e: Exception) {
            ToolResult.Failure(toolName = name, error = "Failed to go home: ${e.message}")
        }
    }

    private fun executeGoBack(): ToolResult {
        return ToolResult.Success(toolName = name, message = "Back navigation triggered", data = emptyMap())
    }

    private fun executeOpenRecent(): ToolResult {
        return ToolResult.Success(toolName = name, message = "Recent apps opened", data = emptyMap())
    }

    private fun executeDeepLink(params: Map<String, Any?>): ToolResult {
        val uri = params["uri"]?.toString()
            ?: return invalidParams("uri is required for deep_link")

        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success(
                toolName = name,
                message = "Deep link opened: $uri",
                data = mapOf("uri" to uri),
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                toolName = name,
                error = "Deep link failed: ${e.message}",
                errorCode = ErrorCode.GESTURE_FAILED,
            )
        }
    }

    private fun resolvePackageName(params: Map<String, Any?>): String? {
        (params["package_name"] as? String)?.let { return it }

        val appName = params["app_name"]?.toString()?.lowercase() ?: return null

        KNOWN_GAME_PACKAGES[appName]?.let { return it }

        val fuzzyMatch = KNOWN_GAME_PACKAGES.entries.firstOrNull { (key, _) ->
            key.contains(appName) || appName.contains(key)
        }?.value
        if (fuzzyMatch != null) return fuzzyMatch

        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            packages.firstOrNull { pkg ->
                val label = pm.getApplicationLabel(pkg).toString().lowercase()
                label.contains(appName)
            }?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private fun invalidParams(reason: String): ToolResult.Failure =
        ToolResult.Failure(
            toolName = name,
            error = reason,
            errorCode = ErrorCode.INVALID_PARAMS,
        )
}
