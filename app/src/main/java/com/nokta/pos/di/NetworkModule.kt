package com.nokta.pos.di

import com.nokta.pos.BuildConfig
import com.nokta.pos.network.BearerAuthInterceptor
import com.nokta.pos.network.DeviceTokenInterceptor
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.UnauthorizedInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        deviceTokenInterceptor: DeviceTokenInterceptor,
        bearerAuthInterceptor: BearerAuthInterceptor,
        unauthorizedInterceptor: UnauthorizedInterceptor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // BASIC em produção de propósito: BODY logaria o corpo do
            // device-login, que carrega as credenciais Cielo da unidade.
            level = if (BuildConfig.ENVIRONMENT == "production") {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.BODY
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(deviceTokenInterceptor)
            .addInterceptor(bearerAuthInterceptor)
            // Depois dos que anexam credencial: precisa ver a resposta do
            // request já autenticado para saber se o 401 é de sessão morta.
            .addInterceptor(unauthorizedInterceptor)
            .addInterceptor(logging)
            // Padrão do cliente: curto e agressivo. Numa rede de evento —
            // viva, mas ruim — o pior cenário não é "sem conexão" (isso falha
            // rápido), é a conexão ENGASGADA: o socket abre e fica pendurado.
            // 15s/20s deixava o operador olhando pro spinner por até 20s antes
            // de qualquer coisa acontecer. Consultas com fallback de cache
            // (cardápio, mesas, comandas) usam um timeout ainda mais curto —
            // ver `readThroughTimeout` — porque para elas existe uma resposta
            // boa pronta no disco esperando a rede desistir.
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideNoktaApi(retrofit: Retrofit): NoktaApi = retrofit.create(NoktaApi::class.java)
}
