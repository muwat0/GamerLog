package dev.gamerlog.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackloggdDaoTest {

    private lateinit var db: GamerLogDatabase
    private lateinit var gamerLogDao: GamerLogDao
    private lateinit var backloggdDao: BackloggdDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GamerLogDatabase::class.java).build()
        gamerLogDao = db.dao()
        backloggdDao = db.backloggdDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun eligibleSessionsRequireMappingDateAndPositiveDurationAndSkipQueuedSessions() = runBlocking {
        val eligibleId = insertSession("eligible.game", startTime = 100L, durationMs = 500L)
        insertSession("eligible.game", startTime = 99L, durationMs = 500L)
        insertSession("eligible.game", startTime = 101L, durationMs = 0L)
        insertSession("unmapped.game", startTime = 102L, durationMs = 500L, slug = null)

        assertEquals(
            listOf(eligibleId),
            backloggdDao.eligibleSessions(sinceMs = 100L).map { it.sessionId }
        )

        backloggdDao.insertExports(listOf(export(eligibleId, marker = "eligible-marker")))
        assertTrue(backloggdDao.eligibleSessions(sinceMs = 100L).isEmpty())

        // Mapping edits affect future candidates but do not rewrite the frozen queued payload.
        backloggdDao.setGameMapping("eligible.game", "changed-slug")
        val queued = backloggdDao.getExport(eligibleId)
        assertEquals("game-slug", queued?.gameSlug)
    }

    @Test
    fun exportInsertIgnoresDuplicateSessionsAndMarkers() = runBlocking {
        val firstSession = insertSession("duplicate.game", startTime = 100L)
        val secondSession = insertSession("duplicate.game", startTime = 200L)

        val first = export(firstSession, marker = "same-marker")
        val sameMarkerDifferentSession = export(secondSession, marker = "same-marker")
        assertEquals(
            listOf(firstSession, -1L),
            backloggdDao.insertExports(listOf(first, sameMarkerDifferentSession))
        )
        assertEquals(-1L, backloggdDao.insertExports(listOf(export(firstSession, marker = "new-marker"))).single())
        assertEquals("same-marker", backloggdDao.getExport(firstSession)?.marker)
        assertNull(backloggdDao.getExport(secondSession))
    }

    @Test
    fun stateCompareAndSetAndDeliveryGuardsPreventDuplicateClaimsOrUnsafeRetries() = runBlocking {
        val sendingId = insertSession("state.sending", startTime = 100L)
        backloggdDao.insertExports(listOf(export(sendingId, marker = "sending-marker")))

        assertEquals(
            1,
            backloggdDao.transition(
                sendingId,
                ExportState.WAITING,
                ExportState.SENDING,
                updatedAtMs = 2L,
                lastError = null
            )
        )
        assertEquals(
            0,
            backloggdDao.transition(
                sendingId,
                ExportState.WAITING,
                ExportState.SENDING,
                updatedAtMs = 3L,
                lastError = null
            )
        )
        assertEquals(1, backloggdDao.incrementAttempt(sendingId, updatedAtMs = 4L))
        assertEquals(1, backloggdDao.incrementAttempt(sendingId, updatedAtMs = 5L))
        assertEquals(1, backloggdDao.markSent(sendingId, "remote-1", updatedAtMs = 6L))
        assertEquals(0, backloggdDao.markSent(sendingId, "remote-1", updatedAtMs = 7L))
        assertEquals(0, backloggdDao.incrementAttempt(sendingId, updatedAtMs = 8L))
        assertEquals(2, backloggdDao.getExport(sendingId)?.attemptCount)
        assertEquals(0, backloggdDao.retry(sendingId, updatedAtMs = 8L))
        assertEquals(0, backloggdDao.exclude(sendingId, updatedAtMs = 9L))
        assertEquals(
            0,
            backloggdDao.transition(
                sendingId,
                ExportState.SENT,
                ExportState.WAITING,
                updatedAtMs = 10L,
                lastError = null
            )
        )
        assertEquals(ExportState.SENT, backloggdDao.getExport(sendingId)?.state)
        assertEquals(
            0,
            backloggdDao.transition(
                sendingId,
                ExportState.SENT,
                ExportState.FAILED,
                updatedAtMs = 10L,
                lastError = "must remain sent"
            )
        )
        assertEquals(
            0,
            backloggdDao.transition(
                sendingId,
                ExportState.WAITING,
                ExportState.SENT,
                updatedAtMs = 10L,
                lastError = null
            )
        )
        assertEquals(ExportState.SENT, backloggdDao.getExport(sendingId)?.state)

        val uncertainId = insertSession("state.uncertain", startTime = 200L)
        val busyId = insertSession("state.busy", startTime = 300L)
        val failedId = insertSession("state.failed", startTime = 400L)
        backloggdDao.insertExports(
            listOf(
                export(uncertainId, marker = "uncertain-marker", state = ExportState.UNCERTAIN),
                export(busyId, marker = "busy-marker", state = ExportState.SENDING),
                export(failedId, marker = "failed-marker", state = ExportState.FAILED)
            )
        )

        for ((sessionId, state) in listOf(
            uncertainId to ExportState.UNCERTAIN,
            busyId to ExportState.SENDING
        )) {
            assertEquals(
                0,
                backloggdDao.transition(
                    sessionId,
                    state,
                    ExportState.WAITING,
                    updatedAtMs = 11L,
                    lastError = null
                )
            )
            assertEquals(0, backloggdDao.retry(sessionId, updatedAtMs = 12L))
        }
        assertEquals(
            0,
            backloggdDao.transition(
                uncertainId,
                ExportState.UNCERTAIN,
                ExportState.SENT,
                updatedAtMs = 12L,
                lastError = null
            )
        )
        assertEquals(1, backloggdDao.retry(failedId, updatedAtMs = 13L))
        assertEquals(ExportState.WAITING, backloggdDao.getExport(failedId)?.state)
        assertEquals(1, backloggdDao.exclude(failedId, updatedAtMs = 14L))
        assertEquals(ExportState.EXCLUDED, backloggdDao.getExport(failedId)?.state)
        assertEquals(1, backloggdDao.retry(failedId, updatedAtMs = 15L))
        assertEquals(ExportState.WAITING, backloggdDao.getExport(failedId)?.state)
        assertEquals(0, backloggdDao.retry(failedId, updatedAtMs = 16L))
    }

    @Test
    fun preparedGameIdCanOnlyBeSetOnceWhileWaiting() = runBlocking {
        val sessionId = insertSession("prepared.game", startTime = 100L)
        backloggdDao.insertExports(listOf(export(sessionId, marker = "prepared-marker")))

        assertEquals(1, backloggdDao.setPreparedGameId(sessionId, "remote-game", updatedAtMs = 2L))
        assertEquals(0, backloggdDao.setPreparedGameId(sessionId, "other-game", updatedAtMs = 3L))
        assertEquals("remote-game", backloggdDao.getExport(sessionId)?.gameId)
        assertEquals(
            1,
            backloggdDao.transition(
                sessionId,
                ExportState.WAITING,
                ExportState.SENDING,
                updatedAtMs = 4L,
                lastError = null
            )
        )

        val otherId = insertSession("prepared.game", startTime = 200L)
        backloggdDao.insertExports(listOf(export(otherId, marker = "prepared-marker-2")))
        assertEquals(
            1,
            backloggdDao.transition(
                otherId,
                ExportState.WAITING,
                ExportState.SENDING,
                updatedAtMs = 5L,
                lastError = null
            )
        )
        assertEquals(0, backloggdDao.setPreparedGameId(otherId, "late-game", updatedAtMs = 6L))
    }

    @Test
    fun settingsAccountChangesDoNotDeleteSessionsOrExportsAndOrderingIsStable() = runBlocking {
        val olderId = insertSession("settings.game", startTime = 100L)
        val newerId = insertSession("settings.game", startTime = 200L)
        backloggdDao.insertExports(
            listOf(
                export(olderId, marker = "older-marker", accountUsername = "old-user", createdAtMs = 10L),
                export(newerId, marker = "newer-marker", accountUsername = "old-user", createdAtMs = 20L)
            )
        )

        backloggdDao.saveSettings(
            BackloggdSettingsEntity(username = "old-user", enabled = true, enabledSinceMs = 50L)
        )
        assertEquals("old-user", backloggdDao.observeSettings().first()?.username)
        backloggdDao.saveSettings(
            BackloggdSettingsEntity(username = "new-user", enabled = false, enabledSinceMs = 0L)
        )

        val settings = backloggdDao.getSettings()
        assertEquals("new-user", settings?.username)
        assertFalse(settings?.enabled ?: true)
        assertEquals(listOf(olderId, newerId), backloggdDao.getExportsForAccount("old-user").map { it.sessionId })
        assertEquals(listOf(newerId, olderId), backloggdDao.observeExports().first().map { it.sessionId })
        assertEquals(olderId, backloggdDao.getExport(olderId)?.sessionId)
        assertEquals(2, countRows("play_sessions"))
    }

    @Test
    fun migrationFromV1PreservesHistoryStateAndIndexesAndAddsCascadeOutbox() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "gamerlog-v1-migration-test.db"
        context.deleteDatabase(databaseName)
        createV1Database(context.getDatabasePath(databaseName))

        val migrated = Room.databaseBuilder(context, GamerLogDatabase::class.java, databaseName)
            .addMigrations(GamerLogDatabase.MIGRATION_1_2)
            .build()
        try {
            val migratedDao = migrated.dao()
            val migratedBackloggdDao = migrated.backloggdDao()
            val sql = migrated.openHelper.writableDatabase
            val preservedGame = migratedDao.observeGames().first().single { it.packageName == "legacy.game" }
            assertEquals("Legacy", preservedGame.appName)
            assertTrue(preservedGame.isTracked)
            assertFalse(preservedGame.isAutoDetected)
            assertEquals("legacy-slug", preservedGame.backloggdSlug)
            assertEquals(1L, preservedGame.createdAt)


            assertEquals(listOf(42L, 77L), queryLongs(sql, "SELECT id FROM play_sessions ORDER BY id"))
            assertEquals(listOf(1000L, 3000L), queryLongs(sql, "SELECT startTime FROM play_sessions ORDER BY id"))
            assertEquals(ScanCheckpointEntity.KEY_USAGE_EVENTS, migratedDao.getCheckpoint()?.key)
            assertEquals(12_345L, migratedDao.getCheckpoint()?.timestampMs)
            val openSessions = migratedDao.getOpenSessions()
            assertEquals(1, openSessions.size)
            assertEquals("legacy.game", openSessions.single().packageName)
            assertEquals(23_456L, openSessions.single().startTime)
            assertEquals(
                BackloggdSettingsEntity(id = 1, username = null, enabled = false, enabledSinceMs = 0L),
                migratedBackloggdDao.getSettings()
            )

            val duplicate = migratedDao.insertSessions(
                listOf(
                    PlaySessionEntity(
                        packageName = "legacy.game",
                        startTime = 1000L,
                        endTime = 2000L,
                        durationMs = 1000L
                    )
                )
            )
            assertEquals(listOf(-1L), duplicate)
            assertEquals(listOf(42L, 77L), queryLongs(sql, "SELECT id FROM play_sessions ORDER BY id"))

            val playSessionColumns = queryStrings(sql, "PRAGMA table_info(`play_sessions`)", "name")
            assertFalse(playSessionColumns.contains("syncStatus"))
            val playSessionIndexes = queryStrings(sql, "PRAGMA index_list(`play_sessions`)", "name")
            assertTrue(playSessionIndexes.contains("index_play_sessions_packageName"))
            assertTrue(playSessionIndexes.contains("index_play_sessions_startTime"))
            assertTrue(playSessionIndexes.contains("index_play_sessions_packageName_startTime_endTime"))
            assertFalse(playSessionIndexes.contains("index_play_sessions_syncStatus"))
            assertEquals("CASCADE", queryStrings(sql, "PRAGMA foreign_key_list(`play_sessions`)", "on_delete").single())

            migratedBackloggdDao.insertExports(listOf(export(77L, marker = "cascade-marker")))
            assertEquals("game-slug", migratedBackloggdDao.getExport(77L)?.gameSlug)
            sql.execSQL("DELETE FROM games WHERE packageName = 'legacy.cascade'")
            assertNull(migratedBackloggdDao.getExport(77L))
            assertEquals(listOf(42L), queryLongs(sql, "SELECT id FROM play_sessions ORDER BY id"))
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun insertSession(
        packageName: String,
        startTime: Long,
        durationMs: Long = 100L,
        slug: String? = "game-slug"
    ): Long {
        gamerLogDao.insertDiscoveredGame(
            GameEntity(packageName = packageName, appName = packageName, backloggdSlug = slug)
        )
        return gamerLogDao.insertSessions(
            listOf(
                PlaySessionEntity(
                    packageName = packageName,
                    startTime = startTime,
                    endTime = startTime + durationMs,
                    durationMs = durationMs
                )
            )
        ).single()
    }

    private fun export(
        sessionId: Long,
        marker: String,
        state: ExportState = ExportState.WAITING,
        accountUsername: String = "user",
        createdAtMs: Long = 1L
    ) = BackloggdExportEntity(
        sessionId = sessionId,
        accountUsername = accountUsername,
        gameSlug = "game-slug",
        playedOn = "2026-11-13",
        durationMs = 100L,
        marker = marker,
        state = state,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs
    )

    private fun countRows(table: String): Int {
        val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`")
        return cursor.use {
            it.moveToFirst()
            it.getInt(0)
        }
    }

    private fun createV1Database(databaseFile: File) {
        databaseFile.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            legacy.execSQL(
                """
                CREATE TABLE `games` (
                    `packageName` TEXT NOT NULL,
                    `appName` TEXT NOT NULL,
                    `customTitle` TEXT,
                    `isTracked` INTEGER NOT NULL,
                    `isAutoDetected` INTEGER NOT NULL,
                    `backloggdSlug` TEXT,
                    `iconUri` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`)
                )
                """.trimIndent()
            )
            legacy.execSQL(
                """
                CREATE TABLE `play_sessions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `startTime` INTEGER NOT NULL,
                    `endTime` INTEGER NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `syncStatus` INTEGER NOT NULL,
                    FOREIGN KEY(`packageName`) REFERENCES `games`(`packageName`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            legacy.execSQL("CREATE INDEX `index_play_sessions_packageName` ON `play_sessions` (`packageName`)")
            legacy.execSQL("CREATE INDEX `index_play_sessions_startTime` ON `play_sessions` (`startTime`)")
            legacy.execSQL("CREATE INDEX `index_play_sessions_syncStatus` ON `play_sessions` (`syncStatus`)")
            legacy.execSQL(
                "CREATE UNIQUE INDEX `index_play_sessions_packageName_startTime_endTime` " +
                    "ON `play_sessions` (`packageName`, `startTime`, `endTime`)"
            )
            legacy.execSQL(
                """
                CREATE TABLE `scan_checkpoints` (
                    `key` TEXT NOT NULL,
                    `timestampMs` INTEGER NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent()
            )
            legacy.execSQL(
                """
                CREATE TABLE `open_sessions` (
                    `packageName` TEXT NOT NULL,
                    `startTime` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`)
                )
                """.trimIndent()
            )
            legacy.execSQL(
                """
                INSERT INTO `games`
                    (`packageName`, `appName`, `isTracked`, `isAutoDetected`, `backloggdSlug`, `createdAt`)
                VALUES ('legacy.game', 'Legacy', 1, 0, 'legacy-slug', 1)
                """.trimIndent()
            )
            legacy.execSQL(
                """
                INSERT INTO `games`
                    (`packageName`, `appName`, `isTracked`, `isAutoDetected`, `createdAt`)
                VALUES ('legacy.cascade', 'Cascade', 1, 0, 2)
                """.trimIndent()
            )
            legacy.execSQL(
                """
                INSERT INTO `play_sessions` (`id`, `packageName`, `startTime`, `endTime`, `durationMs`, `syncStatus`)
                VALUES (42, 'legacy.game', 1000, 2000, 1000, 0)
                """.trimIndent()
            )
            legacy.execSQL(
                """
                INSERT INTO `play_sessions` (`id`, `packageName`, `startTime`, `endTime`, `durationMs`, `syncStatus`)
                VALUES (77, 'legacy.cascade', 3000, 4000, 1000, 2)
                """.trimIndent()
            )
            legacy.execSQL(
                "INSERT INTO `scan_checkpoints` (`key`, `timestampMs`) VALUES ('usage_events', 12345)"
            )
            legacy.execSQL(
                "INSERT INTO `open_sessions` (`packageName`, `startTime`) VALUES ('legacy.game', 23456)"
            )
            legacy.version = 1
        } finally {
            legacy.close()
        }
    }

    private fun queryLongs(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): List<Long> {
        val cursor = database.query(sql)
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
    }

    private fun queryStrings(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        columnName: String
    ): List<String> {
        val cursor = database.query(sql)
        return cursor.use {
            val columnIndex = it.getColumnIndexOrThrow(columnName)
            buildList {
                while (it.moveToNext()) add(it.getString(columnIndex))
            }
        }
    }
}
