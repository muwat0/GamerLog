package dev.gamerlog.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "play_sessions",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["packageName"],
            childColumns = ["packageName"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("packageName"),
        Index("startTime"),
        Index("syncStatus"),
        Index(value = ["packageName", "startTime", "endTime"], unique = true)
    ]
)
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val syncStatus: Int = 0
)
