package dev.gamerlog.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class GamerLogDaoTest {

    private lateinit var db: GamerLogDatabase
    private lateinit var gamerLogDao: GamerLogDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            GamerLogDatabase::class.java
        ).build()
        gamerLogDao = db.dao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun writeGameAndObserveList() = runBlocking {
        val game1 = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale",
            isTracked = true,
            isAutoDetected = true
        )
        val game2 = GameEntity(
            packageName = "com.mojang.minecraftpe",
            appName = "Minecraft",
            isTracked = false,
            isAutoDetected = false
        )

        val id1 = gamerLogDao.insertDiscoveredGame(game1)
        val id2 = gamerLogDao.insertDiscoveredGame(game2)

        assertTrue("First insert should return valid row id", id1 > 0)
        assertTrue("Second insert should return valid row id", id2 > 0)

        val games = gamerLogDao.observeGames().first()
        assertEquals(2, games.size)
        // observeGames orders by appName ASC: "Clash Royale" then "Minecraft"
        assertEquals("com.supercell.clashroyale", games[0].packageName)
        assertEquals("Clash Royale", games[0].appName)
        assertTrue(games[0].isTracked)

        assertEquals("com.mojang.minecraftpe", games[1].packageName)
        assertEquals("Minecraft", games[1].appName)
        assertFalse(games[1].isTracked)

        val tracked = gamerLogDao.getTrackedPackageNames()
        assertEquals(1, tracked.size)
        assertEquals("com.supercell.clashroyale", tracked[0])
    }

    @Test
    fun testDuplicateSessionsIgnored_preservesSingleRecord() = runBlocking {
        val game = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale",
            isTracked = true
        )
        gamerLogDao.insertDiscoveredGame(game)

        val session = PlaySessionEntity(
            packageName = "com.supercell.clashroyale",
            startTime = 1000L,
            endTime = 2000L,
            durationMs = 1000L,
        )

        // Initial insert should succeed
        val firstInsert = gamerLogDao.insertSessions(listOf(session))
        assertEquals(1, firstInsert.size)
        assertTrue("First insertion should produce valid row id", firstInsert[0] != -1L)

        // Attempt duplicate insertion with identical (packageName, startTime, endTime)
        val duplicateInsert = gamerLogDao.insertSessions(listOf(session))
        assertEquals(1, duplicateInsert.size)
        assertEquals(-1L, duplicateInsert[0]) // OnConflictStrategy.IGNORE returns -1L

        // Verify duplicate is ignored by checking SQLite row count via openHelper.writableDatabase
        val count = querySessionCount("com.supercell.clashroyale")
        assertEquals(1, count)

        // Batch containing an existing duplicate and a new unique session
        val newSession = PlaySessionEntity(
            packageName = "com.supercell.clashroyale",
            startTime = 3000L,
            endTime = 4000L,
            durationMs = 1000L,
        )
        val batchInsert = gamerLogDao.insertSessions(listOf(session, newSession))
        assertEquals(2, batchInsert.size)
        assertEquals(-1L, batchInsert[0])
        assertTrue("New unique session in batch should be inserted", batchInsert[1] != -1L)

        val finalCount = querySessionCount("com.supercell.clashroyale")
        assertEquals(2, finalCount)
    }

    @Test
    fun testTransactionRollback_keepsCheckpointOpenSessionAndPlaySessionStateUnchanged() = runBlocking {
        val game = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale",
            isTracked = true
        )
        gamerLogDao.insertDiscoveredGame(game)

        val initialCheckpoint = ScanCheckpointEntity(
            key = ScanCheckpointEntity.KEY_USAGE_EVENTS,
            timestampMs = 10000L
        )
        gamerLogDao.saveCheckpoint(initialCheckpoint)

        val initialOpenSession = OpenSessionEntity(
            packageName = "com.supercell.clashroyale",
            startTime = 10000L
        )
        gamerLogDao.insertOpenSessions(listOf(initialOpenSession))

        val initialPlaySession = PlaySessionEntity(
            packageName = "com.supercell.clashroyale",
            startTime = 1000L,
            endTime = 5000L,
            durationMs = 4000L,
        )
        val initialSessionIds = gamerLogDao.insertSessions(listOf(initialPlaySession))
        assertEquals(1, initialSessionIds.size)
        assertTrue(initialSessionIds[0] != -1L)

        // Execute transaction that modifies checkpoint, open_sessions, and play_sessions, then fails
        var caughtException = false
        try {
            db.withTransaction {
                gamerLogDao.saveCheckpoint(
                    ScanCheckpointEntity(
                        key = ScanCheckpointEntity.KEY_USAGE_EVENTS,
                        timestampMs = 99999L
                    )
                )
                gamerLogDao.clearOpenSessions()
                gamerLogDao.insertOpenSessions(
                    listOf(OpenSessionEntity(packageName = "com.supercell.clashroyale", startTime = 88888L))
                )
                gamerLogDao.insertSessions(
                    listOf(
                        PlaySessionEntity(
                            packageName = "com.supercell.clashroyale",
                            startTime = 6000L,
                            endTime = 7000L,
                            durationMs = 1000L,
                        )
                    )
                )
                throw IllegalStateException("Simulated transaction failure for rollback verification")
            }
            fail("Transaction block should have aborted and thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            caughtException = true
            assertEquals("Simulated transaction failure for rollback verification", e.message)
        }

        assertTrue("Expected transaction failure must be caught", caughtException)

        // Invariant 1: Checkpoint timestamp remains unchanged
        val checkpointAfterRollback = gamerLogDao.getCheckpoint(ScanCheckpointEntity.KEY_USAGE_EVENTS)
        assertNotNull(checkpointAfterRollback)
        assertEquals(10000L, checkpointAfterRollback?.timestampMs)

        // Invariant 2: Open sessions remain unchanged
        val openSessionsAfterRollback = gamerLogDao.getOpenSessions()
        assertEquals(1, openSessionsAfterRollback.size)
        assertEquals("com.supercell.clashroyale", openSessionsAfterRollback[0].packageName)
        assertEquals(10000L, openSessionsAfterRollback[0].startTime)

        // Invariant 3: Play sessions table remains unchanged (only initial session exists)
        val sessionCountAfterRollback = querySessionCount("com.supercell.clashroyale")
        assertEquals(1, sessionCountAfterRollback)
    }

    @Test
    fun testInsertDiscoveredGame_preservesUserTrackedChoice() = runBlocking {
        // Discovered game initially tracked
        val initialGame = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale",
            isTracked = true,
            isAutoDetected = true
        )
        val initialRowId = gamerLogDao.insertDiscoveredGame(initialGame)
        assertTrue(initialRowId > 0)

        // User explicitly toggles tracking OFF
        gamerLogDao.setTracked("com.supercell.clashroyale", false)

        val trackedBeforeRescan = gamerLogDao.getTrackedPackageNames()
        assertFalse(trackedBeforeRescan.contains("com.supercell.clashroyale"))

        val gamesBeforeRescan = gamerLogDao.observeGames().first()
        val gameBeforeRescan = gamesBeforeRescan.first { it.packageName == "com.supercell.clashroyale" }
        assertFalse(gameBeforeRescan.isTracked)
        assertEquals("Clash Royale", gameBeforeRescan.appName)

        // Subsequent scan discovers the game again with isTracked = true and changed appName
        val rescannedGame = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale Overwritten Name",
            isTracked = true,
            isAutoDetected = true
        )
        val rescanRowId = gamerLogDao.insertDiscoveredGame(rescannedGame)
        assertEquals(-1L, rescanRowId) // OnConflictStrategy.IGNORE returns -1L

        // Invariant: User preference (isTracked = false) and original record are preserved
        val trackedAfterRescan = gamerLogDao.getTrackedPackageNames()
        assertFalse(trackedAfterRescan.contains("com.supercell.clashroyale"))

        val gamesAfterRescan = gamerLogDao.observeGames().first()
        val gameAfterRescan = gamesAfterRescan.first { it.packageName == "com.supercell.clashroyale" }
        assertFalse(gameAfterRescan.isTracked)
        assertEquals("Clash Royale", gameAfterRescan.appName)

        // Toggle tracking back ON and verify updated tracked list
        gamerLogDao.setTracked("com.supercell.clashroyale", true)
        val trackedFinal = gamerLogDao.getTrackedPackageNames()
        assertTrue(trackedFinal.contains("com.supercell.clashroyale"))
    }

    @Test
    fun testForeignKeyCascade_deletesPlaySessionsWhenParentGameIsDeleted() = runBlocking {
        val game = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale",
            isTracked = true
        )
        gamerLogDao.insertDiscoveredGame(game)

        val sessions = listOf(
            PlaySessionEntity(
                packageName = "com.supercell.clashroyale",
                startTime = 1000L,
                endTime = 2000L,
                durationMs = 1000L,
            ),
            PlaySessionEntity(
                packageName = "com.supercell.clashroyale",
                startTime = 3000L,
                endTime = 4000L,
                durationMs = 1000L,
            )
        )
        val insertedIds = gamerLogDao.insertSessions(sessions)
        assertEquals(2, insertedIds.size)
        assertTrue(insertedIds.all { it != -1L })

        assertEquals(2, querySessionCount("com.supercell.clashroyale"))

        // Delete parent GameEntity via raw SQL inside withTransaction with foreign keys enabled
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM games WHERE packageName = ?",
                arrayOf("com.supercell.clashroyale")
            )
        }

        // Parent should no longer exist in games table
        val games = gamerLogDao.observeGames().first()
        assertTrue(games.none { it.packageName == "com.supercell.clashroyale" })

        // Child play sessions should be automatically deleted via FOREIGN KEY CASCADE
        val remainingSessionCount = querySessionCount("com.supercell.clashroyale")
        assertEquals(0, remainingSessionCount)
    }

    @Test
    fun testCheckpointAndOpenSessionsLifecycle() = runBlocking {
        // Initial checkpoint is null
        val noCheckpoint = gamerLogDao.getCheckpoint(ScanCheckpointEntity.KEY_USAGE_EVENTS)
        assertNull(noCheckpoint)

        // Save checkpoint
        val cp1 = ScanCheckpointEntity(
            key = ScanCheckpointEntity.KEY_USAGE_EVENTS,
            timestampMs = 15000L
        )
        gamerLogDao.saveCheckpoint(cp1)

        val retrieved1 = gamerLogDao.getCheckpoint(ScanCheckpointEntity.KEY_USAGE_EVENTS)
        assertNotNull(retrieved1)
        assertEquals(15000L, retrieved1?.timestampMs)
        assertEquals(ScanCheckpointEntity.KEY_USAGE_EVENTS, retrieved1?.key)

        // Update checkpoint via REPLACE
        val cp2 = ScanCheckpointEntity(
            key = ScanCheckpointEntity.KEY_USAGE_EVENTS,
            timestampMs = 30000L
        )
        gamerLogDao.saveCheckpoint(cp2)

        val retrieved2 = gamerLogDao.getCheckpoint()
        assertNotNull(retrieved2)
        assertEquals(30000L, retrieved2?.timestampMs)

        // Open sessions: initially empty
        val initialOpen = gamerLogDao.getOpenSessions()
        assertTrue(initialOpen.isEmpty())

        // Insert open sessions
        val openSessions = listOf(
            OpenSessionEntity(packageName = "com.supercell.clashroyale", startTime = 1000L),
            OpenSessionEntity(packageName = "com.mojang.minecraftpe", startTime = 2000L)
        )
        gamerLogDao.insertOpenSessions(openSessions)

        val savedOpen = gamerLogDao.getOpenSessions()
        assertEquals(2, savedOpen.size)
        assertTrue(savedOpen.any { it.packageName == "com.supercell.clashroyale" && it.startTime == 1000L })
        assertTrue(savedOpen.any { it.packageName == "com.mojang.minecraftpe" && it.startTime == 2000L })

        // Clear open sessions
        gamerLogDao.clearOpenSessions()
        val clearedOpen = gamerLogDao.getOpenSessions()
        assertTrue(clearedOpen.isEmpty())
    }

    @Test
    fun testObserveGamePlaytime_boundaryClippingAndAggregation() = runBlocking {
        val game1 = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale",
            customTitle = "Clash Battles",
            isTracked = true
        )
        val game2 = GameEntity(
            packageName = "com.mojang.minecraftpe",
            appName = "Minecraft",
            customTitle = null,
            isTracked = true
        )
        val game3 = GameEntity(
            packageName = "com.valvesoftware.game3",
            appName = "Third Game",
            customTitle = null,
            isTracked = true
        )
        val gameTieA = GameEntity(
            packageName = "com.tied.alpha",
            appName = "Alpha Game",
            customTitle = null,
            isTracked = true
        )
        val gameTieB = GameEntity(
            packageName = "com.tied.beta",
            appName = "Beta Game",
            customTitle = null,
            isTracked = true
        )

        gamerLogDao.insertDiscoveredGame(game1)
        gamerLogDao.insertDiscoveredGame(game2)
        gamerLogDao.insertDiscoveredGame(game3)
        gamerLogDao.insertDiscoveredGame(gameTieA)
        gamerLogDao.insertDiscoveredGame(gameTieB)

        val windowStart = 10_000L
        val windowEnd = 20_000L

        val sessions = listOf(
            // Game 1 sessions:
            // 1. Outside before: ends exactly at startInclusiveMs -> ignored (0ms)
            PlaySessionEntity(packageName = "com.supercell.clashroyale", startTime = 5_000L, endTime = 10_000L, durationMs = 5_000L),
            // 2. Outside after: starts exactly at endExclusiveMs -> ignored (0ms)
            PlaySessionEntity(packageName = "com.supercell.clashroyale", startTime = 20_000L, endTime = 25_000L, durationMs = 5_000L),
            // 3. Far outside before -> ignored (0ms)
            PlaySessionEntity(packageName = "com.supercell.clashroyale", startTime = 1_000L, endTime = 4_000L, durationMs = 3_000L),
            // 4. Far outside after -> ignored (0ms)
            PlaySessionEntity(packageName = "com.supercell.clashroyale", startTime = 30_000L, endTime = 40_000L, durationMs = 10_000L),
            // 5. Spanning left boundary: [8000, 12000) -> clipped to [10000, 12000) = 2000ms
            PlaySessionEntity(packageName = "com.supercell.clashroyale", startTime = 8_000L, endTime = 12_000L, durationMs = 4_000L),
            // 6. Strictly inside: [13000, 15000) -> 2000ms
            PlaySessionEntity(packageName = "com.supercell.clashroyale", startTime = 13_000L, endTime = 15_000L, durationMs = 2_000L),
            // 7. Spanning right boundary: [18000, 22000) -> clipped to [18000, 20000) = 2000ms
            PlaySessionEntity(packageName = "com.supercell.clashroyale", startTime = 18_000L, endTime = 22_000L, durationMs = 4_000L),

            // Game 2 session:
            // 8. Spanning both boundaries: [5000, 25000) -> clipped to [10000, 20000) = 10000ms
            PlaySessionEntity(packageName = "com.mojang.minecraftpe", startTime = 5_000L, endTime = 25_000L, durationMs = 20_000L),

            // Game 3 session:
            // 9. Outside window completely -> ignored, game 3 must not appear in aggregation
            PlaySessionEntity(packageName = "com.valvesoftware.game3", startTime = 1_000L, endTime = 5_000L, durationMs = 4_000L),

            // Tied games sessions with exact same playtime (3000ms each inside window)
            PlaySessionEntity(packageName = "com.tied.alpha", startTime = 11_000L, endTime = 14_000L, durationMs = 3_000L),
            PlaySessionEntity(packageName = "com.tied.beta", startTime = 15_000L, endTime = 18_000L, durationMs = 3_000L)
        )

        gamerLogDao.insertSessions(sessions)

        val result = gamerLogDao.observeGamePlaytime(windowStart, windowEnd).first()

        // Expect 4 games: Game 2 (10000ms), Game 1 (6000ms), GameTieA (3000ms), GameTieB (3000ms)
        // Game 3 has 0 playtime in window and must be excluded
        assertEquals(4, result.size)

        // 1st: Minecraft (10000ms)
        assertEquals("com.mojang.minecraftpe", result[0].packageName)
        assertEquals("Minecraft", result[0].appName)
        assertNull(result[0].customTitle)
        assertEquals(10_000L, result[0].totalDurationMs)

        // 2nd: Clash Royale (6000ms, custom title preserved)
        assertEquals("com.supercell.clashroyale", result[1].packageName)
        assertEquals("Clash Royale", result[1].appName)
        assertEquals("Clash Battles", result[1].customTitle)
        assertEquals(6_000L, result[1].totalDurationMs)

        // 3rd & 4th: Tied games ordered deterministically by packageName ASC
        assertEquals("com.tied.alpha", result[2].packageName)
        assertEquals(3_000L, result[2].totalDurationMs)
        assertEquals("com.tied.beta", result[3].packageName)
        assertEquals(3_000L, result[3].totalDurationMs)

        // Duplicate replay: inserting identical existing session should be ignored and not inflate totals
        val duplicateInsert = gamerLogDao.insertSessions(
            listOf(
                PlaySessionEntity(
                    packageName = "com.supercell.clashroyale",
                    startTime = 13_000L,
                    endTime = 15_000L,
                    durationMs = 2_000L
                )
            )
        )
        assertEquals(-1L, duplicateInsert[0])

        val afterDuplicate = gamerLogDao.observeGamePlaytime(windowStart, windowEnd).first()
        assertEquals(4, afterDuplicate.size)
        assertEquals(6_000L, afterDuplicate[1].totalDurationMs)
    }

    @Test
    fun testObserveGamePlaytime_emitsUpdatedAggregationOnSessionInsertion() = runBlocking {
        val game1 = GameEntity(
            packageName = "com.supercell.clashroyale",
            appName = "Clash Royale",
            isTracked = true
        )
        val game2 = GameEntity(
            packageName = "com.mojang.minecraftpe",
            appName = "Minecraft",
            isTracked = true
        )
        gamerLogDao.insertDiscoveredGame(game1)
        gamerLogDao.insertDiscoveredGame(game2)

        val windowStart = 10_000L
        val windowEnd = 20_000L

        // Initial session: Game 2 has 5000ms
        gamerLogDao.insertSessions(
            listOf(
                PlaySessionEntity(
                    packageName = "com.mojang.minecraftpe",
                    startTime = 10_000L,
                    endTime = 15_000L,
                    durationMs = 5_000L
                )
            )
        )

        val channel = Channel<List<GamePlaytime>>(Channel.UNLIMITED)
        val collectJob = launch {
            gamerLogDao.observeGamePlaytime(windowStart, windowEnd).collect {
                channel.send(it)
            }
        }

        // Initial emission: only Game 2 with 5000ms
        val initialEmission = channel.receive()
        assertEquals(1, initialEmission.size)
        assertEquals("com.mojang.minecraftpe", initialEmission[0].packageName)
        assertEquals(5_000L, initialEmission[0].totalDurationMs)

        // Insert session for Game 1 with 8000ms -> should trigger invalidation and become #1
        gamerLogDao.insertSessions(
            listOf(
                PlaySessionEntity(
                    packageName = "com.supercell.clashroyale",
                    startTime = 11_000L,
                    endTime = 19_000L,
                    durationMs = 8_000L
                )
            )
        )

        val updatedEmission = withTimeout(5_000L) {
            channel.receive()
        }
        assertEquals(2, updatedEmission.size)
        // Game 1 with 8000ms is now #1, Game 2 with 5000ms is #2
        assertEquals("com.supercell.clashroyale", updatedEmission[0].packageName)
        assertEquals(8_000L, updatedEmission[0].totalDurationMs)
        assertEquals("com.mojang.minecraftpe", updatedEmission[1].packageName)
        assertEquals(5_000L, updatedEmission[1].totalDurationMs)

        collectJob.cancel()
    }

    private fun querySessionCount(packageName: String): Int {
        val cursor = db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM play_sessions WHERE packageName = ?",
            arrayOf(packageName)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
