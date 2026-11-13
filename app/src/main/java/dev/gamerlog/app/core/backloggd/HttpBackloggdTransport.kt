package dev.gamerlog.app.core.backloggd

import dev.gamerlog.app.core.backloggd.security.BackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdExportEntity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup

/** A request seam for deterministic protocol tests. Production requests still have a fixed host. */
internal fun interface BackloggdHttpExchange {
    @Throws(IOException::class)
    fun execute(request: BackloggdHttpRequest): BackloggdHttpResponse
}

internal class BackloggdHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?
) {
    override fun toString(): String = "BackloggdHttpRequest(method=$method, path=$path, bodyBytes=${body?.size ?: 0})"
}

internal class BackloggdHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
    val bodyTooLarge: Boolean = false
) {
    fun headerValues(name: String): List<String> = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        .orEmpty()

    fun header(name: String): String? = headerValues(name).singleOrNull()
}
private const val BACKLOGGD_ORIGIN = "https://backloggd.com"
private const val USER_AGENT = "GamerLog/1.0 (Android; user-initiated playtime export)"
private const val HTML_ACCEPT = "text/html,application/xhtml+xml"
private const val JSON_ACCEPT = "application/json"
private const val MAX_RESPONSE_BYTES = 1_048_576
private const val MAX_REQUEST_BYTES = 1_048_576
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 20_000
private val SLUG_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,199}")
private val USERNAME_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
private val COOKIE_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private val UUID_MARKER_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val NOTE_TEXT_KEYS = arrayOf("note", "renderedMarkdown", "rendered_markdown")
private val PROFILE_PATH_PATTERN = Regex("^/u/([A-Za-z0-9_-]{1,128})/$", RegexOption.IGNORE_CASE)
private val SIGN_OUT_PATH_PATTERN = Regex(
    "^/(?:users/sign_out|users/signout|sign_out|signout|logout)/?$",
    RegexOption.IGNORE_CASE
)
private val CHALLENGE_MARKERS = listOf(
    "cf-chl-",
    "challenge-platform",
    "captcha",
    "recaptcha",
    "verify you are human",
    "checking your browser",
    "unusual traffic",
    "making sure you're not a bot",
    "anubis",
    "techaro",
    "botstopper"
)

private const val MAX_REDIRECTS = 5
private const val MAX_TITLE_LENGTH = 512
private const val NEW_RECORD_ID = -1L
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
private val DEFINITIVE_REJECTION_STATUSES = setOf(401, 403, 422)
private val NONNEGATIVE_DECIMAL_PATTERN = Regex("0|[1-9][0-9]*")


/** Blocking HTTPS details stay behind the exchange; every transport entry point dispatches on IO. */
internal object UrlConnectionBackloggdHttpExchange : BackloggdHttpExchange {
    override fun execute(request: BackloggdHttpRequest): BackloggdHttpResponse {
        require(request.method == "GET" || request.method == "POST")
        require(request.path.startsWith('/') && !request.path.startsWith("//"))
        require(request.path.none { it == '\\' || it == '\r' || it == '\n' || it.isISOControl() })
        val connection = URL("$BACKLOGGD_ORIGIN${request.path}").openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.requestMethod = request.method
        request.headers.forEach(connection::setRequestProperty)
        try {
            request.body?.let { body ->
                require(body.size <= MAX_REQUEST_BYTES)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val bounded = stream?.use(::readBounded) ?: BoundedBody(byteArrayOf(), false)
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.orEmpty() }
            return BackloggdHttpResponse(status, headers, bounded.bytes, bounded.tooLarge)
        } finally {
            connection.disconnect()
        }
    }

    private data class BoundedBody(val bytes: ByteArray, val tooLarge: Boolean)

    private fun readBounded(input: java.io.InputStream): BoundedBody {
        val output = ByteArrayOutputStream(minOf(MAX_RESPONSE_BYTES, 8_192))
        val buffer = ByteArray(8_192)
        var tooLarge = false
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val allowed = (MAX_RESPONSE_BYTES - output.size()).coerceAtLeast(0)
            val toWrite = minOf(count, allowed)
            if (toWrite > 0) output.write(buffer, 0, toWrite)
            if (toWrite != count) {
                tooLarge = true
                break
            }
        }
        return BoundedBody(output.toByteArray(), tooLarge)
    }
}

/**
 * Implements the verified Backloggd web protocol without browser impersonation or redirects across
 * origins. Export acknowledgement is established only by reading the unique session marker back.
 */
