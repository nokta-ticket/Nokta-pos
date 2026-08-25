package com.nokta.pos.sync

import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camada fina entre [SyncEngine] e [SyncWorker] — evita que `SyncEngine`
 * (que o `Worker` já injeta para executar o trabalho) dependa de volta do
 * próprio `SyncWorker`, o que seria um ciclo de dependência.
 */
@Singleton
class SyncWorkManagerTrigger @Inject constructor(private val workManager: WorkManager) {
    fun triggerNow() = SyncWorker.triggerNow(workManager)
}
