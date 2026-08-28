package com.nokta.pos.comanda.data

import com.nokta.pos.comanda.domain.LocalSyncState
import com.nokta.pos.comanda.domain.OrderLine
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabItem
import com.nokta.pos.comanda.domain.TabItemModifier
import com.nokta.pos.data.local.entity.PersistedModifier
import com.nokta.pos.comanda.domain.TabPayment
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.comanda.domain.VenueTable
import com.nokta.pos.comanda.domain.negativeIdFromLocalId
import com.nokta.pos.common.Money
import com.nokta.pos.data.local.dao.OutboxDao
import com.nokta.pos.data.local.dao.TabDao
import com.nokta.pos.data.local.entity.OutboxEntity
import com.nokta.pos.data.local.entity.OutboxOperationType
import com.nokta.pos.data.local.entity.OutboxStatus
import com.nokta.pos.data.local.entity.SyncState
import com.nokta.pos.data.local.entity.TabEntity
import com.nokta.pos.data.local.entity.TabItemEntity
import com.nokta.pos.data.local.entity.TabOrderEntity
import com.nokta.pos.data.local.entity.TabPaymentEntity
import com.nokta.pos.data.local.entity.TabWithItemsAndPayments
import com.nokta.pos.data.local.entity.VenueTableEntity
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.ORDER_ALREADY_SENT_MESSAGE
import com.nokta.pos.network.dto.CreateOrderItemRequest
import com.nokta.pos.network.dto.CreateOrderRequest
import com.nokta.pos.network.dto.CreatePaymentRequest
import com.nokta.pos.network.dto.CreateTabRequest
import com.nokta.pos.network.dto.OrderItemModifierRequest
import com.nokta.pos.network.dto.TabResponse
import com.nokta.pos.network.dto.TableResponse
import com.nokta.pos.network.humanizedApiMessage
import com.nokta.pos.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte única de verdade da UI para comandas/mesas — substitui o antigo
 * `OperationRepository`, que falava só com a API (nada funcionava sem rede).
 *
 * Regra central do offline-first: TODA leitura que a UI observa vem do Room;
 * TODA escrita grava no Room IMEDIATAMENTE (a UI nunca espera a rede) e, em
 * paralelo, é ou enviada na hora (se há conexão) ou enfileirada no Outbox
 * (`SyncEngine` drena depois, sozinho, quando a rede voltar — nunca exige
 * tela ou botão específico).
 *
 * IDs: `Tab.id`/`TabItem.id` continuam `Long`/`String` estáveis para não
 * reescrever rotas Compose — ver o comentário em cima de `Tab` em
 * ComandaModels.kt para o motivo de existir `negativeLocalId`.
 */
