package com.nokta.pos.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StartDestination { PAIRING, LOGIN, HOME }

/**
 * Decide onde o app abre. Antes disto o `startDestination` era sempre a tela
 * de pareamento, então um terminal já pareado e com operador logado voltava
 * para "digite o código de 6 dígitos" a cada abertura — o gerente teria que
 * gerar um código novo no dashboard toda vez.
 *
 * Regras (nesta ordem):
 *  1. Terminal não pareado → PAIRING (só o gerente resolve).
 *  2. Sem operador logado, ou sessão vencida → LOGIN (pareamento intacto).
 *  3. Caso contrário → HOME.
 *
 * A sessão é revalidada em background (`refreshAccess`): se o backend
 * recusar, o interceptor de 401 devolve o operador ao login sozinho. Nunca
 * bloqueamos a abertura esperando rede — o app precisa abrir mesmo offline.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _destination = MutableStateFlow<StartDestination?>(null)
    val destination: StateFlow<StartDestination?> = _destination

    init { resolve() }

    private fun resolve() {
        val destination = when {
            !authRepository.isDevicePaired() -> StartDestination.PAIRING
            !authRepository.isOperatorLoggedIn() -> StartDestination.LOGIN
            authRepository.isSessionExpired() -> {
                authRepository.logoutOperator()
                StartDestination.LOGIN
            }
            else -> StartDestination.HOME
        }

        if (destination == StartDestination.HOME) {
            // Revalida permissões silenciosamente (papel pode ter mudado no
            // dashboard desde o último login). Falha não impede abrir.
            authRepository.currentOrganizationId()?.let { orgId ->
                viewModelScope.launch { authRepository.refreshAccess(orgId) }
            }
        }

        _destination.value = destination
    }
}
