package dev.hushyari.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date

class Converters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time

    @TypeConverter
    fun fromStringList(value: String): List<String> =
        if (value.isBlank()) emptyList()
        else json.decodeFromString(value)

    @TypeConverter
    fun stringListToString(list: List<String>): String =
        json.encodeToString(list)

    @TypeConverter
    fun fromLongList(value: String): List<Long> =
        if (value.isBlank()) emptyList()
        else json.decodeFromString(value)

    @TypeConverter
    fun longListToString(list: List<Long>): String =
        json.encodeToString(list)
}
