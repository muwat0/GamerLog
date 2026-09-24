package dev.gamerlog.app.core.catalog

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase

data class DiscoveredApp(
    val packageName: String,
    val appName: String,
    val isGameCategory: Boolean
)

class AppCatalogManager(
    private val context: Context,
    private val database: GamerLogDatabase
) {
    /**
     * Query all launchable apps on the device.
     */
    fun discoverLaunchableApps(): List<DiscoveredApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        val myPackageName = context.packageName

        return resolveInfos
            .mapNotNull { resolveInfo ->
                val pkgName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (pkgName == myPackageName) return@mapNotNull null
                val appLabel = resolveInfo.loadLabel(packageManager)?.toString() ?: pkgName
                val isGame = isGameApp(packageManager, resolveInfo.activityInfo.applicationInfo)
                DiscoveredApp(
                    packageName = pkgName,
                    appName = appLabel,
                    isGameCategory = isGame
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    /**
     * Discovers all launchable apps and syncs them to the database.
     * New apps are inserted with OnConflictStrategy.IGNORE:
     * - If CATEGORY_GAME, isTracked = true, isAutoDetected = true.
     * - If not CATEGORY_GAME, isTracked = false, isAutoDetected = false.
     * Existing user toggles are preserved.
     */
    suspend fun syncDiscoveredApps(): List<DiscoveredApp> {
        val apps = discoverLaunchableApps()
        val dao = database.dao()
        val now = System.currentTimeMillis()

        for (app in apps) {
            val entity = GameEntity(
                packageName = app.packageName,
                appName = app.appName,
                customTitle = null,
                isTracked = app.isGameCategory,
                isAutoDetected = app.isGameCategory,
                backloggdSlug = null,
                iconUri = null,
                createdAt = now
            )
            dao.insertDiscoveredGame(entity)
        }

        return apps
    }

    /**
     * Manually toggle tracking for any app.
     * If untracked, removes any lingering open session for that package transactionally.
     */
    suspend fun setAppTracked(packageName: String, isTracked: Boolean) {
        val dao = database.dao()
        database.withTransaction {
            dao.setTracked(packageName, isTracked)
            if (!isTracked) {
                val openSessions = dao.getOpenSessions()
                if (openSessions.any { it.packageName == packageName }) {
                    val remaining = openSessions.filterNot { it.packageName == packageName }
                    dao.clearOpenSessions()
                    if (remaining.isNotEmpty()) {
                        dao.insertOpenSessions(remaining)
                    }
                }
            }
        }
    }

    private fun isGameApp(pm: PackageManager, appInfo: ApplicationInfo?): Boolean {
        if (appInfo == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_GAME) {
                return true
            }
        }
        @Suppress("DEPRECATION")
        if ((appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0) {
            return true
        }
        return false
    }
}
