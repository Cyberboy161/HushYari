package dev.hushyari.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.hushyari.data.local.dao.GameConfigDao
import dev.hushyari.data.local.dao.SessionLogDao
import dev.hushyari.data.local.dao.SkillDao
import dev.hushyari.data.local.entities.GameConfigEntity
import dev.hushyari.data.local.entities.SessionLogEntity
import dev.hushyari.data.local.entities.SkillEntity

@Database(
    entities = [
        GameConfigEntity::class,
        SkillEntity::class,
        SessionLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class HushyariDatabase : RoomDatabase() {

    abstract fun gameConfigDao(): GameConfigDao
    abstract fun skillDao(): SkillDao
    abstract fun sessionLogDao(): SessionLogDao
}
