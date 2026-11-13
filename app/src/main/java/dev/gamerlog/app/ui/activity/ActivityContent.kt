package dev.gamerlog.app.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.statistics.ActivityStatistics
import dev.gamerlog.app.core.statistics.ActivityWindow
import dev.gamerlog.app.core.statistics.aggregateActivityStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ActivityContent(
    database: GamerLogDatabase,
    date: LocalDate,
    zoneId: ZoneId
) {
    val window = remember(date, zoneId) { ActivityWindow.forDate(date, zoneId) }
    val activityFlow = remember(database, window, zoneId) {
        database.dao()
            .observeActivitySessionIntervals(window.startInclusiveMs, window.endExclusiveMs)
            .map { intervals -> aggregateActivityStatistics(intervals, window, zoneId) }
            .flowOn(Dispatchers.Default)
    }
    val statistics = key(database, window, zoneId) {
        val currentStatistics by activityFlow.collectAsState(initial = null)
        currentStatistics
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Last 28 device-local calendar days",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "${window.startDate.format(DATE_FORMATTER)} – ${window.endDateInclusive.format(DATE_FORMATTER)} (inclusive)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (val currentStatistics = statistics) {
            null -> LoadingActivityCard()
            else -> {
                ActivityTotalCard(currentStatistics.totalDurationMs)
                if (currentStatistics.totalDurationMs == 0L) {
                    ActivityEmptyCard()
                } else {
                    ActivityPeaksCard(currentStatistics)
                }
                ActivityHeatmap(currentStatistics)
            }
        }
    }
}

@Composable
private fun LoadingActivityCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text("Loading recorded activity…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActivityTotalCard(totalDurationMs: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Recorded playtime",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = formatShortDuration(totalDurationMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ActivityEmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "No game activity recorded",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No saved sessions overlap these 28 days. The activity view uses recorded history even when Usage Access is currently off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActivityPeaksCard(statistics: ActivityStatistics) {
    val locale = Locale.ENGLISH
    val peakHours = statistics.peakHours.joinToString(", ") { formatHour(it) }
    val peakWeekdays = statistics.peakWeekdays.joinToString(", ") {
        it.getDisplayName(TextStyle.FULL, locale)
    }
    val hourDuration = statistics.hourlyTotalsMs[statistics.peakHours.first()]
    val weekdayDuration = statistics.weekdayTotalsMs[statistics.peakWeekdays.first().value - 1]

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Peak activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Peak ${if (statistics.peakHours.size == 1) "hour" else "hours"}: $peakHours (${formatShortDuration(hourDuration)})",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Busiest ${if (statistics.peakWeekdays.size == 1) "weekday" else "weekdays"}: $peakWeekdays (${formatShortDuration(weekdayDuration)})",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Tied hours and weekdays are all listed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun ActivityHeatmap(statistics: ActivityStatistics) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val maximumDurationMs = statistics.dayHourDurationsMs.maxOf { it.maxOrNull() ?: 0L }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Activity by weekday and local hour",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Hours are local time (00–23). Scroll horizontally to view all 24 hours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Top) {
            WeekdayAxis()
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                HourAxis()
                Spacer(modifier = Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (dayIndex in 0 until 7) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (hour in 0 until 24) {
                                val durationMs = statistics.dayHourDurationsMs[dayIndex][hour]
                                val weekday = DayOfWeek.of(dayIndex + 1)
                                ActivityCell(
                                    durationMs = durationMs,
                                    maximumDurationMs = maximumDurationMs,
                                    weekday = weekday,
                                    hour = hour,
                                    surfaceColor = surfaceColor,
                                    activeColor = activeColor
                                )
                            }
                        }
                    }
                }
            }
        }
        HeatmapLegend(surfaceColor = surfaceColor, activeColor = activeColor)
    }
}

@Composable
private fun WeekdayAxis() {
    val locale = Locale.ENGLISH
    Column(modifier = Modifier.width(48.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text("Day", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (dayIndex in 0 until 7) {
                val day = DayOfWeek.of(dayIndex + 1)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .semantics {
                            contentDescription = day.getDisplayName(TextStyle.FULL, locale)
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = day.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun HourAxis() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (hour in 0 until 24) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatHour(hour).substringBefore(':'),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ActivityCell(
    durationMs: Long,
    maximumDurationMs: Long,
    weekday: DayOfWeek,
    hour: Int,
    surfaceColor: Color,
    activeColor: Color
) {
    val locale = Locale.ENGLISH
    val description = "${weekday.getDisplayName(TextStyle.FULL, locale)}, ${formatHour(hour)}–${formatHour(hour + 1)}: ${formatAccessibleDuration(durationMs)} recorded"
    val color = activityColor(durationMs, maximumDurationMs, surfaceColor, activeColor)

    Box(
        modifier = Modifier
            .size(width = 32.dp, height = 28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .semantics { contentDescription = description }
    )
}

@Composable
private fun HeatmapLegend(surfaceColor: Color, activeColor: Color) {
    val legendDescriptions = listOf("No playtime", "Low activity", "Medium activity", "High activity", "Most activity")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Less", style = MaterialTheme.typography.labelSmall)
        for (level in 0..4) {
            val color = if (level == 0) {
                surfaceColor
            } else {
                lerp(surfaceColor, activeColor, level / 4f)
            }
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
                    .semantics { contentDescription = legendDescriptions[level] }
            )
        }
        Text("More", style = MaterialTheme.typography.labelSmall)
    }
}

private fun activityColor(
    durationMs: Long,
    maximumDurationMs: Long,
    surfaceColor: Color,
    activeColor: Color
): Color {
    if (durationMs <= 0L || maximumDurationMs <= 0L) return surfaceColor
    val ratio = durationMs.toDouble() / maximumDurationMs.toDouble()
    val level = when {
        ratio <= 0.25 -> 0.25f
        ratio <= 0.50 -> 0.50f
        ratio <= 0.75 -> 0.75f
        else -> 1f
    }
    return lerp(surfaceColor, activeColor, level)
}

private fun formatHour(hour: Int): String {
    val normalized = if (hour == 24) 24 else hour
    return if (normalized < 10) "0$normalized:00" else "$normalized:00"
}

private fun formatShortDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0m"
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        minutes > 0L -> "${minutes}m"
        else -> "${totalSeconds.coerceAtLeast(1L)}s"
    }
}

private fun formatAccessibleDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0 minutes"
    val totalSeconds = durationMs / 1_000L
    if (totalSeconds == 0L) return "less than 1 second"
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val parts = ArrayList<String>(3)
    if (hours > 0L) parts += "$hours ${if (hours == 1L) "hour" else "hours"}"
    if (minutes > 0L) parts += "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
    if (seconds > 0L) parts += "$seconds ${if (seconds == 1L) "second" else "seconds"}"
    return parts.joinToString(" ")
}

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
