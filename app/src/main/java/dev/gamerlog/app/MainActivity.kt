package dev.gamerlog.app

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import dev.gamerlog.app.core.catalog.AppCatalogManager
import dev.gamerlog.app.core.database.GamePlaytime
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.tracker.UsageScanWorker
import dev.gamerlog.app.core.tracker.UsageScanner
import dev.gamerlog.app.ui.activity.ActivityContent
import dev.gamerlog.app.ui.games.GamesScreen
import dev.gamerlog.app.core.backloggd.BackloggdRepository
import dev.gamerlog.app.ui.backloggd.BackloggdScreen
import dev.gamerlog.app.ui.theme.GamerLogTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var usageAccessGranted by mutableStateOf(false)
    private var currentDayWindow by mutableStateOf(DayWindow.today())
    private var resumeRevision by mutableIntStateOf(0)
    private lateinit var database: GamerLogDatabase
    private lateinit var catalogManager: AppCatalogManager
    private lateinit var usageScanner: UsageScanner

    private val timeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            currentDayWindow = DayWindow.today()
            resumeRevision++
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        registerReceiver(timeChangeReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeChangeReceiver)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = GamerLogDatabase.get(applicationContext)
        catalogManager = AppCatalogManager(applicationContext, database)
        usageScanner = UsageScanner(applicationContext, database)

        // onResume is always invoked immediately after onCreate in the activity lifecycle,
        // so we avoid duplicate execution by running scan/discovery only in onResume.

        setContent {
            GamerLogTheme {
                val games by database.dao().observeGames().collectAsState(initial = emptyList())
                val coroutineScope = rememberCoroutineScope()

                LaunchedEffect(currentDayWindow, resumeRevision) {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        val delayUntilMidnight = (currentDayWindow.endExclusiveMs - now).coerceAtLeast(1L)
                        delay(delayUntilMidnight)
                        val refreshed = DayWindow.today()
                        if (refreshed != currentDayWindow) {
                            currentDayWindow = refreshed
                            break
                        }
                    }
                }

                val dailyPlaytime = key(currentDayWindow) {
                    val dailyPlaytimeFlow = remember(currentDayWindow) {
                        database.dao().observeGamePlaytime(
                            startInclusiveMs = currentDayWindow.startInclusiveMs,
                            endExclusiveMs = currentDayWindow.endExclusiveMs
                        )
                    }
                    val state by dailyPlaytimeFlow.collectAsState(initial = null)
                    state
                }

                GamerLogScreen(
                    hasUsageAccess = usageAccessGranted,
                    games = games,
                    dailyPlaytime = dailyPlaytime,
                    currentDayWindow = currentDayWindow,
                    database = database,
                    onToggleTracked = { packageName, isTracked ->
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                catalogManager.setAppTracked(packageName, isTracked)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to toggle tracking for $packageName", e)
                            }
                        }
                    },
                    onOpenUsageSettings = {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        currentDayWindow = DayWindow.today()
        resumeRevision++
        refreshUsageAccess()
        triggerBackgroundScanAndDiscovery()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    BackloggdRepository(applicationContext, database).restoreScheduling()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w(TAG, "Backloggd scheduling unavailable")
            }
        }
    }
    private fun refreshUsageAccess() {
        val appOpsManager = getSystemService(AppOpsManager::class.java)
        val granted = appOpsManager?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED

        usageAccessGranted = granted
        if (granted) {
            UsageScanWorker.schedule(applicationContext)
        } else {
            UsageScanWorker.cancel(applicationContext)
        }
    }

    private fun triggerBackgroundScanAndDiscovery() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    catalogManager.syncDiscoveredApps()
                    if (usageAccessGranted) {
                        usageScanner.scan()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background discovery or usage scan failed", e)
            }
        }
    }
}

