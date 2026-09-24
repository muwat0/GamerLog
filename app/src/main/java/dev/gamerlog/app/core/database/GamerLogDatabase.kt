package dev.gamerlog.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        GameEntity::class,
        PlaySessionEntity::class,
        ScanCheckpointEntity::class,
        OpenSessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GamerLogDatabase : RoomDatabase() {

    abstract fun dao(): GamerLogDao

    companion object {
        private const val DATABASE_NAME = "gamerlog.db"

        @Volatile
        private var instance: GamerLogDatabase? = null

        fun get(context: Context): GamerLogDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GamerLogDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
        }
    }
}
