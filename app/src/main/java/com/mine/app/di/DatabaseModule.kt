package com.mine.app.di

import android.content.Context
import androidx.room.Room
import com.mine.app.data.local.db.MineDatabase
import com.mine.app.data.local.db.dao.ChatDao
import com.mine.app.data.local.db.dao.GraphDao
import com.mine.app.data.local.db.dao.QueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMineDatabase(@ApplicationContext context: Context): MineDatabase {
        val dbPassword = "debug_super_secret_password".toByteArray()
        val supportFactory = SupportFactory(dbPassword)

        return Room.databaseBuilder(
            context,
            MineDatabase::class.java,
            "mine_graph.db"
        )
            .openHelperFactory(supportFactory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideGraphDao(database: MineDatabase): GraphDao = database.graphDao()

    @Provides
    fun provideQueueDao(database: MineDatabase): QueueDao = database.queueDao()

    @Provides
    fun provideChatDao(database: MineDatabase): ChatDao = database.chatDao()
}