@Composable
private fun GamerLogScreen(
    hasUsageAccess: Boolean,
    games: List<GameEntity>,
    dailyPlaytime: List<GamePlaytime>?,
    currentDayWindow: DayWindow,
    database: GamerLogDatabase,
    onToggleTracked: (packageName: String, isTracked: Boolean) -> Unit,
    onOpenUsageSettings: () -> Unit
) {
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }
    var selectedRange by rememberSaveable { mutableIntStateOf(0) }
    val destinations = listOf("Summary", "Games", "Activity", "Backloggd")
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedDestination == 0,
                    onClick = { selectedDestination = 0 },
                    icon = { Text("◷") },
                    label = { Text("Summary") }
                )
                NavigationBarItem(
                    selected = selectedDestination == 1,
                    onClick = { selectedDestination = 1 },
                    icon = { Text("▣") },
                    label = { Text("Games") }
                )
                NavigationBarItem(
                    selected = selectedDestination == 2,
                    onClick = { selectedDestination = 2 },
                    icon = { Text("▦") },
                    label = { Text("Activity") }
                )
                NavigationBarItem(
                    selected = selectedDestination == 3,
                    onClick = { selectedDestination = 3 },
                    icon = { Text("↗") },
                    label = { Text("Backloggd") }
                )
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .then(
                    if (selectedDestination == 1 || selectedDestination == 3) {
                        Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                    } else {
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                    }
                )
        ) {
            Text(
                text = "GamerLog",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = destinations[selectedDestination],
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(20.dp))

            when (selectedDestination) {
                0 -> SummaryScreen(
                    selectedRange = selectedRange,
                    onRangeSelected = { selectedRange = it },
                    hasUsageAccess = hasUsageAccess,
                    dailyPlaytime = dailyPlaytime,
                    currentDayWindow = currentDayWindow,
                    database = database,
                    onOpenUsageSettings = onOpenUsageSettings
                )
                1 -> GamesScreen(
                    games = games,
                    onToggleTracked = onToggleTracked
                )
                2 -> ActivityScreen(
                    hasUsageAccess = hasUsageAccess,
                    onOpenUsageSettings = onOpenUsageSettings,
                    database = database,
                    currentDayWindow = currentDayWindow
                )
                3 -> BackloggdScreen(database)
        }
    }
}
}

@Composable
private fun SummaryScreen(
    selectedRange: Int,
    onRangeSelected: (Int) -> Unit,
    hasUsageAccess: Boolean,
    dailyPlaytime: List<GamePlaytime>?,
    currentDayWindow: DayWindow,
    database: GamerLogDatabase,
    onOpenUsageSettings: () -> Unit
) {
    val ranges = listOf("Day", "Week", "Month")

    UsageAccessCard(
        hasUsageAccess = hasUsageAccess,
        onOpenUsageSettings = onOpenUsageSettings
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Period",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ranges.forEachIndexed { index, range ->
            FilterChip(
                selected = selectedRange == index,
                onClick = { onRangeSelected(index) },
                label = { Text(range) }
            )
        }
    }
    Spacer(modifier = Modifier.height(16.dp))

    when (selectedRange) {
        0 -> {
            PeriodSummaryContent(
                playtime = dailyPlaytime,
                hasUsageAccess = hasUsageAccess,
                cardTitle = "Today's Playtime",
                periodName = "today"
            )
        }
        1 -> {
            val weekWindow = remember(currentDayWindow.date, currentDayWindow.zoneId) {
                WeekWindow.forDate(currentDayWindow.date, currentDayWindow.zoneId)
            }
            val weekPlaytime = key(weekWindow) {
                val weekPlaytimeFlow = remember(weekWindow, database) {
                    database.dao().observeGamePlaytime(
                        startInclusiveMs = weekWindow.startInclusiveMs,
                        endExclusiveMs = weekWindow.endExclusiveMs
                    )
                }
                val state by weekPlaytimeFlow.collectAsState(initial = null)
                state
            }
            PeriodSummaryContent(
                playtime = weekPlaytime,
                hasUsageAccess = hasUsageAccess,
                cardTitle = "This Week's Playtime",
                periodName = "this week"
            )
        }
        else -> {
            val monthWindow = remember(currentDayWindow.date, currentDayWindow.zoneId) {
                MonthWindow.forDate(currentDayWindow.date, currentDayWindow.zoneId)
            }
            val monthPlaytime = key(monthWindow) {
                val monthPlaytimeFlow = remember(monthWindow, database) {
                    database.dao().observeGamePlaytime(
                        startInclusiveMs = monthWindow.startInclusiveMs,
                        endExclusiveMs = monthWindow.endExclusiveMs
                    )
                }
                val state by monthPlaytimeFlow.collectAsState(initial = null)
                state
            }
            PeriodSummaryContent(
                playtime = monthPlaytime,
                hasUsageAccess = hasUsageAccess,
                cardTitle = "This Month's Playtime",
                periodName = "this month"
            )
        }
}
}

