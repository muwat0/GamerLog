package dev.gamerlog.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backloggd_settings")
data class BackloggdSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val username: String? = null,
    val enabled: Boolean = false,
    val enabledSinceMs: Long = 0L
)