@Singleton
class TabRepository @Inject constructor(
    private val api: NoktaApi,
    private val tabDao: TabDao,
    private val outboxDao: OutboxDao,
    private val syncEngine: SyncEngine,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------
    // Leitura — sempre do Room, nunca bloqueada por rede.
    // ---------------------------------------------------------------------

    companion object {
        /** Mesma lista de OCCUPYING_TAB_STATUSES no backend — mesa/comanda ainda em atendimento, mesmo já em processo de fechamento. */
        private const val OCCUPYING_STATUSES_QUERY = "OPEN,CLOSING,PAYMENT_IN_PROGRESS"
    }

    /** A tela de comanda observa isto continuamente. */
    fun observeTab(localId: String): Flow<Tab?> =
        tabDao.observeTabWithDetails(localId).map { it?.toDomain() }

    suspend fun getCachedTab(localId: String): Tab? = tabDao.getTabWithDetails(localId)?.toDomain()

    /**
     * Resolve o `localId` a partir de um `Tab.id` (Long — o mesmo formato
     * gravado em [com.nokta.pos.payment.cielo.PendingCieloAttempt.tabId]):
     * se positivo, é um `serverId` de verdade; se negativo, é o
     * `negativeLocalId` derivado de uma comanda aberta offline por ESTE
     * terminal — sempre já presente no Room, nunca precisa de rede para
     * resolver um id que ele mesmo gerou. O volume de comandas SEM
     * `serverId` num terminal é sempre pequeno (o caso é raro por natureza:
     * abriu offline e ainda não sincronizou), então a varredura completa é
     * barata.
     */
    suspend fun localIdForTabId(organizationId: Long, tabId: Long): String? {
        if (tabId >= 0) return tabDao.getTabByServerId(tabId)?.localId
        return tabDao.getUnsyncedTabsForOrganization(organizationId).firstOrNull { negativeIdFromLocalId(it.localId) == tabId }?.localId
    }

    /**
     * Resolve o `localId` de uma comanda já conhecida pelo `serverId` — usado
     * quando a UI só tem o id de servidor à mão (ex.: `VenueTable.openTabId`,
     * que vem direto do backend) e precisa navegar/observar via `localId`.
     * Se este terminal nunca viu esta comanda, grava um registro mínimo
     * `SYNCED` para que a navegação funcione mesmo sem passar por
     * `getTab`/`searchOpenTabs` antes.
     */
    suspend fun localIdForServerId(organizationId: Long, locationId: Long, serverId: Long): String {
        tabDao.getTabByServerId(serverId)?.let { return it.localId }
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        tabDao.upsertTab(
            TabEntity(
                localId = localId, serverId = serverId, organizationId = organizationId, locationId = locationId,
                publicCode = null, type = TabType.TABLE.name, status = TabStatus.OPEN.name,
                customerName = null, customerPhone = null, tableServerId = null, tableName = null, guestCount = null,
                subtotalCents = 0, discountCents = 0, serviceChargeCents = 0, totalCents = 0, paidCents = 0, remainingCents = 0,
                openedAt = null, syncState = SyncState.SYNCED, lastSyncedAtEpochMs = now, createdAtEpochMs = now,
            ),
        )
        return localId
    }

    suspend fun getTabByPublicCode(organizationId: Long, locationId: Long, publicCode: String): Tab {
        val trimmed = publicCode.trim()
        return try {
            val response = api.getTabByPublicCode(organizationId, locationId, trimmed)
            writeTabFromServer(response).toDomain()
        } catch (e: IOException) {
            tabDao.searchOpenTabsLocal(organizationId, locationId, trimmed).firstOrNull()?.let {
                tabDao.getTabWithDetails(it.localId)?.toDomain()
            } ?: throw e
        }
    }

    /**
     * Comandas abertas: tenta a rede (e sincroniza o snapshot local, exceto
     * comandas PENDING que este terminal ainda não confirmou — ver
     * `TabDao.refreshOpenTabsSnapshot`), mas nunca deixa a busca vazia só
     * porque a rede falhou.
     */
    suspend fun searchOpenTabs(organizationId: Long, locationId: Long, search: String? = null, type: TabType? = null): List<Tab> {
        try {
            // OCCUPYING_STATUSES (não só OPEN): comandas em CLOSING/
            // PAYMENT_IN_PROGRESS ainda estão sendo atendidas — nunca devem
            // desaparecer da busca só porque o garçom já tocou "fechar a conta".
            val response = api.listTabs(organizationId, locationId, status = OCCUPYING_STATUSES_QUERY, type = type?.name, search = search?.trim()?.takeIf { it.isNotEmpty() })
            // toEntity() devolve localId="" de propósito — aqui é o "caller"
            // que o comentário da função promete: reaproveita o localId já
            // conhecido desta comanda (por serverId) para não perder o
            // vínculo com itens/pagamentos já gravados localmente, e só gera
            // um novo UUID na primeira vez que este dispositivo a vê. Sem
            // isso, toda comanda vinda só da busca ficava com localId vazio
            // e travava ao abrir (rota "comanda/" sem argumento).
            val entities = response.map { dto ->
                // Não usar ?: puro aqui: um registro corrompido de antes desta
                // correção (esta era exatamente a causa raiz do bug) tem
                // localId="" salvo, que não é null e passaria direto.
                val existingLocalId = tabDao.getTabByServerId(dto.id)?.localId?.takeIf { it.isNotBlank() }
                dto.toEntity().copy(localId = existingLocalId ?: UUID.randomUUID().toString())
            }
            tabDao.refreshOpenTabsSnapshot(organizationId, locationId, entities)
        } catch (_: IOException) {
            // Sem rede: segue só com o que o Room já tem.
        }
        return tabDao.searchOpenTabsLocal(organizationId, locationId, search?.trim()?.takeIf { it.isNotEmpty() })
            .let { local -> if (type == null) local else local.filter { it.type == type.name } }
            .map { entity -> tabDao.getTabWithDetails(entity.localId)?.toDomain() ?: entity.toDomainShallow() }
    }

    suspend fun listRecentClosedTabs(organizationId: Long, locationId: Long, limit: Int = 20): List<Tab> {
        try {
            val response = api.listTabs(organizationId, locationId, status = TabStatus.CLOSED.name).take(limit)
            // Mesmo motivo do localId em searchOpenTabs acima: toEntity()
            // nunca preenche localId sozinho.
            val entities = response.map { dto ->
                // Não usar ?: puro aqui: um registro corrompido de antes desta
                // correção (esta era exatamente a causa raiz do bug) tem
                // localId="" salvo, que não é null e passaria direto.
                val existingLocalId = tabDao.getTabByServerId(dto.id)?.localId?.takeIf { it.isNotBlank() }
                dto.toEntity().copy(localId = existingLocalId ?: UUID.randomUUID().toString())
            }
            tabDao.upsertClosedTabsSnapshot(entities)
        } catch (_: IOException) {
            // Segue com o histórico local.
        }
        return tabDao.getRecentClosedTabsLocal(organizationId, locationId, limit).map { it.toDomainShallow() }
    }

    /**
     * Se há caixa aberto na unidade agora. Sem cache local de propósito — é
     * um estado que muda a qualquer momento (o gerente pode fechar o caixa
     * no meio do turno) e a única ação que depende disso (cobrar) já revalida
     * contra o servidor de qualquer forma; um valor "aberto" salvo offline
     * seria enganoso assim que a rede caísse. `null` = não foi possível
     * consultar agora (sem rede, ou erro) — a Home trata isso como "não avisa
     * nada", nunca como "está aberto" nem "está fechado".
     */
    suspend fun isCashOpen(organizationId: Long, locationId: Long): Boolean? =
        runCatching { api.getCashStatus(organizationId, locationId).isOpen }.getOrNull()

    /** Mesas: cache read-through — mostra o último dado conhecido com aviso de idade se offline. */
    fun observeTables(organizationId: Long, locationId: Long): Flow<List<VenueTable>> =
        tabDao.observeTables(organizationId, locationId).map { list -> list.map { it.toDomain() } }

    suspend fun refreshTables(organizationId: Long, locationId: Long) {
        try {
            val response = api.listTables(organizationId, locationId)
            tabDao.replaceTablesSnapshot(organizationId, locationId, response.map { it.toEntity(organizationId, locationId) })
        } catch (_: IOException) {
            // Mantém o snapshot anterior — a UI já mostra "atualizado há Xmin" pelo fetchedAtEpochMs.
        }
    }

    suspend fun tablesFetchedAt(organizationId: Long, locationId: Long): Long? = tabDao.getTablesFetchedAt(organizationId, locationId)

    fun observeOpenTabsCount(organizationId: Long, locationId: Long): Flow<Int> = tabDao.observeOpenTabsCount(organizationId, locationId)

    // ---------------------------------------------------------------------
    // Escrita — grava no Room primeiro, sempre; tenta a rede; enfileira se falhar.
    // ---------------------------------------------------------------------

    /**
     * Abre uma comanda. Nasce no Room IMEDIATAMENTE com `syncState = PENDING`
     * e um `Tab.id` negativo (derivado do `localId`) — a UI navega para ela
     * na hora, sem esperar resposta de rede nenhuma. Se a rede estiver
     * disponível, tenta confirmar em linha (troca para o `serverId` real,
     * assim a tela já abre "sincronizada"); se falhar por qualquer motivo de
     * rede, cai para o Outbox e o `SyncEngine` confirma depois — a UI não
     * percebe diferença nenhuma entre os dois casos além do indicador de
     * sincronização.
     */
    suspend fun openTab(
        organizationId: Long,
        locationId: Long,
        type: TabType,
        tableId: Long? = null,
        tableName: String? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        guestCount: Int? = null,
    ): Tab {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val draft = TabEntity(
            localId = localId,
            serverId = null,
            organizationId = organizationId,
            locationId = locationId,
            publicCode = null,
            type = type.name,
            status = TabStatus.OPEN.name,
            customerName = customerName,
            customerPhone = customerPhone,
            tableServerId = tableId,
            tableName = tableName,
            guestCount = guestCount,
            subtotalCents = 0, discountCents = 0, serviceChargeCents = 0,
            totalCents = 0, paidCents = 0, remainingCents = 0,
            openedAt = null,
            syncState = SyncState.PENDING,
            lastSyncedAtEpochMs = null,
            createdAtEpochMs = now,
        )
        tabDao.upsertTab(draft)

        val request = CreateTabRequest(
            type = type.name, tableId = tableId, tableName = tableName, customerName = customerName,
            customerPhone = customerPhone, guestCount = guestCount, clientRequestId = localId,
        )
        try {
            val response = api.createTab(organizationId, locationId, request)
            return writeTabFromServer(response, localOverride = localId).toDomain()
        } catch (e: IOException) {
            enqueueCreateTab(organizationId, localId, request)
            return tabDao.getTabWithDetails(localId)!!.toDomain()
        }
    }

    private suspend fun enqueueCreateTab(organizationId: Long, tabLocalId: String, request: CreateTabRequest) {
        outboxDao.enqueue(
            OutboxEntity(
                operationId = tabLocalId, // a própria comanda: 1 CREATE_TAB por comanda, nunca duplicado
                type = OutboxOperationType.CREATE_TAB,
                organizationId = organizationId,
                tabLocalId = tabLocalId,
                payloadJson = json.encodeToString(request),
                status = OutboxStatus.PENDING,
                retryCount = 0,
                lastError = null,
                createdAtEpochMs = System.currentTimeMillis(),
                lastAttemptAtEpochMs = null,
            ),
        )
        syncEngine.requestSync()
    }

    suspend fun getTab(organizationId: Long, localId: String): Tab {
        try {
            val local = tabDao.getTabByLocalId(localId)
            val serverId = local?.serverId
            if (serverId != null) {
                val response = api.getTab(organizationId, serverId)
                writeTabFromServer(response, localOverride = localId)
            }
        } catch (_: IOException) {
            // Sem rede: devolve o que o Room já tem.
        }
        return tabDao.getTabWithDetails(localId)?.toDomain()
            ?: throw IllegalStateException("Comanda não encontrada localmente: $localId")
    }

    /**
     * Lança itens numa comanda. Grava localmente na hora (status SENT
     * otimista — é assim que o operador vê "já foi") e tenta enviar; se
     * falhar por rede, cai no Outbox com o MESMO `clientRequestId` (o
     * `orderLocalId`) usado no request real, garantindo que o reenvio nunca
     * duplica no backend.
     *
     * `orderLocalId` é opcional: por padrão um UUID novo por chamada, mas um
     * caller que pode CHAMAR ESTA FUNÇÃO MAIS DE UMA VEZ PARA A MESMA
     * INTENÇÃO (ex.: `BalcaoViewModel.finalizeSale`, que pode ser reexecutado
     * num retry) deve passar seu próprio id estável — senão cada retry grava
     * um pedido local novo, duplicando itens no Room mesmo que o primeiro já
     * tenha sido aceito.
     */
    suspend fun submitOrder(organizationId: Long, tabLocalId: String, lines: List<OrderLine>, notes: String? = null, orderLocalId: String = UUID.randomUUID().toString()) {
        if (tabDao.getOrderByLocalId(orderLocalId) != null) return // já lançado nesta tentativa — retry idempotente
        val now = System.currentTimeMillis()
        tabDao.upsertOrder(TabOrderEntity(localId = orderLocalId, serverId = null, tabLocalId = tabLocalId, status = "SENT", syncState = SyncState.PENDING, createdAtEpochMs = now))
        tabDao.upsertItems(
            lines.map { line ->
                TabItemEntity(
                    localId = UUID.randomUUID().toString(),
                    serverId = null,
                    tabLocalId = tabLocalId,
                    orderLocalId = orderLocalId,
                    menuItemId = line.menuItemId,
                    variantId = line.variantId,
                    productName = "",
                    variantName = "",
                    quantity = line.quantity,
                    unitPriceCents = 0,
                    modifiersTotalCents = 0,
                    lineTotalCents = 0,
                    status = "SENT",
                    notes = line.notes,
                    modifiersJson = json.encodeToString(line.modifiers.map { PersistedModifier(name = "", quantity = it.quantity, totalCents = 0) }),
                    createdAtEpochMs = now,
                )
            },
        )

        val request = CreateOrderRequest(
            items = lines.map { line ->
                CreateOrderItemRequest(
                    menuItemId = line.menuItemId, variantId = line.variantId, quantity = line.quantity,
                    notes = line.notes, modifiers = line.modifiers.map { OrderItemModifierRequest(it.modifierGroupId, it.modifierOptionId, it.quantity) },
                )
            },
            clientRequestId = orderLocalId, notes = notes,
        )

        val serverId = tabDao.getTabByLocalId(tabLocalId)?.serverId
        if (serverId == null) {
            enqueueSubmitOrder(organizationId, tabLocalId, orderLocalId, request)
            return
        }
        try {
            val order = api.createOrder(organizationId, serverId, request)
            try {
                api.sendOrder(organizationId, order.id)
            } catch (e: HttpException) {
                // `createOrder` é idempotente (clientRequestId) e pode devolver
                // um pedido que uma tentativa anterior já enviou com sucesso —
                // reenviar recusa com 400 "já foi enviado", que aqui significa
                // "o pedido já está no estado certo", não uma falha real.
                if (e.code() !in 400..499 || e.humanizedApiMessage() != ORDER_ALREADY_SENT_MESSAGE) throw e
            }
            refreshFromServer(organizationId, tabLocalId, serverId)
        } catch (e: IOException) {
            enqueueSubmitOrder(organizationId, tabLocalId, orderLocalId, request)
        }
    }

    private suspend fun enqueueSubmitOrder(organizationId: Long, tabLocalId: String, orderLocalId: String, request: CreateOrderRequest) {
        outboxDao.enqueue(
            OutboxEntity(
                operationId = orderLocalId,
                type = OutboxOperationType.SEND_ORDER,
                organizationId = organizationId,
                tabLocalId = tabLocalId,
                payloadJson = json.encodeToString(request),
                status = OutboxStatus.PENDING,
                retryCount = 0, lastError = null,
                createdAtEpochMs = System.currentTimeMillis(), lastAttemptAtEpochMs = null,
            ),
        )
        syncEngine.requestSync()
    }

    /**
     * Cancela um item. Exige que o item já tenha `serverId` (o backend é
     * quem detém a ledger de auditoria) — um item ainda PENDING (lançado
     * offline, nunca confirmado) é removido localmente em vez de "cancelado",
     * porque não existe registro nenhum no servidor para auditar ainda.
     */
    suspend fun cancelItem(organizationId: Long, itemLocalId: String, reason: String): CancelItemOutcome {
        val item = tabDao.getItemByLocalId(itemLocalId) ?: return CancelItemOutcome.NotFound
        val serverId = item.serverId
        if (serverId == null) {
            // BUG anterior: usava deleteItemsForTab(tabLocalId), que apaga TODOS
            // os itens da comanda — inofensivo só quando havia 1 item só. Com 2+
            // itens (alguns já confirmados, um ainda rascunho), removeria os
            // confirmados da visão local também, dessincronizando do servidor.
            tabDao.deleteItemByLocalId(itemLocalId)
            return CancelItemOutcome.RemovedLocalDraft
        }
        return try {
            api.cancelOrderItem(organizationId, serverId, com.nokta.pos.network.dto.CancelOrderItemRequest(reason))
            tabDao.updateItem(item.copy(status = "CANCELED"))
            CancelItemOutcome.Success
        } catch (e: IOException) {
            // Cancelar é um FATO já decidido pelo operador (diferente de
            // fechar comanda, que depende de ler o estado atual do servidor)
            // — grava otimista local e enfileira, mesmo padrão de submitOrder.
            tabDao.updateItem(item.copy(status = "CANCELED"))
            enqueueCancelItem(organizationId, item.tabLocalId, serverId, reason)
            CancelItemOutcome.QueuedOffline
        }
    }

    private suspend fun enqueueCancelItem(organizationId: Long, tabLocalId: String, itemServerId: Long, reason: String) {
        outboxDao.enqueue(
            OutboxEntity(
                operationId = UUID.randomUUID().toString(),
                type = OutboxOperationType.CANCEL_ITEM,
                organizationId = organizationId,
                tabLocalId = tabLocalId,
                payloadJson = json.encodeToString(com.nokta.pos.network.dto.CancelItemOutboxPayload(itemServerId, reason)),
                status = OutboxStatus.PENDING,
                retryCount = 0, lastError = null,
                createdAtEpochMs = System.currentTimeMillis(), lastAttemptAtEpochMs = null,
            ),
        )
        syncEngine.requestSync()
    }

    /**
     * Lança mais 1 unidade do mesmo produto/variante como um pedido novo —
     * nunca "soma" na linha existente (o backend não tem operação de somar
     * quantidade numa linha já lançada). Reaproveita o mesmo caminho de
     * [submitOrder] usado pelo cardápio, então entra no Outbox normalmente
     * se estiver offline.
     */
    suspend fun increaseItemQuantity(organizationId: Long, item: com.nokta.pos.comanda.domain.TabItem) {
        val tabLocalId = tabDao.getItemByLocalId(item.localId)?.tabLocalId ?: return
        submitOrder(
            organizationId = organizationId,
            tabLocalId = tabLocalId,
            lines = listOf(OrderLine(menuItemId = item.menuItemId, variantId = item.variantId, quantity = 1, notes = item.notes)),
        )
    }

    /**
     * Diminui 1 unidade de uma linha já lançada. Não existe "editar
     * quantidade" no backend — a única forma de reduzir é CANCELAR a linha
     * inteira (auditoria exige motivo) e, se sobrar quantidade, relançar um
     * pedido novo com `quantity - 1`. Do ponto de vista do operador é só o
     * número descendo; por baixo fica um cancelamento + um novo lançamento.
     */
    suspend fun decreaseItemQuantity(organizationId: Long, item: com.nokta.pos.comanda.domain.TabItem): CancelItemOutcome {
        val entity = tabDao.getItemByLocalId(item.localId) ?: return CancelItemOutcome.NotFound
        val outcome = cancelItem(organizationId, item.localId, reason = "Ajuste de quantidade pelo operador")
        if (outcome == CancelItemOutcome.NotFound) return outcome

        // Success, RemovedLocalDraft e QueuedOffline liberam o relançamento —
        // mesmo enfileirado (offline), o CANCEL_ITEM sempre sincroniza antes
        // do SEND_ORDER abaixo (fila FIFO por sequence), nunca invertendo a
        // ordem no servidor.
        val remaining = item.quantity - 1
        if (remaining > 0) {
            submitOrder(
                organizationId = organizationId,
                tabLocalId = entity.tabLocalId,
                lines = listOf(OrderLine(menuItemId = item.menuItemId, variantId = item.variantId, quantity = remaining, notes = item.notes)),
            )
        }
        return outcome
    }

    /**
     * Fechamento DEFINITIVO — sempre síncrono, exige rede. Nunca enfileirado
     * (ver SyncEngine.CLOSE_TAB): depende do total OFICIAL no servidor no
     * instante da chamada, que pode ter mudado por outro terminal.
     */
    suspend fun closeTab(organizationId: Long, tabLocalId: String): Tab {
        val serverId = tabDao.getTabByLocalId(tabLocalId)?.serverId
            ?: throw IllegalStateException("Comanda ainda não sincronizada — aguarde a conexão para fechar.")
        val response = api.closeTab(organizationId, serverId)
        return writeTabFromServer(response, localOverride = tabLocalId).toDomain()
    }

    /** Início do fechamento explícito ("pedir a conta") — OPEN -> CLOSING. Mesma exigência de rede síncrona de closeTab. */
    suspend fun requestCloseTab(organizationId: Long, tabLocalId: String): Tab {
        val serverId = tabDao.getTabByLocalId(tabLocalId)?.serverId
            ?: throw IllegalStateException("Comanda ainda não sincronizada — aguarde a conexão para fechar.")
        val response = api.requestCloseTab(organizationId, serverId)
        return writeTabFromServer(response, localOverride = tabLocalId).toDomain()
    }

    /** Desfaz requestCloseTab() — CLOSING -> OPEN. */
    suspend fun cancelCloseTab(organizationId: Long, tabLocalId: String): Tab {
        val serverId = tabDao.getTabByLocalId(tabLocalId)?.serverId
            ?: throw IllegalStateException("Comanda ainda não sincronizada.")
        val response = api.cancelCloseTab(organizationId, serverId)
        return writeTabFromServer(response, localOverride = tabLocalId).toDomain()
    }

    /**
     * Pagamento em DINHEIRO: fato consumado fisicamente, entra no Outbox se
     * offline (ver OutboxModels.kt para a distinção completa de política por
     * método). Pagamento em CARTÃO nunca passa por aqui offline — só depois
     * de aprovado pela adquirente, com o mesmo raciocínio.
     */
    suspend fun registerPayment(
        organizationId: Long,
        tabLocalId: String,
        method: String,
        amount: Money,
        idempotencyKey: String = UUID.randomUUID().toString(),
        receivedCents: Long? = null,
        externalReference: String? = null,
    ): Tab {
        val now = System.currentTimeMillis()
        tabDao.upsertPayment(
            TabPaymentEntity(
                localId = idempotencyKey, serverId = null, tabLocalId = tabLocalId, method = method,
                amountCents = amount.cents, receivedCents = receivedCents, changeCents = receivedCents?.let { (it - amount.cents).coerceAtLeast(0) },
                isCanceled = false, externalReference = externalReference, confirmedAt = null,
                syncState = SyncState.PENDING, createdAtEpochMs = now,
            ),
        )

        val request = CreatePaymentRequest(method = method, amountCents = amount.cents, receivedCents = receivedCents, idempotencyKey = idempotencyKey, externalReference = externalReference)
        val serverId = tabDao.getTabByLocalId(tabLocalId)?.serverId
        if (serverId == null) {
            enqueueRegisterPayment(organizationId, tabLocalId, idempotencyKey, request)
            return tabDao.getTabWithDetails(tabLocalId)!!.toDomain()
        }
        try {
            api.createPayment(organizationId, serverId, request)
            return refreshFromServer(organizationId, tabLocalId, serverId)
        } catch (e: IOException) {
            enqueueRegisterPayment(organizationId, tabLocalId, idempotencyKey, request)
            return tabDao.getTabWithDetails(tabLocalId)!!.toDomain()
        }
    }

    private suspend fun enqueueRegisterPayment(organizationId: Long, tabLocalId: String, idempotencyKey: String, request: CreatePaymentRequest) {
        outboxDao.enqueue(
            OutboxEntity(
                operationId = idempotencyKey,
                type = OutboxOperationType.REGISTER_PAYMENT,
                organizationId = organizationId,
                tabLocalId = tabLocalId,
                payloadJson = json.encodeToString(request),
                status = OutboxStatus.PENDING,
                retryCount = 0, lastError = null,
                createdAtEpochMs = System.currentTimeMillis(), lastAttemptAtEpochMs = null,
            ),
        )
        syncEngine.requestSync()
    }

    // ---------------------------------------------------------------------
    // Internos
    // ---------------------------------------------------------------------

    private suspend fun refreshFromServer(organizationId: Long, tabLocalId: String, serverId: Long): Tab {
        val response = api.getTab(organizationId, serverId)
        return writeTabFromServer(response, localOverride = tabLocalId).toDomain()
    }

    /**
     * Grava a resposta do servidor por cima do rascunho local. `localOverride`
     * é usado quando SABEMOS qual rascunho local esta resposta confirma (ex.:
     * acabamos de criar); sem ele, procura por `serverId` (uma comanda já
     * conhecida sendo relida).
     */
    private suspend fun writeTabFromServer(response: TabResponse, localOverride: String? = null): TabWithItemsAndPayments {
        val localId = localOverride ?: tabDao.getTabByServerId(response.id)?.localId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val orders = mutableListOf<TabOrderEntity>()
        val items = mutableListOf<TabItemEntity>()
        response.orders.forEach { order ->
            val orderLocalId = UUID.randomUUID().toString()
            orders += TabOrderEntity(localId = orderLocalId, serverId = order.id, tabLocalId = localId, status = order.status, syncState = SyncState.SYNCED, createdAtEpochMs = now)
            items += order.items.map { item ->
                TabItemEntity(
                    localId = UUID.randomUUID().toString(), serverId = item.id, tabLocalId = localId, orderLocalId = orderLocalId,
                    menuItemId = item.productId, variantId = item.variantId, productName = item.productNameSnapshot, variantName = item.variantNameSnapshot,
                    quantity = item.quantity, unitPriceCents = item.unitPriceCents, modifiersTotalCents = item.modifiersTotalCents, lineTotalCents = item.lineTotalCents,
                    status = item.status, notes = item.notes,
                    modifiersJson = json.encodeToString(item.modifiers.map { m -> PersistedModifier(name = m.optionNameSnapshot ?: "Adicional", quantity = m.quantity, totalCents = m.totalPriceCents) }),
                    createdAtEpochMs = now,
                )
            }
        }
        val payments = response.payments.map { p ->
            TabPaymentEntity(
                localId = UUID.randomUUID().toString(), serverId = p.id, tabLocalId = localId, method = p.method,
                amountCents = p.amountCents, receivedCents = p.receivedCents, changeCents = p.changeCents,
                isCanceled = p.status == "CANCELED", externalReference = p.externalReference, confirmedAt = p.confirmedAt,
                syncState = SyncState.SYNCED, createdAtEpochMs = now,
            )
        }

        // Grava tudo (tab + pedidos + itens + pagamentos) numa única
        // transação — ver o comentário em TabDao.writeTabSnapshot para o
        // motivo de nunca voltar a separar isto em chamadas soltas.
        tabDao.writeTabSnapshot(
            tab = response.toEntity().copy(localId = localId, syncState = SyncState.SYNCED, lastSyncedAtEpochMs = now),
            orders = orders,
            items = items,
            payments = payments,
        )
        return tabDao.getTabWithDetails(localId)!!
    }
}

