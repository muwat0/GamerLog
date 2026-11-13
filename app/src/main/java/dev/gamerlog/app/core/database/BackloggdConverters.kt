package dev.gamerlog.app.core.database

import androidx.room.TypeConverter

class BackloggdConverters {
    @TypeConverter
    fun exportStateToString(state: ExportState): String = state.name

    @TypeConverter
    fun stringToExportState(state: String): ExportState = ExportState.valueOf(state)
}
