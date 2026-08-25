package com.nokta.pos.network

import com.nokta.pos.device.DeviceCredentialsStore
import com.nokta.pos.session.SessionEvents
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Anexa X-Device-Token em toda request — prova "terminal físico pareado"
 * (VenueDeviceTokenGuard, backend). Sempre presente depois do pareamento;
 * ausente só nas telas de pareamento em si (que não usam este client, ver
 * PairingApi/NetworkModule).
 */
class DeviceTokenInterceptor @Inject constructor(
    private val credentialsStore: DeviceCredentialsStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = credentialsStore.deviceToken()
        val request = chain.request().newBuilder().apply {
            if (token != null) addHeader("X-Device-Token", token)
        }.build()
        return chain.proceed(request)
    }
}

/**
 * Anexa Authorization: Bearer com o JWT do operador logado — via header, não
 * cookie (o app nativo não tem cookie jar de browser). Ver
 * JwtStrategy.cookieOrBearerExtractor no backend.
 */
class BearerAuthInterceptor @Inject constructor(
    private val credentialsStore: DeviceCredentialsStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val jwt = credentialsStore.jwt()
        val request = chain.request().newBuilder().apply {
            if (jwt != null) addHeader("Authorization", "Bearer $jwt")
        }.build()
        return chain.proceed(request)
    }
}

/**
 * Detecta sessão de operador inválida (401) e avisa a navegação uma vez só.
 *
 * Só reage a 401 (credencial do OPERADOR inválida/expirada) — nunca a 403,
 * que significa "operador autenticado, mas sem permissão para ISTO" e é
 * tratado na tela como mensagem, sem derrubar ninguém. Um 403 do
 * `VenueDeviceTokenGuard` (terminal revogado) também não desloga o operador:
 * é problema de pareamento, resolvido pelo gerente, e forçar logout aqui só
 * esconderia a causa real.
 */
class UnauthorizedInterceptor @Inject constructor(
    private val credentialsStore: DeviceCredentialsStore,
    private val sessionEvents: SessionEvents,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 401 && credentialsStore.jwt() != null) {
            credentialsStore.clearSession()
            sessionEvents.notifyExpired()
        }
        return response
    }
}
