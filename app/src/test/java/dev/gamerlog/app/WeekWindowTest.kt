package dev.gamerlog.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeekWindowTest {

    @Test
    fun forDate_usesMondayInclusiveAndNextMondayExclusiveForEveryWeekday() {
        val zoneId = ZoneId.of("UTC")
        val monday = LocalDate.of(2026, 9, 21)

        for (dayOffset in 0L..6L) {
            val window = WeekWindow.forDate(monday.plusDays(dayOffset), zoneId)

            assertEquals(monday.atStartOfDay(zoneId).toInstant().toEpochMilli(), window.startInclusiveMs)
            assertEquals(monday.plusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli(), window.endExclusiveMs)
        }
    }

    @Test
    fun forDate_usesCalendarMidnightsAcrossSpringDstTransition() {
        val zoneId = ZoneId.of("America/New_York")
        val window = WeekWindow.forDate(LocalDate.of(2026, 3, 4), zoneId)

        assertEquals(
            LocalDate.of(2026, 3, 2).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.startInclusiveMs
        )
        assertEquals(
            LocalDate.of(2026, 3, 9).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.endExclusiveMs
        )
        assertEquals(167L * 60L * 60L * 1_000L, window.endExclusiveMs - window.startInclusiveMs)
    }

    @Test
    fun forDate_usesCalendarMidnightsAcrossAutumnDstTransition() {
        val zoneId = ZoneId.of("America/New_York")
        val window = WeekWindow.forDate(LocalDate.of(2026, 10, 28), zoneId)

        assertEquals(
            LocalDate.of(2026, 10, 26).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.startInclusiveMs
        )
        assertEquals(
            LocalDate.of(2026, 11, 2).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.endExclusiveMs
        )
        assertEquals(169L * 60L * 60L * 1_000L, window.endExclusiveMs - window.startInclusiveMs)
    }
}
