package com.nokta.pos.di

import com.nokta.pos.sync.ConnectivityChecker
import com.nokta.pos.sync.ConnectivityMonitor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    @Singleton
    abstract fun bindConnectivityChecker(impl: ConnectivityMonitor): ConnectivityChecker
}