class HttpBackloggdTransport internal constructor(
    private val credentialStore: BackloggdCredentialStore,
    private val http: BackloggdHttpExchange = UrlConnectionBackloggdHttpExchange
) : BackloggdTransport {

    override suspend fun verifyAccount(credentials: BackloggdCredentials) = onIo {
        validateCredentials(credentials)
        val session = RequestSession(credentials)
        readVerifiedSettings(session, credentials.username)
        Unit
    }

    override suspend fun resolveGame(gameUrlOrSlug: String): BackloggdGame = onIo {
        val slug = parseGameSlug(gameUrlOrSlug)
        val response = get("/games/$slug/", htmlHeaders())
        val body = successfulBody(response, MimeType.HTML)
        val document = Jsoup.parse(body, "$BACKLOGGD_ORIGIN/games/$slug/")
        val meta = document.select("#game-page-meta")
        if (meta.size != 1) fail("Backloggd game details could not be verified")
        val gameId = positiveId(meta.first()?.attr("data-game-id"))
            ?: fail("Backloggd game details could not be verified")
        val canonicalSlug = meta.first()?.attr("data-game-slug").orEmpty()
        val title = meta.first()?.attr("data-name").orEmpty()
        if (!SLUG_PATTERN.matches(canonicalSlug) || title.isBlank() || title.length > MAX_TITLE_LENGTH) {
            fail("Backloggd game details could not be verified")
        }
        BackloggdGame(gameId.toString(), canonicalSlug, title)
    }

    override suspend fun findExport(
        credentials: BackloggdCredentials,
        item: BackloggdExportEntity
    ): String? = onIo {
        validateItem(item)
        validateCredentials(credentials, item.accountUsername)
        rejectChangedStoredAccount(item.accountUsername)
        val session = RequestSession(credentials)
        readVerifiedSettings(session, item.accountUsername)
        val log = readRemoteLog(session, gameId(item))
        val ledger = readLedgerState(log)
        findConfirmedRemoteId(item, ledger)
    }

    override suspend fun sendExport(
        credentials: BackloggdCredentials,
        item: BackloggdExportEntity
    ): String = onIo {
        validateItem(item)
        validateCredentials(credentials, item.accountUsername)
        val startCredentials = latestCredentialsForSend(credentials, item.accountUsername)
        val session = RequestSession(startCredentials)

        // This first settings read binds the request to the destination identity and supplies a
        // current CSRF token. The log preflight below is read-only and prevents duplicate writes.
        val preflightAccount = readVerifiedSettings(session, item.accountUsername)
        currentCoroutineContext().ensureActive()
        val gameId = gameId(item)
        val before = readRemoteLog(session, gameId)
        val beforeLedger = readLedgerState(before)
        currentCoroutineContext().ensureActive()
        findConfirmedRemoteId(item, beforeLedger)?.let { return@onIo it }

        // Refresh both user id and CSRF after the preflight. The authenticated identity must still
        // match immediately before dispatch; a switched connection is never used for a POST.
        val account = readVerifiedSettings(session, item.accountUsername)
        if (account.userId != preflightAccount.userId ||
            !account.username.equals(preflightAccount.username, ignoreCase = true)
        ) {
            fail("The authenticated Backloggd account has changed")
        }
        ensureCurrentStoredAccount(session, item.accountUsername)
        val export = buildExport(before, item, gameId, beforeLedger)
        val requestBody = encodeForm(export.form)
        val request = BackloggdHttpRequest(
            method = "POST",
            path = "/api/user/${account.userId}/log/$gameId",
            headers = authenticatedHeaders(
                session.cookieHeader,
                accept = JSON_ACCEPT,
                referer = "$BACKLOGGD_ORIGIN/games/${item.gameSlug}/",
                csrfToken = account.csrfToken,
                xhr = true
            ),
            body = requestBody
        )
        currentCoroutineContext().ensureActive()

        val posted = try {
            http.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            uncertainDelivery(failureType = failure.javaClass.simpleName)
        }
        try {
            session.acceptCookies(posted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            uncertainDelivery(posted.statusCode)
        }

        if (isChallenge(posted)) challengeDeliveryUncertain()
        if (posted.statusCode in DEFINITIVE_REJECTION_STATUSES) {
            if (!isStructuredRejection(posted)) uncertainDelivery(posted.statusCode)
            throw BackloggdException("Backloggd rejected the export request")
        }
        if (posted.statusCode !in 200..299) uncertainDelivery(posted.statusCode)

        try {
            // HTTP success is never an ack: every prior day contribution and the new one must
            // still be present in the independently-read aggregate ledger.
            parseJsonObject(successfulBody(posted, MimeType.JSON))
            currentCoroutineContext().ensureActive()
            val after = readRemoteLog(session, gameId)
            val afterLedger = readLedgerState(after)
            ensureLedgerContributionsPreserved(beforeLedger, afterLedger)
            findConfirmedRemoteId(item, afterLedger)
                ?: uncertainDelivery(posted.statusCode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            uncertainDelivery(posted.statusCode)
        }
    }

    private fun readVerifiedSettings(
        session: RequestSession,
        expectedUsername: String
    ): VerifiedAccount {
        val response = get("/settings/", htmlHeaders(session.cookieHeader), session)
        val body = successfulBody(response, MimeType.HTML)
        val document = Jsoup.parse(body, "$BACKLOGGD_ORIGIN/settings/")
        val pageBody = document.body() ?: fail("Backloggd account could not be verified")
        if (!pageBody.attr("data-authenticated").equals("true", ignoreCase = true)) {
            fail("Backloggd authentication was rejected")
        }
        val signOutExists = document.select("a[href]").any { isSignOutLink(it.attr("href")) }
        val profileUsernames = document.select("#primary-nav a[href]")
            .mapNotNull { profileUsername(it.attr("href")) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val saveButtons = document.select("#save-profile-btn[user_id]")
        val userId = saveButtons.singleOrNull()?.attr("user_id")?.let(::positiveId)
        val csrfToken = document.select("meta[name=csrf-token][content]")
            .singleOrNull()
            ?.attr("content")
            .orEmpty()
        if (!signOutExists || profileUsernames.size != 1 || userId == null || !validHeaderValue(csrfToken)) {
            fail("Backloggd account could not be verified")
        }
        val username = profileUsernames.single()
        if (!username.equals(expectedUsername, ignoreCase = true) ||
            !username.equals(session.starting.username, ignoreCase = true)
        ) {
            fail("The authenticated Backloggd account does not match the connected account")
        }
        session.markVerified(username)
        return VerifiedAccount(username, userId, csrfToken)
    }

    private fun readRemoteLog(session: RequestSession, gameId: Long): RemoteLog {
        val response = get(
            "/log/edit/$gameId",
            authenticatedHeaders(session.cookieHeader, accept = JSON_ACCEPT, xhr = true),
            session
        )
        return parseRemoteLog(successfulBody(response, MimeType.JSON))
    }

    private fun get(
        initialPath: String,
        headers: Map<String, String>,
        session: RequestSession? = null
    ): BackloggdHttpResponse {
        var path = initialPath
        var redirects = 0
        while (true) {
            val requestHeaders = if (session == null) headers else headers + ("Cookie" to session.cookieHeader)
            val response = executeRead(BackloggdHttpRequest("GET", path, requestHeaders, null))
            session?.acceptCookies(response)
            if (response.statusCode !in REDIRECT_STATUSES) return response
            if (redirects >= MAX_REDIRECTS) fail("Backloggd redirected too many times")
            val location = response.header("Location")
                ?: fail("Backloggd returned an unsafe redirect")
            path = sameHostRedirectPath(path, location)
            redirects++
        }
    }

    private fun executeRead(request: BackloggdHttpRequest): BackloggdHttpResponse = try {
        val response = http.execute(request)
        if (response.statusCode !in 100..599) fail("Backloggd returned an invalid response")
        response
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: BackloggdException) {
        throw failure
    } catch (failure: IOException) {
        throw BackloggdException(
            "Backloggd could not be reached",
            retryable = isTransientIo(failure)
        )
    } catch (_: Exception) {
        fail("Backloggd request could not be completed")
    }

    private fun successfulBody(response: BackloggdHttpResponse, expected: MimeType): String {
        if (!response.bodyTooLarge && isChallenge(response)) challengeFailure()
        if (response.statusCode == 429 || response.statusCode in 500..599) {
            throw BackloggdException("Backloggd is temporarily unavailable", retryable = true)
        }
        if (response.statusCode == 401 || response.statusCode == 403) {
            fail("Backloggd authentication was rejected")
        }
        if (response.statusCode !in 200..299) {
            fail("Backloggd request failed")
        }
        if (response.bodyTooLarge) fail("Backloggd returned an oversized response")
        val contentType = response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: fail("Backloggd returned an unexpected response")
        val contentTypeMatches = when (expected) {
            MimeType.HTML -> contentType == "text/html" || contentType == "application/xhtml+xml"
            MimeType.JSON -> contentType == "application/json" || contentType.endsWith("+json")
        }
        if (!contentTypeMatches) fail("Backloggd returned an unexpected response")
        return decodeUtf8(response.body)
    }

    private fun parseRemoteLog(body: String): RemoteLog {
        val root = parseJsonObject(body)
        val explicitUnlogged = !root.has("game_log") &&
            root.length() == 2 && root.has("playthroughs") &&
            root.has("most_recent_playthrough_id") && root.isNull("most_recent_playthrough_id")
        if (!root.has("game_log") && !explicitUnlogged) {
            fail("Backloggd returned an incomplete game log")
        }
        val gameLog = if (explicitUnlogged) null else when (val raw = root.opt("game_log")) {
            JSONObject.NULL -> null
            is JSONObject -> raw
            else -> fail("Backloggd returned an incomplete game log")
        }
        val rawPlaythroughs = root.opt("playthroughs")
        val playthroughs: List<RemotePlaythrough> = when (rawPlaythroughs) {
            is JSONArray -> buildList {
                for (index in 0 until rawPlaythroughs.length()) {
                    val playthrough = rawPlaythroughs.opt(index) as? JSONObject
                        ?: fail("Backloggd returned an incomplete game log")
                    add(parsePlaythrough(playthrough, null))
                }
            }
            is JSONObject -> buildList {
                val keys = rawPlaythroughs.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val keyId = positiveId(key)
                        ?: fail("Backloggd returned an incomplete game log")
                    val playthrough = rawPlaythroughs.opt(key) as? JSONObject
                        ?: fail("Backloggd returned an incomplete game log")
                    add(parsePlaythrough(playthrough, keyId))
                }
            }
            else -> fail("Backloggd returned an incomplete game log")
        }
        if (explicitUnlogged && playthroughs.isNotEmpty()) {
            fail("Backloggd returned an incomplete game log")
        }
        val mostRecentId = root.opt("most_recent_playthrough_id")
            .takeUnless { it == null || it == JSONObject.NULL }
            ?.let(::positiveId)
        return RemoteLog(gameLog, playthroughs, mostRecentId)
    }

    private fun parsePlaythrough(raw: JSONObject, mapKeyId: Long?): RemotePlaythrough {
        val actualId = if (raw.has("id") && !raw.isNull("id")) {
            positiveId(raw.opt("id")) ?: fail("Backloggd returned an incomplete game log")
        } else {
            mapKeyId ?: fail("Backloggd returned an incomplete game log")
        }
        if (mapKeyId != null && mapKeyId != actualId) fail("Backloggd returned an incomplete game log")
        val rawDates = raw.opt("play_dates") as? JSONArray
            ?: fail("Backloggd returned an incomplete game log")
        val dates = buildList {
            for (index in 0 until rawDates.length()) {
                val date = rawDates.opt(index) as? JSONObject
                    ?: fail("Backloggd returned an incomplete game log")
                val id = positiveId(date.opt("id"))
                    ?: fail("Backloggd returned an incomplete game log")
                validateNoteText(date)
                add(
                    RemotePlayDate(
                        id = id,
                        startDate = optionalString(date, "range_start_date"),
                        endDate = optionalString(date, "range_end_date"),
                        hours = optionalNonnegativeLong(date, "hours"),
                        minutes = optionalNonnegativeLong(date, "minutes"),
                        note = optionalString(date, "note"),
                        raw = date
                    )
                )
            }
        }
        return RemotePlaythrough(actualId, dates, raw)
    }

    private fun readLedgerState(log: RemoteLog): RemoteLedgerState {
        val records = ArrayList<RemoteLedgerDate>()
        val contributions = HashMap<String, RemoteLedgerContribution>()
        val unmanagedMarkers = HashSet<String>()
        for (playthrough in log.playthroughs) {
            for (date in playthrough.dates) {
                val note = date.note ?: continue
                val dayLedger = try {
                    parseBackloggdDayLedger(note)
                } catch (_: IllegalArgumentException) {
                    conflict()
                }
                for (match in UUID_MARKER_PATTERN.findAll(note)) {
                    val outsideLedger = dayLedger == null ||
                        match.range.first < dayLedger.blockStart ||
                        match.range.last >= dayLedger.blockEndExclusive
                    if (outsideLedger) unmanagedMarkers += match.value.lowercase(Locale.ROOT)
                }
                if (dayLedger == null) continue
                val remoteMinutes = remoteMinutes(date)
                val managedMinutes = backloggdManagedMinutes(dayLedger)
                val requiredMinutes = try {
                    Math.addExact(dayLedger.baseMinutes, managedMinutes)
                } catch (_: ArithmeticException) {
                    conflict()
                }
                if (remoteMinutes < requiredMinutes) conflict()
                val range = dateRange(date)
                val record = RemoteLedgerDate(
                    playthrough.id,
                    date,
                    dayLedger,
                    remoteMinutes,
                    requiredMinutes
                )
                records += record
                for (contribution in dayLedger.contributions) {
                    val contributionDate = parseDate(contribution.playedOn) ?: conflict()
                    if (!range.contains(contributionDate)) conflict()
                    val indexed = RemoteLedgerContribution(record, contribution)
                    if (contributions.put(contribution.marker, indexed) != null) conflict()
                }
            }
        }
        return RemoteLedgerState(records, contributions, unmanagedMarkers)
    }

    private fun findConfirmedRemoteId(
        item: BackloggdExportEntity,
        ledger: RemoteLedgerState
    ): String? {
        val marker = item.marker.lowercase(Locale.ROOT)
        if (marker in ledger.unmanagedMarkers) conflict()
        val match = ledger.contributions[marker] ?: return null
        if (match.contribution.playedOn != itemDate(item).toString() ||
            match.contribution.durationMs != item.durationMs ||
            !dateRange(match.record.date).contains(itemDate(item))
        ) {
            conflict()
        }
        return "${gameId(item)}:${match.record.playthroughId}:${match.record.date.id}"
    }

    private fun ensureLedgerContributionsPreserved(
        before: RemoteLedgerState,
        after: RemoteLedgerState
    ) {
        before.contributions.forEach { (marker, prior) ->
            val confirmed = after.contributions[marker] ?: conflict()
            if (prior.contribution != confirmed.contribution) conflict()
        }
    }

    private fun buildExport(
        log: RemoteLog,
        item: BackloggdExportEntity,
        gameId: Long,
        ledger: RemoteLedgerState
    ): BuiltExport {
        val playedOn = itemDate(item)
        val playedOnText = playedOn.toString()
        val target = if (log.playthroughs.isEmpty()) {
            null
        } else {
            if (log.gameLog == null) conflict()
            choosePlaythrough(log)
        }
        val targetPlaythrough = target?.id ?: NEW_RECORD_ID
        val playthroughPayload: List<Map<String, Any?>> = if (target == null) {
            listOf(
                linkedMapOf(
                    "id" to NEW_RECORD_ID,
                    "title" to "GamerLog",
                    "sync_sessions" to true,
                    "is_master" to false,
                    "is_replay" to false
                )
            )
        } else {
            val preservedFields = LinkedHashMap<String, Any?>()
            val keys = target.raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key != "play_dates") preservedFields[key] = target.raw.get(key)
            }
            if (target.raw.isNull("id")) preservedFields["id"] = target.id
            listOf(preservedFields)
        }

        var targetDate: RemotePlayDate? = null
        target?.dates?.forEach { date ->
            if (dateRange(date).contains(playedOn)) {
                if (targetDate != null) conflict()
                targetDate = date
            }
        }
        val selectedDate = targetDate
        val existingLedger = if (target != null && selectedDate != null) {
            ledger.records.singleOrNull {
                it.playthroughId == target.id && it.date.id == selectedDate.id
            }
        } else {
            null
        }
        val originalNote = selectedDate?.note.orEmpty()
        val baseMinutes = if (selectedDate == null) {
            0L
        } else if (existingLedger == null) {
            remoteMinutes(selectedDate)
        } else {
            if (existingLedger.remoteMinutes < existingLedger.requiredMinutes) conflict()
            try {
                Math.addExact(
                    existingLedger.dayLedger.baseMinutes,
                    existingLedger.remoteMinutes - existingLedger.requiredMinutes
                )
            } catch (_: ArithmeticException) {
                conflict()
            }
        }
        val previousContributions = existingLedger?.dayLedger?.contributions.orEmpty()
        val marker = item.marker.lowercase(Locale.ROOT)
        if (previousContributions.any { it.marker == marker }) conflict()
        val contributions = previousContributions + BackloggdDayContribution(
            marker = marker,
            playedOn = playedOnText,
            durationMs = item.durationMs
        )
        val totalMinutes = try {
            Math.addExact(baseMinutes, backloggdManagedMinutes(contributions))
        } catch (_: ArithmeticException) {
            conflict()
        }
        val note = renderBackloggdDayLedger(
            originalNote,
            existingLedger?.dayLedger,
            baseMinutes,
            contributions
        )
        val date = if (selectedDate == null) {
            linkedMapOf(
                "id" to NEW_RECORD_ID,
                "range_start_date" to playedOnText,
                "range_end_date" to playedOn.plusDays(1).toString(),
                "hours" to (totalMinutes / 60L),
                "minutes" to (totalMinutes % 60L),
                "status" to "",
                "edited" to true,
                "note" to note
            )
        } else {
            val preservedDate = LinkedHashMap<String, Any?>()
            val keys = selectedDate.raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                preservedDate[key] = selectedDate.raw.get(key)
            }
            preservedDate["hours"] = totalMinutes / 60L
            preservedDate["minutes"] = totalMinutes % 60L
            preservedDate["note"] = note
            preservedDate
        }

        val logData: Any = log.gameLog ?: linkedMapOf(
            "id" to "",
            "is_play" to true,
            "is_playing" to false,
            "is_backlog" to false,
            "is_wishlist" to false,
            "status" to "played",
            "time_source" to 1
        )
        val form = linkedMapOf<String, Any?>("game_id" to gameId, "modal_type" to "full")
        form["playthroughs"] = playthroughPayload
        form["dates"] = linkedMapOf(targetPlaythrough.toString() to listOf(date))
        form["log"] = logData
        return BuiltExport(form)
    }

    private fun choosePlaythrough(log: RemoteLog): RemotePlaythrough {
        log.mostRecentPlaythroughId?.let { mostRecent ->
            log.playthroughs.firstOrNull { it.id == mostRecent }?.let { return it }
        }
        return log.playthroughs.first()
    }

    private fun remoteMinutes(date: RemotePlayDate): Long {
        val hours = date.hours
        val minutes = date.minutes
        if (hours == null && minutes == null) return 0L
        if (hours == null || minutes == null) conflict()
        return try {
            Math.addExact(Math.multiplyExact(hours, 60L), minutes)
        } catch (_: ArithmeticException) {
            conflict()
        }
    }

    private fun dateRange(date: RemotePlayDate): DateRange {
        val start = date.startDate?.let(::parseDate) ?: conflict()
        val end = date.endDate?.let(::parseDate) ?: conflict()
        if (!start.isBefore(end)) conflict()
        return DateRange(start, end)
    }

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value).takeIf { it.toString() == value }
    } catch (_: Exception) {
        null
    }


    private fun encodeForm(fields: Map<String, Any?>): ByteArray {
        val pairs = ArrayList<String>()
        fields.forEach { (name, value) -> appendFormValue(name, value, pairs) }
        val encoded = pairs.joinToString("&")
        val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_REQUEST_BYTES) fail("Backloggd export is too large")
        return bytes
    }

    private fun appendFormValue(name: String, value: Any?, output: MutableList<String>) {
        when (value) {
            null, JSONObject.NULL -> output += formPair(name, "")
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    appendFormValue("$name[$key]", value.get(key), output)
                }
            }
            is Map<*, *> -> value.forEach { (key, nested) ->
                if (key !is String) fail("Backloggd export could not be encoded")
                appendFormValue("$name[$key]", nested, output)
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val nested = value.get(index)
                    val childName = if (isCompound(nested)) "$name[$index]" else "$name[]"
                    appendFormValue(childName, nested, output)
                }
            }
            is Iterable<*> -> value.forEachIndexed { index, nested ->
                val childName = if (isCompound(nested)) "$name[$index]" else "$name[]"
                appendFormValue(childName, nested, output)
            }
            is String, is Number, is Boolean -> output += formPair(name, value.toString())
            else -> fail("Backloggd export could not be encoded")
        }
    }

    private fun isCompound(value: Any?): Boolean =
        value is JSONObject || value is JSONArray || value is Map<*, *> || value is Iterable<*>

    private fun formPair(name: String, value: String): String =
        "${formEncode(name)}=${formEncode(value)}"

    private fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun latestCredentialsForSend(
        provided: BackloggdCredentials,
        expectedUsername: String
    ): BackloggdCredentials {
        val stored = storedCredentials()
            ?: fail("Backloggd account is no longer connected")
        if (!stored.username.equals(expectedUsername, ignoreCase = true)) {
            fail("The connected Backloggd account has changed")
        }
        return if (stored.cookieHeader != provided.cookieHeader) stored else provided
    }

    private fun rejectChangedStoredAccount(expectedUsername: String) {
        val stored = storedCredentials() ?: return
        if (!stored.username.equals(expectedUsername, ignoreCase = true)) {
            fail("The connected Backloggd account has changed")
        }
    }

    private fun ensureCurrentStoredAccount(session: RequestSession, expectedUsername: String) {
        val stored = storedCredentials()
            ?: fail("Backloggd account is no longer connected")
        if (!stored.username.equals(expectedUsername, ignoreCase = true) ||
            !stored.username.equals(session.verifiedUsername, ignoreCase = true) ||
            stored.cookieHeader != session.persistedCookieSnapshot
        ) {
            fail("The connected Backloggd account has changed")
        }
    }

    private fun storedCredentials(): BackloggdCredentials? = try {
        credentialStore.load()
    } catch (_: Exception) {
        fail("Saved Backloggd credentials are unavailable")
    }

    private inner class RequestSession(val starting: BackloggdCredentials) {
        var cookieHeader: String = starting.cookieHeader
            private set
        var persistedCookieSnapshot: String = starting.cookieHeader
            private set
        var verifiedUsername: String? = null
            private set
        private var pendingRotation = false

        fun markVerified(username: String) {
            verifiedUsername = username
            persistPendingRotation()
        }

        fun acceptCookies(response: BackloggdHttpResponse) {
            val setCookies = response.headerValues("Set-Cookie")
            if (setCookies.isEmpty()) return
            val rotated = mergeCookies(cookieHeader, setCookies)
            if (rotated != cookieHeader) {
                cookieHeader = rotated
                pendingRotation = true
            }
            persistPendingRotation()
        }

        private fun persistPendingRotation() {
            val verified = verifiedUsername ?: return
            if (!pendingRotation) return
            val stored = storedCredentials() ?: return
            if (!stored.username.equals(starting.username, ignoreCase = true) ||
                !stored.username.equals(verified, ignoreCase = true) ||
                stored.cookieHeader != persistedCookieSnapshot
            ) {
                return
            }
            if (stored.cookieHeader != cookieHeader) {
                try {
                    credentialStore.save(BackloggdCredentials(stored.username, cookieHeader))
                } catch (_: Exception) {
                    fail("Saved Backloggd credentials could not be updated")
                }
            }
            persistedCookieSnapshot = cookieHeader
            pendingRotation = false
        }
    }

    private data class VerifiedAccount(
        val username: String,
        val userId: Long,
        val csrfToken: String
    )

    private data class RemoteLog(
        val gameLog: JSONObject?,
        val playthroughs: List<RemotePlaythrough>,
        val mostRecentPlaythroughId: Long?
    )

    private data class RemotePlaythrough(
        val id: Long,
        val dates: List<RemotePlayDate>,
        val raw: JSONObject
    )

    private data class RemotePlayDate(
        val id: Long,
        val startDate: String?,
        val endDate: String?,
        val hours: Long?,
        val minutes: Long?,
        val note: String?,
        val raw: JSONObject
    )

    private data class RemoteLedgerDate(
        val playthroughId: Long,
        val date: RemotePlayDate,
        val dayLedger: BackloggdDayLedger,
        val remoteMinutes: Long,
        val requiredMinutes: Long
    )

    private data class RemoteLedgerContribution(
        val record: RemoteLedgerDate,
        val contribution: BackloggdDayContribution
    )

    private data class RemoteLedgerState(
        val records: List<RemoteLedgerDate>,
        val contributions: Map<String, RemoteLedgerContribution>,
        val unmanagedMarkers: Set<String>
    )

    private data class DateRange(val start: LocalDate, val end: LocalDate) {
        fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(end)
    }

    private data class BuiltExport(val form: Map<String, Any?>)

    private enum class MimeType { HTML, JSON }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BackloggdException) {
            throw failure
        } catch (_: Exception) {
            fail("Backloggd request could not be completed")
        }
    }

}


