package dev.gamerlog.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
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
import java.time.DayOfWeek
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WeekSummaryUiTest {

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
    fun weekSelectionDisplaysFixtureDurationAndWeekTotalRelativeToBaseline() {
        val uniqueSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val packageName = "dev.gamerlog.fixture.week_$uniqueSuffix"
        val customTitle = "Fake Week Game $uniqueSuffix"
        fixturePackageName = packageName

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val todayStartMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val tomorrowStartMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val week = WeekWindow.forDate(today, zoneId)
        val durationMs = 60_000L
        val monday = today.with(DayOfWeek.MONDAY)
        val weekSessionDate = if (today == monday) today.plusDays(1) else monday
        val weekSessionStart = weekSessionDate.atStartOfDay(zoneId)
            .plusHours(1)
            .toInstant()
            .toEpochMilli()
        val todaySessionStart = todayStartMs + 7_200_000L
        val fakeGame = GameEntity(
            packageName = packageName,
            appName = "Fake App Name $uniqueSuffix",
            customTitle = customTitle,
            isTracked = false,
            isAutoDetected = false
        )
        val weekSession = PlaySessionEntity(
            packageName = packageName,
            startTime = weekSessionStart,
            endTime = weekSessionStart + durationMs,
            durationMs = durationMs,
        )
        val todaySession = PlaySessionEntity(
            packageName = packageName,
            startTime = todaySessionStart,
            endTime = todaySessionStart + durationMs,
            durationMs = durationMs,
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = GamerLogDatabase.get(context)
        val dao = database.dao()
        val (weeklyBaselineTotalMs, expectedGameCountText, dailyBaselineTotalMs) = runBlocking {
            val weeklyBaseline = dao.observeGamePlaytime(
                week.startInclusiveMs,
                week.endExclusiveMs
            ).first()
            val dailyBaseline = dao.observeGamePlaytime(todayStartMs, tomorrowStartMs).first()
            Triple(
                weeklyBaseline.sumOf { it.totalDurationMs },
                if (weeklyBaseline.size + 1 == 1) {
                    "1 game played this week"
                } else {
                    "${weeklyBaseline.size + 1} games played this week"
                },
                dailyBaseline.sumOf { it.totalDurationMs }
            )
        }

        composeTestRule.onNodeWithText("Week").performClick()
        runBlocking {
            dao.insertDiscoveredGame(fakeGame)
            dao.insertSessions(listOf(weekSession, todaySession))
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(customTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("This Week's Playtime").performScrollTo().assertIsDisplayed()
        val weeklyGameDuration = composeTestRule.onNode(
            hasText("2m") and hasAnySibling(hasText(customTitle))
        )
        weeklyGameDuration.performScrollTo()
        composeTestRule.onNodeWithText(customTitle).assertIsDisplayed()
        weeklyGameDuration.assertIsDisplayed()
        composeTestRule.onNode(
            hasText(formatDuration(weeklyBaselineTotalMs + 2 * durationMs)) and
                hasAnySibling(hasText("This Week's Playtime"))
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedGameCountText).performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Day").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Today's Playtime").performScrollTo().assertIsDisplayed()
        val dailyGameDuration = composeTestRule.onNode(
            hasText("1m") and hasAnySibling(hasText(customTitle))
        )
        dailyGameDuration.performScrollTo()
        composeTestRule.onNodeWithText(customTitle).assertIsDisplayed()
        dailyGameDuration.assertIsDisplayed()
        composeTestRule.onNode(
            hasText(formatDuration(dailyBaselineTotalMs + durationMs)) and
                hasAnySibling(hasText("Today's Playtime"))
        ).performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Week").performScrollTo().performClick()
        composeTestRule.onNodeWithText("This Week's Playtime").performScrollTo().assertIsDisplayed()
        weeklyGameDuration.performScrollTo()
        composeTestRule.onNodeWithText(customTitle).assertIsDisplayed()
        weeklyGameDuration.assertIsDisplayed()
        composeTestRule.onNode(
            hasText(formatDuration(weeklyBaselineTotalMs + 2 * durationMs)) and
                hasAnySibling(hasText("This Week's Playtime"))
        ).performScrollTo().assertIsDisplayed()

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
