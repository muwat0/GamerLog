package dev.gamerlog.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            syncStatus = 0
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
            syncStatus = 0
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
            syncStatus = 0
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
                            syncStatus = 0
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
                syncStatus = 0
            ),
            PlaySessionEntity(
                packageName = "com.supercell.clashroyale",
                startTime = 3000L,
                endTime = 4000L,
                durationMs = 1000L,
                syncStatus = 0
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