private fun parseGameSlug(input: String): String {
    if (input.isEmpty()) invalidGameInput()
    if (SLUG_PATTERN.matches(input)) return input
    val uri = try {
        URI(input)
    } catch (_: Exception) {
        invalidGameInput()
    }
    val host = uri.host?.lowercase(Locale.ROOT)
    if (uri.scheme?.equals("https", ignoreCase = true) != true ||
        host !in setOf("backloggd.com", "www.backloggd.com") ||
        uri.userInfo != null || uri.port !in setOf(-1, 443) ||
        uri.rawQuery != null || uri.rawFragment != null
    ) {
        invalidGameInput()
    }
    val match = Regex("^/games/([A-Za-z0-9][A-Za-z0-9_-]{0,199})/?$").matchEntire(uri.rawPath.orEmpty())
        ?: invalidGameInput()
    return match.groupValues[1]
}

private fun sameHostRedirectPath(currentPath: String, location: String): String {
    if (location.isBlank() || location.length > 4_096 ||
        location.any { it == '\r' || it == '\n' || it.isISOControl() }
    ) {
        fail("Backloggd returned an unsafe redirect")
    }
    val current = try {
        URI("https://backloggd.com$currentPath")
    } catch (_: Exception) {
        fail("Backloggd returned an unsafe redirect")
    }
    val target = try {
        current.resolve(URI(location))
    } catch (_: Exception) {
        fail("Backloggd returned an unsafe redirect")
    }
    if (target.scheme?.equals("https", ignoreCase = true) != true ||
        target.host?.equals("backloggd.com", ignoreCase = true) != true ||
        target.userInfo != null || target.port !in setOf(-1, 443) || target.rawFragment != null
    ) {
        fail("Backloggd returned an unsafe redirect")
    }
    val path = target.rawPath.orEmpty()
    if (!path.startsWith('/') || path.startsWith("//") || path.contains('\\')) {
        fail("Backloggd returned an unsafe redirect")
    }
    val query = target.rawQuery?.let { "?$it" }.orEmpty()
    return path + query
}

