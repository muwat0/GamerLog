package dev.gamerlog.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "backloggd_exports",
    foreignKeys = [
        ForeignKey(
            entity = PlaySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["marker"], unique = true),
        Index(value = ["accountUsername", "state"])
    ]
)
data class BackloggdExportEntity(
    @PrimaryKey
    val sessionId: Long,
    val accountUsername: String,
    val gameSlug: String,
    val gameId: String? = null,
    val playedOn: String,
    val durationMs: Long,
    val marker: String,
    val state: ExportState = ExportState.WAITING,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val remoteEntryId: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

enum class ExportState {
    WAITING,
    SENDING,
    SENT,
    EXCLUDED,
    FAILED,
    UNCERTAIN
}
