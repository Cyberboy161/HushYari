package dev.hushyari.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.hushyari.data.local.entities.SessionLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SessionLogEntity>)

    @Query("UPDATE session_logs SET ended_at = :endedAt, status = :status, total_steps = :totalSteps WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, endedAt: Long? = System.currentTimeMillis(), totalSteps: Int = 0)

    @Query("DELETE FROM session_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM session_logs ORDER BY started_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<SessionLogEntity>>

    @Query("SELECT * FROM session_logs ORDER BY started_at DESC")
    fun getAll(): Flow<List<SessionLogEntity>>

    @Query("SELECT * FROM session_logs WHERE game_package = :gamePackage ORDER BY started_at DESC")
    fun getForGame(gamePackage: String): Flow<List<SessionLogEntity>>

    @Query("DELETE FROM session_logs")
    suspend fun deleteAll()

    @Query("DELETE FROM session_logs WHERE started_at < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    @Query("SELECT COUNT(*) FROM session_logs")
    suspend fun count(): Int
}
