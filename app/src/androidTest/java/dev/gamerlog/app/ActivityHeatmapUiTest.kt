package dev.gamerlog.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gamerlog.app.core.database.GameEntity
import dev.gamerlog.app.core.database.GamerLogDatabase
import dev.gamerlog.app.core.database.PlaySessionEntity
import dev.gamerlog.app.ui.activity.ActivityContent
import dev.gamerlog.app.ui.theme.GamerLogTheme
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityHeatmapUiTest {

    @get:Rule
    val composeTestRule = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()

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
    fun roomUpdatesHeatmapCellAndPeakInsightsReactively() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val packageName = "dev.gamerlog.fixture.activity_$suffix"
        fixturePackageName = packageName
        val zoneId = ZoneOffset.UTC
        // A future window keeps unrelated existing device history out of this deterministic fixture.
        val selectedDate = LocalDate.of(2100, 1, 28)
        val monday = LocalDate.of(2100, 1, 4)
        val database = GamerLogDatabase.get(ApplicationProvider.getApplicationContext())

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                GamerLogTheme {
                    ActivityContent(database = database, date = selectedDate, zoneId = zoneId)
                }
            }
        }
        composeTestRule.onNodeWithText("Last 28 device-local calendar days").assertIsDisplayed()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("No game activity recorded")
                .fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            val dao = database.dao()
            dao.insertDiscoveredGame(
                GameEntity(
                    packageName = packageName,
                    appName = "Activity heatmap fixture",
                    customTitle = "Known Heatmap Game $suffix",
                    isTracked = false,
                    isAutoDetected = false
                )
            )
            dao.insertSessions(
                listOf(
                    session(packageName, monday, 10, 15, 10, 45),
                    session(packageName, monday, 10, 55, 11, 10)
                )
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Peak hour: 10:00 (35m)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Peak hour: 10:00 (35m)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Busiest weekday: Monday (45m)").assertIsDisplayed()
        composeTestRule.onNode(
            hasContentDescription("Monday, 10:00–11:00: 35 minutes recorded")
        ).performScrollTo().assertIsDisplayed()
    }

    private fun session(
        packageName: String,
        date: LocalDate,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): PlaySessionEntity {
        val startMs = LocalDateTime.of(date, java.time.LocalTime.of(startHour, startMinute))
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        val endMs = LocalDateTime.of(date, java.time.LocalTime.of(endHour, endMinute))
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        return PlaySessionEntity(
            packageName = packageName,
            startTime = startMs,
            endTime = endMs,
            durationMs = endMs - startMs
        )
    }
}