private fun htmlHeaders(cookie: String? = null): Map<String, String> = buildMap {
    put("User-Agent", USER_AGENT)
    put("Accept", HTML_ACCEPT)
    if (cookie != null) put("Cookie", cookie)
}

private fun authenticatedHeaders(
    cookie: String,
    accept: String,
    referer: String? = null,
    csrfToken: String? = null,
    xhr: Boolean = false
): Map<String, String> = buildMap {
    put("User-Agent", USER_AGENT)
    put("Accept", accept)
    put("Cookie", cookie)
    if (xhr) put("X-Requested-With", "XMLHttpRequest")
    if (referer != null) {
        put("Origin", "https://backloggd.com")
        put("Referer", referer)
        put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    }
    if (csrfToken != null) put("X-CSRF-Token", csrfToken)
}

private fun validateCredentials(credentials: BackloggdCredentials, expectedUsername: String? = null) {
    if (!USERNAME_PATTERN.matches(credentials.username) || !validCookieHeader(credentials.cookieHeader)) {
        fail("Backloggd credentials are invalid")
    }
    if (expectedUsername != null && !credentials.username.equals(expectedUsername, ignoreCase = true)) {
        fail("The authenticated Backloggd account does not match the connected account")
    }
}

private fun validCookieHeader(header: String): Boolean {
    if (header.isBlank() || header.length > 16_384 || header.any { it.isISOControl() }) return false
    val pieces = header.split(';')
    if (pieces.isEmpty()) return false
    return pieces.all { piece ->
        val cookie = piece.trim()
        val equalsAt = cookie.indexOf('=')
        if (equalsAt <= 0) return@all false
        val name = cookie.substring(0, equalsAt)
        val value = cookie.substring(equalsAt + 1).trim()
        COOKIE_NAME_PATTERN.matches(name) && value.none { it.isISOControl() || it == ';' }
    }
}

