package dev.gamerlog.app.core.backloggd

import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdExportEntity

/** Implementations must return only confirmed identity, mapping, and delivery results. */
interface BackloggdTransport {
    suspend fun verifyAccount(credentials: BackloggdCredentials)
    suspend fun resolveGame(gameUrlOrSlug: String): BackloggdGame
    /** Null means the marker was not found, not that a previous POST is safe to repeat. */
    suspend fun findExport(credentials: BackloggdCredentials, item: BackloggdExportEntity): String?
    /** Returns a remote record identifier only after the unique marker is acknowledged/read back. */
    suspend fun sendExport(credentials: BackloggdCredentials, item: BackloggdExportEntity): String
}

data class BackloggdGame(val id: String, val slug: String, val title: String)

/** A sanitized, user-facing failure. Never pass HTTP bodies, credentials, or raw causes here. */
class BackloggdException(
    message: String,
    val retryable: Boolean = false,
    val deliveryMayHaveOccurred: Boolean = false
) : Exception(message)
