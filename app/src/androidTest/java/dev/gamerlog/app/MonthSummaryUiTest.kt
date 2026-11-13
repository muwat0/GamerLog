package dev.gamerlog.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class MonthSummaryUiTest {

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
    fun monthSelectionReactsToRoomAndExcludesAdjacentMonthThenReturnsToDayAndWeek() {
        val uniqueSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val packageName = "dev.gamerlog.fixture.month_$uniqueSuffix"
        val customTitle = "Fake Month Game $uniqueSuffix"
        fixturePackageName = packageName

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val monthWindow = MonthWindow.forDate(today, zoneId)
        val insideMonthStart = monthWindow.startInclusiveMs + 3_600_000L
        val outsideMonthStart = monthWindow.endExclusiveMs + 3_600_000L
        val insideMonthDurationMs = 120_000L
        val outsideMonthDurationMs = 300_000L
        val fakeGame = GameEntity(
            packageName = packageName,
            appName = "Fake Month App $uniqueSuffix",
            customTitle = customTitle,
            isTracked = false,
            isAutoDetected = false
        )
        val insideMonthSession = PlaySessionEntity(
            packageName = packageName,
            startTime = insideMonthStart,
            endTime = insideMonthStart + insideMonthDurationMs,
            durationMs = insideMonthDurationMs,
            syncStatus = 0
        )
        val outsideMonthSession = PlaySessionEntity(
            packageName = packageName,
            startTime = outsideMonthStart,
            endTime = outsideMonthStart + outsideMonthDurationMs,
            durationMs = outsideMonthDurationMs,
            syncStatus = 0
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = GamerLogDatabase.get(context)
        val dao = database.dao()
        val baselineTotalMs = runBlocking {
            dao.observeGamePlaytime(monthWindow.startInclusiveMs, monthWindow.endExclusiveMs)
                .first()
                .sumOf { it.totalDurationMs }
        }

        composeTestRule.onNodeWithText("Month").performClick().assertIsSelected()
        runBlocking {
            dao.insertDiscoveredGame(fakeGame)
            dao.insertSessions(listOf(insideMonthSession, outsideMonthSession))
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(customTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("This Month's Playtime").performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(
            hasText("2m") and hasAnySibling(hasText(customTitle))
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(
            hasText(formatDuration(baselineTotalMs + insideMonthDurationMs)) and
                hasAnySibling(hasText("This Month's Playtime"))
        ).performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("Day").performScrollTo().performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Week").performScrollTo().performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Month").performScrollTo().performClick().assertIsSelected()
        composeTestRule.onNodeWithText("This Month's Playtime").performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(
            hasText("2m") and hasAnySibling(hasText(customTitle))
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
