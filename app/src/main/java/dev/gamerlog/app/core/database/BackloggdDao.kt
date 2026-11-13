package dev.gamerlog.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A local session plus its explicitly mapped Backloggd game. */
data class EligibleExportSession(
    val sessionId: Long,
    val packageName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val gameSlug: String
)

@Dao
interface BackloggdDao {

    @Query("SELECT * FROM backloggd_settings WHERE id = 1")
    fun observeSettings(): Flow<BackloggdSettingsEntity?>

    @Query("SELECT * FROM backloggd_settings WHERE id = 1")
    suspend fun getSettings(): BackloggdSettingsEntity?

    @Upsert
    suspend fun saveSettings(settings: BackloggdSettingsEntity)

    @Query("UPDATE backloggd_settings SET enabled = 0 WHERE id = 1 AND enabled = 1")
    suspend fun disableExports(): Int

    @Query(
        """
        SELECT play_sessions.id AS sessionId,
               play_sessions.packageName AS packageName,
               play_sessions.startTime AS startTime,
               play_sessions.endTime AS endTime,
               play_sessions.durationMs AS durationMs,
               games.backloggdSlug AS gameSlug
        FROM play_sessions
        INNER JOIN games ON games.packageName = play_sessions.packageName
        WHERE play_sessions.startTime >= :sinceMs
          AND play_sessions.durationMs > 0
          AND games.backloggdSlug IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM backloggd_exports
              WHERE backloggd_exports.sessionId = play_sessions.id
          )
        ORDER BY play_sessions.startTime ASC, play_sessions.id ASC
        """
    )
    suspend fun eligibleSessions(sinceMs: Long): List<EligibleExportSession>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExports(exports: List<BackloggdExportEntity>): List<Long>

    @Query("SELECT * FROM backloggd_exports ORDER BY createdAtMs DESC, sessionId DESC")
    fun observeExports(): Flow<List<BackloggdExportEntity>>

    @Query(
        """
        SELECT * FROM backloggd_exports
        WHERE accountUsername = :username
        ORDER BY createdAtMs ASC, sessionId ASC
        """
    )
    suspend fun getExportsForAccount(username: String): List<BackloggdExportEntity>

    @Query("SELECT * FROM backloggd_exports WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getExport(sessionId: Long): BackloggdExportEntity?

    @Query(
        """
        UPDATE backloggd_exports
        SET state = :newState,
            updatedAtMs = :updatedAtMs,
            lastError = :lastError
        WHERE sessionId = :sessionId
          AND state = :expectedState
          AND state != 'SENT'
          AND :newState != 'SENT'
          AND NOT (
              :newState = 'WAITING'
              AND state IN ('SENT', 'SENDING', 'UNCERTAIN')
          )
        """
    )
    suspend fun transition(
        sessionId: Long,
        expectedState: ExportState,
        newState: ExportState,
        updatedAtMs: Long,
        lastError: String?
    ): Int

    @Query(
        """
        UPDATE backloggd_exports
        SET gameId = :gameId, updatedAtMs = :updatedAtMs
        WHERE sessionId = :sessionId
          AND state = 'WAITING'
          AND gameId IS NULL
        """
    )
    suspend fun setPreparedGameId(sessionId: Long, gameId: String, updatedAtMs: Long): Int

    @Query(
        """
        UPDATE backloggd_exports
        SET state = 'SENT', remoteEntryId = :remoteEntryId,
            updatedAtMs = :updatedAtMs, lastError = NULL
        WHERE sessionId = :sessionId
          AND state IN ('SENDING', 'UNCERTAIN')
        """
    )
    suspend fun markSent(sessionId: Long, remoteEntryId: String, updatedAtMs: Long): Int

    @Query(
        """
        UPDATE backloggd_exports
        SET attemptCount = attemptCount + 1, updatedAtMs = :updatedAtMs
        WHERE sessionId = :sessionId AND state = 'SENDING'
        """
    )
    suspend fun incrementAttempt(sessionId: Long, updatedAtMs: Long): Int

    @Query(
        """
        UPDATE backloggd_exports
        SET state = 'EXCLUDED', updatedAtMs = :updatedAtMs, lastError = NULL
        WHERE sessionId = :sessionId AND state IN ('WAITING', 'FAILED')
        """
    )
    suspend fun exclude(sessionId: Long, updatedAtMs: Long): Int

    @Query(
        """
        UPDATE backloggd_exports
        SET state = 'WAITING', updatedAtMs = :updatedAtMs, lastError = NULL
        WHERE sessionId = :sessionId AND state IN ('FAILED', 'EXCLUDED')
        """
    )
    suspend fun retry(sessionId: Long, updatedAtMs: Long): Int

    @Query("UPDATE games SET backloggdSlug = :slug WHERE packageName = :packageName")
    suspend fun setGameMapping(packageName: String, slug: String?): Int
}
