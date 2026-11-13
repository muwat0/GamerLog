package dev.gamerlog.app.core.backloggd

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.gamerlog.app.core.backloggd.security.AndroidBackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdSettingsEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class BackloggdRepositoryDependencies(
    val credentialStore: BackloggdCredentialStore,
    val transport: BackloggdTransport
)

private fun productionDependencies(context: Context): BackloggdRepositoryDependencies {
    val credentialStore = AndroidBackloggdCredentialStore(context.applicationContext)
    return BackloggdRepositoryDependencies(
        credentialStore = credentialStore,
        transport = HttpBackloggdTransport(credentialStore)
    )
}

/** User-facing Backloggd account, mapping, queue, and scheduling operations. */
class BackloggdRepository private constructor(
    context: Context,
    private val database: GamerLogDatabase,
    dependencies: BackloggdRepositoryDependencies,
    private val clock: Clock,
    zoneId: ZoneId?
) {
    constructor(context: Context, database: GamerLogDatabase) : this(
        context.applicationContext,
        database,
        productionDependencies(context.applicationContext),
        Clock.systemUTC(),
        null
    )

    internal constructor(
        context: Context,
        database: GamerLogDatabase,
        credentialStore: BackloggdCredentialStore,
        transport: BackloggdTransport,
        clock: Clock = Clock.systemUTC(),
        zoneId: ZoneId? = null
    ) : this(
        context.applicationContext,
        database,
        BackloggdRepositoryDependencies(credentialStore, transport),
        clock,
        zoneId
    )

    private val credentialStore = dependencies.credentialStore
    private val transport = dependencies.transport
    private val appContext = context.applicationContext
    private val dao = database.backloggdDao()
    private val engine = BackloggdExportEngine(database, credentialStore, transport, clock, zoneId)

    suspend fun connect(username: String, cookieHeader: String) {
        BackloggdOperationLock.mutex.withLock {
            val cleanUsername = username.trim()
            validateCredentialsInput(cleanUsername, cookieHeader)
            val credentials = BackloggdCredentials(cleanUsername, cookieHeader)
            try {
                transport.verifyAccount(credentials)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BackloggdException) {
                throw failure
            } catch (_: Exception) {
                throw BackloggdException(ACCOUNT_VERIFICATION_FAILED, retryable = true)
            }

            val oldSettings = dao.getSettings() ?: BackloggdSettingsEntity()
            if (oldSettings.enabled) dao.saveSettings(oldSettings.copy(enabled = false))
            cancelWork()
            try {
                withContext(Dispatchers.IO) { credentialStore.save(credentials) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw BackloggdException(CREDENTIAL_SAVE_FAILED)
            }
            dao.saveSettings(
                oldSettings.copy(
                    username = cleanUsername.canonicalIdentity(),
                    enabled = false,
                    enabledSinceMs = 0L
                )
            )
        }
    }

    suspend fun disconnect() {
        dao.disableExports()
        var schedulingFailure: BackloggdException? = null
        try {
            cancelWork()
        } catch (failure: BackloggdException) {
            schedulingFailure = failure
        }
        BackloggdOperationLock.mutex.withLock {
            dao.disableExports()
            try {
                cancelWork()
            } catch (failure: BackloggdException) {
                if (schedulingFailure == null) schedulingFailure = failure
            }
            val settings = dao.getSettings() ?: BackloggdSettingsEntity()
            dao.saveSettings(settings.copy(username = null, enabled = false, enabledSinceMs = 0L))
            try {
                withContext(Dispatchers.IO) { credentialStore.clear() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw BackloggdException(CREDENTIAL_CLEAR_FAILED)
            }
        }
        schedulingFailure?.let { throw it }
    }

    suspend fun setExportEnabled(enabled: Boolean) {
        if (!enabled) {
            pauseExports()
            return
        }
        BackloggdOperationLock.mutex.withLock {
            val settings = dao.getSettings() ?: BackloggdSettingsEntity()
            val username = settings.username?.canonicalIdentity()
                ?: throw BackloggdException(NOT_CONNECTED)
            val credentials = loadCredentials() ?: throw BackloggdException(NOT_CONNECTED)
            if (credentials.username.canonicalIdentity() != username) {
                throw BackloggdException(NOT_CONNECTED)
            }
            if (settings.enabled) {
                schedulePeriodicWork()
            } else {
                dao.saveSettings(settings.copy(enabled = true, enabledSinceMs = clockNow()))
                schedulePeriodicWork()
            }
        }
    }

    suspend fun setGameMapping(packageName: String, gameUrlOrSlug: String) {
        BackloggdOperationLock.mutex.withLock {
            val cleanPackageName = packageName.trim()
            val target = gameUrlOrSlug.trim()
            if (cleanPackageName.isBlank() || target.isBlank()) {
                throw BackloggdException(INVALID_MAPPING)
            }
            val game = try {
                transport.resolveGame(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BackloggdException) {
                throw failure
            } catch (_: Exception) {
                throw BackloggdException(GAME_LOOKUP_FAILED, retryable = true)
            }
            if (game.id.isBlank() || game.slug.isBlank() || game.title.isBlank()) {
                throw BackloggdException(GAME_LOOKUP_FAILED)
            }
            if (dao.setGameMapping(cleanPackageName, game.slug) != 1) {
                throw BackloggdException(APP_NOT_FOUND)
            }
        }
    }

    suspend fun clearGameMapping(packageName: String) {
        BackloggdOperationLock.mutex.withLock {
            val cleanPackageName = packageName.trim()
            if (cleanPackageName.isBlank() || dao.setGameMapping(cleanPackageName, null) != 1) {
                throw BackloggdException(APP_NOT_FOUND)
            }
        }
    }

    suspend fun queueHistory(): Int = engine.queueHistory()

    suspend fun retry(sessionId: Long) = engine.retry(sessionId)

    suspend fun exclude(sessionId: Long) = engine.exclude(sessionId)

    suspend fun syncNow() {
        BackloggdOperationLock.mutex.withLock {
            val settings = dao.getSettings() ?: BackloggdSettingsEntity()
            val username = settings.username?.canonicalIdentity()
            if (!settings.enabled || username == null) {
                throw BackloggdException(EXPORTS_DISABLED_OR_DISCONNECTED)
            }
            val credentials = loadCredentials() ?: throw BackloggdException(NOT_CONNECTED)
            if (credentials.username.canonicalIdentity() != username) {
                throw BackloggdException(NOT_CONNECTED)
            }
            scheduleOneTimeWork()
        }
    }

    suspend fun restoreScheduling() {
        BackloggdOperationLock.mutex.withLock {
            val enabled = dao.getSettings()?.enabled == true
            if (enabled) schedulePeriodicWork() else cancelWork()
        }
    }

    private suspend fun pauseExports() {
        dao.disableExports()
        var schedulingFailure: BackloggdException? = null
        try {
            cancelWork()
        } catch (failure: BackloggdException) {
            schedulingFailure = failure
        }
        try {
            BackloggdOperationLock.mutex.withLock {
                dao.disableExports()
                cancelWork()
            }
        } catch (failure: BackloggdException) {
            if (schedulingFailure == null) schedulingFailure = failure
        }
        schedulingFailure?.let { throw it }
    }

    private suspend fun loadCredentials(): BackloggdCredentials? = withContext(Dispatchers.IO) {
        try {
            credentialStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackloggdException(CREDENTIALS_UNAVAILABLE)
        }
    }

    private fun schedulePeriodicWork() {
        try {
            val request = PeriodicWorkRequestBuilder<BackloggdExportWorker>(
                WORK_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackloggdException(WORK_SCHEDULING_FAILED, retryable = true)
        }
    }

    private fun scheduleOneTimeWork() {
        try {
            val request = OneTimeWorkRequestBuilder<BackloggdExportWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackloggdException(WORK_SCHEDULING_FAILED, retryable = true)
        }
    }

    private fun cancelWork() {
        try {
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(ONE_TIME_WORK_NAME)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackloggdException(WORK_SCHEDULING_FAILED, retryable = true)
        }
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun clockNow(): Long = clock.millis()

    private fun validateCredentialsInput(username: String, cookieHeader: String) {
        if (!USERNAME_PATTERN.matches(username) || cookieHeader.isBlank() ||
            cookieHeader.length > MAX_COOKIE_LENGTH ||
            cookieHeader.any { it == '\r' || it == '\n' || it.isISOControl() }
        ) {
            throw BackloggdException(INVALID_CREDENTIALS)
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "backloggd_periodic_export"
        const val ONE_TIME_WORK_NAME = "backloggd_export_now"
        private const val WORK_INTERVAL_MINUTES = 15L
        private const val BACKOFF_DELAY_MILLIS = 30_000L
        private const val MAX_COOKIE_LENGTH = 16_384
        private val USERNAME_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        private const val INVALID_CREDENTIALS = "Enter a valid Backloggd username and cookie header."
        private const val ACCOUNT_VERIFICATION_FAILED = "Could not verify this Backloggd account."
        private const val CREDENTIAL_SAVE_FAILED = "Backloggd credentials could not be saved."
        private const val CREDENTIAL_CLEAR_FAILED = "Backloggd credentials could not be cleared."
        private const val CREDENTIALS_UNAVAILABLE = "Stored Backloggd credentials are unavailable."
        private const val NOT_CONNECTED = "Connect a Backloggd account before continuing."
        private const val EXPORTS_DISABLED = "Backloggd exports are disabled."
        private const val EXPORTS_DISABLED_OR_DISCONNECTED =
            "Enable exports and connect a Backloggd account before syncing."
        private const val INVALID_MAPPING = "Enter an installed app and a Backloggd game."
        private const val GAME_LOOKUP_FAILED = "Could not verify that Backloggd game."
        private const val APP_NOT_FOUND = "The installed app could not be found."
        private const val WORK_SCHEDULING_FAILED = "Backloggd export scheduling failed."
    }
}

