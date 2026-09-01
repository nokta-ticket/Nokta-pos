package com.nokta.pos.sync

import com.nokta.pos.common.Money
import com.nokta.pos.data.local.dao.OutboxDao
import com.nokta.pos.data.local.dao.TabDao
import com.nokta.pos.data.local.entity.OutboxEntity
import com.nokta.pos.data.local.entity.OutboxOperationType
import com.nokta.pos.data.local.entity.OutboxStatus
import com.nokta.pos.data.local.entity.SyncState
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.CancelItemOutboxPayload
import com.nokta.pos.network.dto.CancelOrderItemRequest
import com.nokta.pos.network.dto.CreateOrderRequest
import com.nokta.pos.network.dto.CreatePaymentRequest
import com.nokta.pos.network.dto.CreateTabRequest
import com.nokta.pos.network.ITEM_ALREADY_CANCELED_MESSAGE
import com.nokta.pos.network.ORDER_ALREADY_SENT_MESSAGE
import com.nokta.pos.network.humanizedApiMessage
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

                    // Pedido recusado pelo servidor nunca vai existir — sem
                    // apagar o rascunho local, o item ficava para sempre na
                    // comanda como "Enviado" de R$ 0,00, e o operador continuava
                    // vendo (e servindo) um consumo que o sistema recusou e
                    // ninguém iria cobrar. Só SEND_ORDER: CREATE_TAB é tratado
                    // pela cascata abaixo, e pagamento recusado nunca deve
                    // sumir da tela sem alguém decidir o que fazer com o
                    // dinheiro.
                    if (operation.type == OutboxOperationType.SEND_ORDER) {
                        tabDao.discardLocalOrder(operation.operationId)
                    }

                    // Sem serverId, toda operação restante desta comanda
                    // (SEND_ORDER/REGISTER_PAYMENT, que dependem do serverId
                    // que só o CREATE_TAB cria) nunca teria como ser aceita —
                    // sem esta cascata elas ficam retentando para sempre,
                    // bloqueando inclusive a fila inteira (a ordem por
                    // `sequence` nunca deixa nada depois delas ser
                    // processado). Cobre tanto o CREATE_TAB rejeitado agora
                    // quanto uma comanda que já ficou órfã antes desta
                    // cascata existir.
                    if (tabDao.getTabByLocalId(operation.tabLocalId)?.serverId == null) {
                        outboxDao.rejectAllForTab(operation.tabLocalId, "Comanda não sincronizada: ${outcome.reason}")
                        tabDao.markTabFailed(operation.tabLocalId)
                    }
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
                            serviceChargeCents = response.serviceChargeCents, serviceChargeRateBps = response.serviceChargeRateBps,
                            totalCents = response.totalCents, paidCents = response.paidCents, remainingCents = response.remainingCents,
                            openedAt = response.openedAt,
                        ),
                    )
                    StepOutcome.Success
                }
            }
            OutboxOperationType.SEND_ORDER -> {
                val request = json.decodeFromString<CreateOrderRequest>(operation.payloadJson)
                when (val serverId = resolveTabServerIdOrWaitReason(operation.tabLocalId)) {
                    is TabServerIdLookup.Resolved -> {
                        // `createOrder` é idempotente por `clientRequestId` (o
                        // backend devolve o pedido já existente em retry), mas
                        // `sendOrder` não é: um pedido fora de DRAFT sempre
                        // recusa com 400 "Este pedido já foi enviado.". Isso
                        // acontece quando a 1ª tentativa enviou com sucesso no
                        // servidor mas a resposta se perdeu antes de chegar
                        // aqui (timeout/queda no meio) — o app achou que
                        // falhou e reenfileirou o mesmo SEND_ORDER. Tratar
                        // esse 400 específico como sucesso é o que garante a
                        // idempotência de ponta a ponta prometida no
                        // cabeçalho deste arquivo.
                        val order = api.createOrder(operation.organizationId, serverId.value, request)
                        try {
                            api.sendOrder(operation.organizationId, order.id)
                        } catch (e: HttpException) {
                            if (e.code() !in 400..499 || e.humanizedApiMessage() != ORDER_ALREADY_SENT_MESSAGE) throw e
                        }
                        refreshTabSnapshot(operation.organizationId, operation.tabLocalId, serverId.value)
                        StepOutcome.Success
                    }
                    TabServerIdLookup.StillWaiting -> StepOutcome.Retry("Comanda ainda não sincronizada")
                    TabServerIdLookup.Orphaned -> StepOutcome.Rejected("Comanda nunca foi criada no servidor")
                }
            }
            OutboxOperationType.REGISTER_PAYMENT -> {
                val request = json.decodeFromString<CreatePaymentRequest>(operation.payloadJson)
                when (val serverId = resolveTabServerIdOrWaitReason(operation.tabLocalId)) {
                    is TabServerIdLookup.Resolved -> {
                        api.createPayment(operation.organizationId, serverId.value, request)
                        refreshTabSnapshot(operation.organizationId, operation.tabLocalId, serverId.value)
                        StepOutcome.Success
                    }
                    TabServerIdLookup.StillWaiting -> StepOutcome.Retry("Comanda ainda não sincronizada")
                    TabServerIdLookup.Orphaned -> StepOutcome.Rejected("Comanda nunca foi criada no servidor")
                }
            }
            OutboxOperationType.CANCEL_ITEM -> {
                val payload = json.decodeFromString<CancelItemOutboxPayload>(operation.payloadJson)
                try {
                    api.cancelOrderItem(operation.organizationId, payload.itemServerId, CancelOrderItemRequest(payload.reason))
                } catch (e: HttpException) {
                    // Mesmo princípio de SEND_ORDER acima: um retry cuja 1ª
                    // tentativa já teve sucesso no servidor (resposta perdida
                    // por timeout/queda) recebe "já está cancelado" — trata
                    // como sucesso, nunca como falha visível ao operador.
                    if (e.code() !in 400..499 || e.humanizedApiMessage() != ITEM_ALREADY_CANCELED_MESSAGE) throw e
                }
                tabDao.getItemByServerId(payload.itemServerId)?.let { local ->
                    tabDao.updateItem(local.copy(status = "CANCELED"))
                }
                tabDao.getTabByLocalId(operation.tabLocalId)?.serverId?.let { serverId ->
                    refreshTabSnapshot(operation.organizationId, operation.tabLocalId, serverId)
                }
                StepOutcome.Success
            }
            // CLOSE_TAB permanece deliberadamente SÍNCRONO, para sempre — não é
            // "reservado para o futuro". Fechar uma comanda depende do estado
            // financeiro ATUAL no servidor (outro terminal pode ter lançado
            // item ou registrado pagamento nesse meio tempo); enfileirar "feche
            // isso" para rodar quando a rede voltar arriscaria fechar com um
            // total que já não é mais real. Diferente de SEND_ORDER/
            // CANCEL_ITEM/REGISTER_PAYMENT (fatos já consumados, imutáveis),
            // fechar é uma decisão que precisa ler o servidor antes de agir —
            // por isso TabRepository.closeTab sempre exige rede.
            OutboxOperationType.CLOSE_TAB -> {
                StepOutcome.Rejected("Fechamento de comanda nunca é enfileirado — exige rede no momento da ação.")
            }
            OutboxOperationType.ADD_ITEM -> {
                // Valor morto do enum: o fluxo real de "adicionar item" usa
                // SEND_ORDER (criar pedido + enviar), nunca ADD_ITEM. Rejeita
                // se aparecer — sinal de bug em quem enfileirou.
                StepOutcome.Rejected("Tipo de operação não processado pelo SyncEngine: ${operation.type}")
            }
        }
    } catch (e: IOException) {
        StepOutcome.Retry(e.message ?: "Sem conexão")
    } catch (e: HttpException) {
        if (e.code() in 400..499) StepOutcome.Rejected(e.humanizedApiMessage("Operação recusada pelo servidor (${e.code()})"))
        else StepOutcome.Retry("Servidor indisponível (${e.code()})")
    } catch (e: Exception) {
        StepOutcome.Retry(e.message ?: "Falha ao sincronizar")
    }

    private sealed class TabServerIdLookup {
        data class Resolved(val value: Long) : TabServerIdLookup()
        /** CREATE_TAB desta comanda ainda está pendente na fila — vale a pena esperar. */
        data object StillWaiting : TabServerIdLookup()
        /**
         * Sem `serverId` E sem nenhum CREATE_TAB pendente para esta comanda:
         * ele já foi processado (com sucesso, o que teria gravado o
         * `serverId` — ou rejeitado, cuja cascata normalmente já teria
         * rejeitado esta operação também). Isto cobre o caso de dado
         * corrompido por uma versão anterior do app, sem a cascata em
         * [OutboxDao.rejectAllForTab]: nunca há mais nada a esperar.
         */
        data object Orphaned : TabServerIdLookup()
    }

    private suspend fun resolveTabServerIdOrWaitReason(tabLocalId: String): TabServerIdLookup {
        tabDao.getTabByLocalId(tabLocalId)?.serverId?.let { return TabServerIdLookup.Resolved(it) }
        val hasPendingCreateTab = outboxDao.getPendingForTab(tabLocalId).any { it.type == OutboxOperationType.CREATE_TAB }
        return if (hasPendingCreateTab) TabServerIdLookup.StillWaiting else TabServerIdLookup.Orphaned
    }

    private suspend fun refreshTabSnapshot(organizationId: Long, tabLocalId: String, serverId: Long) {
        val response = api.getTab(organizationId, serverId)
        val local = tabDao.getTabByLocalId(tabLocalId) ?: return
        tabDao.updateTab(
            local.copy(
                publicCode = response.publicCode, subtotalCents = response.subtotalCents, discountCents = response.discountCents,
                serviceChargeCents = response.serviceChargeCents, serviceChargeRateBps = response.serviceChargeRateBps,
                totalCents = response.totalCents, paidCents = response.paidCents,
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
