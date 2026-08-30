package com.nokta.pos.device

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nokta.pos.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val HEARTBEAT_INTERVAL_MS = 20_000L

/**
 * Mantém `lastSeenAt` do terminal atualizado enquanto o app está em
 * primeiro plano, mesmo sem nenhuma ação do operador — sem isto, um garçom
 * parado na Home esperando o cliente (conversando, aguardando forma de
 * pagamento) nunca gera nenhuma requisição de rede, e o terminal aparece
 * "Desconectado" no dashboard depois de ONLINE_THRESHOLD_MS (60s, backend)
 * mesmo com o app aberto e em uso de verdade — bug real reportado pelo
 * usuário, distinto do middleware de heartbeat passivo (que só reage a
 * ações que já geravam chamada de rede por outro motivo).
 *
 * 20s de intervalo dá 3 tentativas dentro da janela de 60s do backend — uma
 * falha isolada de rede não derruba o status; só 3 falhas seguidas (60s
 * inteiros sem nenhum ping bem-sucedido) reproduzem o "Desconectado" real.
 *
 * Reaproveita `AuthRepository.checkDeviceStatus()` (não chama a API direto):
 * é o mesmo `GET venue-devices/me` já usado no boot, mas agora com a MESMA
 * decisão de revogação nos dois lugares — antes, só a chamada do boot
 * limpava o token/disparava `DeviceEvents.notifyRevoked()` num 401/403; se
 * essa chamada específica falhasse por qualquer motivo (ex.: coroutine
 * cancelada por recomposição/navegação antes de processar a resposta), o
 * app nunca mais detectava a revogação pelo resto da sessão, mesmo o
 * servidor devolvendo 403 a cada heartbeat pra sempre — bug real
 * reproduzido em 2026-08-30 (terminal revogado no backend, app preso em
 * LOGIN sem nunca cair pra PAIRING). Falha que não é 401/403 (sem rede,
 * timeout, 5xx) continua sendo ignorada, ver doc de `checkDeviceStatus`.
 */
@Singleton
class DeviceHeartbeatCoordinator @Inject constructor(
    private val authRepository: AuthRepository,
    private val credentialsStore: DeviceCredentialsStore,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                if (credentialsStore.isPaired()) {
                    authRepository.checkDeviceStatus()
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        loopJob?.cancel()
        loopJob = null
    }
}
