package dev.hushyari.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.hushyari.data.local.entities.GameConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: GameConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<GameConfigEntity>)

    @Query("UPDATE game_configs SET config_json = :configJson, updated_at = :updatedAt WHERE package_name = :packageName")
    suspend fun updateConfig(packageName: String, configJson: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(config: GameConfigEntity)

    @Query("DELETE FROM game_configs WHERE package_name = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("SELECT * FROM game_configs WHERE package_name = :packageName LIMIT 1")
    suspend fun getForPackage(packageName: String): GameConfigEntity?

    @Query("SELECT * FROM game_configs ORDER BY updated_at DESC")
    fun getAll(): Flow<List<GameConfigEntity>>

    @Query("SELECT * FROM game_configs WHERE game_name LIKE '%' || :query || '%' OR package_name LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<GameConfigEntity>

    @Query("SELECT COUNT(*) FROM game_configs")
    suspend fun count(): Int
}
