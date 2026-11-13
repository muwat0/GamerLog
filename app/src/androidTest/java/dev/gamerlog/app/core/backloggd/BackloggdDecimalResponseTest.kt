package dev.gamerlog.app.core.backloggd

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentialStore
import dev.gamerlog.app.core.backloggd.security.BackloggdCredentials
import dev.gamerlog.app.core.database.BackloggdExportEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackloggdDecimalResponseTest {
    @Test
    fun androidJsonDecimalHoursCanConfirmTheExactExportedSession() = runBlocking {
        val credentials = BackloggdCredentials("test_owner", "session=fixture")
        val store = object : BackloggdCredentialStore {
            override fun load() = credentials
            override fun save(credentials: BackloggdCredentials) = Unit
            override fun clear() = Unit
        }
        val item = BackloggdExportEntity(
            sessionId = 1L,
            accountUsername = "test_owner",
            gameSlug = "celeste",
            gameId = "26226",
            playedOn = "2026-11-12",
            durationMs = 3_900_123L,
            marker = "11111111-1111-1111-1111-111111111111",
            createdAtMs = 0L,
            updatedAtMs = 0L
        )
        val settings = """
            <html><head><meta name="csrf-token" content="fixture-csrf"></head>
            <body data-authenticated="true"><nav id="primary-nav">
            <a href="/u/test_owner/">Profile</a><a href="/users/sign_out">Sign out</a>
            </nav><button id="save-profile-btn" user_id="17">Save</button></body></html>
        """.trimIndent()
        val log = """
            {"game_log":{"id":101},"playthroughs":{"7":{
              "id":7,"title":"GamerLog","play_dates":[{
                "id":301,"range_start_date":"2026-11-12","range_end_date":"2026-11-13",
                "hours":1.0,"minutes":5.0,
                "note":"[gamerlog-day-ledger:v1]\nbase_minutes=0\n11111111-1111-1111-1111-111111111111|2026-11-12|3900123\n[/gamerlog-day-ledger]"
              }]
            }}}
        """.trimIndent()
        val exchange = BackloggdHttpExchange { request ->
            val isSettings = request.path == "/settings/"
            check(isSettings || request.path == "/log/edit/26226")
            BackloggdHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf(if (isSettings) "text/html" else "application/json")),
                body = (if (isSettings) settings else log).toByteArray(Charsets.UTF_8)
            )
        }
        assertEquals("26226:7:301", HttpBackloggdTransport(store, exchange).findExport(credentials, item))
    }
}
