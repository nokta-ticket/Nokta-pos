package com.nokta.pos.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sincronização em background — sobrevive ao app fechado, respeita
 * conectividade (`NetworkType.CONNECTED`, o próprio WorkManager só executa
 * com rede disponível), tem retry/backoff nativo do WorkManager em caso de
 * falha, e nunca duplica execução concorrente (`ExistingPeriodicWorkPolicy.KEEP`
 * mais um `enqueueUniqueWork` de "kick" imediato com `REPLACE` só quando
 * ainda não há nada rodando).
 *
 * Periódico a cada 15 minutos é o MÍNIMO permitido pelo Android para
 * `PeriodicWorkRequest` — não é o único gatilho: [SyncEngine.requestSync]
 * mais o `OneTimeWorkRequest` expresso disparado por
 * [SyncTriggerCoordinator] cobrem o caso comum (operação feita agora, rede
 * disponível agora) sem esperar até 15min.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = syncEngine.syncAll()
            Result.success(workDataOf("processed" to result.processed))
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "nokta_outbox_sync_periodic"
        private const val EXPRESS_WORK_NAME = "nokta_outbox_sync_now"

        fun schedulePeriodic(workManager: WorkManager) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequestBackoffMinimum, TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Disparo imediato (assim que a rede volta, ou logo após uma escrita cair no Outbox). */
        fun triggerNow(workManager: WorkManager) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniqueWork(EXPRESS_WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
        }
    }
}

private val WorkRequestBackoffMinimum = TimeUnit.SECONDS.toMillis(30)
