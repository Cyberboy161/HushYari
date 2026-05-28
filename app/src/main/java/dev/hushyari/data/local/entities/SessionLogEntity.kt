package dev.hushyari.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_logs")
data class SessionLogEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "game_package")
    val gamePackage: String = "",

    @ColumnInfo(name = "task_description")
    val taskDescription: String = "",

    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,

    @ColumnInfo(name = "status")
    val status: String = "PENDING",

    @ColumnInfo(name = "total_steps")
    val totalSteps: Int = 0,

    @ColumnInfo(name = "log_json")
    val logJson: String = "",
)
