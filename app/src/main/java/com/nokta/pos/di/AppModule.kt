package com.nokta.pos.di

import android.content.Context
import com.nokta.pos.device.DeviceCredentialsStore
import com.nokta.pos.payment.cielo.CieloCredentialsProvider
import com.nokta.pos.payment.cielo.CieloDeepLinkPaymentProvider
import com.nokta.pos.payment.domain.PaymentProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideContext(@ApplicationContext context: Context): Context = context
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PaymentModule {
    @Binds
    abstract fun bindPaymentProvider(impl: CieloDeepLinkPaymentProvider): PaymentProvider

    @Binds
    abstract fun bindCieloCredentialsProvider(impl: DeviceCredentialsStore): CieloCredentialsProvider
}
