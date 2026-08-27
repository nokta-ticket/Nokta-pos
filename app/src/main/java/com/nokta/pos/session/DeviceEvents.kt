package com.nokta.pos.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canal único de "o TERMINAL foi revogado" — irmão de [SessionEvents], mas
 * nunca o mesmo canal: sessão de operador caída volta para LOGIN (pareamento
 * intacto); terminal revogado tem que voltar para PAIRING (só o gerente
 * resolve gerando um código novo). Misturar os dois faria um terminal
 * revogado cair em LOGIN mostrando "sessão expirada", escondendo a causa
 * real (mesmo problema que a correção de humanizeLoginError já resolveu
 * para o caso síncrono de tentar logar com o terminal morto).
 *
 * Emitido por [com.nokta.pos.auth.AuthRepository.checkDeviceStatus], chamada
 * em background pelo Splash sem nunca bloquear a abertura do app.
 */
@Singleton
class DeviceEvents @Inject constructor() {

    private val _revoked = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val revoked: SharedFlow<Unit> = _revoked

    fun notifyRevoked() {
        _revoked.tryEmit(Unit)
    }

    /** Chamado depois que a navegação já levou o terminal ao pareamento. */
    fun consume() {
        _revoked.resetReplayCache()
    }
}
