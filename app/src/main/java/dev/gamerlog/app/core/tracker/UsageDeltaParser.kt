package dev.gamerlog.app.core.tracker

import java.time.Instant
import java.time.ZoneId

enum class UsageEventKind {
    RESUMED,
    PAUSED,
    STOPPED
}

data class UsageEvent(
    val packageName: String,
    val timestampMs: Long,
    val kind: UsageEventKind
)

data class SessionSlice(
    val packageName: String,
    val startTime: Long,
    val endTime: Long
) {
    val durationMs: Long
        get() = endTime - startTime

    init {
        require(endTime >= startTime) { "endTime ($endTime) must be >= startTime ($startTime)" }
    }
}

data class ParseResult(
    val sessions: List<SessionSlice>,
    val openSessions: Map<String, Long>
)

object UsageDeltaParser {
    const val MIN_SESSION_DURATION_MS = 15_000L

    /**
     * Parses usage events and computes completed session slices.
     *
     * Invariants:
     * - Only events for [trackedPackages] are processed.
     * - Only positive timestamps (> 0) are considered.
     * - Events are processed in chronological order, preserving relative order for events at the same millisecond.
     * - Only completed sessions of at least 15,000 ms (15s) are recorded. Sessions shorter than 15s are noise.
     * - Completed sessions of >= 15,000 ms that cross local midnight are split at local midnight
     *   boundaries using [zoneId], properly respecting DST transitions.
     * - Unmatched open sessions (RESUMED without closing event) are retained in [ParseResult.openSessions]
     *   across scans without artificial closure.
     * - Repeated/replayed events and invalid/empty time intervals (endTime <= startTime) do not fabricate duration.
     */
    fun parse(
        events: List<UsageEvent>,
        openSessions: Map<String, Long>,
        trackedPackages: Set<String>,
        zoneId: ZoneId
    ): ParseResult {
        if (trackedPackages.isEmpty()) {
            return ParseResult(sessions = emptyList(), openSessions = emptyMap())
        }

        // Filter and retain open sessions only for currently tracked packages with valid timestamps
        val currentOpen = openSessions.filterKeys { it in trackedPackages }
            .filterValues { it > 0 }
            .toMutableMap()

        // Filter events: only tracked packages and strictly positive timestamps
        val filteredEvents = events.filter { it.packageName in trackedPackages && it.timestampMs > 0 }

        // Sort events chronologically. For equal timestamps, stable sort preserves original occurrence sequence.
        val sortedEvents = filteredEvents.sortedBy { it.timestampMs }

        val completedSlices = mutableListOf<SessionSlice>()

        for (event in sortedEvents) {
            val pkg = event.packageName
            val ts = event.timestampMs

            when (event.kind) {
                UsageEventKind.RESUMED -> {
                    val existingStart = currentOpen[pkg]
                    if (existingStart == null) {
                        currentOpen[pkg] = ts
                    } else if (ts > existingStart) {
                        // Strictly later RESUMED without prior PAUSED/STOPPED indicates
                        // a crash/reboot or unmatched previous open. Reset start time to fresh start
                        // to avoid fabricating hours/days of phantom duration.
                        // (Trade-off: intra-app activity transitions without intermediate pause
                        // may undercount preceding activity duration, but this is the safe conservative choice).
                        currentOpen[pkg] = ts
                    }
                    // If ts <= existingStart: duplicate same-timestamp or stale replayed RESUMED is a no-op.
                }
                UsageEventKind.PAUSED, UsageEventKind.STOPPED -> {
                    val startTime = currentOpen[pkg]
                    if (startTime != null) {
                        if (ts > startTime) {
                            currentOpen.remove(pkg)
                            val totalDuration = ts - startTime
                            if (totalDuration >= MIN_SESSION_DURATION_MS) {
                                completedSlices.addAll(
                                    splitSessionByMidnight(pkg, startTime, ts, zoneId)
                                )
                            }
                        } else if (ts == startTime) {
                            // Instantaneous open and close at exact same millisecond: zero duration noise.
                            currentOpen.remove(pkg)
                        }
                        // If ts < startTime: stale out-of-order event occurring before session opened;
                        // ignore and keep active open session intact.
                    }
                    // If startTime == null: no open session, ignore stray pause/stop event.
                }
            }
        }

        return ParseResult(
            sessions = completedSlices,
            openSessions = currentOpen
        )
    }


    /**
     * Splits a completed session spanning [startTime] to [endTime] by local date midnight boundaries in [zoneId].
     */
    fun splitSessionByMidnight(
        packageName: String,
        startTime: Long,
        endTime: Long,
        zoneId: ZoneId
    ): List<SessionSlice> {
        if (endTime <= startTime) return emptyList()

        val slices = mutableListOf<SessionSlice>()
        var currStart = startTime

        while (currStart < endTime) {
            val zdt = Instant.ofEpochMilli(currStart).atZone(zoneId)
            val nextMidnight = zdt.toLocalDate().plusDays(1).atStartOfDay(zoneId)
            val nextMidnightMs = nextMidnight.toInstant().toEpochMilli()

            if (nextMidnightMs <= currStart) {
                // Safety guard against pathological timezone edge cases
                slices.add(SessionSlice(packageName, currStart, endTime))
                break
            }

            if (endTime <= nextMidnightMs) {
                slices.add(SessionSlice(packageName, currStart, endTime))
                currStart = endTime
            } else {
                slices.add(SessionSlice(packageName, currStart, nextMidnightMs))
                currStart = nextMidnightMs
            }
        }

        return slices
    }
}
