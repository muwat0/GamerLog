package dev.gamerlog.app.core.backloggd

import android.os.Bundle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.gamerlog.app.core.backloggd.security.AndroidBackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.ExportState
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.database.PlaySessionEntity
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit, disposable-account integration test; never enabled by an ordinary instrument run. */
class BackloggdLiveExportTest {
    @Test
    fun exportsAndReconcilesOnlySyntheticFixtureSessions() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Fixture-creating live export test is disabled in reconcile mode",
            arguments.getString("backloggdLivePhase") != "reconcile"
        )
        assumeTrue("Live Backloggd test is opt-in", arguments.getString("backloggdLive") == "true")

        val expectedAccount = arguments.getString("backloggdAccount")
            ?.takeIf(String::isNotBlank)
            ?: fail("Missing required backloggdAccount instrumentation argument")
        val gameSlug = arguments.getString("backloggdGameSlug")
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_GAME_SLUG
        val context = instrumentation.targetContext
        val credentialStore = AndroidBackloggdCredentialStore(context)
        val sessionInput = File(context.cacheDir, SESSION_FILE_NAME)
        val supplied = readCredentials(sessionInput, credentialStore)
        if (!supplied.username.equals(expectedAccount, ignoreCase = true)) {
            fail("Credential username does not match backloggdAccount")
        }

        val fixtureMarkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val ownPostAttempts = AtomicInteger()
        val coroutineContext = currentCoroutineContext()
        val pacer = LiveRequestPacer()
        val exchange = BackloggdHttpExchange { request ->
            pacer.awaitNextStart()
            coroutineContext.ensureActive()
            if (request.method == "POST" && request.body != null) {
                val requestBody = String(request.body, Charsets.UTF_8)
                if (fixtureMarkers.any(requestBody::contains)) ownPostAttempts.incrementAndGet()
            }
            val response = UrlConnectionBackloggdHttpExchange.execute(request)
            reportHttp(instrumentation, request, response.statusCode)
            response
        }
        val transport = HttpBackloggdTransport(credentialStore, exchange)
        val database = openLiveDatabase(context, arguments)
        val repository = BackloggdRepository(context, database, credentialStore, transport)
        var connected = false
        var fixtureCreated = false
        var successProven = false
        var fixturePackage: String? = null
        var fixtureSessionIds: List<Long> = emptyList()

        try {
            try {
                repository.connect(supplied.username, supplied.cookieHeader)
            } catch (failure: BackloggdException) {
                fail(failure.message ?: "Backloggd account connection failed")
            }
            connected = true
            status(instrumentation, "connected")

            val dao = database.backloggdDao()
            assertTrue(
                "Existing account exports exist; refusing to touch them",
                dao.getExportsForAccount(supplied.username.canonicalIdentity()).isEmpty()
            )
            assertTrue(
                "Unrelated eligible mapped history exists; refusing to materialize work",
                dao.eligibleSessions(0L).isEmpty()
            )

            val fixtureUuid = UUID.randomUUID().toString()
            val packageName = "dev.gamerlog.live.$fixtureUuid"
            fixturePackage = packageName
            val insertedGame = database.dao().insertDiscoveredGame(
                GameEntity(packageName, "GamerLog live-test fixture", isTracked = false)
            )
            assertTrue("Could not insert unique live fixture game", insertedGame >= 0L)
            fixtureCreated = true

            val zone = ZoneId.systemDefault()
            val yesterday = LocalDate.now(zone).minusDays(1)
            val firstStart = yesterday.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            val secondStart = yesterday.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
            fixtureSessionIds = database.dao().insertSessions(
                listOf(
                    PlaySessionEntity(
                        packageName = packageName,
                        startTime = firstStart,
                        endTime = firstStart + 65_123L,
                        durationMs = 65_123L
                    ),
                    PlaySessionEntity(
                        packageName = packageName,
                        startTime = secondStart,
                        endTime = secondStart + 125_987L,
                        durationMs = 125_987L
                    )
                )
            )
            assertEquals("Could not insert both live fixture sessions", 2, fixtureSessionIds.size)
            assertTrue(fixtureSessionIds.all { it > 0L })

            repository.setGameMapping(packageName, gameSlug)
            assertTrue(
                "Unrelated mapped history exists; refusing to queue or export it",
                dao.eligibleSessions(0L).all { it.packageName == packageName }
            )
            cancelLiveWork(context)
            val settings = dao.getSettings() ?: fail("Verified account settings were not persisted")
            dao.saveSettings(settings.copy(enabled = true, enabledSinceMs = System.currentTimeMillis()))
            assertEquals("Unexpected number of queued fixture sessions", 2, repository.queueHistory())
            fixtureMarkers.addAll(fixtureSessionIds.map { dao.getExport(it)!!.marker })

            val engine = BackloggdExportEngine(database, credentialStore, transport)
            assertEquals("Backloggd export did not complete", BackloggdSyncOutcome.COMPLETE, engine.run())
            val sentRows = fixtureSessionIds.map { sessionId ->
                val row = dao.getExport(sessionId)
                assertNotNull("Fixture export row was not created", row)
                assertEquals(ExportState.SENT, row?.state)
                assertTrue("Remote entry ID was empty", !row?.remoteEntryId.isNullOrBlank())
                row!!
            }
            assertEquals("Only the two fixture entries should be posted", 2, ownPostAttempts.get())
            val originalRemoteIds = sentRows.map { it.remoteEntryId!! }
            val realCredentials = credentialStore.load()
                ?: fail("Live Backloggd credentials became unavailable")
            sentRows.forEachIndexed { index, row ->
                assertEquals(
                    "Remote entry could not be found after both initial sends",
                    row.remoteEntryId,
                    transport.findExport(realCredentials, row)
                )
                assertTrue("Fixture marker was not retained", row.marker in fixtureMarkers)
                assertEquals("Expected remote entry ID was not retained", row.remoteEntryId, originalRemoteIds[index])
            }

            val writable = database.openHelper.writableDatabase
            writable.execSQL(
                "UPDATE backloggd_exports SET state = 'SENDING', remoteEntryId = NULL " +
                    "WHERE sessionId IN (?, ?) AND state = 'SENT' AND remoteEntryId IS NOT NULL",
                arrayOf(fixtureSessionIds[0], fixtureSessionIds[1])
            )
            assertEquals(
                "Lost-ack simulation did not update exactly the fixture rows",
                2,
                writable.compileStatement(
                    "SELECT COUNT(*) FROM backloggd_exports " +
                        "WHERE sessionId IN (?, ?) AND state = 'SENDING' AND remoteEntryId IS NULL"
                ).run {
                    bindLong(1, fixtureSessionIds[0])
                    bindLong(2, fixtureSessionIds[1])
                    simpleQueryForLong().toInt()
                }
            )

            assertEquals("Interrupted sends were not reconciled", BackloggdSyncOutcome.COMPLETE, engine.run())
            assertEquals("Post-recovery run changed fixture state", BackloggdSyncOutcome.COMPLETE, engine.run())
            val recoveredRows = fixtureSessionIds.map { sessionId ->
                val row = dao.getExport(sessionId)
                assertNotNull("Recovered fixture export row was not found", row)
                assertEquals(ExportState.SENT, row?.state)
                assertTrue("Recovered remote entry ID was empty", !row?.remoteEntryId.isNullOrBlank())
                row!!
            }
            assertEquals("Recovery changed a remote entry ID", originalRemoteIds, recoveredRows.map { it.remoteEntryId })
            assertEquals("Recovery attempted a duplicate POST", 2, ownPostAttempts.get())

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("stage", "live-export-complete")
                    putString("gameId", recoveredRows.first().gameId)
                    putString("fixtureUuid", fixtureUuid)
                    putStringArrayList("markers", ArrayList(fixtureMarkers))
                    putStringArrayList("remoteIds", ArrayList(originalRemoteIds))
                }
            )
            successProven = true
        } catch (failure: BackloggdException) {
            fail(failure.message ?: "Backloggd operation failed")
        } catch (failure: AssertionError) {
            fail(failure.message ?: "Live Backloggd assertion failed")
        } catch (failure: Exception) {
            fail("Live Backloggd test failed (${failure.javaClass.simpleName})")
        } finally {
            if (connected) runCatching { repository.setExportEnabled(false) }
            runCatching { cancelLiveWork(context) }
            if (fixtureCreated && successProven) {
                fixturePackage?.let { packageName ->
                    runCatching {
                        database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM games WHERE packageName = ?",
                            arrayOf(packageName)
                        )
                    }
                }
            }
            database.close()
        }
    }

    @Test
    fun reconcilesExistingLiveExportsWithoutPosting() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live Backloggd test is opt-in", arguments.getString("backloggdLive") == "true")
        assumeTrue(
            "Reconcile mode must be explicitly selected",
            arguments.getString("backloggdLivePhase") == "reconcile"
        )

        val expectedAccount = arguments.getString("backloggdAccount")
            ?.takeIf(String::isNotBlank)
            ?: fail("Missing required backloggdAccount instrumentation argument")
        val context = instrumentation.targetContext
        val credentialStore = AndroidBackloggdCredentialStore(context)
        val credentials = try {
            credentialStore.load() ?: fail("Stored Backloggd credentials are unavailable")
        } catch (_: Exception) {
            fail("Stored Backloggd credentials are unavailable")
        }
        if (credentials.username != expectedAccount) {
            fail("Stored credential username does not match backloggdAccount")
        }

        val coroutineContext = currentCoroutineContext()
        val postAttempts = AtomicInteger()
        val pacer = LiveRequestPacer()
        val exchange = BackloggdHttpExchange { request ->
            pacer.awaitNextStart()
            coroutineContext.ensureActive()
            if (request.method == "POST") {
                postAttempts.incrementAndGet()
                throw AssertionError("POST requests are forbidden during live reconciliation")
            }
            val response = UrlConnectionBackloggdHttpExchange.execute(request)
            reportHttp(instrumentation, request, response.statusCode)
            response
        }
        val transport = HttpBackloggdTransport(credentialStore, exchange)
        val database = openLiveDatabase(context, arguments)
        val repository = BackloggdRepository(context, database, credentialStore, transport)
        val dao = database.backloggdDao()
        var fixturePackage: String? = null
        var successProven = false

        try {
            val settings = dao.getSettings() ?: fail("Backloggd account settings are unavailable")
            assertEquals(
                "Stored Backloggd account does not match backloggdAccount",
                expectedAccount.trim().lowercase(java.util.Locale.ROOT),
                settings.username?.canonicalIdentity()
            )
            assertTrue("Backloggd exports must already be disabled", !settings.enabled)
            val accountRows = dao.getExportsForAccount(credentials.username.canonicalIdentity())
            assertEquals("Expected exactly two existing account export rows", 2, accountRows.size)
            assertTrue(
                "Existing rows must be in an interrupted or completed state",
                accountRows.all {
                    it.state == ExportState.SENDING ||
                        it.state == ExportState.UNCERTAIN ||
                        it.state == ExportState.SENT
                }
            )
            assertEquals(
                "Existing rows do not have the expected fixture durations",
                setOf(65_123L, 125_987L),
                accountRows.map { it.durationMs }.toSet()
            )
            assertEquals("Expected distinct fixture sessions", 2, accountRows.map { it.sessionId }.toSet().size)
            assertTrue(
                "Unrelated eligible mapped history exists; refusing to materialize work",
                dao.eligibleSessions(0L).isEmpty()
            )

            val sessionArgs = accountRows.map { it.sessionId.toString() }.toTypedArray()
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT play_sessions.packageName, games.appName " +
                    "FROM play_sessions INNER JOIN games " +
                    "ON games.packageName = play_sessions.packageName " +
                    "WHERE play_sessions.id IN (?, ?)",
                sessionArgs
            )
            val fixtureGames = cursor.use { rows ->
                buildList {
                    while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1))
                }
            }
            assertEquals("Could not verify both existing fixture games", 2, fixtureGames.size)
            assertTrue(
                "Existing exports do not belong to the live-test fixture game",
                fixtureGames.all {
                    it.first.startsWith("dev.gamerlog.live.") &&
                        it.second == "GamerLog live-test fixture"
                } && fixtureGames.map { it.first }.toSet().size == 1
            )
            fixturePackage = fixtureGames.first().first

            transport.verifyAccount(credentials)
            status(instrumentation, "account-verified")
            cancelLiveWork(context)
            dao.saveSettings(settings.copy(enabled = true, enabledSinceMs = System.currentTimeMillis()))
            val engine = BackloggdExportEngine(database, credentialStore, transport)
            assertEquals(
                "Existing exports were not reconciled",
                BackloggdSyncOutcome.COMPLETE,
                engine.run()
            )
            val sentRows = accountRows.map { original ->
                val row = dao.getExport(original.sessionId)
                assertNotNull("Existing export row was not found", row)
                assertEquals(ExportState.SENT, row?.state)
                assertTrue("Remote entry ID was empty", !row?.remoteEntryId.isNullOrBlank())
                row!!
            }
            val remoteIds = sentRows.map { it.remoteEntryId!! }
            assertEquals("Reconciliation attempted a POST", 0, postAttempts.get())
            assertEquals(
                "Repeated reconciliation did not complete",
                BackloggdSyncOutcome.COMPLETE,
                engine.run()
            )
            val repeatedRows = accountRows.map { dao.getExport(it.sessionId)!! }
            assertTrue("Repeated reconciliation changed export state", repeatedRows.all { it.state == ExportState.SENT })
            assertEquals("Repeated reconciliation changed remote IDs", remoteIds, repeatedRows.map { it.remoteEntryId })
            assertEquals("Repeated reconciliation attempted a POST", 0, postAttempts.get())

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("stage", "live-reconcile-complete")
                    putString("gameId", sentRows.first().gameId)
                    putStringArrayList("markers", ArrayList(accountRows.map { it.marker }))
                    putStringArrayList("remoteIds", ArrayList(remoteIds))
                }
            )
            successProven = true
        } catch (failure: BackloggdException) {
            fail(failure.message ?: "Backloggd reconciliation failed")
        } catch (failure: AssertionError) {
            fail(failure.message ?: "Live Backloggd reconciliation assertion failed")
        } catch (failure: Exception) {
            fail("Live Backloggd reconciliation failed (${failure.javaClass.simpleName})")
        } finally {
            runCatching { repository.setExportEnabled(false) }
            runCatching { cancelLiveWork(context) }
            if (successProven) {
                fixturePackage?.let { packageName ->
                    runCatching {
                        database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM games WHERE packageName = ?",
                            arrayOf(packageName)
                        )
                    }
                }
            }
            database.close()
        }
    }

    private fun openLiveDatabase(
        context: android.content.Context,
        arguments: Bundle
    ): GamerLogDatabase {
        val databaseName = arguments.getString("backloggdLiveDb") ?: DEFAULT_DATABASE_NAME
        if (!databaseName.matches(Regex(LIVE_DATABASE_NAME_PATTERN)) || '/' in databaseName || '\\' in databaseName) {
            fail("backloggdLiveDb must be a gamerlog-live-*.db basename")
        }
        return Room.databaseBuilder(context, GamerLogDatabase::class.java, databaseName)
            .addMigrations(GamerLogDatabase.MIGRATION_1_2)
            .build()
    }

    private fun readCredentials(
        input: File,
        credentialStore: AndroidBackloggdCredentialStore
    ): BackloggdCredentials {
        if (!input.exists()) {
            return try {
                credentialStore.load() ?: fail("No live Backloggd credentials were supplied")
            } catch (_: Exception) {
                fail("Stored Backloggd credentials are unavailable")
            }
        }
        val jsonText = try {
            input.readText()
        } catch (_: Exception) {
            fail("Could not read the live Backloggd credential file")
        } finally {
            if (input.exists() && !input.delete()) fail("Could not remove the live Backloggd credential file")
        }
        return try {
            val json = JSONObject(jsonText)
            val username = json.getString("username")
            val cookieHeader = json.getString("cookieHeader")
            if (username.isBlank() || cookieHeader.isBlank()) fail("Malformed live Backloggd credentials")
            BackloggdCredentials(username, cookieHeader)
        } catch (_: Exception) {
            fail("Malformed live Backloggd credential JSON")
        }
    }

    private fun reportHttp(
        instrumentation: android.app.Instrumentation,
        request: BackloggdHttpRequest,
        statusCode: Int
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("stage", "http")
                putString("method", request.method)
                putString("path", request.path)
                putInt("status", statusCode)
            }
        )
    }

    private fun cancelLiveWork(context: android.content.Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(BackloggdRepository.PERIODIC_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
        workManager.cancelUniqueWork(BackloggdRepository.ONE_TIME_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
    }

    private fun status(instrumentation: android.app.Instrumentation, stage: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stage", stage) })
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    /** Test-only synchronous limiter; exchange calls are made on the transport's IO dispatcher. */
    private class LiveRequestPacer {
        private var lastStartNanos: Long? = null

        @Synchronized
        fun awaitNextStart() {
            val now = System.nanoTime()
            val previous = lastStartNanos
            if (previous != null) {
                val remainingNanos = TimeUnit.MILLISECONDS.toNanos(MIN_REQUEST_INTERVAL_MS) - (now - previous)
                if (remainingNanos > 0L) {
                    val millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                    val nanos = (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                    Thread.sleep(millis, nanos)
                }
            }
            lastStartNanos = System.nanoTime()
        }
    }

    private companion object {
        const val SESSION_FILE_NAME = "backloggd-live-session.json"
        const val DEFAULT_GAME_SLUG = "hades"
        const val DEFAULT_DATABASE_NAME = "gamerlog-live-v2.db"
        const val LIVE_DATABASE_NAME_PATTERN = "gamerlog-live-[A-Za-z0-9_-]+\\.db"
        const val MIN_REQUEST_INTERVAL_MS = 4_000L
    }
}
