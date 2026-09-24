package dev.gamerlog.app.core.tracker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.gamerlog.app.core.catalog.AppCatalogManager
import dev.gamerlog.app.core.database.GamerLogDatabase
import java.util.concurrent.TimeUnit

class UsageScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "UsageScanWorker"
        const val UNIQUE_WORK_NAME = "periodic_usage_scan_worker"
        private const val SCAN_INTERVAL_MINUTES = 15L

        /**
         * Schedules the periodic scan worker if Usage Access permission is granted.
         * Uses ExistingPeriodicWorkPolicy.KEEP to prevent restarting intervals.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageScanWorker>(
                SCAN_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val database = GamerLogDatabase.get(applicationContext)
        val scanner = UsageScanner(applicationContext, database)

        if (!scanner.hasUsageAccess()) {
            // Permission was revoked while scheduled; cancel future work
            cancel(applicationContext)
            return Result.success()
        }

        return try {
            // Discover any newly installed launchable games before scanning deltas
            val catalogManager = AppCatalogManager(applicationContext, database)
            catalogManager.syncDiscoveredApps()

            val insertedCount = scanner.scan()
            Log.d(TAG, "Usage scan completed successfully; inserted $insertedCount session slices")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Periodic usage scan failed", e)
            Result.retry()
        }
    }
}
