package dev.gamerlog.app.core.statistics

import dev.gamerlog.app.core.database.ActivitySessionInterval
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityStatisticsTest {

    @Test
    fun windowCoversSelectedDateAndPrevious27LocalCalendarDays() {
        val zoneId = ZoneId.of("America/Los_Angeles")
        val date = LocalDate.of(2026, 3, 15)

        val window = ActivityWindow.forDate(date, zoneId)

        assertEquals(LocalDate.of(2026, 2, 16), window.startDate)
        assertEquals(date, window.endDateInclusive)
        assertEquals(
            window.startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.startInclusiveMs
        )
        assertEquals(
            date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.endExclusiveMs
        )
        assertEquals(28L, window.startDate.datesUntil(date.plusDays(1)).count())
    }

    @Test
    fun aggregationClipsToBoundsSplitsMidnightAndConservesElapsedTime() {
        val zoneId = ZoneOffset.UTC
        val window = ActivityWindow.forDate(LocalDate.of(2026, 2, 28), zoneId)
        val crossingMidnightStart = LocalDateTime.of(2026, 2, 10, 23, 30)
            .toInstant(zoneId).toEpochMilli()
        val crossingMidnightEnd = LocalDateTime.of(2026, 2, 11, 1, 30)
            .toInstant(zoneId).toEpochMilli()
        val hourMs = 60L * 60L * 1_000L

        val result = aggregateActivityStatistics(
            intervals = listOf(
                ActivitySessionInterval(window.startInclusiveMs - hourMs, window.startInclusiveMs + hourMs),
                ActivitySessionInterval(crossingMidnightStart, crossingMidnightEnd),
                ActivitySessionInterval(window.endExclusiveMs - hourMs, window.endExclusiveMs + hourMs),
                ActivitySessionInterval(window.endExclusiveMs + hourMs, window.endExclusiveMs + 2 * hourMs)
            ),
            window = window,
            zoneId = zoneId
        )

        assertEquals(4 * hourMs, result.totalDurationMs)
        assertEquals(hourMs, result.dayHourDurationsMs[DayOfWeek.SUNDAY.value - 1][0])
        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[DayOfWeek.TUESDAY.value - 1][23])
        assertEquals(hourMs, result.dayHourDurationsMs[DayOfWeek.WEDNESDAY.value - 1][0])
        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[DayOfWeek.WEDNESDAY.value - 1][1])
        assertEquals(hourMs, result.dayHourDurationsMs[DayOfWeek.SATURDAY.value - 1][23])
        assertEquals(result.totalDurationMs, result.dayHourDurationsMs.flatten().sum())
    }

    @Test
    fun fractionalOffsetZoneSplitsAtLocalHourRatherThanUtcHour() {
        val zoneId = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 4, 13)
        val window = ActivityWindow.forDate(date, zoneId)
        val startMs = LocalDateTime.of(date, java.time.LocalTime.of(9, 30))
            .atZone(zoneId).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(date, java.time.LocalTime.of(10, 30))
            .atZone(zoneId).toInstant().toEpochMilli()

        val result = aggregateActivityStatistics(
            listOf(ActivitySessionInterval(startMs, endMs)),
            window,
            zoneId
        )

        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[0][9])
        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[0][10])
        assertEquals(60L * 60L * 1_000L, result.totalDurationMs)
    }

    @Test
    fun springForwardSkippedHourDoesNotReceiveTime() {
        val zoneId = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val window = ActivityWindow.forDate(date, zoneId)
        val startMs = LocalDateTime.of(date, java.time.LocalTime.of(1, 30))
            .atZone(zoneId).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(date, java.time.LocalTime.of(3, 30))
            .atZone(zoneId).toInstant().toEpochMilli()

        val result = aggregateActivityStatistics(
            listOf(ActivitySessionInterval(startMs, endMs)),
            window,
            zoneId
        )

        assertEquals(60L * 60L * 1_000L, result.totalDurationMs)
        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[6][1])
        assertEquals(0L, result.dayHourDurationsMs[6][2])
        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[6][3])
    }

    @Test
    fun autumnRepeatedHourAccumulatesBothOccurrencesInSameLocalHourCell() {
        val zoneId = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 11, 1)
        val window = ActivityWindow.forDate(date, zoneId)
        val startMs = LocalDateTime.of(date, java.time.LocalTime.of(0, 30))
            .atZone(zoneId).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(date, java.time.LocalTime.of(2, 30))
            .atZone(zoneId).toInstant().toEpochMilli()

        val result = aggregateActivityStatistics(
            listOf(ActivitySessionInterval(startMs, endMs)),
            window,
            zoneId
        )

        assertEquals(3L * 60L * 60L * 1_000L, result.totalDurationMs)
        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[6][0])
        assertEquals(2L * 60L * 60L * 1_000L, result.dayHourDurationsMs[6][1])
        assertEquals(30L * 60L * 1_000L, result.dayHourDurationsMs[6][2])
    }

    @Test
    fun emptyDataHasNoPeakInsights() {
        val result = aggregateActivityStatistics(
            intervals = emptyList(),
            window = ActivityWindow.forDate(LocalDate.of(2026, 5, 20), ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC
        )

        assertEquals(0L, result.totalDurationMs)
        assertTrue(result.peakHours.isEmpty())
        assertTrue(result.peakWeekdays.isEmpty())
    }

    @Test
    fun allHourAndWeekdayTiesAreReturnedRatherThanSilentlyBreakingTies() {
        val zoneId = ZoneOffset.UTC
        val date = LocalDate.of(2026, 6, 30)
        val window = ActivityWindow.forDate(date, zoneId)
        val mondayStart = LocalDateTime.of(2026, 6, 8, 9, 0)
            .toInstant(zoneId).toEpochMilli()
        val wednesdayStart = LocalDateTime.of(2026, 6, 10, 11, 0)
            .toInstant(zoneId).toEpochMilli()
        val hourMs = 60L * 60L * 1_000L

        val result = aggregateActivityStatistics(
            intervals = listOf(
                ActivitySessionInterval(mondayStart, mondayStart + hourMs),
                ActivitySessionInterval(wednesdayStart, wednesdayStart + hourMs)
            ),
            window = window,
            zoneId = zoneId
        )

        assertEquals(listOf(9, 11), result.peakHours)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), result.peakWeekdays)
        assertEquals(hourMs, result.hourlyTotalsMs[9])
        assertEquals(hourMs, result.hourlyTotalsMs[11])
    }
}