@Composable
private fun PeriodSummaryContent(
    playtime: List<GamePlaytime>?,
    hasUsageAccess: Boolean,
    cardTitle: String,
    periodName: String
) {
    when {
        playtime == null -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        playtime.isEmpty() -> {
            if (!hasUsageAccess) {
                EmptyStateCard(
                    title = "Usage access required",
                    description = "No game sessions recorded $periodName. Grant Android Usage Access permission above to enable automatic playtime tracking."
                )
            } else {
                EmptyStateCard(
                    title = "No game sessions recorded $periodName",
                    description = "Completed sessions for tracked games will appear here once played. You can configure which apps are tracked in the Games tab."
                )
            }
        }
        else -> {
            val totalPlaytimeMs = remember(playtime) {
                playtime.sumOf { it.totalDurationMs }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = cardTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = formatDuration(totalPlaytimeMs),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    val gameCountText = if (playtime.size == 1) {
                        "1 game played $periodName"
                    } else {
                        "${playtime.size} games played $periodName"
                    }
                    Text(
                        text = gameCountText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Games",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                playtime.forEach { item ->
                    GameItemCard(
                        item = item,
                        totalPlaytimeMs = totalPlaytimeMs
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityScreen(
    hasUsageAccess: Boolean,
    onOpenUsageSettings: () -> Unit,
    database: GamerLogDatabase,
    currentDayWindow: DayWindow
) {
    UsageAccessCard(
        hasUsageAccess = hasUsageAccess,
        onOpenUsageSettings = onOpenUsageSettings
    )
    Spacer(modifier = Modifier.height(24.dp))
    ActivityContent(
        database = database,
        date = currentDayWindow.date,
        zoneId = currentDayWindow.zoneId
    )
}

@Composable
private fun UsageAccessCard(
    hasUsageAccess: Boolean,
    onOpenUsageSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (hasUsageAccess) {
                    "Usage access active"
                } else {
                    "Usage access required"
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (hasUsageAccess) {
                    "GamerLog can passively inspect game usage events in the background."
                } else {
                    "To track game playtime in the background, Android Usage Access (PACKAGE_USAGE_STATS) permission must be granted."
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasUsageAccess) {
                Button(onClick = onOpenUsageSettings) {
                    Text("Open Settings")
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class DayWindow(
    val startInclusiveMs: Long,
    val endExclusiveMs: Long,
    val date: LocalDate,
    val zoneId: ZoneId
) {
    companion object {
        fun today(zoneId: ZoneId = ZoneId.systemDefault()): DayWindow {
            val today = LocalDate.now(zoneId)
            val startZoned = today.atStartOfDay(zoneId)
            val nextStartZoned = today.plusDays(1).atStartOfDay(zoneId)
            return DayWindow(
                startInclusiveMs = startZoned.toInstant().toEpochMilli(),
                endExclusiveMs = nextStartZoned.toInstant().toEpochMilli(),
                date = today,
                zoneId = zoneId
            )
        }
    }
}

data class WeekWindow(
    val startInclusiveMs: Long,
    val endExclusiveMs: Long
) {
    companion object {
        fun forDate(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): WeekWindow {
            val monday = date.with(DayOfWeek.MONDAY)
            val nextMonday = monday.plusWeeks(1)
            val startZoned = monday.atStartOfDay(zoneId)
            val endZoned = nextMonday.atStartOfDay(zoneId)
            return WeekWindow(
                startInclusiveMs = startZoned.toInstant().toEpochMilli(),
                endExclusiveMs = endZoned.toInstant().toEpochMilli()
            )
        }
    }
}

@Composable
private fun GameItemCard(
    item: GamePlaytime,
    totalPlaytimeMs: Long
) {
    val displayName = item.customTitle?.takeIf { it.isNotBlank() } ?: item.appName
    val fraction = if (totalPlaytimeMs > 0L) {
        (item.totalDurationMs.toFloat() / totalPlaytimeMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = item.packageName,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = formatDuration(item.totalDurationMs),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0s"
    val totalSeconds = durationMs / 1000L
    if (totalSeconds < 60L) {
        return "${totalSeconds.coerceAtLeast(1L)}s"
    }
    val totalMinutes = totalSeconds / 60L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}
