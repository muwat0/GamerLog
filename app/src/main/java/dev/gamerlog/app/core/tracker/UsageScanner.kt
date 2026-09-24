package dev.gamerlog.app.core.tracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.database.OpenSessionEntity
import dev.gamerlog.app.core.database.PlaySessionEntity
import dev.gamerlog.app.core.database.ScanCheckpointEntity
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class UsageScanner(
    private val context: Context,
    private val database: GamerLogDatabase
) {
    companion object {
        const val CHECKPOINT_KEY = "usage_events"
        // Initial lookback if no checkpoint exists: 24 hours
        val INITIAL_LOOKBACK_MS = TimeUnit.DAYS.toMillis(1)

        // Mutex to serialize in-process scans
        private val scanMutex = Mutex()
    }

    /**
     * Checks if PACKAGE_USAGE_STATS permission is granted via AppOpsManager.
     */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Executes a scan of UsageEvents.
     * @return Number of newly persisted session slices inserted, or 0 if skipped/no-op.
     */
    suspend fun scan(): Int {
        if (!hasUsageAccess()) {
            return 0
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0

        return scanMutex.withLock {
            val dao = database.dao()

            // 1. Read current checkpoint outside or before querying events
            val currentCheckpoint = dao.getCheckpoint()
            val now = System.currentTimeMillis()
            val startTime = currentCheckpoint?.timestampMs ?: (now - INITIAL_LOOKBACK_MS)

            if (startTime >= now) {
                return@withLock 0
            }

            // 2. Read tracked package names and open sessions
            val trackedPackages = dao.getTrackedPackageNames().toSet()
            if (trackedPackages.isEmpty()) {
                // If there are no tracked packages, update checkpoint without parsing events
                database.withTransaction {
                    val dbCheckpoint = dao.getCheckpoint()
                    val latestTracked = dao.getTrackedPackageNames().toSet()
                    if (latestTracked.isEmpty() && dbCheckpoint?.timestampMs == currentCheckpoint?.timestampMs) {
                        dao.saveCheckpoint(ScanCheckpointEntity(CHECKPOINT_KEY, now))
                    }
                }
                return@withLock 0
            }

            val durableOpenSessions = dao.getOpenSessions().associate { it.packageName to it.startTime }

            // 3. Query UsageEvents between startTime and now
            val events = mutableListOf<UsageEvent>()
            val usageEvents = usageStatsManager.queryEvents(startTime, now)
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (pkg !in trackedPackages) continue

                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventKind.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventKind.PAUSED
                    UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventKind.STOPPED
                    else -> null
                } ?: continue

                events.add(UsageEvent(pkg, event.timeStamp, kind))
            }

            // 4. Parse events using UsageDeltaParser
            val zoneId = ZoneId.systemDefault()
            val parseResult = UsageDeltaParser.parse(
                events = events,
                openSessions = durableOpenSessions,
                trackedPackages = trackedPackages,
                zoneId = zoneId
            )

            val sessionEntities = parseResult.sessions.map { slice ->
                PlaySessionEntity(
                    packageName = slice.packageName,
                    startTime = slice.startTime,
                    endTime = slice.endTime,
                    durationMs = slice.durationMs,
                    syncStatus = 0
                )
            }

            val newOpenSessions = parseResult.openSessions.map { (pkg, start) ->
                OpenSessionEntity(packageName = pkg, startTime = start)
            }

            // 5. Transactionally verify checkpoint AND trackedPackages have not changed
            var insertedCount = 0
            database.withTransaction {
                val latestCheckpoint = dao.getCheckpoint()
                // If checkpoint was updated by another process/worker while we were querying, abort to avoid stale overwrite
                if (latestCheckpoint?.timestampMs != currentCheckpoint?.timestampMs) {
                    return@withTransaction
                }

                // If user toggled any app's tracked status concurrently, abort so next scan runs with new tracked set
                // and avoids inserting sessions or restoring open state for untracked packages
                val latestTrackedPackages = dao.getTrackedPackageNames().toSet()
                if (latestTrackedPackages != trackedPackages) {
                    return@withTransaction
                }

                if (sessionEntities.isNotEmpty()) {
                    val rowIds = dao.insertSessions(sessionEntities)
                    // With OnConflictStrategy.IGNORE, ignored conflicting rows return -1L
                    insertedCount = rowIds.count { it != -1L }
                }

                dao.clearOpenSessions()
                if (newOpenSessions.isNotEmpty()) {
                    dao.insertOpenSessions(newOpenSessions)
                }

                dao.saveCheckpoint(ScanCheckpointEntity(CHECKPOINT_KEY, now))
            }

            insertedCount
        }
    }
}
