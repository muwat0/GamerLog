package dev.gamerlog.app.ui.backloggd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.gamerlog.app.core.backloggd.BackloggdException
import dev.gamerlog.app.core.backloggd.BackloggdRepository
import dev.gamerlog.app.core.database.BackloggdExportEntity
import dev.gamerlog.app.core.database.ExportState
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun BackloggdScreen(database: GamerLogDatabase) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context, database) { BackloggdRepository(context, database) }
    val backloggdDao = remember(database) { database.backloggdDao() }
    val settings by backloggdDao.observeSettings().collectAsState(initial = null)
    val exports by backloggdDao.observeExports().collectAsState(initial = emptyList())
    val games by database.dao().observeGames().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var username by rememberSaveable { mutableStateOf("") }
    // Cookie material is intentionally kept only in ordinary composition state, never saved.
    var cookieHeader by remember { mutableStateOf("") }
    var gameFilter by rememberSaveable { mutableIntStateOf(0) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showQueueConfirmation by remember { mutableStateOf(false) }

    val connectedUsername = settings?.username?.takeIf(String::isNotBlank)
    val isConnected = connectedUsername != null
    val exportEnabled = settings?.enabled == true
    val filteredGames = remember(games, gameFilter) {
        if (gameFilter == 1) games.filter(GameEntity::isTracked) else games
    }

    fun performAction(label: String, action: suspend () -> String?) {
        if (pendingAction != null) return
        pendingAction = label
        actionError = null
        actionMessage = null
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                actionMessage = result ?: "$label completed."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BackloggdException) {
                actionError = failure.message?.takeIf(String::isNotBlank)
                    ?: "Could not complete $label. Please try again."
            } catch (_: Exception) {
                actionError = "Could not complete $label. Please try again."
            } finally {
                pendingAction = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "account") {
            AccountCard(
                connectedUsername = connectedUsername,
                username = username,
                onUsernameChange = { username = it },
                cookieHeader = cookieHeader,
                onCookieHeaderChange = { cookieHeader = it },
                enabled = pendingAction == null,
                onConnect = {
                    actionError = null
                    actionMessage = null
                    val submittedUsername = username.trim()
                    val submittedCookie = cookieHeader
                    if (submittedUsername.isBlank()) {
                        actionError = "Enter your Backloggd username."
                    } else if (submittedCookie.isBlank()) {
                        actionError = "Enter the Cookie header copied from a Backloggd browser request."
                    } else {
                        // Clear the visible field immediately; the captured value is used only for this attempt.
                        cookieHeader = ""
                        performAction("Connect") {
                            repository.connect(submittedUsername, submittedCookie)
                            "Connected to Backloggd as $submittedUsername. No export was started."
                        }
                    }
                },
                onDisconnect = {
                    performAction("Disconnect") {
                        repository.disconnect()
                        "Disconnected. Local sessions and export history were kept."
                    }
                }
            )
        }

        if (pendingAction != null) {
            item(key = "busy") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pendingAction}…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        actionError?.let { error ->
            item(key = "action-error") { MessageCard(text = error, isError = true) }
        }
        actionMessage?.let { message ->
            item(key = "action-message") { MessageCard(text = message, isError = false) }
        }

        item(key = "export-settings") {
            ExportSettingsCard(
                enabled = exportEnabled,
                connected = isConnected,
                controlsEnabled = pendingAction == null,
                onEnabledChange = { enabled ->
                    performAction(if (enabled) "Enable Backloggd export" else "Pause Backloggd export") {
                        repository.setExportEnabled(enabled)
                        if (enabled) {
                            "Backloggd export enabled for future mapped sessions."
                        } else {
                            "Backloggd export paused. Queued history is retained."
                        }
                    }
                },
                onSyncNow = {
                    performAction("Sync request") {
                        repository.syncNow()
                        "Sync requested. Delivery runs later when connectivity is available."
                    }
                },
                onQueueHistory = { showQueueConfirmation = true }
            )
        }

        item(key = "outbox-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Export history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Same-day sessions accumulate before conversion to whole minutes. Exact local durations stay in the note ledger; earlier notes and time are retained. Notes may be publicly visible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (exports.isEmpty()) {
            item(key = "empty-outbox") {
                MessageCard(text = "No export records yet. Connecting or mapping a game does not export sessions.", isError = false)
            }
        } else {
            items(exports, key = { it.sessionId }) { export ->
                ExportCard(
                    export = export,
                    canUseAccount = connectedUsername == export.accountUsername,
                    deliveryEnabled = exportEnabled,
                    controlsEnabled = pendingAction == null,
                    onRetry = {
                        performAction("Retry export") {
                            repository.retry(export.sessionId)
                            "Retry queued for ${export.sessionId}."
                        }
                    },
                    onReconcile = {
                        performAction("Reconcile export") {
                            repository.syncNow()
                            "Reconciliation requested. May already exist; will not resend automatically."
                        }
                    },
                    onExclude = {
                        performAction("Exclude export") {
                            repository.exclude(export.sessionId)
                            "Export excluded. The local session was kept."
                        }
                    }
                )
            }
        }
        item(key = "mappings-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Game mappings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose a Backloggd game explicitly for each app. GamerLog never guesses a match; only mapped apps can be exported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = gameFilter == 0,
                        onClick = { gameFilter = 0 },
                        label = { Text("All (${games.size})") }
                    )
                    val trackedCount = remember(games) { games.count(GameEntity::isTracked) }
                    FilterChip(
                        selected = gameFilter == 1,
                        onClick = { gameFilter = 1 },
                        label = { Text("Tracked ($trackedCount)") }
                    )
                }
            }
        }

        if (filteredGames.isEmpty()) {
            item(key = "no-games") {
                MessageCard(
                    text = if (games.isEmpty()) "No apps are available to map yet." else "No tracked apps match this filter.",
                    isError = false
                )
            }
        } else {
            items(filteredGames, key = { it.packageName }) { game ->
                GameMappingCard(
                    game = game,
                    controlsEnabled = pendingAction == null,
                    onMap = { value ->
                        performAction("Verify and map game") {
                            repository.setGameMapping(game.packageName, value)
                            "Verified and mapped ${game.customTitle ?: game.appName}."
                        }
                    },
                    onClear = {
                        performAction("Clear game mapping") {
                            repository.clearGameMapping(game.packageName)
                            "Cleared the mapping for ${game.customTitle ?: game.appName}."
                        }
                    }
                )
            }
        }

    }

    if (showQueueConfirmation) {
        AlertDialog(
            onDismissRequest = { if (pendingAction == null) showQueueConfirmation = false },
            title = { Text("Queue recorded history?") },
            text = {
                Text(
                    "This will queue recorded sessions for the connected account only when their app has an explicit Backloggd game mapping. Existing export records will not be duplicated or rebound. It can create journal notes that may be publicly visible. Local playtime will remain unchanged."
                )
            },
            confirmButton = {
                Button(
                    enabled = pendingAction == null && isConnected && exportEnabled,
                    onClick = {
                        showQueueConfirmation = false
                        performAction("Queue recorded history") {
                            val count = repository.queueHistory()
                            if (count == 0) "No new mapped history records were queued."
                            else "Queued $count mapped historical session${if (count == 1) "" else "s"}."
                        }
                    }
                ) { Text("Queue history") }
            },
            dismissButton = {
                TextButton(
                    enabled = pendingAction == null,
                    onClick = { showQueueConfirmation = false }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AccountCard(
    connectedUsername: String?,
    username: String,
    onUsernameChange: (String) -> Unit,
    cookieHeader: String,
    onCookieHeaderChange: (String) -> Unit,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Backloggd account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (connectedUsername == null) {
                Text("Disconnected", fontWeight = FontWeight.Medium)
                Text(
                    "Connect using the Cookie header copied from a Backloggd browser request. Do not enter your password: GamerLog does not store it. The account check is read-only, and connecting alone never starts exports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backloggd username") },
                    singleLine = true,
                    enabled = enabled
                )
                OutlinedTextField(
                    value = cookieHeader,
                    onValueChange = onCookieHeaderChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cookie header") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = enabled
                )
                Button(onClick = onConnect, enabled = enabled) { Text("Connect (read-only)") }
            } else {
                Text("Connected as $connectedUsername", fontWeight = FontWeight.Medium)
                Text(
                    "The connection is used for read-only account/game checks and optional one-way exports. Disconnecting clears the stored credential but keeps local sessions and export history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onDisconnect, enabled = enabled) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun ExportSettingsCard(
    enabled: Boolean,
    connected: Boolean,
    controlsEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onQueueHistory: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Export controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Export is off by default and uses the existing most-recent playthrough. Enabling automatically queues sessions started afterward only for apps with explicit game mappings. Exported journal notes may be publicly visible and retain exact local durations. Turning it off pauses sending; an already dispatched request may still finish. Queued history is retained. Older sessions require the separate confirmation below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Backloggd export", fontWeight = FontWeight.Medium)
                    Text(if (enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = connected && controlsEnabled
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow, enabled = connected && enabled && controlsEnabled) { Text("Sync now") }
                TextButton(onClick = onQueueHistory, enabled = connected && enabled && controlsEnabled) { Text("Queue recorded history") }
            }
            if (connected && !enabled) {
                Text(
                    "Enable Backloggd export before syncing or queuing history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GameMappingCard(
    game: GameEntity,
    controlsEnabled: Boolean,
    onMap: (String) -> Unit,
    onClear: () -> Unit
) {
    var mappingInput by remember(game.packageName, game.backloggdSlug) {
        mutableStateOf(game.backloggdSlug.orEmpty())
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(game.customTitle ?: game.appName, fontWeight = FontWeight.SemiBold)
            Text(game.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (game.backloggdSlug == null) "${if (game.isTracked) "Tracked" else "Not tracked"} · Not mapped"
                else "${if (game.isTracked) "Tracked" else "Not tracked"} · Backloggd: ${game.backloggdSlug}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = mappingInput,
                onValueChange = { mappingInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backloggd game URL or slug") },
                singleLine = true,
                enabled = controlsEnabled
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onMap(mappingInput.trim()) },
                    enabled = controlsEnabled && mappingInput.isNotBlank()
                ) { Text("Verify & map") }
                if (game.backloggdSlug != null) {
                    TextButton(onClick = onClear, enabled = controlsEnabled) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    export: BackloggdExportEntity,
    canUseAccount: Boolean,
    deliveryEnabled: Boolean,
    controlsEnabled: Boolean,
    onRetry: () -> Unit,
    onReconcile: () -> Unit,
    onExclude: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("${export.accountUsername} · ${export.gameSlug}", fontWeight = FontWeight.SemiBold)
            Text(
                "${formatPlayedOn(export.playedOn)} · Local duration ${formatLocalDuration(export.durationMs)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("State: ${export.state.displayName()}", style = MaterialTheme.typography.bodyMedium)
            export.lastError?.takeIf(String::isNotBlank)?.let { safeError ->
                Text(
                    safeError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (!deliveryEnabled && (
                    export.state == ExportState.FAILED ||
                        export.state == ExportState.EXCLUDED ||
                        export.state == ExportState.UNCERTAIN
                    )
            ) {
                Text(
                    "Enable Backloggd export before retrying or reconciling this item.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!canUseAccount && (
                    export.state == ExportState.FAILED ||
                        export.state == ExportState.EXCLUDED ||
                        export.state == ExportState.UNCERTAIN
                    )
            ) {
                Text(
                    "Connect as ${export.accountUsername} to retry or reconcile this item.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (export.state) {
                ExportState.WAITING -> TextButton(onClick = onExclude, enabled = controlsEnabled) { Text("Exclude") }
                ExportState.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry, enabled = controlsEnabled && canUseAccount && deliveryEnabled) { Text("Retry") }
                    TextButton(onClick = onExclude, enabled = controlsEnabled) { Text("Exclude") }
                }
                ExportState.EXCLUDED -> Button(onClick = onRetry, enabled = controlsEnabled && canUseAccount && deliveryEnabled) { Text("Retry") }
                ExportState.UNCERTAIN -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "May already exist; will not resend automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onReconcile, enabled = controlsEnabled && canUseAccount && deliveryEnabled) { Text("Reconcile") }
                }
                ExportState.SENDING -> Text(
                    "A delivery attempt may still be in progress. Do not retry until its outcome is known.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExportState.SENT -> Unit
            }
        }
    }
}

@Composable
private fun MessageCard(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun ExportState.displayName(): String = when (this) {
    ExportState.WAITING -> "Waiting"
    ExportState.SENDING -> "Sending"
    ExportState.SENT -> "Sent"
    ExportState.EXCLUDED -> "Excluded"
    ExportState.FAILED -> "Failed"
    ExportState.UNCERTAIN -> "Uncertain delivery"
}

private fun formatPlayedOn(value: String): String = try {
    LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
} catch (_: Exception) {
    value
}

private fun formatLocalDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0 || isEmpty()) add("${seconds}s")
    }.joinToString(" ")
}
