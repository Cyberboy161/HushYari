package dev.hushyari.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hushyari.data.local.dao.GameConfigDao
import dev.hushyari.data.local.entities.GameConfigEntity
import dev.hushyari.statemachine.GameConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isGame: Boolean = false,
)

private val KNOWN_GAME_PACKAGES = setOf(
    "com.supercell.clashofclans",
    "com.supercell.clashroyale",
    "com.supercell.brawlstars",
    "com.supercell.boombeach",
    "com.supercell.hayday",
    "com.supercell.clashquest",
    "com.supercell.squad",
    "com.lilithgame.roc.gp",
    "com.igg.android.lordsmobile",
    "com.topwar.android",
    "com.longtech.lastwars.gp",
    "com.mobile.legends",
    "com.miHoYo.GenshinImpact",
    "com.miHoYo.HoKAI3rd",
    "com.tencent.ig",
    "com.pubg.imobile",
    "com.epicgames.fortnite",
    "com.roblox.client",
    "com.mojang.minecraftpe",
    "com.nianticlabs.pokemongo",
    "com.netflix.NGP.GameController",
    "com.ea.game.pvz2_row",
    "com.king.candycrushsaga",
    "com.ea.gp.fifamobile",
    "com.scopely.monopolygo",
)

@Singleton
class GameRepository @Inject constructor(
    private val dao: GameConfigDao,
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun getGameConfigs(): Flow<List<GameConfig>> = dao.getAll().map { entities ->
        entities.mapNotNull { parseConfig(it) }
    }

    suspend fun getGameConfig(packageName: String): GameConfig? {
        val entity = dao.getForPackage(packageName) ?: return null
        return parseConfig(entity)
    }

    suspend fun saveGameConfig(config: GameConfig) {
        val entity = GameConfigEntity(
            packageName = config.gamePackage,
            gameName = config.gameName,
            configJson = json.encodeToString(GameConfig.serializer(), config),
            updatedAt = System.currentTimeMillis(),
        )
        val existing = dao.getForPackage(config.gamePackage)
        if (existing != null) {
            dao.insert(entity.copy(createdAt = existing.createdAt))
        } else {
            dao.insert(entity)
        }
    }

    suspend fun deleteGameConfig(packageName: String) {
        dao.deleteByPackage(packageName)
    }

    suspend fun getInstalledGames(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchableActivities = pm.queryIntentActivities(launchIntent, 0)
        val launchablePackages = launchableActivities.map { it.activityInfo.packageName }.toSet()

        val gameIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MARKET)
        }
        val categoryGamePackages = try {
            val catIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory("android.intent.category.GAME")
            }
            pm.queryIntentActivities(catIntent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val rawPackages = pm.getInstalledPackages(0)
            .filter { it.packageName in launchablePackages }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }

        rawPackages.map { pkg ->
            val pn = pkg.packageName
            val appInfo = pkg.applicationInfo ?: return@map null
            val isGame = pn in categoryGamePackages ||
                pn in KNOWN_GAME_PACKAGES ||
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0

            AppInfo(
                packageName = pn,
                appName = pm.getApplicationLabel(appInfo).toString(),
                isGame = isGame,
            )
        }
            .filterNotNull()
            .sortedWith(compareByDescending<AppInfo> { it.isGame }.thenBy { it.appName.lowercase() })
    }

    suspend fun importConfig(jsonString: String): GameConfig? {
        return try {
            val config = json.decodeFromString(GameConfig.serializer(), jsonString)
            saveGameConfig(config)
            config
        } catch (_: Exception) {
            null
        }
    }

    suspend fun exportConfig(packageName: String): String? {
        val config = getGameConfig(packageName) ?: return null
        return json.encodeToString(GameConfig.serializer(), config)
    }

    private fun parseConfig(entity: GameConfigEntity): GameConfig? {
        return try {
            json.decodeFromString(GameConfig.serializer(), entity.configJson)
        } catch (_: Exception) {
            null
        }
    }
}
