package dev.hushyari.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import android.content.ClipboardManager
import androidx.room.Room
import dev.hushyari.controller.GestureDispatcher
import dev.hushyari.data.local.HushyariDatabase
import dev.hushyari.data.local.dao.GameConfigDao
import dev.hushyari.data.local.dao.SessionLogDao
import dev.hushyari.data.local.dao.SkillDao

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HushyariDatabase {
        return Room.databaseBuilder(context, HushyariDatabase::class.java, "hushyari.db").build()
    }

    @Provides
    fun provideGameConfigDao(db: HushyariDatabase): GameConfigDao = db.gameConfigDao()

    @Provides
    fun provideSkillDao(db: HushyariDatabase): SkillDao = db.skillDao()

    @Provides
    fun provideSessionLogDao(db: HushyariDatabase): SessionLogDao = db.sessionLogDao()

    @Provides
    @Singleton
    fun provideClipboardManager(@ApplicationContext context: Context): ClipboardManager {
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
}