private fun validHeaderValue(value: String): Boolean =
    value.isNotBlank() && value.length <= 8_192 && value.none { it.isISOControl() }

private fun mergeCookies(currentHeader: String, setCookieHeaders: List<String>): String {
    val cookies = LinkedHashMap<String, String>()
    currentHeader.split(';').forEach { raw ->
        val part = raw.trim()
        val equalsAt = part.indexOf('=')
        if (equalsAt <= 0) fail("Backloggd returned invalid session cookies")
        cookies[part.substring(0, equalsAt)] = part.substring(equalsAt + 1).trim()
    }
    setCookieHeaders.forEach { raw ->
        if (raw.length > 8_192 || raw.any { it.isISOControl() }) {
            fail("Backloggd returned invalid session cookies")
        }
        val first = raw.substringBefore(';').trim()
        val equalsAt = first.indexOf('=')
        if (equalsAt <= 0) fail("Backloggd returned invalid session cookies")
        val name = first.substring(0, equalsAt).trim()
        val value = first.substring(equalsAt + 1).trim()
        if (!COOKIE_NAME_PATTERN.matches(name) || value.any { it.isISOControl() || it == ';' }) {
            fail("Backloggd returned invalid session cookies")
        }
        val maxAge = raw.split(';').drop(1).firstNotNullOfOrNull { attribute ->
            val parts = attribute.trim().split('=', limit = 2)
            if (parts.firstOrNull()?.equals("max-age", ignoreCase = true) == true) {
                parts.getOrNull(1)?.trim()?.toLongOrNull()
            } else {
                null
            }
        }
        if (maxAge != null && maxAge <= 0L) cookies.remove(name) else cookies[name] = value
    }
    val merged = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (!validCookieHeader(merged)) fail("Backloggd returned invalid session cookies")
    return merged
}

