package dev.gamerlog.app.core.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageDeltaParserTest {

    private val defaultZone = ZoneId.of("Europe/Istanbul")
    private val testPackage = "com.supercell.clashroyale"

    @Test
    fun testBelowThresholdDuration_14999ms_isIgnored() {
        val startTs = 1_000_000L
        val endTs = startTs + 14_999L // 14.999 seconds

        val events = listOf(
            UsageEvent(testPackage, startTs, UsageEventKind.RESUMED),
            UsageEvent(testPackage, endTs, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertTrue("Session under 15s must be filtered out as noise", result.sessions.isEmpty())
        assertTrue("No open sessions should remain", result.openSessions.isEmpty())
    }

    @Test
    fun testExactThresholdDuration_15000ms_isRecorded() {
        val startTs = 1_000_000L
        val endTs = startTs + 15_000L // exactly 15.000 seconds

        val events = listOf(
            UsageEvent(testPackage, startTs, UsageEventKind.RESUMED),
            UsageEvent(testPackage, endTs, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Session of exactly 15s must be recorded", 1, result.sessions.size)
        val slice = result.sessions.first()
        assertEquals(testPackage, slice.packageName)
        assertEquals(startTs, slice.startTime)
        assertEquals(endTs, slice.endTime)
        assertEquals(15_000L, slice.durationMs)
        assertTrue(result.openSessions.isEmpty())
    }

    @Test
    fun testCrossMidnight_splitAtLocalMidnight() {
        // 2026-09-24 23:50:00 to 2026-09-25 00:20:00 in Europe/Istanbul (+03:00)
        val tStart = ZonedDateTime.of(2026, 9, 24, 23, 50, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tMidnight = ZonedDateTime.of(2026, 9, 25, 0, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tEnd = ZonedDateTime.of(2026, 9, 25, 0, 20, 0, 0, defaultZone).toInstant().toEpochMilli()

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Cross-midnight session must be split into 2 slices", 2, result.sessions.size)

        val slice1 = result.sessions[0]
        assertEquals(tStart, slice1.startTime)
        assertEquals(tMidnight, slice1.endTime)
        assertEquals(10 * 60 * 1000L, slice1.durationMs) // 10 minutes

        val slice2 = result.sessions[1]
        assertEquals(tMidnight, slice2.startTime)
        assertEquals(tEnd, slice2.endTime)
        assertEquals(20 * 60 * 1000L, slice2.durationMs) // 20 minutes

        // Total duration preserved
        assertEquals(tEnd - tStart, slice1.durationMs + slice2.durationMs)
        assertTrue(result.openSessions.isEmpty())
    }

    @Test
    fun testCrossMidnight_shortSessionUnder15s_isIgnored() {
        // Starts 5s before midnight, ends 5s after midnight: total 10s (< 15s)
        val tMidnight = ZonedDateTime.of(2026, 9, 25, 0, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tStart = tMidnight - 5_000L
        val tEnd = tMidnight + 5_000L

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertTrue("Session under 15s crossing midnight must be filtered as noise", result.sessions.isEmpty())
    }

    @Test
    fun testCrossMidnight_completed15sWithSmallSlices_splitsSuccessfully() {
        // Starts 10s before midnight, ends 5s after midnight: total 15s (>= 15s)
        val tMidnight = ZonedDateTime.of(2026, 9, 25, 0, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tStart = tMidnight - 10_000L
        val tEnd = tMidnight + 5_000L

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Session of 15s crossing midnight must produce 2 slices", 2, result.sessions.size)
        assertEquals(tStart, result.sessions[0].startTime)
        assertEquals(tMidnight, result.sessions[0].endTime)
        assertEquals(10_000L, result.sessions[0].durationMs)

        assertEquals(tMidnight, result.sessions[1].startTime)
        assertEquals(tEnd, result.sessions[1].endTime)
        assertEquals(5_000L, result.sessions[1].durationMs)
    }

    @Test
    fun testMultiDaySession_splitAcrossMultipleMidnights() {
        // Day 1 23:00 to Day 3 01:00 (crossing two midnights)
        val tStart = ZonedDateTime.of(2026, 9, 20, 23, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tMidnight1 = ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tMidnight2 = ZonedDateTime.of(2026, 9, 22, 0, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tEnd = ZonedDateTime.of(2026, 9, 22, 1, 0, 0, 0, defaultZone).toInstant().toEpochMilli()

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Session crossing 2 midnights must yield 3 slices", 3, result.sessions.size)
        assertEquals(tStart, result.sessions[0].startTime)
        assertEquals(tMidnight1, result.sessions[0].endTime)

        assertEquals(tMidnight1, result.sessions[1].startTime)
        assertEquals(tMidnight2, result.sessions[1].endTime)

        assertEquals(tMidnight2, result.sessions[2].startTime)
        assertEquals(tEnd, result.sessions[2].endTime)

        val totalSliceDuration = result.sessions.sumOf { it.durationMs }
        assertEquals(tEnd - tStart, totalSliceDuration)
    }

    @Test
    fun testDst_springForward_transition() {
        // America/New_York jumps from 02:00 to 03:00 on 2026-03-08
        val nyZone = ZoneId.of("America/New_York")
        val tStart = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, nyZone).toInstant().toEpochMilli()
        val tMidnight = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, nyZone).toInstant().toEpochMilli()
        val tEnd = ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, nyZone).toInstant().toEpochMilli()

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = nyZone
        )

        assertEquals("Must split at local midnight into 2 slices across spring forward", 2, result.sessions.size)
        assertEquals(tStart, result.sessions[0].startTime)
        assertEquals(tMidnight, result.sessions[0].endTime)
        assertEquals(3600_000L, result.sessions[0].durationMs) // 1 hour (23:00 to 00:00)

        assertEquals(tMidnight, result.sessions[1].startTime)
        assertEquals(tEnd, result.sessions[1].endTime)
        // 00:00 to 04:00 with 1 hour skipped = 3 hours elapsed (3 * 3600_000 = 10_800_000 ms)
        assertEquals(10_800_000L, result.sessions[1].durationMs)

        assertEquals(tEnd - tStart, result.sessions.sumOf { it.durationMs })
    }

    @Test
    fun testDst_fallBack_transition() {
        // America/New_York turns back from 02:00 EDT to 01:00 EST on 2026-11-01 (25-hour day)
        val nyZone = ZoneId.of("America/New_York")
        val tStart = ZonedDateTime.of(2026, 10, 31, 23, 0, 0, 0, nyZone).toInstant().toEpochMilli()
        val tMidnight = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, nyZone).toInstant().toEpochMilli()
        val tEnd = ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, nyZone).toInstant().toEpochMilli()

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = nyZone
        )

        assertEquals("Must split at local midnight into 2 slices across fall back", 2, result.sessions.size)
        assertEquals(tStart, result.sessions[0].startTime)
        assertEquals(tMidnight, result.sessions[0].endTime)
        assertEquals(3600_000L, result.sessions[0].durationMs) // 1 hour

        assertEquals(tMidnight, result.sessions[1].startTime)
        assertEquals(tEnd, result.sessions[1].endTime)
        // 00:00 to 03:00 with 1 repeated hour = 4 hours elapsed (4 * 3600_000 = 14_400_000 ms)
        assertEquals(14_400_000L, result.sessions[1].durationMs)

        assertEquals(tEnd - tStart, result.sessions.sumOf { it.durationMs })
    }

    @Test
    fun testMissingPause_retainsOpenSessionAcrossScans() {
        val tStart = 500_000L
        val tEnd = 600_000L // 100s later

        // Scan 1: RESUMED event with no closing event (app still running or crashed)
        val scan1Events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED)
        )

        val scan1Result = UsageDeltaParser.parse(
            events = scan1Events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertTrue("Scan 1 should emit no sessions without pause event", scan1Result.sessions.isEmpty())
        assertEquals("Scan 1 should retain open session", mapOf(testPackage to tStart), scan1Result.openSessions)

        // Scan 2: Empty events (user still playing)
        val scan2Result = UsageDeltaParser.parse(
            events = emptyList(),
            openSessions = scan1Result.openSessions,
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )
        assertTrue("Scan 2 should emit no sessions", scan2Result.sessions.isEmpty())
        assertEquals("Scan 2 should still retain open session", mapOf(testPackage to tStart), scan2Result.openSessions)

        // Scan 3: PAUSED event arrives
        val scan3Events = listOf(
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val scan3Result = UsageDeltaParser.parse(
            events = scan3Events,
            openSessions = scan2Result.openSessions,
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Scan 3 should complete the session", 1, scan3Result.sessions.size)
        val session = scan3Result.sessions.first()
        assertEquals(tStart, session.startTime)
        assertEquals(tEnd, session.endTime)
        assertEquals(100_000L, session.durationMs)
        assertTrue("Open session should be cleared after close", scan3Result.openSessions.isEmpty())
    }

    @Test
    fun testReplay_idempotentOutput() {
        val tStart = 100_000L
        val tEnd = 200_000L

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result1 = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        val result2 = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals(result1.sessions, result2.sessions)
        assertEquals(result1.openSessions, result2.openSessions)
    }

    @Test
    fun testReplay_partialOverlap_noFabricatedDuration() {
        val tStart = 100_000L
        val tEnd = 130_000L

        // Prior scan left open session at tStart
        val openSessions = mapOf(testPackage to tStart)

        // Scan has overlapping lookback that re-emits RESUMED at tStart followed by PAUSED at tEnd
        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = openSessions,
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Only one session slice should be emitted", 1, result.sessions.size)
        val session = result.sessions.first()
        assertEquals(tStart, session.startTime)
        assertEquals(tEnd, session.endTime)
        assertEquals(30_000L, session.durationMs)
        assertTrue(result.openSessions.isEmpty())
    }

    @Test
    fun testIgnoredPackages_untrackedAreSkipped() {
        val tracked = "com.tracked.game"
        val untracked = "com.whatsapp"

        val events = listOf(
            UsageEvent(untracked, 100_000L, UsageEventKind.RESUMED),
            UsageEvent(tracked, 110_000L, UsageEventKind.RESUMED),
            UsageEvent(untracked, 200_000L, UsageEventKind.PAUSED),
            UsageEvent(tracked, 210_000L, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = mapOf(untracked to 50_000L),
            trackedPackages = setOf(tracked),
            zoneId = defaultZone
        )

        assertEquals("Only tracked package sessions should be produced", 1, result.sessions.size)
        assertEquals(tracked, result.sessions.first().packageName)
        assertEquals(110_000L, result.sessions.first().startTime)
        assertEquals(210_000L, result.sessions.first().endTime)
        assertTrue("Untracked package open sessions must be discarded", result.openSessions.isEmpty())
    }

    @Test
    fun testRepeatedEvents_duplicateSameTimestampResumedAndPaused() {
        val tStart = 100_000L
        val tEnd = 130_000L

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED), // duplicate same-timestamp RESUMED
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED),
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED),    // duplicate same-timestamp PAUSED
            UsageEvent(testPackage, tEnd + 500L, UsageEventKind.PAUSED) // trailing duplicate pause
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Duplicate events must produce exactly 1 session slice", 1, result.sessions.size)
        val session = result.sessions.first()
        assertEquals("Start time must match tStart", tStart, session.startTime)
        assertEquals(tEnd, session.endTime)
        assertEquals(30_000L, session.durationMs)
        assertTrue(result.openSessions.isEmpty())
    }

    @Test
    fun testOldResumed_dayLaterResumed_recordsOnlyLatterDuration() {
        // Day 1: app resumed at 10:00, crashed/killed without PAUSED
        val tDay1Resumed = ZonedDateTime.of(2026, 9, 20, 10, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        // Day 2: app resumed at 10:00 (24h later), played for 30 minutes, then paused
        val tDay2Resumed = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, defaultZone).toInstant().toEpochMilli()
        val tDay2Paused = ZonedDateTime.of(2026, 9, 21, 10, 30, 0, 0, defaultZone).toInstant().toEpochMilli()

        // Scan 1 left open session from Day 1
        val openSessions = mapOf(testPackage to tDay1Resumed)

        // Scan 2 detects Day 2 events
        val events = listOf(
            UsageEvent(testPackage, tDay2Resumed, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tDay2Paused, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = openSessions,
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        // Must record only the 30-minute session from Day 2, discarding phantom 24h+ duration
        assertEquals("Must produce exactly 1 session slice for the latter session", 1, result.sessions.size)
        val session = result.sessions.first()
        assertEquals(testPackage, session.packageName)
        assertEquals(tDay2Resumed, session.startTime)
        assertEquals(tDay2Paused, session.endTime)
        assertEquals(30 * 60 * 1000L, session.durationMs)
        assertTrue(result.openSessions.isEmpty())
    }

    @Test
    fun testOutOfOrderEvents_sortedChronologically() {
        val tStart = 100_000L
        val tEnd = 140_000L

        // Inverted input order
        val events = listOf(
            UsageEvent(testPackage, tEnd, UsageEventKind.PAUSED),
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("Out-of-order events must be ordered and resolved", 1, result.sessions.size)
        assertEquals(tStart, result.sessions.first().startTime)
        assertEquals(tEnd, result.sessions.first().endTime)
        assertEquals(40_000L, result.sessions.first().durationMs)
    }

    @Test
    fun testInvalidAndEmptyIntervals_ignored() {
        val tStart = 100_000L

        val events = listOf(
            // Invalid negative timestamp
            UsageEvent(testPackage, -100L, UsageEventKind.RESUMED),
            // Zero timestamp
            UsageEvent(testPackage, 0L, UsageEventKind.RESUMED),
            // Instant pause with zero duration
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tStart, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertTrue("Invalid and empty intervals must produce no sessions", result.sessions.isEmpty())
        assertTrue("No open sessions should remain", result.openSessions.isEmpty())
    }

    @Test
    fun testStoppedKind_actsAsCloseEvent() {
        val tStart = 100_000L
        val tEnd = 150_000L

        val events = listOf(
            UsageEvent(testPackage, tStart, UsageEventKind.RESUMED),
            UsageEvent(testPackage, tEnd, UsageEventKind.STOPPED),
            // Trailing PAUSED after STOPPED
            UsageEvent(testPackage, tEnd + 1000L, UsageEventKind.PAUSED)
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(testPackage),
            zoneId = defaultZone
        )

        assertEquals("STOPPED must successfully close session", 1, result.sessions.size)
        assertEquals(tStart, result.sessions.first().startTime)
        assertEquals(tEnd, result.sessions.first().endTime)
        assertEquals(50_000L, result.sessions.first().durationMs)
        assertTrue(result.openSessions.isEmpty())
    }

    @Test
    fun testMultipleIndependentTrackedPackages() {
        val gameA = "com.game.a"
        val gameB = "com.game.b"

        val events = listOf(
            UsageEvent(gameA, 100_000L, UsageEventKind.RESUMED),
            UsageEvent(gameB, 110_000L, UsageEventKind.RESUMED),
            UsageEvent(gameA, 130_000L, UsageEventKind.PAUSED),  // 30s session
            UsageEvent(gameB, 150_000L, UsageEventKind.STOPPED)  // 40s session
        )

        val result = UsageDeltaParser.parse(
            events = events,
            openSessions = emptyMap(),
            trackedPackages = setOf(gameA, gameB),
            zoneId = defaultZone
        )

        assertEquals("Both packages must have recorded sessions", 2, result.sessions.size)
        val sessionA = result.sessions.first { it.packageName == gameA }
        val sessionB = result.sessions.first { it.packageName == gameB }

        assertEquals(100_000L, sessionA.startTime)
        assertEquals(130_000L, sessionA.endTime)
        assertEquals(30_000L, sessionA.durationMs)

        assertEquals(110_000L, sessionB.startTime)
        assertEquals(150_000L, sessionB.endTime)
        assertEquals(40_000L, sessionB.durationMs)
        assertTrue(result.openSessions.isEmpty())
    }
}
