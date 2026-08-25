package com.nokta.pos.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canal único de "a sessão do operador caiu".
 *
 * Antes disso, um 401 (turno longo passando do TTL da sessão, ou sessão
 * revogada pelo dashboard) só aparecia como erro de rede solto na tela em que
 * o operador estava — ele tentava de novo, tomava o mesmo erro, e a maquininha
 * parecia quebrada. Agora o interceptor emite aqui e a navegação leva ao
 * login uma vez só, de qualquer tela.
 *
 * `replay = 1` de propósito: se o 401 chegar enquanto a UI está sendo
 * recomposta (ex.: rotação, volta do app do background), o evento não se perde.
 *
 * Só o LOGIN do operador cai — o pareamento do terminal continua intacto
 * (`DeviceCredentialsStore.clearSession` nunca toca no deviceToken).
 */
@Singleton
class SessionEvents @Inject constructor() {

    private val _expired = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired

    fun notifyExpired() {
        _expired.tryEmit(Unit)
    }

    /** Chamado depois que a navegação já levou o operador ao login. */
    fun consume() {
        _expired.resetReplayCache()
    }
}