private fun isSignOutLink(href: String): Boolean {
    if (href.isBlank()) return false
    val uri = try {
        URI(href)
    } catch (_: Exception) {
        return false
    }
    if (uri.isAbsolute || uri.rawAuthority != null) return false
    return SIGN_OUT_PATH_PATTERN.matches(uri.path.orEmpty())
}

private fun profileUsername(href: String): String? {
    val uri = try {
        URI(href)
    } catch (_: Exception) {
        return null
    }
    if (uri.isAbsolute || uri.rawAuthority != null) return null
    return PROFILE_PATH_PATTERN.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
}

private fun parseJsonObject(body: String): JSONObject = try {
    JSONObject(body)
} catch (_: JSONException) {
    fail("Backloggd returned malformed data")
}

private fun validateNoteText(date: JSONObject) {
    for (key in NOTE_TEXT_KEYS) {
        if (!date.has(key) || date.isNull(key)) continue
        if (date.opt(key) !is String) fail("Backloggd returned malformed data")
    }
}

private fun optionalString(json: JSONObject, key: String): String? {
    if (!json.has(key) || json.isNull(key)) return null
    return json.opt(key) as? String ?: fail("Backloggd returned malformed data")
}
private fun optionalNonnegativeLong(json: JSONObject, key: String): Long? {
    if (!json.has(key) || json.isNull(key)) return null
    val value = json.opt(key)
    return when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            .takeIf { it >= 0L } ?: fail("Backloggd returned malformed data")
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            if (!number.isFinite() || number < 0.0 ||
                number >= Long.MAX_VALUE.toDouble() || number % 1.0 != 0.0
            ) {
                fail("Backloggd returned malformed data")
            }
            number.toLong()
        }
        is java.math.BigDecimal -> try {
            value.longValueExact().takeIf { it >= 0L }
                ?: fail("Backloggd returned malformed data")
        } catch (_: ArithmeticException) {
            fail("Backloggd returned malformed data")
        }
        is java.math.BigInteger -> try {
            value.longValueExact().takeIf { it >= 0L }
                ?: fail("Backloggd returned malformed data")
        } catch (_: ArithmeticException) {
            fail("Backloggd returned malformed data")
        }
        is String -> {
            if (!NONNEGATIVE_DECIMAL_PATTERN.matches(value)) fail("Backloggd returned malformed data")
            value.toLongOrNull() ?: fail("Backloggd returned malformed data")
        }
        else -> fail("Backloggd returned malformed data")
    }
}

