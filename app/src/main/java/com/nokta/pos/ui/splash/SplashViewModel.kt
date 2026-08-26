package com.nokta.pos.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.sync.ConnectivityChecker
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
 *  2. Sem operador logado, ou sessão vencida de verdade (JWT expirado) →
 *     LOGIN (pareamento intacto).
 *  3. Aparelho reiniciou desde o login E há rede → LOGIN. Reiniciar costuma
 *     ser o momento em que a maquininha troca de mão (fim de turno); com
 *     rede disponível, sempre vale reconfirmar quem está com ela.
 *  4. Aparelho reiniciou desde o login mas SEM rede → HOME, reaproveitando a
 *     sessão. Travar o caixa porque a internet caiu no exato instante do
 *     boot seria pior que o risco de continuar com o operador anterior por
 *     mais um pouco — assim que a rede voltar, o próximo reboot exige login.
 *  5. Caso contrário → HOME.
 *
 * A sessão é revalidada em background (`refreshAccess`): se o backend
 * recusar, o interceptor de 401 devolve o operador ao login sozinho. Nunca
 * bloqueamos a abertura esperando rede — o app precisa abrir mesmo offline.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val connectivityChecker: ConnectivityChecker,
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
            authRepository.didRebootSinceLastSession() && connectivityChecker.isOnline() -> {
                authRepository.logoutOperator()
                StartDestination.LOGIN
            }
            else -> {
                // Sem reboot pendente, ou reboot pendente mas sem rede para
                // reconfirmar: reaproveita a sessão e marca este boot como
                // já confirmado, para não repetir a checagem a cada abertura
                // enquanto a rede continuar fora.
                if (authRepository.didRebootSinceLastSession()) {
                    authRepository.confirmSessionAfterReboot()
                }
                StartDestination.HOME
            }
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
