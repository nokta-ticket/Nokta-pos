package com.nokta.pos.sync

import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ponte entre "a rede voltou" e "a fila é drenada" — sem isto, sincronização
 * automática dependeria só do tick de 15min do WorkManager (o mínimo
 * permitido pelo Android para trabalho periódico), o que não bate com "sem
 * exigir tela específica nem botão manual, idealmente quase imediato".
 *
 * Iniciado uma vez, no processo inteiro (Application.onCreate) — nunca por
 * uma tela específica, porque a sincronização não pode depender de qual tela
 * está aberta no momento em que a rede volta.
 */
@Singleton
class SyncTriggerCoordinator @Inject constructor(
    private val connectivityMonitor: ConnectivityMonitor,
    private val workManager: WorkManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            connectivityMonitor.observe().filter { online -> online }.collectLatest {
                SyncWorker.triggerNow(workManager)
            }
        }
    }
}
