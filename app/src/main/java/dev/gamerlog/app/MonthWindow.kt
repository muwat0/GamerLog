package dev.gamerlog.app

import java.time.LocalDate
import java.time.ZoneId

/** Half-open local-calendar-month interval represented as epoch milliseconds. */
data class MonthWindow(
    val startInclusiveMs: Long,
    val endExclusiveMs: Long
) {
    companion object {
        fun forDate(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): MonthWindow {
            val firstDay = date.withDayOfMonth(1)
            val firstDayOfNextMonth = firstDay.plusMonths(1)
            return MonthWindow(
                startInclusiveMs = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                endExclusiveMs = firstDayOfNextMonth.atStartOfDay(zoneId).toInstant().toEpochMilli()
            )
        }
    }
}
