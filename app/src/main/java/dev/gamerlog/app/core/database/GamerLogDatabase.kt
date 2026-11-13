package dev.gamerlog.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@TypeConverters(BackloggdConverters::class)

@Database(
    entities = [
        GameEntity::class,
        PlaySessionEntity::class,
        ScanCheckpointEntity::class,
        OpenSessionEntity::class,
        BackloggdExportEntity::class,
        BackloggdSettingsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class GamerLogDatabase : RoomDatabase() {

    abstract fun dao(): GamerLogDao
    abstract fun backloggdDao(): BackloggdDao

    companion object {
        private const val DATABASE_NAME = "gamerlog.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS `index_play_sessions_syncStatus`")
                database.execSQL("ALTER TABLE `play_sessions` RENAME TO `play_sessions_v1`")
                database.execSQL(
                    """
                    CREATE TABLE `play_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        FOREIGN KEY(`packageName`) REFERENCES `games`(`packageName`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `play_sessions`
                        (`id`, `packageName`, `startTime`, `endTime`, `durationMs`)
                    SELECT `id`, `packageName`, `startTime`, `endTime`, `durationMs`
                    FROM `play_sessions_v1`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `play_sessions_v1`")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_play_sessions_packageName` " +
                        "ON `play_sessions` (`packageName`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_play_sessions_startTime` " +
                        "ON `play_sessions` (`startTime`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_play_sessions_packageName_startTime_endTime` " +
                        "ON `play_sessions` (`packageName`, `startTime`, `endTime`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE `backloggd_exports` (
                        `sessionId` INTEGER NOT NULL,
                        `accountUsername` TEXT NOT NULL,
                        `gameSlug` TEXT NOT NULL,
                        `gameId` TEXT,
                        `playedOn` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `marker` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `remoteEntryId` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `play_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX `index_backloggd_exports_marker` " +
                        "ON `backloggd_exports` (`marker`)"
                )
                database.execSQL(
                    "CREATE INDEX `index_backloggd_exports_accountUsername_state` " +
                        "ON `backloggd_exports` (`accountUsername`, `state`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE `backloggd_settings` (
                        `id` INTEGER NOT NULL,
                        `username` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `enabledSinceMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `backloggd_settings` (`id`, `username`, `enabled`, `enabledSinceMs`)
                    VALUES (1, NULL, 0, 0)
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: GamerLogDatabase? = null

        fun get(context: Context): GamerLogDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GamerLogDatabase::class.java,
                    DATABASE_NAME
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