enum class CancelItemOutcome { Success, RemovedLocalDraft, QueuedOffline, NotFound }

private fun TabResponse.toEntity(): TabEntity = TabEntity(
    localId = "", // substituído pelo caller antes de gravar
    serverId = id, organizationId = organizationId, locationId = locationId,
    publicCode = publicCode, type = type, status = status,
    customerName = customerName, customerPhone = customerPhone,
    tableServerId = tableId, tableName = table?.nome, guestCount = guestCount,
    subtotalCents = subtotalCents, discountCents = discountCents, serviceChargeCents = serviceChargeCents,
    serviceChargeRateBps = serviceChargeRateBps,
    totalCents = totalCents, paidCents = paidCents, remainingCents = remainingCents,
    openedAt = openedAt, syncState = SyncState.SYNCED, lastSyncedAtEpochMs = System.currentTimeMillis(),
    createdAtEpochMs = System.currentTimeMillis(),
)

private fun TableResponse.toEntity(organizationId: Long, locationId: Long): VenueTableEntity = VenueTableEntity(
    serverId = id, organizationId = organizationId, locationId = locationId, nome = nome, capacidade = capacidade, active = active,
    openTabServerId = openTab?.id, openTabCode = openTab?.publicCode, openTabStatus = openTab?.status, openTabTotalCents = openTab?.totalCents,
    openTabRemainingCents = openTab?.remainingCents, openTabCustomerName = openTab?.customerName,
    fetchedAtEpochMs = System.currentTimeMillis(),
)

