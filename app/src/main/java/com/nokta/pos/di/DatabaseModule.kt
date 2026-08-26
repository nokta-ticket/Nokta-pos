package com.nokta.pos.di

import android.content.Context
import androidx.room.Room
import com.nokta.pos.data.local.NoktaDatabase
import com.nokta.pos.data.local.dao.MenuDao
import com.nokta.pos.data.local.dao.OutboxDao
import com.nokta.pos.data.local.dao.TabDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NoktaDatabase =
        Room.databaseBuilder(context, NoktaDatabase::class.java, "nokta_pos.db")
            // Projeto ainda não publicado (ver comentário em NoktaDatabase) —
            // schema muda com frequência nesta fase, e nenhum dado local
            // sobrevivendo a uma reinstalação importa ainda. Apagar e
            // recriar o banco no próximo boot após qualquer mudança de
            // schema evita escrever uma Migration manual por coluna nova.
            // Trocar por Migrations reais antes do primeiro lançamento.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMenuDao(db: NoktaDatabase): MenuDao = db.menuDao()

    @Provides
    fun provideTabDao(db: NoktaDatabase): TabDao = db.tabDao()

    @Provides
    fun provideOutboxDao(db: NoktaDatabase): OutboxDao = db.outboxDao()
}
