package dev.gamerlog.app.core.backloggd

import dev.gamerlog.app.core.backloggd.security.BackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdExportEntity
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpBackloggdTransportTest {
    private val credentials = BackloggdCredentials("Murat", "session=original; theme=dark")

    @Test
    fun resolveGameAcceptsCanonicalWwwUrlAndReadsVerifiedGameMetadata() = runBlocking {
        val exchange = QueueExchange(
            html(
                """
                <div id="game-page-meta" data-game-id="26226" data-game-slug="celeste" data-name="Celeste"></div>
                """.trimIndent()
            )
        )
        val transport = HttpBackloggdTransport(MemoryCredentialStore(null), exchange)

        val game = transport.resolveGame("https://www.backloggd.com/games/celeste/")

        assertEquals(BackloggdGame("26226", "celeste", "Celeste"), game)
        assertEquals("/games/celeste/", exchange.requests.single().path)
        assertNull(exchange.requests.single().headers["Cookie"])
        assertTrue(exchange.requests.single().headers.getValue("User-Agent").startsWith("GamerLog/"))
        assertFalse(exchange.requests.single().headers.getValue("User-Agent").contains("Mozilla"))
    }

    @Test
    fun verifyAccountUsesSettingsIdentityAndPersistsOnlyMatchingCookieRotation() = runBlocking {
        val store = MemoryCredentialStore(credentials)
        val exchange = QueueExchange(
            settings(
                extraHeaders = mapOf("Set-Cookie" to listOf("session=rotated; Path=/; HttpOnly")),
                duplicateProfileLink = true
            ),
            json(remoteLog(gameLog(), JSONArray()))
        )
        val transport = HttpBackloggdTransport(store, exchange)

        assertNull(transport.findExport(credentials, item()))

        assertEquals("session=rotated; theme=dark", store.current?.cookieHeader)
        assertEquals("session=rotated; theme=dark", exchange.requests[1].headers["Cookie"])
        assertEquals("XMLHttpRequest", exchange.requests[1].headers["X-Requested-With"])
        assertEquals("/settings/", exchange.requests[0].path)
        assertEquals("/log/edit/26226", exchange.requests[1].path)

        val newerStoredCredentials = credentials.copy(cookieHeader = "session=newer; theme=dark")
        val concurrentStore = MemoryCredentialStore(newerStoredCredentials)
        val concurrentExchange = QueueExchange(
            settings(extraHeaders = mapOf("Set-Cookie" to listOf("session=rotated; Path=/"))),
            json(remoteLog(gameLog(), JSONArray()))
        )
        assertNull(
            HttpBackloggdTransport(concurrentStore, concurrentExchange)
                .findExport(credentials, item())
        )
        assertEquals(newerStoredCredentials.cookieHeader, concurrentStore.current?.cookieHeader)
        assertEquals("session=rotated; theme=dark", concurrentExchange.requests[1].headers["Cookie"])
        assertEquals(0, concurrentStore.saveCount)
    }

    @Test
    fun appendIncludesTargetPlaythroughWithoutChangingExistingMetadataOrDates() = runBlocking {
        val item = item(durationMs = 65 * 60_000L + 2_345L)
        val before = existingRemoteLog(item, includeMarker = false)
        val after = existingRemoteLog(item, includeMarker = true)
        val exchange = QueueExchange(
            settings(),
            json(before),
            settings(),
            jsonAck(),
            json(after)
        )
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)

        assertEquals("26226:8:301", transport.sendExport(credentials, item))

        val post = exchange.requests.single { it.method == "POST" }
        assertEquals("/api/user/17/log/26226", post.path)
        assertEquals("XMLHttpRequest", exchange.requests[1].headers["X-Requested-With"])
        assertEquals("fresh-csrf-token", post.headers["X-CSRF-Token"])
        assertEquals("XMLHttpRequest", post.headers["X-Requested-With"])
        assertEquals("https://backloggd.com", post.headers["Origin"])
        assertEquals("https://backloggd.com/games/celeste/", post.headers["Referer"])
        assertEquals("session=original; theme=dark", post.headers["Cookie"])

        val form = decodedForm(post)
        assertEquals("26226", form["game_id"])
        assertEquals("full", form["modal_type"])
        assertEquals("101", form["log[id]"])
        assertEquals("playing", form["log[status]"])
        assertEquals("false", form["log[is_play]"])
        assertEquals("4", form["log[rating]"])
        assertEquals("Keep this review", form["log[review]"])
        assertEquals("true", form["log[review_spoilers]"])
        assertEquals("manual", form["log[extra][source]"])
        assertEquals("9", form["log[library_entries][0][id]"])
        assertEquals("true", form["log[library_entries][0][pinned]"])
        assertEquals("-1", form["dates[8][0][id]"])
        assertEquals("2026-11-12", form["dates[8][0][range_start_date]"])
        assertEquals("2026-11-13", form["dates[8][0][range_end_date]"])
        assertEquals("1", form["dates[8][0][hours]"])
        assertEquals("5", form["dates[8][0][minutes]"])
        assertEquals("", form["dates[8][0][status]"])
        assertEquals("true", form["dates[8][0][edited]"])
        assertEquals(ledgerNote(item), form["dates[8][0][note]"])
        assertEquals("8", form["playthroughs[0][id]"])
        assertEquals("Later replay", form["playthroughs[0][title]"])
        assertEquals("Do not change", form["playthroughs[0][review]"])
        assertEquals("false", form["playthroughs[0][sync_sessions]"])
        assertFalse(form.keys.any { it.startsWith("playthroughs[0][play_dates]") })
        assertFalse(form.keys.any { it.startsWith("playthroughs[1]") })
        assertFalse(form.keys.any { it.startsWith("deleted_") })
        assertFalse(form.keys.any { it.startsWith("dates[7]") })
        assertFalse(form.keys.any { it.startsWith("dates[8][1]") })
    }

    @Test
    fun newUnloggedGameUsesMinimalLogAndKeepsSubminuteDurationInNote() = runBlocking {
        val item = item(durationMs = 1_234L)
        val before = JSONObject()
            .put("playthroughs", JSONObject())
            .put("most_recent_playthrough_id", JSONObject.NULL)
        val afterPlaythrough = JSONObject()
            .put("id", 11)
            .put("title", "GamerLog")
            .put("play_dates", JSONArray().put(remoteDate(item, dateId = 44)))
        val after = remoteLog(gameLog(), JSONArray().put(afterPlaythrough), mostRecentId = 11)
        val exchange = QueueExchange(
            settings(),
            json(before),
            settings(),
            jsonAck(),
            json(after)
        )
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)

        assertEquals("26226:11:44", transport.sendExport(credentials, item))

        val form = decodedForm(exchange.requests.single { it.method == "POST" })
        assertEquals("", form["log[id]"])
        assertEquals("true", form["log[is_play]"])
        assertEquals("false", form["log[is_playing]"])
        assertEquals("false", form["log[is_backlog]"])
        assertEquals("false", form["log[is_wishlist]"])
        assertEquals("played", form["log[status]"])
        assertEquals("1", form["log[time_source]"])
        assertEquals("-1", form["playthroughs[0][id]"])
        assertEquals("GamerLog", form["playthroughs[0][title]"])
        assertEquals("true", form["playthroughs[0][sync_sessions]"])
        assertEquals("false", form["playthroughs[0][is_master]"])
        assertEquals("false", form["playthroughs[0][is_replay]"])
        assertEquals("0", form["dates[-1][0][hours]"])
        assertEquals("0", form["dates[-1][0][minutes]"])
        assertEquals(ledgerNote(item), form["dates[-1][0][note]"])
    }

    @Test
    fun findExportAcceptsObjectKeyedPlaythroughsAndRequiresRealDateDurationMatch() = runBlocking {
        val item = item(durationMs = 65L * 60_000L + 99L)
        val playthrough = JSONObject()
            .put("title", "First run")
            .put("play_dates", JSONArray().put(remoteDate(item, dateId = 301)))
        val keyedPlaythroughs = JSONObject().put("7", playthrough)
        val exchange = QueueExchange(settings(), json(remoteLog(gameLog(), keyedPlaythroughs, 7)))
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)

        assertEquals("26226:7:301", transport.findExport(credentials, item))

        val mismatchedDate = remoteDate(item, dateId = 302)
            .put("minutes", 6)
            .put(
                "note",
                "[gamerlog-day-ledger:v1]\nbase_minutes=0\n" +
                    "${item.marker}|${item.playedOn}|${item.durationMs + 60_000L}\n[/gamerlog-day-ledger]"
            )
        val mismatchPlaythrough = JSONObject()
            .put("id", 7)
            .put("title", "First run")
            .put("play_dates", JSONArray().put(mismatchedDate))
        val mismatchExchange = QueueExchange(
            settings(),
            json(remoteLog(gameLog(), JSONArray().put(mismatchPlaythrough), 7))
        )
        val mismatch = expectBackloggdException {
            runBlocking {
                HttpBackloggdTransport(MemoryCredentialStore(credentials), mismatchExchange)
                    .findExport(credentials, item)
            }
        }
        assertFalse(mismatch.deliveryMayHaveOccurred)
    }

    @Test
    fun decimalWireHoursMustBeIntegralBeforeConfirmingSessionDuration() = runBlocking {
        val item = item(durationMs = 65L * 60_000L + 99L)
        for (hours in listOf("1.0", "1.5")) {
            // Keep the wire notation: JSONObject serialization would normalize 1.0 to 1.
            val body = """
                {
                  "game_log": {"id": 101},
                  "playthroughs": {
                    "7": {
                      "id": 7,
                      "title": "GamerLog",
                      "play_dates": [{
                        "id": 301,
                        "range_start_date": "2026-11-12",
                        "range_end_date": "2026-11-13",
                        "hours": $hours,
                        "minutes": 5.0,
                        "note": ${JSONObject.quote(ledgerNote(item))}
                      }]
                    }
                  }
                }
            """.trimIndent()
            val response = BackloggdHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = body.toByteArray(StandardCharsets.UTF_8)
            )
            val transport = HttpBackloggdTransport(
                MemoryCredentialStore(credentials),
                QueueExchange(settings(), response)
            )
            if (hours == "1.0") {
                assertEquals("26226:7:301", transport.findExport(credentials, item))
            } else {
                val failure = expectBackloggdException {
                    runBlocking { transport.findExport(credentials, item) }
                }
                assertFalse(failure.deliveryMayHaveOccurred)
            }
        }
    }

    @Test
    fun duplicateMarkersAndMalformedRemoteShapesFailClosed() = runBlocking {
        val item = item()
        val duplicate = JSONObject()
            .put("id", 7)
            .put("title", "First")
            .put("play_dates", JSONArray().put(remoteDate(item, 301)))
        val duplicateAgain = JSONObject()
            .put("id", 8)
            .put("title", "Second")
            .put("play_dates", JSONArray().put(remoteDate(item, 302)))
        val duplicateExchange = QueueExchange(
            settings(),
            json(remoteLog(gameLog(), JSONArray().put(duplicate).put(duplicateAgain)))
        )
        val duplicateError = expectBackloggdException {
            runBlocking {
                HttpBackloggdTransport(MemoryCredentialStore(credentials), duplicateExchange)
                    .findExport(credentials, item)
            }
        }
        assertFalse(duplicateError.deliveryMayHaveOccurred)

        val malformed = remoteLog(
            gameLog(),
            JSONArray().put(JSONObject().put("id", 7).put("title", "Missing dates"))
        )
        val malformedExchange = QueueExchange(settings(), json(malformed))
        val malformedError = expectBackloggdException {
            runBlocking {
                HttpBackloggdTransport(MemoryCredentialStore(credentials), malformedExchange)
                    .findExport(credentials, item)
            }
        }
        assertFalse(malformedError.deliveryMayHaveOccurred)
    }

    @Test
    fun unstructuredRequestedUuidIsUncertainAndNeverPosted() = runBlocking {
        val item = item()
        val date = remoteDate(item, 301).put("note", "manual [gamerlog_session_id:${item.marker}]")
        val playthrough = JSONObject()
            .put("id", 7)
            .put("title", "First run")
            .put("play_dates", JSONArray().put(date))
        val exchange = QueueExchange(
            settings(),
            json(remoteLog(gameLog(), JSONArray().put(playthrough)))
        )

        val failure = expectBackloggdException {
            runBlocking { HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange).sendExport(credentials, item) }
        }

        assertFalse(failure.deliveryMayHaveOccurred)
        assertEquals(0, exchange.requests.count { it.method == "POST" })
    }

    @Test
    fun lostPostAcknowledgementIsUncertainAndNeverResent() = runBlocking {
        val item = item()
        val exchange = QueueExchange(
            listOf(
                ExchangeStep(settings()),
                ExchangeStep(json(remoteLog(gameLog(), JSONArray()))),
                ExchangeStep(settings()),
                ExchangeStep(failure = SocketTimeoutException("private transport detail"))
            )
        )
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)

        val failure = expectBackloggdException { runBlocking { transport.sendExport(credentials, item) } }

        assertTrue(failure.deliveryMayHaveOccurred)
        assertFalse(failure.retryable)
        assertEquals(1, exchange.requests.count { it.method == "POST" })
        assertTrue(failure.message.orEmpty().contains("reconcile"))
        assertFalse(failure.message.orEmpty().contains("private transport detail"))
    }

    @Test
    fun cancellationDuringBlockingPreflightNeverStartsPost() = runBlocking {
        val enteredPreflight = CountDownLatch(1)
        val releasePreflight = CountDownLatch(1)
        val requests = mutableListOf<BackloggdHttpRequest>()
        val exchange = object : BackloggdHttpExchange {
            private var call = 0

            override fun execute(request: BackloggdHttpRequest): BackloggdHttpResponse {
                requests += request
                return when (call++) {
                    0 -> settings()
                    1 -> {
                        enteredPreflight.countDown()
                        try {
                            if (!releasePreflight.await(5, TimeUnit.SECONDS)) {
                                throw IOException("preflight timed out")
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("preflight interrupted")
                        }
                        json(remoteLog(gameLog(), JSONArray()))
                    }
                    else -> throw IOException("unexpected request")
                }
            }
        }
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)
        val job = launch { transport.sendExport(credentials, item()) }

        try {
            assertTrue(withContext(Dispatchers.IO) { enteredPreflight.await(5, TimeUnit.SECONDS) })
            job.cancel()
        } finally {
            job.cancel()
            releasePreflight.countDown()
        }
        job.join()

        assertEquals(2, requests.size)
        assertEquals(0, requests.count { it.method == "POST" })
    }

    @Test
    fun knownBotChallengeFailsClosedWithoutAttemptingBypass() = runBlocking {
        val exchange = QueueExchange(
            html("<title>Making sure you're not a bot!</title><div>Techaro Anubis BotStopper</div>")
        )
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)

        val failure = expectBackloggdException { runBlocking { transport.verifyAccount(credentials) } }

        assertTrue(failure.message.orEmpty().contains("browser challenge"))
        assertFalse(failure.retryable)
        assertEquals(1, exchange.requests.size)
    }


    @Test
    fun changedAuthenticatedUserIdDuringPreflightPreventsPost() = runBlocking {
        val exchange = QueueExchange(
            settings(userId = "17"),
            json(remoteLog(gameLog(), JSONArray())),
            settings(userId = "18")
        )
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)

        val failure = expectBackloggdException {
            runBlocking { transport.sendExport(credentials, item()) }
        }

        assertFalse(failure.deliveryMayHaveOccurred)
        assertEquals(3, exchange.requests.size)
        assertEquals(0, exchange.requests.count { it.method == "POST" })
    }
    @Test
    fun crossHostSettingsRedirectIsRejectedWithoutForwardingCookies() = runBlocking {
        val redirect = BackloggdHttpResponse(
            statusCode = 302,
            headers = mapOf("Location" to listOf("https://evil.example/collect"))
        )
        val exchange = QueueExchange(redirect)
        val transport = HttpBackloggdTransport(MemoryCredentialStore(credentials), exchange)

        val failure = expectBackloggdException { runBlocking { transport.verifyAccount(credentials) } }

        assertFalse(failure.deliveryMayHaveOccurred)
        assertEquals(1, exchange.requests.size)
        assertEquals("session=original; theme=dark", exchange.requests.single().headers["Cookie"])
    }

    private fun item(
        durationMs: Long = 2_345L,
        marker: String = UUID.fromString("d0f884ac-36f9-44b4-8e53-64497746f521").toString()
    ) = BackloggdExportEntity(
        sessionId = 7,
        accountUsername = "Murat",
        gameSlug = "celeste",
        gameId = "26226",
        playedOn = "2026-11-12",
        durationMs = durationMs,
        marker = marker,
        createdAtMs = 1,
        updatedAtMs = 1
    )

    private fun settings(
        extraHeaders: Map<String, List<String>> = emptyMap(),
        duplicateProfileLink: Boolean = false,
        userId: String = "17"
    ): BackloggdHttpResponse {
        val secondProfile = if (duplicateProfileLink) "<a href=\"/u/mURAT/\">Profile</a>" else ""
        return html(
            """
            <body data-authenticated="true">
              <nav id="primary-nav">
                <a href="/u/Murat/">Profile</a>
                $secondProfile
                <a href="/users/sign_out">Sign out</a>
              </nav>
              <button id="save-profile-btn" user_id="$userId"></button>
              <meta name="csrf-token" content="fresh-csrf-token">
            </body>
            """.trimIndent(),
            extraHeaders
        )
    }

    private fun html(body: String, extraHeaders: Map<String, List<String>> = emptyMap()) =
        BackloggdHttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")) + extraHeaders,
            body = "<!doctype html><html><head></head>$body</html>".toByteArray(StandardCharsets.UTF_8)
        )

    private fun json(value: JSONObject) = BackloggdHttpResponse(
        statusCode = 200,
        headers = mapOf("Content-Type" to listOf("application/json; charset=utf-8")),
        body = value.toString().toByteArray(StandardCharsets.UTF_8)
    )

    private fun jsonAck() = BackloggdHttpResponse(
        statusCode = 200,
        headers = mapOf("Content-Type" to listOf("application/json")),
        body = "{\"success\":true}".toByteArray(StandardCharsets.UTF_8)
    )

    private fun remoteLog(gameLog: Any, playthroughs: Any, mostRecentId: Int? = null): JSONObject =
        JSONObject()
            .put("game_log", gameLog)
            .put("playthroughs", playthroughs)
            .also { if (mostRecentId != null) it.put("most_recent_playthrough_id", mostRecentId) }

    private fun gameLog() = JSONObject()
        .put("id", 101)
        .put("is_play", false)
        .put("is_playing", true)
        .put("is_backlog", false)
        .put("is_wishlist", false)
        .put("status", "playing")
        .put("rating", 4)
        .put("review", "Keep this review")
        .put("review_spoilers", true)
        .put("extra", JSONObject().put("source", "manual"))
        .put("library_entries", JSONArray().put(JSONObject().put("id", 9).put("pinned", true)))

    private fun existingRemoteLog(item: BackloggdExportEntity, includeMarker: Boolean): JSONObject {
        val oldDate = JSONObject()
            .put("id", 201)
            .put("range_start_date", "2026-11-10")
            .put("range_end_date", "2026-11-11")
            .put("hours", 0)
            .put("minutes", 20)
            .put("status", 5)
            .put("note", "Old session stays untouched")
        val gamerLogPlaythrough = JSONObject()
            .put("id", 7)
            .put("title", "GamerLog")
            .put("review", "Existing playthrough review")
            .put("sync_sessions", false)
            .put("play_dates", JSONArray().put(oldDate))
        val latestPlaythrough = JSONObject()
            .put("id", 8)
            .put("title", "Later replay")
            .put("review", "Do not change")
            .put("sync_sessions", false)
            .put("play_dates", JSONArray())
        if (includeMarker) latestPlaythrough.getJSONArray("play_dates").put(remoteDate(item, 301))
        return remoteLog(
            gameLog(),
            JSONArray().put(gamerLogPlaythrough).put(latestPlaythrough),
            mostRecentId = 8
        )
    }

    private fun remoteDate(
        item: BackloggdExportEntity,
        dateId: Int,
        hours: Long = (item.durationMs / 60_000L) / 60L,
        minutes: Long = (item.durationMs / 60_000L) % 60L
    ) = JSONObject()
        .put("id", dateId)
        .put("range_start_date", item.playedOn)
        .put("range_end_date", "2026-11-13")
        .put("hours", hours)
        .put("minutes", minutes)
        .put("status", 5)
        .put("edited", true)
        .put("note", ledgerNote(item))

    private fun ledgerNote(item: BackloggdExportEntity): String =
        "[gamerlog-day-ledger:v1]\nbase_minutes=0\n" +
            "${item.marker.lowercase()}|${item.playedOn}|${item.durationMs}\n[/gamerlog-day-ledger]"

    private fun decodedForm(request: BackloggdHttpRequest): Map<String, String> =
        requireNotNull(request.body)
            .toString(StandardCharsets.UTF_8)
            .split('&')
            .associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8) to
                    URLDecoder.decode(pair.substringAfter('='), StandardCharsets.UTF_8)
            }

    private fun expectBackloggdException(block: () -> Unit): BackloggdException {
        try {
            block()
        } catch (failure: BackloggdException) {
            return failure
        }
        throw AssertionError("Expected BackloggdException")
    }

    private class MemoryCredentialStore(var current: BackloggdCredentials?) : BackloggdCredentialStore {
        var saveCount: Int = 0
            private set

        override fun load(): BackloggdCredentials? = current

        override fun save(credentials: BackloggdCredentials) {
            current = credentials
            saveCount++
        }

        override fun clear() {
            current = null
        }
    }

    private class QueueExchange(private val steps: List<ExchangeStep>) : BackloggdHttpExchange {
        constructor(vararg responses: BackloggdHttpResponse) : this(responses.map(::ExchangeStep))

        val requests = mutableListOf<BackloggdHttpRequest>()
        private var index = 0

        override fun execute(request: BackloggdHttpRequest): BackloggdHttpResponse {
            requests += request
            val step = steps.getOrNull(index++) ?: throw IOException("unexpected request")
            step.failure?.let { throw it }
            return requireNotNull(step.response)
        }
    }

    private class ExchangeStep(
        val response: BackloggdHttpResponse? = null,
        val failure: IOException? = null
    )
}