private fun VenueTableEntity.toDomain(): VenueTable = VenueTable(
    id = serverId, name = nome, capacity = capacidade, active = active,
    openTabId = openTabServerId, openTabCode = openTabCode,
    openTabStatus = openTabStatus?.let { com.nokta.pos.comanda.domain.TabStatus.parse(it) },
    openTabTotal = openTabTotalCents?.let { Money(it) }, openTabRemaining = openTabRemainingCents?.let { Money(it) },
    openTabCustomerName = openTabCustomerName,
)

/** Sem itens/pagamentos carregados — usado em listas (busca, histórico) que não precisam do detalhe. */
private fun TabEntity.toDomainShallow(): Tab = toDomain(items = emptyList(), payments = emptyList())

private fun TabWithItemsAndPayments.toDomain(): Tab = tab.toDomain(
    items = items.map { it.toDomain() },
    payments = payments.map { it.toDomain() },
)

private fun TabEntity.toDomain(items: List<TabItem>, payments: List<TabPayment>): Tab {
    return Tab(
        localId = localId, serverId = serverId, negativeLocalId = negativeIdFromLocalId(localId),
        organizationId = organizationId, locationId = locationId, publicCode = publicCode ?: "…",
        type = TabType.entries.firstOrNull { it.name == type } ?: TabType.INDIVIDUAL,
        status = TabStatus.parse(status),
        customerName = customerName, customerPhone = customerPhone,
        tableId = tableServerId, tableName = tableName, guestCount = guestCount,
        subtotal = Money(subtotalCents), discount = Money(discountCents), serviceCharge = Money(serviceChargeCents),
        serviceChargeRateBps = serviceChargeRateBps,
        total = Money(totalCents), paid = Money(paidCents), remaining = Money(remainingCents),
        openedAt = openedAt, items = items, payments = payments,
        syncState = when (syncState) { SyncState.SYNCED -> LocalSyncState.SYNCED; SyncState.PENDING -> LocalSyncState.PENDING; SyncState.FAILED -> LocalSyncState.FAILED },
    )
}

