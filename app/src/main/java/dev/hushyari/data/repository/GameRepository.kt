package dev.hushyari.data.repository

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hushyari.data.local.dao.GameConfigDao
import dev.hushyari.data.local.entities.GameConfigEntity
import dev.hushyari.statemachine.GameConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isGame: Boolean = false,
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

    fun getInstalledGames(): List<AppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return packages.mapNotNull { app ->
            if (app.packageName == context.packageName) return@mapNotNull null

            val isGame = app.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0 ||
                    app.packageName.contains("game", ignoreCase = true) ||
                    app.packageName.contains("play", ignoreCase = true)

            AppInfo(
                packageName = app.packageName,
                appName = pm.getApplicationLabel(app).toString(),
                isGame = isGame,
            )
        }.sortedByDescending { it.isGame }
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
