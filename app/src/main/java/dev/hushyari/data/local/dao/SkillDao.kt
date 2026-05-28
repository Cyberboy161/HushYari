package dev.hushyari.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.hushyari.data.local.entities.SkillEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: SkillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(skills: List<SkillEntity>)

    @Query("UPDATE skills SET skill_json = :skillJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSkill(id: String, skillJson: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM skills WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE game_package = :gamePackage OR game_package IS NULL ORDER BY updated_at DESC")
    fun getForGame(gamePackage: String): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY updated_at DESC")
    fun getAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE skill_json LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<SkillEntity>

    @Query("SELECT COUNT(*) FROM skills")
    suspend fun count(): Int
}
