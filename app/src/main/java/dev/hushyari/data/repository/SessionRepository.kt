package dev.hushyari.data.repository

import dev.hushyari.data.local.dao.SessionLogDao
import dev.hushyari.data.local.entities.SessionLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SessionLog(
    val id: String = UUID.randomUUID().toString(),
    val gamePackage: String = "",
    val taskDescription: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val status: SessionStatus = SessionStatus.PENDING,
    val totalSteps: Int = 0,
    val logEntries: List<String> = emptyList(),
)

enum class SessionStatus {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED;

    companion object {
        fun fromString(value: String): SessionStatus = try {
            valueOf(value)
        } catch (_: Exception) {
            PENDING
        }
    }
}

@Singleton
class SessionRepository @Inject constructor(
    private val dao: SessionLogDao,
) {

    fun getRecentSessions(limit: Int = 50): Flow<List<SessionLog>> =
        dao.getRecent(limit).mapToDomain()

    fun getAllSessions(): Flow<List<SessionLog>> =
        dao.getAll().mapToDomain()

    fun getSessionsForGame(packageName: String): Flow<List<SessionLog>> =
        dao.getForGame(packageName).mapToDomain()

    suspend fun saveSession(session: SessionLog) {
        val entity = SessionLogEntity(
            id = session.id,
            gamePackage = session.gamePackage,
            taskDescription = session.taskDescription,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            status = session.status.name,
            totalSteps = session.totalSteps,
            logJson = session.logEntries.joinToString("\n"),
        )
        dao.insert(entity)
    }

    suspend fun updateSessionStatus(
        id: String,
        status: SessionStatus,
        endedAt: Long? = System.currentTimeMillis(),
        totalSteps: Int = 0,
    ) {
        dao.updateStatus(id, status.name, endedAt, totalSteps)
    }

    suspend fun deleteSession(id: String) {
        dao.deleteById(id)
    }

    suspend fun clearOldSessions(keepDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - (keepDays * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }

    private fun Flow<List<SessionLogEntity>>.mapToDomain(): Flow<List<SessionLog>> = map { entities ->
        entities.map { it.toDomain() }
    }

    private fun SessionLogEntity.toDomain(): SessionLog = SessionLog(
        id = id,
        gamePackage = gamePackage,
        taskDescription = taskDescription,
        startedAt = startedAt,
        endedAt = endedAt,
        status = SessionStatus.fromString(status),
        totalSteps = totalSteps,
        logEntries = if (logJson.isBlank()) emptyList() else logJson.split("\n"),
    )
}
