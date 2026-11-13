package dev.gamerlog.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class GamePlaytime(
    val packageName: String,
    val appName: String,
    val customTitle: String?,
    val totalDurationMs: Long
)

data class ActivitySessionInterval(
    val startInclusiveMs: Long,
    val endExclusiveMs: Long
)


@Dao
interface GamerLogDao {

    @Query(
        """
        SELECT play_sessions.packageName AS packageName,
               games.appName AS appName,
               games.customTitle AS customTitle,
               SUM(
                   MIN(play_sessions.endTime, :endExclusiveMs) -
                   MAX(play_sessions.startTime, :startInclusiveMs)
               ) AS totalDurationMs
        FROM play_sessions
        INNER JOIN games ON games.packageName = play_sessions.packageName
        WHERE play_sessions.startTime < :endExclusiveMs
          AND play_sessions.endTime > :startInclusiveMs
        GROUP BY play_sessions.packageName, games.appName, games.customTitle
        ORDER BY totalDurationMs DESC,
                 play_sessions.packageName ASC,
                 games.appName ASC,
                 games.customTitle ASC
        """
    )
    fun observeGamePlaytime(
        startInclusiveMs: Long,
        endExclusiveMs: Long
    ): Flow<List<GamePlaytime>>

    @Query(
        """
        SELECT MAX(play_sessions.startTime, :startInclusiveMs) AS startInclusiveMs,
               MIN(play_sessions.endTime, :endExclusiveMs) AS endExclusiveMs
        FROM play_sessions
        WHERE play_sessions.startTime < :endExclusiveMs
          AND play_sessions.endTime > :startInclusiveMs
        """
    )
    fun observeActivitySessionIntervals(
        startInclusiveMs: Long,
        endExclusiveMs: Long
    ): Flow<List<ActivitySessionInterval>>

    @Query("SELECT * FROM games ORDER BY appName ASC")
    fun observeGames(): Flow<List<GameEntity>>

    @Query("SELECT packageName FROM games WHERE isTracked = 1")
    suspend fun getTrackedPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscoveredGame(game: GameEntity): Long

    @Query("UPDATE games SET isTracked = :isTracked WHERE packageName = :packageName")
    suspend fun setTracked(packageName: String, isTracked: Boolean)

    @Query("SELECT * FROM scan_checkpoints WHERE `key` = :key LIMIT 1")
    suspend fun getCheckpoint(key: String = ScanCheckpointEntity.KEY_USAGE_EVENTS): ScanCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: ScanCheckpointEntity)

    @Query("SELECT * FROM open_sessions")
    suspend fun getOpenSessions(): List<OpenSessionEntity>

    @Query("DELETE FROM open_sessions")
    suspend fun clearOpenSessions()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpenSessions(openSessions: List<OpenSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessions(sessions: List<PlaySessionEntity>): List<Long>
}
