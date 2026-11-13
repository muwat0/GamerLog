package dev.gamerlog.app.core.backloggd

import dev.gamerlog.app.core.backloggd.security.BackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdExportEntity
import dev.gamerlog.app.core.database.BackloggdSettingsEntity
import dev.gamerlog.app.core.database.ExportState
import dev.gamerlog.app.core.database.GamerLogDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object BackloggdOperationLock {
    val mutex = Mutex()
}

internal enum class BackloggdSyncOutcome {
    COMPLETE,
    RETRY
}

/** Serializes local export mutations and delivers only durable, account-bound outbox rows. */
internal class BackloggdExportEngine(
    private val database: GamerLogDatabase,
    private val credentialStore: BackloggdCredentialStore,
    private val transport: BackloggdTransport,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId? = null
) {
    private val dao = database.backloggdDao()

    suspend fun queueHistory(): Int = BackloggdOperationLock.mutex.withLock {
        val settings = dao.getSettings() ?: BackloggdSettingsEntity()
        if (!settings.enabled) throw BackloggdException(EXPORTS_DISABLED)
        val username = settings.username?.canonicalIdentity()
            ?: throw BackloggdException(NOT_CONNECTED)
        val credentials = loadCredentials()
            ?: throw BackloggdException(NOT_CONNECTED)
        if (credentials.username.canonicalIdentity() != username) {
            throw BackloggdException(NOT_CONNECTED)
        }
        enqueueEligible(username, sinceMs = 0L)
    }

    /** Runs recovery, materialization, reconciliation, then safe sends under the shared lock. */
    suspend fun run(): BackloggdSyncOutcome = BackloggdOperationLock.mutex.withLock {
        recoverInterruptedSends()

        val settings = dao.getSettings() ?: BackloggdSettingsEntity()
        val username = settings.username?.canonicalIdentity()
        if (settings.enabled && username != null) {
            enqueueEligible(username, settings.enabledSinceMs)
        }
        if (!settings.enabled || username == null) return@withLock BackloggdSyncOutcome.COMPLETE

        val credentials = try {
            loadCredentials()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock BackloggdSyncOutcome.RETRY
        } ?: return@withLock BackloggdSyncOutcome.COMPLETE
        if (credentials.username.canonicalIdentity() != username) {
            return@withLock BackloggdSyncOutcome.COMPLETE
        }

        val exports = dao.getExportsForAccount(username)
        if (exports.isEmpty()) return@withLock BackloggdSyncOutcome.COMPLETE

        try {
            transport.verifyAccount(credentials)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BackloggdException) {
            if (failure.retryable) return@withLock BackloggdSyncOutcome.RETRY
            if (!isActiveAccount(username, credentials)) return@withLock BackloggdSyncOutcome.COMPLETE
            exports.filter { it.state == ExportState.WAITING }.forEach {
                failWaiting(it.sessionId, safeMessage(failure, ACCOUNT_VERIFICATION_FAILED))
            }
            return@withLock BackloggdSyncOutcome.COMPLETE
        } catch (_: Exception) {
            return@withLock BackloggdSyncOutcome.RETRY
        }

        for (export in exports) {
            if (!isActiveAccount(username, credentials)) return@withLock BackloggdSyncOutcome.COMPLETE
            when (export.state) {
                ExportState.UNCERTAIN -> {
                    if (reconcile(credentials, export) == BackloggdSyncOutcome.RETRY) {
                        return@withLock BackloggdSyncOutcome.RETRY
                    }
                }
                ExportState.WAITING -> {
                    if (prepareAndSend(username, credentials, export) == BackloggdSyncOutcome.RETRY) {
                        return@withLock BackloggdSyncOutcome.RETRY
                    }
                }
                // New SENDING rows are converted to UNCERTAIN in recoverInterruptedSends().
                ExportState.SENDING,
                ExportState.SENT,
                ExportState.EXCLUDED,
                ExportState.FAILED -> Unit
            }
        }
        BackloggdSyncOutcome.COMPLETE
    }

    private suspend fun isActiveAccount(
        accountUsername: String,
        credentials: BackloggdCredentials
    ): Boolean {
        if (credentials.username.canonicalIdentity() != accountUsername) return false
        val settings = dao.getSettings() ?: return false
        return settings.enabled && settings.username?.canonicalIdentity() == accountUsername
    }

    suspend fun retry(sessionId: Long) = BackloggdOperationLock.mutex.withLock {
        val export = dao.getExport(sessionId) ?: throw BackloggdException(EXPORT_NOT_FOUND)
        if (export.state != ExportState.FAILED && export.state != ExportState.EXCLUDED) {
            throw BackloggdException(RETRY_NOT_ALLOWED)
        }
        val changed = dao.transition(
            sessionId = sessionId,
            expectedState = export.state,
            newState = ExportState.WAITING,
            updatedAtMs = clock.millis(),
            lastError = null
        )
        if (changed != 1) throw BackloggdException(RETRY_NOT_ALLOWED)
    }

    suspend fun exclude(sessionId: Long) = BackloggdOperationLock.mutex.withLock {
        val export = dao.getExport(sessionId) ?: throw BackloggdException(EXPORT_NOT_FOUND)
        if (export.state != ExportState.WAITING && export.state != ExportState.FAILED) {
            throw BackloggdException(EXCLUDE_NOT_ALLOWED)
        }
        val changed = dao.transition(
            sessionId = sessionId,
            expectedState = export.state,
            newState = ExportState.EXCLUDED,
            updatedAtMs = clock.millis(),
            lastError = null
        )
        if (changed != 1) throw BackloggdException(EXCLUDE_NOT_ALLOWED)
    }

    private suspend fun enqueueEligible(username: String, sinceMs: Long): Int {
        val sessions = dao.eligibleSessions(sinceMs)
        if (sessions.isEmpty()) return 0
        val now = clock.millis()
        val exports = sessions.mapNotNull { session ->
            if (session.durationMs <= 0L || session.gameSlug.isBlank()) return@mapNotNull null
            val playedOn = LocalDate.ofInstant(
                Instant.ofEpochMilli(session.startTime),
                zoneId ?: ZoneId.systemDefault()
            ).toString()
            BackloggdExportEntity(
                sessionId = session.sessionId,
                accountUsername = username,
                gameSlug = session.gameSlug,
                gameId = null,
                playedOn = playedOn,
                durationMs = session.durationMs,
                marker = UUID.randomUUID().toString(),
                state = ExportState.WAITING,
                createdAtMs = now,
                updatedAtMs = now
            )
        }
        if (exports.isEmpty()) return 0
        return dao.insertExports(exports).count { it >= 0L }
    }

    private suspend fun recoverInterruptedSends() {
        val sending = dao.observeExports().first().filter { it.state == ExportState.SENDING }
        for (export in sending) {
            val changed = dao.transition(
                sessionId = export.sessionId,
                expectedState = ExportState.SENDING,
                newState = ExportState.UNCERTAIN,
                updatedAtMs = clock.millis(),
                lastError = DELIVERY_UNCERTAIN
            )
            if (changed != 1 && dao.getExport(export.sessionId)?.state == ExportState.SENDING) {
                throw outboxChanged()
            }
        }
    }

    private suspend fun prepareAndSend(
        accountUsername: String,
        credentials: BackloggdCredentials,
        original: BackloggdExportEntity
    ): BackloggdSyncOutcome {
        var export = original
        if (export.gameId == null) {
            val game = try {
                transport.resolveGame(export.gameSlug)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BackloggdException) {
                return handleWaitingFailure(export.sessionId, failure)
            } catch (_: Exception) {
                setWaitingError(export.sessionId, TEMPORARY_FAILURE)
                return BackloggdSyncOutcome.RETRY
            }
            if (game.id.isBlank() || game.slug != export.gameSlug) {
                failWaiting(export.sessionId, GAME_MAPPING_INVALID)
                return BackloggdSyncOutcome.COMPLETE
            }
            if (dao.setPreparedGameId(export.sessionId, game.id, clock.millis()) != 1) {
                val latest = dao.getExport(export.sessionId)
                if (latest?.state != ExportState.WAITING) return BackloggdSyncOutcome.COMPLETE
                export = latest
                if (export.gameId == null) throw outboxChanged()
            } else {
                export = export.copy(gameId = game.id)
            }
        }

        if (!isActiveAccount(accountUsername, credentials)) return BackloggdSyncOutcome.COMPLETE
        val existingRemoteId = try {
            transport.findExport(credentials, export)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BackloggdException) {
            return handleWaitingFailure(export.sessionId, failure)
        } catch (_: Exception) {
            setWaitingError(export.sessionId, TEMPORARY_FAILURE)
            return BackloggdSyncOutcome.RETRY
        }
        if (!isActiveAccount(accountUsername, credentials)) return BackloggdSyncOutcome.COMPLETE
        if (existingRemoteId?.isBlank() == true) {
            setWaitingError(export.sessionId, TEMPORARY_FAILURE)
            return BackloggdSyncOutcome.RETRY
        }
        if (existingRemoteId != null) {
            val claimed = dao.transition(
                sessionId = export.sessionId,
                expectedState = ExportState.WAITING,
                newState = ExportState.SENDING,
                updatedAtMs = clock.millis(),
                lastError = null
            )
            if (claimed != 1) return BackloggdSyncOutcome.COMPLETE
            markSent(export.sessionId, existingRemoteId)
            return BackloggdSyncOutcome.COMPLETE
        }

        if (!isActiveAccount(accountUsername, credentials)) return BackloggdSyncOutcome.COMPLETE
        val claimed = dao.transition(
            sessionId = export.sessionId,
            expectedState = ExportState.WAITING,
            newState = ExportState.SENDING,
            updatedAtMs = clock.millis(),
            lastError = null
        )
        if (claimed != 1) return BackloggdSyncOutcome.COMPLETE
        return sendClaimed(accountUsername, credentials, export)
    }

    private suspend fun sendClaimed(
        accountUsername: String,
        credentials: BackloggdCredentials,
        export: BackloggdExportEntity
    ): BackloggdSyncOutcome {
        try {
            if (!isActiveAccount(accountUsername, credentials)) {
                returnToWaiting(export.sessionId, PAUSED_BEFORE_SEND)
                return BackloggdSyncOutcome.COMPLETE
            }
            if (dao.incrementAttempt(export.sessionId, clock.millis()) != 1) {
                val latest = dao.getExport(export.sessionId)
                if (latest?.state == ExportState.SENDING) throw outboxChanged()
                return BackloggdSyncOutcome.COMPLETE
            }
            if (!isActiveAccount(accountUsername, credentials)) {
                returnToWaiting(export.sessionId, PAUSED_BEFORE_SEND)
                return BackloggdSyncOutcome.COMPLETE
            }
        } catch (cancelled: CancellationException) {
            persistUncertainAfterCancellation(export.sessionId)
            throw cancelled
        } catch (_: Exception) {
            markUncertain(export.sessionId)
            return BackloggdSyncOutcome.RETRY
        }

        val remoteEntryId = try {
            transport.sendExport(credentials, export)
        } catch (cancelled: CancellationException) {
            persistUncertainAfterCancellation(export.sessionId)
            throw cancelled
        } catch (failure: BackloggdException) {
            if (failure.deliveryMayHaveOccurred) {
                markUncertain(export.sessionId, safeMessage(failure, DELIVERY_UNCERTAIN))
                return if (failure.retryable) BackloggdSyncOutcome.RETRY else BackloggdSyncOutcome.COMPLETE
            }
            if (failure.retryable) {
                returnToWaiting(export.sessionId, safeMessage(failure, TEMPORARY_FAILURE))
                return BackloggdSyncOutcome.RETRY
            }
            failSending(export.sessionId, safeMessage(failure, SEND_REJECTED))
            return BackloggdSyncOutcome.COMPLETE
        } catch (_: Exception) {
            markUncertain(export.sessionId)
            return BackloggdSyncOutcome.RETRY
        }

        if (remoteEntryId.isBlank()) {
            markUncertain(export.sessionId)
            return BackloggdSyncOutcome.RETRY
        }
        try {
            markSent(export.sessionId, remoteEntryId)
            return BackloggdSyncOutcome.COMPLETE
        } catch (cancelled: CancellationException) {
            persistUncertainAfterCancellation(export.sessionId)
            throw cancelled
        } catch (_: Exception) {
            markUncertain(export.sessionId)
            return BackloggdSyncOutcome.RETRY
        }
    }

    private suspend fun reconcile(
        credentials: BackloggdCredentials,
        export: BackloggdExportEntity
    ): BackloggdSyncOutcome {
        val remoteEntryId = try {
            transport.findExport(credentials, export)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BackloggdException) {
            return if (failure.retryable) BackloggdSyncOutcome.RETRY else BackloggdSyncOutcome.COMPLETE
        } catch (_: Exception) {
            return BackloggdSyncOutcome.RETRY
        } ?: return BackloggdSyncOutcome.COMPLETE
        if (remoteEntryId.isBlank()) return BackloggdSyncOutcome.RETRY
        markSent(export.sessionId, remoteEntryId)
        return BackloggdSyncOutcome.COMPLETE
    }

    private suspend fun handleWaitingFailure(
        sessionId: Long,
        failure: BackloggdException
    ): BackloggdSyncOutcome {
        val message = safeMessage(failure, TEMPORARY_FAILURE)
        if (failure.retryable) {
            setWaitingError(sessionId, message)
            return BackloggdSyncOutcome.RETRY
        }
        failWaiting(sessionId, message)
        return BackloggdSyncOutcome.COMPLETE
    }

    private suspend fun setWaitingError(sessionId: Long, message: String) {
        val changed = dao.transition(
            sessionId = sessionId,
            expectedState = ExportState.WAITING,
            newState = ExportState.WAITING,
            updatedAtMs = clock.millis(),
            lastError = message
        )
        if (changed != 1 && dao.getExport(sessionId)?.state == ExportState.WAITING) {
            throw outboxChanged()
        }
    }

    private suspend fun failWaiting(sessionId: Long, message: String) {
        val changed = dao.transition(
            sessionId = sessionId,
            expectedState = ExportState.WAITING,
            newState = ExportState.FAILED,
            updatedAtMs = clock.millis(),
            lastError = message
        )
        if (changed != 1 && dao.getExport(sessionId)?.state == ExportState.WAITING) {
            throw outboxChanged()
        }
    }

    private suspend fun failSending(sessionId: Long, message: String) {
        val changed = dao.transition(
            sessionId = sessionId,
            expectedState = ExportState.SENDING,
            newState = ExportState.FAILED,
            updatedAtMs = clock.millis(),
            lastError = message
        )
        if (changed != 1 && dao.getExport(sessionId)?.state == ExportState.SENDING) {
            throw outboxChanged()
        }
    }

    private suspend fun returnToWaiting(sessionId: Long, message: String) {
        val failed = dao.transition(
            sessionId = sessionId,
            expectedState = ExportState.SENDING,
            newState = ExportState.FAILED,
            updatedAtMs = clock.millis(),
            lastError = message
        )
        val state = dao.getExport(sessionId)?.state
        if (failed != 1 && state != ExportState.FAILED) {
            if (state == ExportState.SENDING) throw outboxChanged()
            return
        }

        val retried = dao.transition(
            sessionId = sessionId,
            expectedState = ExportState.FAILED,
            newState = ExportState.WAITING,
            updatedAtMs = clock.millis(),
            lastError = message
        )
        if (retried != 1 && dao.getExport(sessionId)?.state == ExportState.FAILED) {
            throw outboxChanged()
        }
    }

    private suspend fun markUncertain(sessionId: Long, message: String = DELIVERY_UNCERTAIN) {
        val changed = dao.transition(
            sessionId = sessionId,
            expectedState = ExportState.SENDING,
            newState = ExportState.UNCERTAIN,
            updatedAtMs = clock.millis(),
            lastError = message
        )
        if (changed != 1 && dao.getExport(sessionId)?.state == ExportState.SENDING) {
            throw outboxChanged()
        }
    }
    private suspend fun persistUncertainAfterCancellation(sessionId: Long) {
        try {
            withContext(NonCancellable) { markUncertain(sessionId) }
        } catch (_: Exception) {
            // Preserve the original cancellation even if durable recovery itself fails.
        }
    }

    private suspend fun markSent(sessionId: Long, remoteEntryId: String) {
        if (remoteEntryId.isBlank()) throw BackloggdException(DELIVERY_UNCERTAIN)
        val changed = dao.markSent(sessionId, remoteEntryId, clock.millis())
        if (changed != 1 && dao.getExport(sessionId)?.state != ExportState.SENT) {
            throw outboxChanged()
        }
    }

    private suspend fun loadCredentials(): BackloggdCredentials? = withContext(Dispatchers.IO) {
        try {
            credentialStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackloggdException(CREDENTIALS_UNAVAILABLE, retryable = true)
        }
    }

    private fun safeMessage(failure: BackloggdException, fallback: String): String =
        failure.message?.takeIf(String::isNotBlank) ?: fallback
    private fun outboxChanged(): BackloggdException =
        BackloggdException(OUTBOX_CHANGED, retryable = true)

    companion object {
        private const val NOT_CONNECTED = "Connect a Backloggd account before exporting."
        private const val EXPORTS_DISABLED = "Enable Backloggd exports before queueing history."
        private const val EXPORT_NOT_FOUND = "The export item could not be found."
        private const val RETRY_NOT_ALLOWED = "Only failed or excluded exports can be retried."
        private const val EXCLUDE_NOT_ALLOWED = "Only waiting or failed exports can be excluded."
        private const val GAME_MAPPING_INVALID = "The selected Backloggd game could not be verified."
        private const val ACCOUNT_VERIFICATION_FAILED = "Could not verify the connected Backloggd account."
        private const val CREDENTIALS_UNAVAILABLE = "Stored Backloggd credentials are unavailable."
        private const val DELIVERY_UNCERTAIN = "Delivery is uncertain; checking Backloggd before retrying."
        private const val SEND_REJECTED = "Backloggd rejected this export."
        private const val TEMPORARY_FAILURE = "Backloggd is temporarily unavailable."
        private const val PAUSED_BEFORE_SEND = "Export was paused before sending."
        private const val OUTBOX_CHANGED = "The export state changed unexpectedly; try again."
    }
}

internal fun String.canonicalIdentity(): String = trim().lowercase(Locale.ROOT)
