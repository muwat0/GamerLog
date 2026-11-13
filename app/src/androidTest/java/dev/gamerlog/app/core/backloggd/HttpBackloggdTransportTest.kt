package dev.gamerlog.app.core.backloggd

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdExportEntity
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private fun Map<String, List<String>>.one(key: String): String =
    get(key)?.singleOrNull() ?: throw AssertionError("Missing or repeated form field: $key")


@RunWith(AndroidJUnit4::class)
class HttpBackloggdTransportTest {
    @Test
    fun sameDayContributionsUpdateTheExistingDateAndRemainFindable() = runBlocking {
        val first = export(FIRST_MARKER, DAY, 60_000L)
        val second = export(SECOND_MARKER, DAY, 120_000L)
        val exchange = FixtureExchange(
            remoteLog(
                listOf(
                    playthrough(7L, "Existing", listOf(remoteDate(301L, DAY, "2026-11-13", note = "manual")))
                )
            )
        )
        val transport = transport(exchange)

        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, first))
        assertEquals("301", exchange.posts.single().one("dates[7][0][id]"))
        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, second))

        val note = exchange.currentDate(7L, 301L).getString("note")
        assertEquals(
            "manual\n\n[gamerlog-day-ledger:v1]\nbase_minutes=0\n" +
                "$FIRST_MARKER|$DAY|60000\n$SECOND_MARKER|$DAY|120000\n" +
                "[/gamerlog-day-ledger]",
            note
        )
        assertEquals(3L, remoteDurationMinutes(exchange.currentDate(7L, 301L)))
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, first))
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, second))
        assertEquals(2, exchange.posts.size)
        assertEquals("301", exchange.posts.last().one("dates[7][0][id]"))
    }

    @Test
    fun sameDaySubminuteContributionsCarryTheirRemainder() = runBlocking {
        val first = export(FIRST_MARKER, DAY, 40_000L)
        val second = export(SECOND_MARKER, DAY, 40_000L)
        val exchange = existingDateExchange()
        val transport = transport(exchange)

        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, first))
        assertEquals(0L, remoteDurationMinutes(exchange.currentDate(7L, 301L)))
        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, second))

        assertEquals(1L, remoteDurationMinutes(exchange.currentDate(7L, 301L)))
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, first))
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, second))
    }

    @Test
    fun existingMostRecentRangePreservesManualTimeNotesPrivacyTagsAndReview() = runBlocking {
        val originalNote = "Manual notes stay byte-for-byte.\n" +
            "[gamerlog_session_id:$SECOND_MARKER] [gamerlog_duration_ms:120000] " +
            "[gamerlog_exported_minutes:2]"
        val olderDate = remoteDate(301L, "2026-11-10", "2026-11-14", 2L, 15L, "older note")
        val mostRecentDate = remoteDate(
            302L,
            "2026-11-10",
            "2026-11-14",
            2L,
            15L,
            originalNote
        ).apply {
            put("status", "finished")
            put("is_private", true)
            put("tags", JSONArray().put("story").put("co-op"))
            put("started_at", "2026-11-10T08:00:00Z")
            put("finished_at", "2026-11-14T20:00:00Z")
            put("unrelated", "keep-me")
        }
        val older = remotePlaythrough(7L, "GamerLog", listOf(olderDate)).put("review", "older review")
        val mostRecent = remotePlaythrough(8L, "My existing playthrough", listOf(mostRecentDate)).apply {
            put("review", "original review")
            put("rating", 4)
        }
        val exchange = FixtureExchange(remoteLog(listOf(older, mostRecent), mostRecentId = 8L))
        val transport = transport(exchange)
        val item = export(SECOND_MARKER, DAY, 120_000L)

        assertNull(transport.findExport(CREDENTIALS, item))
        assertEquals("26226:8:302", transport.sendExport(CREDENTIALS, item))

        val posted = exchange.posts.single()
        assertEquals("302", posted.one("dates[8][0][id]"))
        assertEquals("2026-11-10", posted.one("dates[8][0][range_start_date]"))
        assertEquals("2026-11-14", posted.one("dates[8][0][range_end_date]"))
        assertEquals("2", posted.one("dates[8][0][hours]"))
        assertEquals("17", posted.one("dates[8][0][minutes]"))
        assertEquals("finished", posted.one("dates[8][0][status]"))
        assertEquals("true", posted.one("dates[8][0][is_private]"))
        assertEquals(listOf("story", "co-op"), posted["dates[8][0][tags][]"])
        assertEquals("2026-11-10T08:00:00Z", posted.one("dates[8][0][started_at]"))
        assertEquals("2026-11-14T20:00:00Z", posted.one("dates[8][0][finished_at]"))
        assertEquals("keep-me", posted.one("dates[8][0][unrelated]"))
        assertEquals("original review", posted.one("playthroughs[0][review]"))
        assertEquals("4", posted.one("playthroughs[0][rating]"))
        assertEquals("preserve", posted.one("log[custom_metadata]"))
        assertEquals("played", posted.one("log[status]"))
        assertEquals(
            "$originalNote\n\n[gamerlog-day-ledger:v1]\nbase_minutes=135\n" +
                "$SECOND_MARKER|$DAY|120000\n[/gamerlog-day-ledger]",
            posted.one("dates[8][0][note]")
        )
        assertEquals("true", exchange.currentDate(8L, 302L).getString("is_private"))
        assertEquals("26226:8:302", transport.findExport(CREDENTIALS, item))
    }

    @Test
    fun invalidMostRecentIdFallsBackToTheFirstExistingPlaythrough() = runBlocking {
        val exchange = FixtureExchange(
            remoteLog(
                listOf(
                    playthrough(7L, "First existing", listOf(remoteDate(301L, DAY, "2026-11-13"))),
                    playthrough(8L, "GamerLog", listOf(remoteDate(302L, DAY, "2026-11-13")))
                ),
                mostRecentId = 999L
            )
        )

        assertEquals(
            "26226:7:301",
            transport(exchange).sendExport(CREDENTIALS, export(FIRST_MARKER, DAY, 60_000L))
        )
        assertEquals("301", exchange.posts.single().one("dates[7][0][id]"))
    }


    @Test
    fun multidayRangeKeepsEachContributionOnItsOriginalDayForRounding() = runBlocking {
        val firstDay = "2026-11-11"
        val secondDay = "2026-11-12"
        val first = export(FIRST_MARKER, firstDay, 40_000L)
        val second = export(SECOND_MARKER, secondDay, 40_000L)
        val exchange = FixtureExchange(
            remoteLog(
                listOf(
                    playthrough(7L, "Existing", listOf(remoteDate(301L, firstDay, "2026-11-13", note = "manual")))
                )
            )
        )
        val transport = transport(exchange)

        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, first))
        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, second))

        val stored = exchange.currentDate(7L, 301L)
        assertEquals("2026-11-11", stored.getString("range_start_date"))
        assertEquals("2026-11-13", stored.getString("range_end_date"))
        assertEquals(0L, remoteDurationMinutes(stored))
        assertTrue(stored.getString("note").contains("$FIRST_MARKER|2026-11-11|40000"))
        assertTrue(stored.getString("note").contains("$SECOND_MARKER|2026-11-12|40000"))
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, first))
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, second))
    }

    @Test
    fun existingMatchingContributionIsIdempotentAndConflictingUuidFailsClosed() = runBlocking {
        val existingNote = """User note before.

[gamerlog-day-ledger:v1]
base_minutes=0
$FIRST_MARKER|$DAY|60000
[/gamerlog-day-ledger]

User note after."""
        val exchange = FixtureExchange(
            remoteLog(
                listOf(
                    playthrough(7L, "Existing", listOf(remoteDate(301L, DAY, "2026-11-13", 0L, 3L, existingNote)))
                )
            )
        )
        val transport = transport(exchange)
        val same = export(FIRST_MARKER, DAY, 60_000L)

        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, same))
        assertEquals(0, exchange.posts.size)
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, same))
        assertEquals(
            "26226:7:301",
            transport.sendExport(CREDENTIALS, export(SECOND_MARKER, DAY, 60_000L))
        )
        assertEquals(
            "User note before.\n\n[gamerlog-day-ledger:v1]\nbase_minutes=2\n" +
                "$FIRST_MARKER|$DAY|60000\n$SECOND_MARKER|$DAY|60000\n" +
                "[/gamerlog-day-ledger]\n\nUser note after.",
            exchange.currentDate(7L, 301L).getString("note")
        )
        assertEquals(1, exchange.posts.size)
        assertConflict { transport.findExport(CREDENTIALS, export(FIRST_MARKER, DAY, 120_000L)) }
    }

    @Test
    fun malformedDuplicateAndUndercountedLedgersFailClosed() = runBlocking {
        val malformed = """[gamerlog-day-ledger:v1]
base_minutes=not-a-number
$FIRST_MARKER|$DAY|60000
[/gamerlog-day-ledger]"""
        val malformedExchange = ledgerExchange(malformed, 0L, 1L)
        assertConflict { transport(malformedExchange).findExport(CREDENTIALS, export(FIRST_MARKER, DAY, 60_000L)) }

        val duplicate = """[gamerlog-day-ledger:v1]
base_minutes=0
$FIRST_MARKER|$DAY|60000
$FIRST_MARKER|$DAY|60000
[/gamerlog-day-ledger]"""
        val duplicateExchange = ledgerExchange(duplicate, 0L, 1L)
        assertConflict { transport(duplicateExchange).sendExport(CREDENTIALS, export(SECOND_MARKER, DAY, 60_000L)) }
        assertEquals(0, duplicateExchange.posts.size)

        val undercounted = """[gamerlog-day-ledger:v1]
base_minutes=2
$FIRST_MARKER|$DAY|120000
[/gamerlog-day-ledger]"""
        val undercountedExchange = ledgerExchange(undercounted, 0L, 3L)
        assertConflict { transport(undercountedExchange).findExport(CREDENTIALS, export(FIRST_MARKER, DAY, 120_000L)) }

        val duplicateTrailing = """[gamerlog-day-ledger:v1]
base_minutes=0
$FIRST_MARKER|$DAY|60000
[/gamerlog-day-ledger]
[/gamerlog-day-ledger]"""
        val duplicateTrailingExchange = ledgerExchange(duplicateTrailing, 0L, 1L)
        assertConflict {
            transport(duplicateTrailingExchange).sendExport(CREDENTIALS, export(SECOND_MARKER, DAY, 60_000L))
        }
        assertEquals(0, duplicateTrailingExchange.posts.size)

        val unknownTrailing = """[gamerlog-day-ledger:v1]
base_minutes=0
$FIRST_MARKER|$DAY|60000
[/gamerlog-day-ledger]
[gamerlog-day-ledger:v2]
ignored"""
        val unknownTrailingExchange = ledgerExchange(unknownTrailing, 0L, 1L)
        assertConflict {
            transport(unknownTrailingExchange).sendExport(CREDENTIALS, export(SECOND_MARKER, DAY, 60_000L))
        }
        assertEquals(0, unknownTrailingExchange.posts.size)
    }

    @Test
    fun rawUnstructuredRequestedMarkerIsAConflictAndNeverPosted() = runBlocking {
        val exchange = ledgerExchange("manual note [gamerlog_session_id:$FIRST_MARKER]", 0L, 0L)

        assertConflict {
            transport(exchange).sendExport(CREDENTIALS, export(FIRST_MARKER, DAY, 60_000L))
        }

        assertEquals(0, exchange.posts.size)
    }

    @Test
    fun overlappingRangesFailBeforeAnyPost() = runBlocking {
        val exchange = FixtureExchange(
            remoteLog(
                listOf(
                    playthrough(
                        7L,
                        "Existing",
                        listOf(
                            remoteDate(301L, "2026-11-11", "2026-11-13"),
                            remoteDate(302L, "2026-11-12", "2026-11-14")
                        )
                    )
                )
            )
        )

        assertConflict { transport(exchange).sendExport(CREDENTIALS, export(FIRST_MARKER, DAY, 60_000L)) }
        assertEquals(0, exchange.posts.size)
    }

    @Test
    fun readbackMustRetainEveryPreviousLedgerContribution() = runBlocking {
        val previousNote = """[gamerlog-day-ledger:v1]
base_minutes=0
$FIRST_MARKER|$DAY|60000
[/gamerlog-day-ledger]"""
        val exchange = ledgerExchange(previousNote, 0L, 1L)
        val afterPostWithoutPrevious = """[gamerlog-day-ledger:v1]
base_minutes=0
$SECOND_MARKER|$DAY|60000
[/gamerlog-day-ledger]"""
        exchange.afterPost = {
            exchange.logBody = remoteLog(
                listOf(
                    playthrough(7L, "Existing", listOf(remoteDate(301L, DAY, "2026-11-13", 0L, 1L, afterPostWithoutPrevious)))
                )
            )
        }

        val failure = captureBackloggdFailure {
            transport(exchange).sendExport(CREDENTIALS, export(SECOND_MARKER, DAY, 60_000L))
        }
        assertTrue(failure.deliveryMayHaveOccurred)
        assertTrue(exchange.posts.single().one("dates[7][0][note]").contains(FIRST_MARKER))
    }

    @Test
    fun emptyNewLogUsesTheNormalNewPlaythroughAndDateShape() = runBlocking {
        val exchange = FixtureExchange("""{"playthroughs":{},"most_recent_playthrough_id":null}""")
        val transport = transport(exchange)

        assertEquals("26226:7:301", transport.sendExport(CREDENTIALS, export(FIRST_MARKER, DAY, 30_000L)))

        val posted = exchange.posts.single()
        assertEquals("-1", posted.one("playthroughs[0][id]"))
        assertEquals("GamerLog", posted.one("playthroughs[0][title]"))
        assertEquals("-1", posted.one("dates[-1][0][id]"))
        assertEquals(DAY, posted.one("dates[-1][0][range_start_date]"))
        assertEquals("2026-11-13", posted.one("dates[-1][0][range_end_date]"))
        assertEquals("0", posted.one("dates[-1][0][hours]"))
        assertEquals("0", posted.one("dates[-1][0][minutes]"))
        assertEquals("", posted.one("dates[-1][0][status]"))
        assertEquals("true", posted.one("log[is_play]"))
        assertEquals("played", posted.one("log[status]"))
        assertTrue(posted.one("dates[-1][0][note]").contains("$FIRST_MARKER|$DAY|30000"))
    }

    @Test
    fun lostPostAcknowledgementIsUncertainAndCanBeReconciledWithoutResend() = runBlocking {
        val exchange = existingDateExchange().apply { losePostAcknowledgement = true }
        val transport = transport(exchange)
        val item = export(FIRST_MARKER, DAY, 60_000L)

        val failure = captureBackloggdFailure { transport.sendExport(CREDENTIALS, item) }
        assertTrue(failure.deliveryMayHaveOccurred)
        assertEquals("26226:7:301", transport.findExport(CREDENTIALS, item))
        assertEquals(1, exchange.posts.size)
    }

    @Test
    fun cancellationDuringPostReadbackStillPropagates() = runBlocking {
        val exchange = existingDateExchange().apply { cancelAfterPost = true }
        val send = launch {
            transport(exchange).sendExport(CREDENTIALS, export(FIRST_MARKER, DAY, 60_000L))
        }
        send.join()

        assertTrue(send.isCancelled)
        assertEquals(1, exchange.posts.size)
    }

    private fun existingDateExchange(): FixtureExchange = FixtureExchange(
        remoteLog(
            listOf(
                playthrough(7L, "Existing", listOf(remoteDate(301L, DAY, "2026-11-13", note = "manual")))
            )
        )
    )

    private fun ledgerExchange(note: String, hours: Long, minutes: Long): FixtureExchange = FixtureExchange(
        remoteLog(
            listOf(
                playthrough(7L, "Existing", listOf(remoteDate(301L, DAY, "2026-11-13", hours, minutes, note)))
            )
        )
    )

    private fun transport(exchange: FixtureExchange): HttpBackloggdTransport =
        HttpBackloggdTransport(FixtureCredentialStore, exchange)

    private fun export(marker: String, day: String, durationMs: Long): BackloggdExportEntity =
        BackloggdExportEntity(
            sessionId = 1L,
            accountUsername = "test_owner",
            gameSlug = "celeste",
            gameId = "26226",
            playedOn = day,
            durationMs = durationMs,
            marker = marker,
            createdAtMs = 0L,
            updatedAtMs = 0L
        )

    private fun remoteLog(
        playthroughs: List<JSONObject>,
        mostRecentId: Any? = 7L
    ): String {
        val playthroughMap = JSONObject()
        playthroughs.forEach { playthrough ->
            playthroughMap.put(playthrough.getLong("id").toString(), playthrough)
        }
        val root = JSONObject()
            .put("game_log", JSONObject().put("id", 101).put("status", "played").put("custom_metadata", "preserve"))
            .put("playthroughs", playthroughMap)
            .put("most_recent_playthrough_id", mostRecentId ?: JSONObject.NULL)
        return root.toString()
    }

    private fun playthrough(id: Long, title: String, dates: List<JSONObject>): JSONObject =
        remotePlaythrough(id, title, dates)

    private fun remotePlaythrough(id: Long, title: String, dates: List<JSONObject>): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("play_dates", JSONArray().apply { dates.forEach { put(it) } })

    private fun remoteDate(
        id: Long,
        start: String,
        end: String,
        hours: Long = 0L,
        minutes: Long = 0L,
        note: String = ""
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("range_start_date", start)
        .put("range_end_date", end)
        .put("hours", hours)
        .put("minutes", minutes)
        .put("note", note)

    private fun remoteDurationMinutes(date: JSONObject): Long =
        date.getLong("hours") * 60L + date.getLong("minutes")

    private suspend fun assertConflict(operation: suspend () -> Unit) {
        val failure = try {
            operation()
            null
        } catch (caught: BackloggdException) {
            caught
        }
        assertNotNull("Expected a fail-closed Backloggd conflict", failure)
        assertFalse(failure!!.deliveryMayHaveOccurred)
    }

    private suspend fun captureBackloggdFailure(operation: suspend () -> String): BackloggdException {
        try {
            operation()
        } catch (failure: BackloggdException) {
            return failure
        }
        throw AssertionError("Expected Backloggd delivery failure")
    }


    private object FixtureCredentialStore : BackloggdCredentialStore {
        override fun load() = CREDENTIALS
        override fun save(credentials: BackloggdCredentials) = Unit
        override fun clear() = Unit
    }

    private class FixtureExchange(initialLog: String) : BackloggdHttpExchange {
        var logBody: String = initialLog
        val posts = ArrayList<Map<String, List<String>>>()
        var losePostAcknowledgement = false
        var cancelAfterPost = false
        var afterPost: (() -> Unit)? = null
        private var cancelNextLogRead = false

        override fun execute(request: BackloggdHttpRequest): BackloggdHttpResponse {
            if (request.method == "GET" && request.path == "/settings/") {
                return response(
                    "text/html",
                    """<html><head><meta name="csrf-token" content="fixture-csrf"></head>
                       <body data-authenticated="true"><nav id="primary-nav">
                       <a href="/u/test_owner/">Profile</a><a href="/users/sign_out">Sign out</a>
                       </nav><button id="save-profile-btn" user_id="17">Save</button></body></html>"""
                )
            }
            if (request.method == "GET" && request.path == "/log/edit/26226") {
                if (cancelNextLogRead) {
                    cancelNextLogRead = false
                    throw CancellationException("fixture cancellation")
                }
                return response("application/json", logBody)
            }
            if (request.method == "POST" && request.path == "/api/user/17/log/26226") {
                val fields = decodeForm(request.body ?: throw AssertionError("POST had no form body"))
                posts += fields
                acceptPost(fields)
                afterPost?.invoke()
                afterPost = null
                if (losePostAcknowledgement) {
                    losePostAcknowledgement = false
                    throw SocketTimeoutException("fixture lost acknowledgement")
                }
                if (cancelAfterPost) {
                    cancelAfterPost = false
                    cancelNextLogRead = true
                }
                return response("application/json", """{"success":true}""")
            }
            throw AssertionError("Unexpected fixture request ${request.method} ${request.path}")
        }

        fun currentDate(playthroughId: Long, dateId: Long): JSONObject {
            val root = JSONObject(logBody)
            val playthrough = root.getJSONObject("playthroughs").getJSONObject(playthroughId.toString())
            val dates = playthrough.getJSONArray("play_dates")
            for (index in 0 until dates.length()) {
                val date = dates.getJSONObject(index)
                if (date.getLong("id") == dateId) return date
            }
            throw AssertionError("Fixture date $playthroughId:$dateId was not stored")
        }

        private fun acceptPost(fields: Map<String, List<String>>) {
            val dateIdField = fields.keys.firstOrNull { it.matches(Regex("dates\\[-?[0-9]+]\\[0]\\[id]")) }
                ?: throw AssertionError("No submitted date id")
            val ids = Regex("dates\\[(-?[0-9]+)]\\[0]\\[id]").matchEntire(dateIdField)!!
            val requestedPlaythroughId = ids.groupValues[1].toLong()
            val requestedDateId = fields.one(dateIdField).toLong()
            val playthroughId = if (requestedPlaythroughId < 0L) 7L else requestedPlaythroughId
            val dateId = if (requestedDateId < 0L) 301L else requestedDateId
            val root = JSONObject(logBody)
            val playthroughs = root.optJSONObject("playthroughs") ?: JSONObject()
            val playthroughKey = playthroughId.toString()
            var targetPlaythrough = playthroughs.optJSONObject(playthroughKey)
            if (targetPlaythrough == null) {
                targetPlaythrough = JSONObject()
                    .put("id", playthroughId)
                    .put("title", fields["playthroughs[0][title]"]?.singleOrNull() ?: "GamerLog")
                    .put("play_dates", JSONArray())
                playthroughs.put(playthroughKey, targetPlaythrough)
                root.put("most_recent_playthrough_id", playthroughId)
            }
            val dates = targetPlaythrough.optJSONArray("play_dates") ?: JSONArray()
            val existingIndex = (0 until dates.length()).firstOrNull {
                dates.getJSONObject(it).optLong("id") == dateId
            } ?: -1
            val date = if (existingIndex >= 0) {
                JSONObject(dates.getJSONObject(existingIndex).toString())
            } else {
                JSONObject()
            }
            val prefix = "dates[$requestedPlaythroughId][0]["
            fields.forEach { (key, values) ->
                if (key.startsWith(prefix) && key.endsWith("]") && values.size == 1) {
                    val fieldName = key.substring(prefix.length, key.length - 1)
                    if (!fieldName.contains('[')) date.put(fieldName, values.single())
                }
            }
            date.put("id", dateId)
            date.put("range_start_date", fields.one("$prefix" + "range_start_date]"))
            date.put("range_end_date", fields.one("$prefix" + "range_end_date]"))
            date.put("hours", fields.one("$prefix" + "hours]").toLong())
            date.put("minutes", fields.one("$prefix" + "minutes]").toLong())
            date.put("note", fields.one("$prefix" + "note]"))
            if (existingIndex >= 0) {
                dates.put(existingIndex, date)
            } else {
                dates.put(date)
            }
            targetPlaythrough.put("play_dates", dates)
            playthroughs.put(playthroughKey, targetPlaythrough)
            root.put("playthroughs", playthroughs)
            if (!root.has("game_log") || root.isNull("game_log")) {
                root.put("game_log", JSONObject().put("id", 101).put("status", "played"))
            }
            if (!root.has("most_recent_playthrough_id") || root.isNull("most_recent_playthrough_id")) {
                root.put("most_recent_playthrough_id", playthroughId)
            }
            logBody = root.toString()
        }

        private fun decodeForm(body: ByteArray): Map<String, List<String>> {
            val fields = LinkedHashMap<String, MutableList<String>>()
            String(body, Charsets.UTF_8).split('&').forEach { pair ->
                val separator = pair.indexOf('=')
                val key = URLDecoder.decode(pair.substring(0, separator), "UTF-8")
                val value = URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
                fields.getOrPut(key) { ArrayList() }.add(value)
            }
            return fields.mapValues { it.value.toList() }
        }

        private fun response(contentType: String, body: String) = BackloggdHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to listOf(contentType)),
            body = body.toByteArray(Charsets.UTF_8)
        )
    }

    private companion object {
        const val DAY = "2026-11-12"
        const val FIRST_MARKER = "11111111-1111-1111-1111-111111111111"
        const val SECOND_MARKER = "22222222-2222-2222-2222-222222222222"
        val CREDENTIALS = BackloggdCredentials("test_owner", "session=fixture")
    }
}
