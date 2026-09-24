package dev.gamerlog.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val customTitle: String? = null,
    val isTracked: Boolean = true,
    val isAutoDetected: Boolean = false,
    val backloggdSlug: String? = null,
    val iconUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
