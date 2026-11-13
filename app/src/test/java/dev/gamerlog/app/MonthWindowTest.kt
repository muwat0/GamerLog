package dev.gamerlog.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MonthWindowTest {

    @Test
    fun forDate_coversLeapFebruaryWithHalfOpenCalendarBounds() {
        val zoneId = ZoneId.of("UTC")
        val window = MonthWindow.forDate(LocalDate.of(2024, 2, 29), zoneId)

        assertEquals(
            LocalDate.of(2024, 2, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.startInclusiveMs
        )
        assertEquals(
            LocalDate.of(2024, 3, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.endExclusiveMs
        )
        assertEquals(29L * 24L * 60L * 60L * 1_000L, window.endExclusiveMs - window.startInclusiveMs)
    }

    @Test
    fun forDate_handlesYearTransition() {
        val zoneId = ZoneId.of("UTC")
        val window = MonthWindow.forDate(LocalDate.of(2026, 12, 31), zoneId)

        assertEquals(
            LocalDate.of(2026, 12, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.startInclusiveMs
        )
        assertEquals(
            LocalDate.of(2027, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.endExclusiveMs
        )
        assertEquals(31L * 24L * 60L * 60L * 1_000L, window.endExclusiveMs - window.startInclusiveMs)
    }

    @Test
    fun forDate_usesCalendarMidnightsAcrossSpringDstTransition() {
        val zoneId = ZoneId.of("America/New_York")
        val window = MonthWindow.forDate(LocalDate.of(2026, 3, 15), zoneId)

        assertEquals(
            LocalDate.of(2026, 3, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.startInclusiveMs
        )
        assertEquals(
            LocalDate.of(2026, 4, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.endExclusiveMs
        )
        assertEquals(743L * 60L * 60L * 1_000L, window.endExclusiveMs - window.startInclusiveMs)
    }

    @Test
    fun forDate_usesCalendarMidnightsAcrossAutumnDstTransition() {
        val zoneId = ZoneId.of("America/New_York")
        val window = MonthWindow.forDate(LocalDate.of(2026, 11, 15), zoneId)

        assertEquals(
            LocalDate.of(2026, 11, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.startInclusiveMs
        )
        assertEquals(
            LocalDate.of(2026, 12, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            window.endExclusiveMs
        )
        assertEquals(721L * 60L * 60L * 1_000L, window.endExclusiveMs - window.startInclusiveMs)
    }
}