private fun TabItemEntity.toDomain(): TabItem {
    val json = Json { ignoreUnknownKeys = true }
    val modifiers = runCatching { json.decodeFromString<List<PersistedModifier>>(modifiersJson) }.getOrDefault(emptyList())
        .map { TabItemModifier(name = it.name, quantity = it.quantity, total = Money(it.totalCents)) }
    return TabItem(
        localId = localId, serverId = serverId, orderId = null,
        menuItemId = menuItemId, variantId = variantId,
        productName = productName, variantName = variantName, quantity = quantity,
        unitPrice = Money(unitPriceCents), modifiersTotal = Money(modifiersTotalCents), lineTotal = Money(lineTotalCents),
        status = com.nokta.pos.comanda.domain.OrderItemStatus.parse(status), notes = notes, modifiers = modifiers,
        createdAt = null, syncState = if (serverId != null) LocalSyncState.SYNCED else LocalSyncState.PENDING,
    )
}

private fun TabPaymentEntity.toDomain(): TabPayment = TabPayment(
    localId = localId, serverId = serverId, method = PaymentMethodParse(method),
    amount = Money(amountCents), received = receivedCents?.let { Money(it) }, change = changeCents?.let { Money(it) },
    isCanceled = isCanceled, externalReference = externalReference, confirmedAt = confirmedAt,
    syncState = if (serverId != null) LocalSyncState.SYNCED else LocalSyncState.PENDING,
)

private fun PaymentMethodParse(raw: String) = com.nokta.pos.comanda.domain.PaymentMethod.parse(raw)
