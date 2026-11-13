package dev.gamerlog.app.core.backloggd

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdExportEntity
import dev.gamerlog.app.core.database.BackloggdSettingsEntity
import dev.gamerlog.app.core.database.ExportState
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.database.PlaySessionEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackloggdExportEngineTest {
    private lateinit var context: Context
    private lateinit var database: GamerLogDatabase
    private lateinit var credentialStore: MemoryCredentialStore
    private lateinit var transport: FakeTransport
    private lateinit var engine: BackloggdExportEngine
    private val fixedClock = Clock.fixed(Instant.parse("2100-01-01T12:00:00Z"), ZoneOffset.UTC)
    private val deviceZone = ZoneId.of("Europe/Istanbul")

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GamerLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        credentialStore = MemoryCredentialStore(BackloggdCredentials("player", "cookie=value"))
        transport = FakeTransport()
        engine = BackloggdExportEngine(database, credentialStore, transport, fixedClock, deviceZone)
        database.backloggdDao().saveSettings(
            BackloggdSettingsEntity(username = "player", enabled = true, enabledSinceMs = 0L)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun queueHistoryFreezesDeviceDateExactDurationSlugAndAccount() = runBlocking {
        val start = Instant.parse("2100-01-01T23:59:00Z").toEpochMilli()
        val sessionId = insertSession(startTime = start, durationMs = 90_123L)
        database.backloggdDao().saveSettings(
            BackloggdSettingsEntity(username = "player", enabled = true, enabledSinceMs = start + 1L)
        )

        assertEquals(1, engine.queueHistory())
        val export = database.backloggdDao().getExport(sessionId)
        assertNotNull(export)
        assertEquals("player", export?.accountUsername)
        assertEquals("fixture-game", export?.gameSlug)
        assertEquals("2100-01-02", export?.playedOn)
        assertEquals(90_123L, export?.durationMs)
        assertTrue(export?.marker?.let { runCatching { UUID.fromString(it) }.isSuccess } == true)
        assertEquals(ExportState.WAITING, export?.state)
    }

    @Test
    fun automaticQueueStartsAtExplicitOptInTime() = runBlocking {
        val beforeOptIn = insertSession(startTime = BASE_START_MS)
        val afterOptIn = insertSession(startTime = BASE_START_MS + 10L)
        database.backloggdDao().saveSettings(
            BackloggdSettingsEntity(
                username = "player",
                enabled = true,
                enabledSinceMs = BASE_START_MS + 1L
            )
        )
        transport.resolveFailure = BackloggdException("Temporary network failure", retryable = true)

        assertEquals(BackloggdSyncOutcome.RETRY, engine.run())
        assertNull(database.backloggdDao().getExport(beforeOptIn))
        assertNotNull(database.backloggdDao().getExport(afterOptIn))
    }

    @Test
    fun offlinePreflightFailureKeepsWaitingRowAndRequestsRetry() = runBlocking {
        insertSession()
        transport.resolveFailure = BackloggdException("Temporary network failure", retryable = true)

        assertEquals(BackloggdSyncOutcome.RETRY, engine.run())
        val exports = database.backloggdDao().getExportsForAccount("player")
        assertEquals(1, exports.size)
        assertEquals(ExportState.WAITING, exports.single().state)
        assertEquals(0, transport.sendCount.get())
    }

    @Test
    fun lostAcknowledgementIsReadBackWithoutSecondSend() = runBlocking {
        insertSession()
        transport.sendAction = { item ->
            transport.remoteEntries[item.marker] = "remote-1"
            throw BackloggdException(
                "Acknowledgement unavailable",
                retryable = true,
                deliveryMayHaveOccurred = true
            )
        }

        assertEquals(BackloggdSyncOutcome.RETRY, engine.run())
        assertEquals(ExportState.UNCERTAIN, onlyExport().state)

        assertEquals(BackloggdSyncOutcome.COMPLETE, engine.run())
        val sent = onlyExport()
        assertEquals(ExportState.SENT, sent.state)
        assertEquals("remote-1", sent.remoteEntryId)
        assertEquals(1, transport.sendCount.get())
    }

    @Test
    fun absentMarkerLeavesUncertainAndNeverResends() = runBlocking {
        insertSession()
        transport.sendAction = {
            throw BackloggdException(
                "Acknowledgement unavailable",
                retryable = true,
                deliveryMayHaveOccurred = true
            )
        }

        assertEquals(BackloggdSyncOutcome.RETRY, engine.run())
        assertEquals(BackloggdSyncOutcome.COMPLETE, engine.run())
        assertEquals(ExportState.UNCERTAIN, onlyExport().state)
        assertEquals(1, transport.sendCount.get())
    }

    @Test
    fun interruptedSendingRowIsReconciledButNeverResent() = runBlocking {
        insertSession()
        engine.queueHistory()
        val queued = onlyExport()
        database.backloggdDao().transition(
            sessionId = queued.sessionId,
            expectedState = ExportState.WAITING,
            newState = ExportState.SENDING,
            updatedAtMs = fixedClock.millis(),
            lastError = null
        )

        assertEquals(BackloggdSyncOutcome.COMPLETE, engine.run())
        assertEquals(ExportState.UNCERTAIN, onlyExport().state)
        assertEquals(1, transport.findCount.get())
        assertEquals(0, transport.sendCount.get())
    }

    @Test
    fun concurrentRunsClaimAndSendOnlyOnce() = runBlocking {
        insertSession()
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        transport.sendAction = { item ->
            sendStarted.complete(Unit)
            releaseSend.await()
            transport.remoteEntries[item.marker] = "remote-concurrent"
            "remote-concurrent"
        }

        val first = async(Dispatchers.Default) { engine.run() }
        sendStarted.await()
        val second = async(Dispatchers.Default) { engine.run() }
        releaseSend.complete(Unit)

        first.await()
        second.await()
        assertEquals(1, transport.sendCount.get())
        assertEquals(ExportState.SENT, onlyExport().state)
    }

    @Test
    fun switchingAccountsNeverSendsOldRowsToNewDestination() = runBlocking {
        val sessionId = insertSession()
        assertEquals(1, engine.queueHistory())
        val oldExport = database.backloggdDao().getExport(sessionId)
        assertEquals("player", oldExport?.accountUsername)

        database.backloggdDao().saveSettings(
            BackloggdSettingsEntity(username = "different-player", enabled = true, enabledSinceMs = 0L)
        )
        credentialStore.credentials = BackloggdCredentials("different-player", "other-cookie")

        assertEquals(BackloggdSyncOutcome.COMPLETE, engine.run())
        assertEquals(0, transport.sendCount.get())
        assertEquals("player", database.backloggdDao().getExport(sessionId)?.accountUsername)
        assertEquals(ExportState.WAITING, database.backloggdDao().getExport(sessionId)?.state)
    }

    @Test
    fun disablingExportsPreservesQueueAndLocalSession() = runBlocking {
        val sessionId = insertSession()
        engine.queueHistory()
        val repository = BackloggdRepository(
            context,
            database,
            credentialStore,
            transport,
            fixedClock,
            deviceZone
        )

        repository.setExportEnabled(false)

        assertEquals(BackloggdSyncOutcome.COMPLETE, engine.run())
        assertEquals(ExportState.WAITING, database.backloggdDao().getExport(sessionId)?.state)
        val session = database.dao().observeGamePlaytime(
            startInclusiveMs = BASE_START_MS - 1,
            endExclusiveMs = BASE_START_MS + BASE_DURATION_MS + 1
        ).first().single()
        assertEquals(BASE_DURATION_MS, session.totalDurationMs)
    }

    @Test
    fun pausingDuringInflightSendStopsTheRestOfTheBatch() = runBlocking {
        verifyPauseStopsBatch(disconnect = false)
    }

    @Test
    fun disconnectingDuringInflightSendStopsTheRestOfTheBatch() = runBlocking {
        verifyPauseStopsBatch(disconnect = true)
    }

    @Test
    fun workerMapsRetryableEngineOutcomeToWorkManagerRetry() = runBlocking {
        insertSession()
        transport.resolveFailure = BackloggdException("Temporary failure", retryable = true)
        val worker = TestListenableWorkerBuilder<BackloggdExportWorker>(context).build()

        assertEquals(ListenableWorker.Result.retry(), worker.execute(engine))
        assertEquals(ExportState.WAITING, onlyExport().state)
    }

    @Test
    fun retryableDefiniteSendRejectionReturnsToWaitingWithoutLosingAttempt() = runBlocking {
        insertSession()
        transport.sendAction = {
            throw BackloggdException(
                "Request was rejected before dispatch",
                retryable = true,
                deliveryMayHaveOccurred = false
            )
        }

        assertEquals(BackloggdSyncOutcome.RETRY, engine.run())
        val export = onlyExport()
        assertEquals(ExportState.WAITING, export.state)
        assertEquals(1, export.attemptCount)
        assertEquals(1, transport.sendCount.get())
    }

    @Test
    fun cancellationAfterClaimPersistsUncertainAndPropagates() = runBlocking {
        insertSession()
        transport.sendAction = { throw CancellationException("cancelled") }

        var cancellationPropagated = false
        try {
            engine.run()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertEquals(ExportState.UNCERTAIN, onlyExport().state)
    }

    @Test
    fun onlySafeQueueStatesCanBeRetriedOrExcluded() = runBlocking {
        val sessionId = insertSession()
        engine.queueHistory()
        engine.exclude(sessionId)
        assertEquals(ExportState.EXCLUDED, onlyExport().state)

        engine.retry(sessionId)
        assertEquals(ExportState.WAITING, onlyExport().state)
        database.backloggdDao().transition(
            sessionId = sessionId,
            expectedState = ExportState.WAITING,
            newState = ExportState.UNCERTAIN,
            updatedAtMs = fixedClock.millis(),
            lastError = null
        )

        var retryRejected = false
        try {
            engine.retry(sessionId)
        } catch (_: BackloggdException) {
            retryRejected = true
        }
        var excludeRejected = false
        try {
            engine.exclude(sessionId)
        } catch (_: BackloggdException) {
            excludeRejected = true
        }
        assertTrue(retryRejected)
        assertTrue(excludeRejected)
        assertEquals(ExportState.UNCERTAIN, onlyExport().state)
    }

    private suspend fun verifyPauseStopsBatch(disconnect: Boolean) = coroutineScope {
        val firstSessionId = insertSession()
        val secondSessionId = insertSession(startTime = BASE_START_MS + BASE_DURATION_MS + 60_000L)
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        transport.sendAction = { item ->
            if (transport.sendCount.get() == 1) {
                sendStarted.complete(Unit)
                releaseSend.await()
            }
            val remoteId = "remote-${transport.sendCount.get()}"
            transport.remoteEntries[item.marker] = remoteId
            remoteId
        }

        val run = async(Dispatchers.Default) { engine.run() }
        sendStarted.await()
        val repository = BackloggdRepository(
            context,
            database,
            credentialStore,
            transport,
            fixedClock,
            deviceZone
        )
        val pause = async(Dispatchers.Default) {
            if (disconnect) repository.disconnect() else repository.setExportEnabled(false)
        }
        withTimeout(5_000L) {
            while (database.backloggdDao().getSettings()?.enabled != false) delay(10L)
        }
        releaseSend.complete(Unit)

        run.await()
        pause.await()
        assertEquals(1, transport.sendCount.get())
        assertEquals(ExportState.SENT, database.backloggdDao().getExport(firstSessionId)?.state)
        assertEquals(ExportState.WAITING, database.backloggdDao().getExport(secondSessionId)?.state)
    }
    private suspend fun insertSession(
        startTime: Long = BASE_START_MS,
        durationMs: Long = BASE_DURATION_MS
    ): Long {
        val packageName = "dev.gamerlog.fixture.backloggd_${UUID.randomUUID().toString().take(8)}"
        database.dao().insertDiscoveredGame(
            GameEntity(
                packageName = packageName,
                appName = "Backloggd fixture",
                isTracked = false,
                isAutoDetected = false,
                backloggdSlug = "fixture-game"
            )
        )
        val session = PlaySessionEntity(
            packageName = packageName,
            startTime = startTime,
            endTime = startTime + BASE_DURATION_MS,
            durationMs = durationMs
        )
        return database.dao().insertSessions(listOf(session)).single()
    }

    private suspend fun onlyExport(): BackloggdExportEntity =
        database.backloggdDao().getExportsForAccount("player").single()

    private class MemoryCredentialStore(
        var credentials: BackloggdCredentials?
    ) : BackloggdCredentialStore {
        override fun load(): BackloggdCredentials? = credentials
        override fun save(credentials: BackloggdCredentials) {
            this.credentials = credentials
        }
        override fun clear() {
            credentials = null
        }
    }

    private class FakeTransport : BackloggdTransport {
        val sendCount = AtomicInteger()
        val findCount = AtomicInteger()
        val remoteEntries = mutableMapOf<String, String>()
        var resolveFailure: BackloggdException? = null
        var sendAction: suspend (BackloggdExportEntity) -> String = { item ->
            val id = "remote-${sendCount.get()}"
            remoteEntries[item.marker] = id
            id
        }

        override suspend fun verifyAccount(credentials: BackloggdCredentials) = Unit

        override suspend fun resolveGame(gameUrlOrSlug: String): BackloggdGame {
            resolveFailure?.let { throw it }
            return BackloggdGame(id = "game-$gameUrlOrSlug", slug = gameUrlOrSlug, title = "Fixture")
        }

        override suspend fun findExport(
            credentials: BackloggdCredentials,
            item: BackloggdExportEntity
        ): String? {
            findCount.incrementAndGet()
            return remoteEntries[item.marker]
        }

        override suspend fun sendExport(
            credentials: BackloggdCredentials,
            item: BackloggdExportEntity
        ): String {
            sendCount.incrementAndGet()
            return sendAction(item)
        }
    }

    companion object {
        private val BASE_START_MS = Instant.parse("2100-01-01T10:00:00Z").toEpochMilli()
        private const val BASE_DURATION_MS = 45 * 60_000L
    }
}
