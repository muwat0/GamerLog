package dev.gamerlog.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.database.PlaySessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DailySummaryUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private var fixturePackageName: String? = null

    @After
    fun tearDown() {
        val packageName = fixturePackageName ?: return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = GamerLogDatabase.get(context)
        runBlocking {
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM games WHERE packageName = ?",
                    arrayOf(packageName)
                )
            }
        }
        fixturePackageName = null
    }

    @Test
    fun testDailySummaryDisplaysGameTitleAndExactDuration() {
        val uniqueSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val packageName = "dev.gamerlog.fixture.testgame_$uniqueSuffix"
        val customTitle = "Fake Summary Game $uniqueSuffix"
        val appName = "Fake App Name $uniqueSuffix"
        fixturePackageName = packageName

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val startOfDayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endOfDayMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val sessionStart = startOfDayMs + 3_600_000L // 01:00:00 local time
        val sessionEnd = sessionStart + 60_000L      // exactly 60 seconds duration
        val durationMs = 60_000L

        val fakeGame = GameEntity(
            packageName = packageName,
            appName = appName,
            customTitle = customTitle,
            isTracked = false,
            isAutoDetected = false
        )
        val fakeSession = PlaySessionEntity(
            packageName = packageName,
            startTime = sessionStart,
            endTime = sessionEnd,
            durationMs = durationMs,
            syncStatus = 0
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = GamerLogDatabase.get(context)
        val dao = database.dao()

        // Read baseline daily playtime to verify total playtime without assuming an empty database
        val baselineTotalMs = runBlocking {
            dao.observeGamePlaytime(startOfDayMs, endOfDayMs).first().sumOf { it.totalDurationMs }
        }
        val expectedTotalFormatted = formatDuration(baselineTotalMs + durationMs)

        // Insert fixture into real app Room DB after MainActivity launch
        runBlocking {
            dao.insertDiscoveredGame(fakeGame)
            dao.insertSessions(listOf(fakeSession))
        }

        // Await Compose recomposition triggered by Room Flow emission
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(customTitle).fetchSemanticsNodes().isNotEmpty()
        }

        // Assert displayed game's custom title
        composeTestRule.onNodeWithText(customTitle).assertIsDisplayed()

        // Assert fixture's per-game duration alongside the game title within the same card row
        composeTestRule.onNode(
            hasText("1m") and hasAnySibling(hasText(customTitle))
        ).assertIsDisplayed()

        // Assert updated daily total duration within "Today's Playtime" card
        composeTestRule.onNode(
            hasText(expectedTotalFormatted) and hasAnySibling(hasText("Today's Playtime"))
        ).assertIsDisplayed()
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0s"
        val totalSeconds = durationMs / 1000L
        if (totalSeconds < 60L) {
            return "${totalSeconds.coerceAtLeast(1L)}s"
        }
        val totalMinutes = totalSeconds / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}