private fun positiveId(value: Any?): Long? {
    val text = when (value) {
        is String -> value
        is Byte, is Short, is Int, is Long -> value.toString()
        else -> return null
    }
    if (!POSITIVE_DECIMAL_PATTERN.matches(text)) return null
    return text.toLongOrNull()?.takeIf { it > 0L }
}

private fun parseRemoteId(value: String?): Long? = positiveId(value)

private fun gameId(item: BackloggdExportEntity): Long = parseRemoteId(item.gameId)
    ?: fail("The mapped Backloggd game is unavailable")

private fun validateItem(item: BackloggdExportEntity) {
    if (!USERNAME_PATTERN.matches(item.accountUsername) || !SLUG_PATTERN.matches(item.gameSlug)) {
        fail("Backloggd export details are invalid")
    }
    if (item.durationMs <= 0L || !UUID_MARKER_PATTERN.matches(item.marker)) {
        fail("Backloggd export details are invalid")
    }
    itemDate(item)
    gameId(item)
}

private fun itemDate(item: BackloggdExportEntity): LocalDate = try {
    LocalDate.parse(item.playedOn)
} catch (_: Exception) {
    fail("Backloggd export date is invalid")
}


private fun isChallenge(response: BackloggdHttpResponse): Boolean {
    if (response.bodyTooLarge || response.body.isEmpty()) return false
    val text = String(response.body, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
    return CHALLENGE_MARKERS.any(text::contains)
}

private fun isStructuredRejection(response: BackloggdHttpResponse): Boolean {
    val contentType = response.header("Content-Type")
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return false
    if (contentType != "application/json" && !contentType.endsWith("+json")) return false
    if (response.bodyTooLarge) return false
    val body = try {
        decodeUtf8(response.body)
    } catch (_: BackloggdException) {
        return false
    }
    val json = try {
        JSONObject(body)
    } catch (_: JSONException) {
        return false
    }
    val success = json.opt("success")
    val explicitFailure = success is Boolean && !success
    val hasError = sequenceOf("error", "errors", "message").any { key ->
        val value = json.opt(key)
        when (value) {
            is String -> value.isNotBlank()
            is JSONArray -> value.length() > 0
            is JSONObject -> value.length() > 0
            else -> false
        }
    }
    return hasError && (explicitFailure || response.statusCode == 422 || response.statusCode == 401 || response.statusCode == 403)
}

private fun isTransientIo(failure: IOException): Boolean =
    failure is SocketTimeoutException ||
        failure is ConnectException ||
        failure is NoRouteToHostException ||
        failure is UnknownHostException ||
        failure is SocketException

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    fail("Backloggd returned malformed data")
}

private fun invalidGameInput(): Nothing =
    fail("Enter a Backloggd game slug or HTTPS game URL")

private fun conflict(): Nothing =
    fail("A Backloggd record conflicts with this session; export was not confirmed")

private fun fail(message: String): Nothing = throw BackloggdException(message)

private fun uncertainDelivery(statusCode: Int? = null, failureType: String? = null): Nothing = throw BackloggdException(
    "Backloggd may have recorded this session; reconcile before retrying" +
        when {
            statusCode != null -> " (HTTP $statusCode)"
            failureType != null -> " (transport $failureType)"
            else -> " (network acknowledgement unavailable)"
        },
    deliveryMayHaveOccurred = true
)

private fun challengeFailure(): Nothing = throw BackloggdException(
    "Backloggd requires a browser challenge; request stopped"
)

private fun challengeDeliveryUncertain(): Nothing = throw BackloggdException(
    "Backloggd requires a browser challenge; delivery may have occurred; reconcile before retrying",
    deliveryMayHaveOccurred = true
)

private val POSITIVE_DECIMAL_PATTERN = Regex("[1-9][0-9]*")
