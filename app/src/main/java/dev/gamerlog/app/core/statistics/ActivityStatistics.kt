package dev.gamerlog.app.core.statistics

import dev.gamerlog.app.core.database.ActivitySessionInterval
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules

/** A 28-calendar-day window ending on [endDateInclusive], using local midnights. */
data class ActivityWindow(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val startInclusiveMs: Long,
    val endExclusiveMs: Long
) {
    companion object {
        const val DAY_COUNT = 28

        fun forDate(date: LocalDate, zoneId: ZoneId): ActivityWindow {
            val startDate = date.minusDays(DAY_COUNT - 1L)
            return ActivityWindow(
                startDate = startDate,
                endDateInclusive = date,
                startInclusiveMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                endExclusiveMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            )
        }
    }
}

/** Durations are indexed Monday through Sunday, then by local hour 0 through 23. */
data class ActivityStatistics(
    val dayHourDurationsMs: List<List<Long>>,
    val hourlyTotalsMs: List<Long>,
    val weekdayTotalsMs: List<Long>,
    val totalDurationMs: Long,
    /** All hour-of-day ties for the maximum positive hourly total; empty when there is no playtime. */
    val peakHours: List<Int>,
    /** All weekday ties for the maximum positive weekday total; empty when there is no playtime. */
    val peakWeekdays: List<DayOfWeek>
)

/**
 * Aggregates clipped session intervals into Monday-to-Sunday rows and local-hour columns.
 * Every interval is split at actual local-hour boundaries, so fractional-offset zones and
 * offset transitions (including skipped and repeated hours) retain exact elapsed duration.
 */
fun aggregateActivityStatistics(
    intervals: List<ActivitySessionInterval>,
    window: ActivityWindow,
    zoneId: ZoneId
): ActivityStatistics {
    val durationsByCell = LongArray(7 * 24)
    val zoneRules = zoneId.rules

    for (interval in intervals) {
        var cursorMs = maxOf(interval.startInclusiveMs, window.startInclusiveMs)
        val intervalEndMs = minOf(interval.endExclusiveMs, window.endExclusiveMs)
        if (cursorMs >= intervalEndMs) continue

        while (cursorMs < intervalEndMs) {
            val instant = Instant.ofEpochMilli(cursorMs)
            val localTime = instant.atZone(zoneId)
            val cellIndex = (localTime.dayOfWeek.value - 1) * 24 + localTime.hour
            val nextBoundaryMs = nextLocalHourBoundaryMs(cursorMs, localTime.toLocalDate(), localTime.hour, zoneId, zoneRules)
            val segmentEndMs = minOf(intervalEndMs, nextBoundaryMs)
            check(segmentEndMs > cursorMs) { "Local-hour boundary did not advance" }
            durationsByCell[cellIndex] += segmentEndMs - cursorMs
            cursorMs = segmentEndMs
        }
    }

    val dayHourDurations = ArrayList<List<Long>>(7)
    val hourlyTotals = LongArray(24)
    val weekdayTotals = LongArray(7)
    var totalDurationMs = 0L

    for (dayIndex in 0 until 7) {
        val dayDurations = ArrayList<Long>(24)
        for (hour in 0 until 24) {
            val durationMs = durationsByCell[dayIndex * 24 + hour]
            dayDurations += durationMs
            hourlyTotals[hour] += durationMs
            weekdayTotals[dayIndex] += durationMs
            totalDurationMs += durationMs
        }
        dayHourDurations += dayDurations
    }

    val peakHourMs = hourlyTotals.maxOrNull() ?: 0L
    val peakHours = if (peakHourMs == 0L) {
        emptyList()
    } else {
        (0 until 24).filter { hourlyTotals[it] == peakHourMs }
    }
    val peakWeekdayMs = weekdayTotals.maxOrNull() ?: 0L
    val peakWeekdays = if (peakWeekdayMs == 0L) {
        emptyList()
    } else {
        (0 until 7)
            .filter { weekdayTotals[it] == peakWeekdayMs }
            .map { DayOfWeek.of(it + 1) }
    }

    return ActivityStatistics(
        dayHourDurationsMs = dayHourDurations,
        hourlyTotalsMs = hourlyTotals.toList(),
        weekdayTotalsMs = weekdayTotals.toList(),
        totalDurationMs = totalDurationMs,
        peakHours = peakHours,
        peakWeekdays = peakWeekdays
    )
}

private fun nextLocalHourBoundaryMs(
    cursorMs: Long,
    localDate: LocalDate,
    localHour: Int,
    zoneId: ZoneId,
    zoneRules: ZoneRules
): Long {
    val cursor = Instant.ofEpochMilli(cursorMs)
    val nextLocalHour = LocalDateTime.of(localDate, java.time.LocalTime.of(localHour, 0)).plusHours(1)
    val validOffsets = zoneRules.getValidOffsets(nextLocalHour)
    var nextBoundaryMs = Long.MAX_VALUE

    for (offset: ZoneOffset in validOffsets) {
        val candidateMs = nextLocalHour.toInstant(offset).toEpochMilli()
        if (candidateMs > cursorMs && candidateMs < nextBoundaryMs) {
            nextBoundaryMs = candidateMs
        }
    }

    if (nextBoundaryMs == Long.MAX_VALUE) {
        // The nominal next wall-clock hour can be inside a spring-forward gap.
        val gapTransitionMs = zoneRules.getTransition(nextLocalHour)?.instant?.toEpochMilli()
        if (gapTransitionMs != null && gapTransitionMs > cursorMs) {
            nextBoundaryMs = gapTransitionMs
        }
    }

    // A transition can change the local hour before the nominal wall-clock boundary, such as
    // when clocks move backward into a repeated hour. Splitting at every transition is safe;
    // adjacent pieces that map to the same cell are summed into that same cell.
    val transitionMs = zoneRules.nextTransition(cursor)?.instant?.toEpochMilli()
    if (transitionMs != null && transitionMs > cursorMs && transitionMs < nextBoundaryMs) {
        nextBoundaryMs = transitionMs
    }

    check(nextBoundaryMs != Long.MAX_VALUE) { "No next local-hour boundary for $zoneId at $cursor" }
    return nextBoundaryMs
}
