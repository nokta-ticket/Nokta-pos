package com.nokta.pos.sync

import com.nokta.pos.common.Money
import com.nokta.pos.data.local.dao.OutboxDao
import com.nokta.pos.data.local.dao.TabDao
import com.nokta.pos.data.local.entity.OutboxEntity
import com.nokta.pos.data.local.entity.OutboxOperationType
import com.nokta.pos.data.local.entity.OutboxStatus
import com.nokta.pos.data.local.entity.SyncState
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.CreateOrderRequest
import com.nokta.pos.network.dto.CreatePaymentRequest
import com.nokta.pos.network.dto.CreateTabRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drena a fila do Outbox, em ordem — sempre a mesma ordem de criação
 * (`sequence ASC`), porque lançar itens antes de registrar o pagamento da
 * mesma comanda importa: um pagamento cujo total ainda não existe no
 * servidor seria recusado.
 *
 * Chamado por: [SyncWorker] (WorkManager, periódico + acionado por
 * conectividade), e diretamente por qualquer escrita do [com.nokta.pos.comanda.data.TabRepository]
 * que caiu no Outbox (`requestSync()`, best-effort — se não houver rede
 * agora, o worker tenta de novo depois sozinho).
 *
 * NUNCA sincroniza "estado final" — cada operação da fila é a mesma
 * chamada HTTP incremental que seria feita online (create/send/cancel/pay),
 * sempre com a mesma chave de idempotência que usaria em request direto.
 * Reenviar não duplica nada no backend.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: NoktaApi,
    private val outboxDao: OutboxDao,
    private val tabDao: TabDao,
    private val connectivityChecker: ConnectivityChecker,
    private val syncStatusStore: SyncStatusStore,
    private val workManagerTrigger: SyncWorkManagerTrigger,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 8)
    val events: Flow<SyncEvent> = _events.asSharedFlow()

    /**
     * Pedido "best-effort" de sincronização imediata — usado pelas escritas
     * do [com.nokta.pos.comanda.data.TabRepository] para não deixar uma
     * operação esperando até o próximo tick periódico do WorkManager (até
     * 15 min de atraso em Android real, o mínimo permitido pela plataforma).
     * Dispara um `OneTimeWorkRequest` com `NetworkType.CONNECTED` — se não
     * há rede agora, o próprio WorkManager segura a execução até haver,
     * nunca falha silenciosamente.
     */
    fun requestSync() {
        workManagerTrigger.triggerNow()
    }

    /**
     * Processa toda a fila pendente. Para na primeira falha de REDE (não de
     * negócio) — se a conexão caiu no meio, as próximas falhariam igual, e
     * insistir arriscaria inverter a ordem entre operações da mesma comanda.
     * Falha de NEGÓCIO (4xx) não para a fila: é definitiva para aquela
     * operação e não afeta as seguintes.
     */
    suspend fun syncAll(): SyncRunResult {
        if (!connectivityChecker.isOnline()) return SyncRunResult(processed = 0, stoppedByNetwork = true)

        var processed = 0
        for (operation in outboxDao.getPending()) {
            val outcome = runOperation(operation)
            when (outcome) {
                is StepOutcome.Success -> {
                    outboxDao.delete(operation)
                    processed++
                    _events.tryEmit(SyncEvent.OperationSynced(operation.type))
                }
                is StepOutcome.Rejected -> {
                    // Definitivo: nunca reentra na fila. Fica visível como pendência
                    // resolvida "errada" — mas nunca some sem rastro (log local).
                    outboxDao.delete(operation)
                    processed++
                    _events.tryEmit(SyncEvent.OperationRejected(operation.type, outcome.reason))
                }
                is StepOutcome.Retry -> {
                    outboxDao.update(operation.copy(status = OutboxStatus.FAILED_RETRYABLE, retryCount = operation.retryCount + 1, lastError = outcome.reason, lastAttemptAtEpochMs = System.currentTimeMillis()))
                    return SyncRunResult(processed = processed, stoppedByNetwork = true)
                }
            }
        }
        // A fila esvaziou por completo (nunca parou por rede) — momento em
        // que o terminal está comprovadamente em dia com o servidor.
        syncStatusStore.markSynced()
        return SyncRunResult(processed = processed, stoppedByNetwork = false)
    }

    private sealed class StepOutcome {
        data object Success : StepOutcome()
        data class Retry(val reason: String) : StepOutcome()
        data class Rejected(val reason: String) : StepOutcome()
    }

    private suspend fun runOperation(operation: OutboxEntity): StepOutcome = try {
        when (operation.type) {
            OutboxOperationType.CREATE_TAB -> {
                val request = json.decodeFromString<CreateTabRequest>(operation.payloadJson)
                val tabLocal = tabDao.getTabByLocalId(operation.tabLocalId)
                if (tabLocal == null) {
                    StepOutcome.Rejected("Comanda local não encontrada — descartando criação órfã.")
                } else if (tabLocal.serverId != null) {
                    StepOutcome.Success // já sincronizada por outro caminho (ex.: getTab manual) — idempotente por natureza
                } else {
                    val response = api.createTab(operation.organizationId, tabLocal.locationId, request)
                    tabDao.updateTab(
                        tabLocal.copy(
                            serverId = response.id, publicCode = response.publicCode, syncState = SyncState.SYNCED,
                            lastSyncedAtEpochMs = System.currentTimeMillis(), subtotalCents = response.subtotalCents,
                            totalCents = response.totalCents, paidCents = response.paidCents, remainingCents = response.remainingCents,
                            openedAt = response.openedAt,
                        ),
                    )
                    StepOutcome.Success
                }
            }
            OutboxOperationType.SEND_ORDER -> {
                val request = json.decodeFromString<CreateOrderRequest>(operation.payloadJson)
                val serverId = requireTabServerId(operation.tabLocalId)
                if (serverId == null) {
                    StepOutcome.Retry("Comanda ainda não sincronizada")
                } else {
                    val order = api.createOrder(operation.organizationId, serverId, request)
                    api.sendOrder(operation.organizationId, order.id)
                    refreshTabSnapshot(operation.organizationId, operation.tabLocalId, serverId)
                    StepOutcome.Success
                }
            }
            OutboxOperationType.REGISTER_PAYMENT -> {
                val request = json.decodeFromString<CreatePaymentRequest>(operation.payloadJson)
                val serverId = requireTabServerId(operation.tabLocalId)
                if (serverId == null) {
                    StepOutcome.Retry("Comanda ainda não sincronizada")
                } else {
                    api.createPayment(operation.organizationId, serverId, request)
                    refreshTabSnapshot(operation.organizationId, operation.tabLocalId, serverId)
                    StepOutcome.Success
                }
            }
            OutboxOperationType.CANCEL_ITEM, OutboxOperationType.CLOSE_TAB, OutboxOperationType.ADD_ITEM -> {
                // Reservado para extensão futura — hoje estas operações são
                // sempre síncronas (ver TabRepository.cancelItem/closeTab),
                // nunca enfileiradas. Se aparecerem aqui, é um bug de quem
                // enfileirou: melhor rejeitar e expor do que travar a fila.
                StepOutcome.Rejected("Tipo de operação não processado pelo SyncEngine: ${operation.type}")
            }
        }
    } catch (e: IOException) {
        StepOutcome.Retry(e.message ?: "Sem conexão")
    } catch (e: HttpException) {
        if (e.code() in 400..499) StepOutcome.Rejected(e.message() ?: "Operação recusada pelo servidor (${e.code()})")
        else StepOutcome.Retry("Servidor indisponível (${e.code()})")
    } catch (e: Exception) {
        StepOutcome.Retry(e.message ?: "Falha ao sincronizar")
    }

    private suspend fun requireTabServerId(tabLocalId: String): Long? = tabDao.getTabByLocalId(tabLocalId)?.serverId

    private suspend fun refreshTabSnapshot(organizationId: Long, tabLocalId: String, serverId: Long) {
        val response = api.getTab(organizationId, serverId)
        val local = tabDao.getTabByLocalId(tabLocalId) ?: return
        tabDao.updateTab(
            local.copy(
                publicCode = response.publicCode, subtotalCents = response.subtotalCents, discountCents = response.discountCents,
                serviceChargeCents = response.serviceChargeCents, totalCents = response.totalCents, paidCents = response.paidCents,
                remainingCents = response.remainingCents, syncState = SyncState.SYNCED, lastSyncedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        tabDao.deleteItemsForTab(tabLocalId)
        tabDao.deletePaymentsForTab(tabLocalId)
        response.orders.forEach { order ->
            val orderLocalId = java.util.UUID.randomUUID().toString()
            tabDao.upsertOrder(com.nokta.pos.data.local.entity.TabOrderEntity(localId = orderLocalId, serverId = order.id, tabLocalId = tabLocalId, status = order.status, syncState = SyncState.SYNCED, createdAtEpochMs = System.currentTimeMillis()))
            tabDao.upsertItems(
                order.items.map { item ->
                    com.nokta.pos.data.local.entity.TabItemEntity(
                        localId = java.util.UUID.randomUUID().toString(), serverId = item.id, tabLocalId = tabLocalId, orderLocalId = orderLocalId,
                        menuItemId = item.productId, variantId = item.variantId, productName = item.productNameSnapshot, variantName = item.variantNameSnapshot,
                        quantity = item.quantity, unitPriceCents = item.unitPriceCents, modifiersTotalCents = item.modifiersTotalCents, lineTotalCents = item.lineTotalCents,
                        status = item.status, notes = item.notes,
                        modifiersJson = json.encodeToString(item.modifiers.map { m -> com.nokta.pos.data.local.entity.PersistedModifier(name = m.optionNameSnapshot ?: "Adicional", quantity = m.quantity, totalCents = m.totalPriceCents) }),
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                },
            )
        }
        tabDao.upsertPayments(
            response.payments.map { p ->
                com.nokta.pos.data.local.entity.TabPaymentEntity(
                    localId = java.util.UUID.randomUUID().toString(), serverId = p.id, tabLocalId = tabLocalId, method = p.method,
                    amountCents = p.amountCents, receivedCents = p.receivedCents, changeCents = p.changeCents,
                    isCanceled = p.status == "CANCELED", externalReference = p.externalReference, confirmedAt = p.confirmedAt,
                    syncState = SyncState.SYNCED, createdAtEpochMs = System.currentTimeMillis(),
                )
            },
        )
    }
}

data class SyncRunResult(val processed: Int, val stoppedByNetwork: Boolean)

sealed class SyncEvent {
    data class OperationSynced(val type: OutboxOperationType) : SyncEvent()
    data class OperationRejected(val type: OutboxOperationType, val reason: String) : SyncEvent()
}
