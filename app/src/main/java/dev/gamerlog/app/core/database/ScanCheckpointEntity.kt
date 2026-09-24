package dev.gamerlog.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_checkpoints")
data class ScanCheckpointEntity(
    @PrimaryKey
    val key: String = KEY_USAGE_EVENTS,
    val timestampMs: Long
) {
    companion object {
        const val KEY_USAGE_EVENTS = "usage_events"
    }
}
