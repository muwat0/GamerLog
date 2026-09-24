package dev.gamerlog.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "open_sessions")
data class OpenSessionEntity(
    @PrimaryKey
    val packageName: String,
    val startTime: Long
)
