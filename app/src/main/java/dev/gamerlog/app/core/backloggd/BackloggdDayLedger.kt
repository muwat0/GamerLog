package dev.gamerlog.app.core.backloggd

import java.time.LocalDate

internal data class BackloggdDayContribution(
    val marker: String,
    val playedOn: String,
    val durationMs: Long
)

internal data class BackloggdDayLedger(
    val baseMinutes: Long,
    val contributions: List<BackloggdDayContribution>,
    val blockStart: Int,
    val blockEndExclusive: Int
)

private const val LEDGER_BEGIN = "[gamerlog-day-ledger:v1]"
private const val LEDGER_END = "[/gamerlog-day-ledger]"
private const val LEDGER_PREFIX = "[gamerlog-day-ledger"
private const val LEDGER_END_PREFIX = "[/gamerlog-day-ledger"
private val LEDGER_BASE_PATTERN = Regex("base_minutes=(0|[1-9][0-9]*)")
private val LEDGER_MARKER_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val LEDGER_DURATION_PATTERN = Regex("[1-9][0-9]*")

internal fun parseBackloggdDayLedger(note: String): BackloggdDayLedger? {
    val begin = note.indexOf(LEDGER_PREFIX)
    val endPrefix = note.indexOf(LEDGER_END_PREFIX)
    if (begin < 0 && endPrefix < 0) return null
    if (begin < 0 || endPrefix < 0 ||
        note.indexOf(LEDGER_PREFIX, begin + LEDGER_PREFIX.length) >= 0 ||
        note.indexOf(LEDGER_END_PREFIX, endPrefix + LEDGER_END_PREFIX.length) >= 0 ||
        begin > endPrefix
    ) {
        throw IllegalArgumentException("Malformed GamerLog day ledger")
    }
    if (!note.startsWith(LEDGER_BEGIN, begin)) {
        throw IllegalArgumentException("Unknown GamerLog day ledger version")
    }
    val end = note.indexOf(LEDGER_END, begin + LEDGER_BEGIN.length)
    if (end != endPrefix || note.indexOf(LEDGER_END, end + LEDGER_END.length) >= 0) {
        throw IllegalArgumentException("Malformed GamerLog day ledger")
    }
    val bodyStart = begin + LEDGER_BEGIN.length
    if (bodyStart >= end || note[bodyStart] != '\n' || note[end - 1] != '\n') {
        throw IllegalArgumentException("Malformed GamerLog day ledger")
    }
    val lines = note.substring(bodyStart + 1, end - 1).split('\n')
    if (lines.isEmpty()) throw IllegalArgumentException("Malformed GamerLog day ledger")
    val base = LEDGER_BASE_PATTERN.matchEntire(lines.first())?.groupValues?.get(1)?.toLongOrNull()
        ?: throw IllegalArgumentException("Malformed GamerLog day ledger")
    val contributions = ArrayList<BackloggdDayContribution>(lines.size - 1)
    val markers = HashSet<String>(lines.size - 1)
    for (lineIndex in 1 until lines.size) {
        val parts = lines[lineIndex].split('|')
        if (parts.size != 3 || !LEDGER_MARKER_PATTERN.matches(parts[0]) ||
            !markers.add(parts[0]) || !LEDGER_DURATION_PATTERN.matches(parts[2])
        ) {
            throw IllegalArgumentException("Malformed GamerLog day ledger")
        }
        val playedOn = try {
            LocalDate.parse(parts[1]).takeIf { it.toString() == parts[1] }
        } catch (_: Exception) {
            null
        } ?: throw IllegalArgumentException("Malformed GamerLog day ledger")
        val durationMs = parts[2].toLongOrNull()
            ?: throw IllegalArgumentException("Malformed GamerLog day ledger")
        contributions += BackloggdDayContribution(parts[0], playedOn.toString(), durationMs)
    }
    if (contributions.isEmpty()) throw IllegalArgumentException("Malformed GamerLog day ledger")
    return BackloggdDayLedger(base, contributions, begin, end + LEDGER_END.length)
}

internal fun backloggdManagedMinutes(ledger: BackloggdDayLedger): Long =
    backloggdManagedMinutes(ledger.contributions)

internal fun backloggdManagedMinutes(contributions: List<BackloggdDayContribution>): Long {
    val dayMillis = HashMap<String, Long>(contributions.size)
    for (entry in contributions) {
        dayMillis[entry.playedOn] = Math.addExact(dayMillis[entry.playedOn] ?: 0L, entry.durationMs)
    }
    var total = 0L
    for (millis in dayMillis.values) total = Math.addExact(total, millis / 60_000L)
    return total
}

internal fun renderBackloggdDayLedger(
    originalNote: String,
    existing: BackloggdDayLedger?,
    baseMinutes: Long,
    contributions: List<BackloggdDayContribution>
): String {
    require(baseMinutes >= 0L && contributions.isNotEmpty())
    val ordered = contributions.sortedWith(compareBy(BackloggdDayContribution::playedOn, BackloggdDayContribution::marker))
    val block = buildString {
        append(LEDGER_BEGIN)
        append('\n')
        append("base_minutes=")
        append(baseMinutes)
        for (entry in ordered) {
            append('\n')
            append(entry.marker)
            append('|')
            append(entry.playedOn)
            append('|')
            append(entry.durationMs)
        }
        append('\n')
        append(LEDGER_END)
    }
    if (existing == null) {
        return if (originalNote.isEmpty()) block else "$originalNote\n\n$block"
    }
    return buildString(originalNote.length - (existing.blockEndExclusive - existing.blockStart) + block.length) {
        append(originalNote, 0, existing.blockStart)
        append(block)
        append(originalNote, existing.blockEndExclusive, originalNote.length)
    }
}
