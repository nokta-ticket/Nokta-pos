package com.nokta.pos.device

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nokta.pos.network.NoktaApi
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
private const val TAG = "DeviceHeartbeat"

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
 * `GET venue-devices/me` é reaproveitado de propósito (já existe, já é
 * exatamente "confirma o token e atualiza lastSeenAt", nunca precisa de
 * endpoint novo). Falha (sem rede, terminal revogado) é sempre ignorada
 * aqui — não é responsabilidade deste coordinator decidir navegação; quem
 * cuida de terminal revogado é `AuthRepository.checkDeviceStatus`
 * (disparado no boot) e o interceptor de 401/403 das rotas normais.
 */
@Singleton
class DeviceHeartbeatCoordinator @Inject constructor(
    private val api: NoktaApi,
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
                    runCatching { api.getDeviceStatus() }
                        .onSuccess { Log.d(TAG, "loop: heartbeat ok") }
                        .onFailure { Log.w(TAG, "loop: heartbeat falhou (lastSeenAt não avança)", it) }
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
