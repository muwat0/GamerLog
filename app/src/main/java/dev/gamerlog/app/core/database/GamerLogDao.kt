package dev.gamerlog.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GamerLogDao {

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
