package dev.gamerlog.app

import android.app.AppOpsManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.gamerlog.app.core.catalog.AppCatalogManager
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.tracker.UsageScanWorker
import dev.gamerlog.app.core.tracker.UsageScanner
import dev.gamerlog.app.ui.games.GamesScreen
import dev.gamerlog.app.ui.theme.GamerLogTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var usageAccessGranted by mutableStateOf(false)
    private lateinit var database: GamerLogDatabase
    private lateinit var catalogManager: AppCatalogManager
    private lateinit var usageScanner: UsageScanner

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

                GamerLogScreen(
                    hasUsageAccess = usageAccessGranted,
                    games = games,
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
        refreshUsageAccess()
        triggerBackgroundScanAndDiscovery()
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
    onToggleTracked: (packageName: String, isTracked: Boolean) -> Unit,
    onOpenUsageSettings: () -> Unit
) {
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }
    var selectedRange by rememberSaveable { mutableIntStateOf(0) }
    val destinations = listOf("Özet", "Oyunlar", "Aktivite")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedDestination == 0,
                    onClick = { selectedDestination = 0 },
                    icon = { Text("◷") },
                    label = { Text("Özet") }
                )
                NavigationBarItem(
                    selected = selectedDestination == 1,
                    onClick = { selectedDestination = 1 },
                    icon = { Text("▣") },
                    label = { Text("Oyunlar") }
                )
                NavigationBarItem(
                    selected = selectedDestination == 2,
                    onClick = { selectedDestination = 2 },
                    icon = { Text("▦") },
                    label = { Text("Aktivite") }
                )
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .then(
                    if (selectedDestination == 1) {
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
                    onOpenUsageSettings = onOpenUsageSettings
                )
                1 -> GamesScreen(
                    games = games,
                    onToggleTracked = onToggleTracked
                )
                else -> ActivityScreen(
                    hasUsageAccess = hasUsageAccess,
                    onOpenUsageSettings = onOpenUsageSettings
                )
            }
        }
    }
}

@Composable
private fun SummaryScreen(
    selectedRange: Int,
    onRangeSelected: (Int) -> Unit,
    hasUsageAccess: Boolean,
    onOpenUsageSettings: () -> Unit
) {
    val ranges = listOf("Gün", "Hafta", "Ay")

    UsageAccessCard(
        hasUsageAccess = hasUsageAccess,
        onOpenUsageSettings = onOpenUsageSettings
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Dönem",
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
    EmptyStateCard(
        title = "${ranges[selectedRange]} özeti henüz hazır değil",
        description = "Oyun oturumu kaydı Faz 1 ile başlatıldı. Gerçek ${ranges[selectedRange].lowercase()} istatistikleri ve agregasyonları Faz 2 kapsamında sunulacak."
    )
}

@Composable
private fun ActivityScreen(
    hasUsageAccess: Boolean,
    onOpenUsageSettings: () -> Unit
) {
    UsageAccessCard(
        hasUsageAccess = hasUsageAccess,
        onOpenUsageSettings = onOpenUsageSettings
    )
    Spacer(modifier = Modifier.height(24.dp))
    EmptyStateCard(
        title = "24 × 7 aktivite görünümü henüz hazır değil",
        description = "Oyun oturumu kaydı Faz 1 ile arka planda toplanmaya başlandı. Haftanın 7 günü ve günün 24 saatini kapsayan ısı haritası ve pik saat agregasyonları Faz 2 kapsamında sunulacak."
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
                    "Kullanım erişimi açık"
                } else {
                    "Kullanım erişimi gerekiyor"
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (hasUsageAccess) {
                    "GamerLog arka planda oyun kullanım verilerini pasif olarak okuyabilir."
                } else {
                    "Oyun sürelerinin arka planda tespit edilebilmesi için Android Kullanım Erişimi (PACKAGE_USAGE_STATS) iznini açmanız gerekir."
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasUsageAccess) {
                Button(onClick = onOpenUsageSettings) {
                    Text("Ayarlarda Aç")
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
