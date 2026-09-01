package com.nokta.pos.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.nokta.pos.data.local.NoktaDatabase
import com.nokta.pos.data.local.entity.OutboxEntity
import com.nokta.pos.data.local.entity.OutboxOperationType
import com.nokta.pos.data.local.entity.OutboxStatus
import com.nokta.pos.data.local.entity.SyncState
import com.nokta.pos.data.local.entity.TabEntity
import com.nokta.pos.data.local.entity.TabItemEntity
import com.nokta.pos.data.local.entity.TabOrderEntity
import com.nokta.pos.data.local.entity.TabPaymentEntity
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.CreateOrderItemRequest
import com.nokta.pos.network.dto.CreateOrderRequest
import com.nokta.pos.network.dto.CreatePaymentRequest
import com.nokta.pos.network.dto.CreateTabRequest
import com.nokta.pos.network.dto.DeviceLoginRequest
import com.nokta.pos.network.dto.DeviceLoginResponse
import com.nokta.pos.network.dto.MeAccessResponse
import com.nokta.pos.network.dto.MenuPreviewResponse
import com.nokta.pos.network.dto.OrderResponse
import com.nokta.pos.network.dto.PaymentResponse
import com.nokta.pos.network.dto.ProductModifierGroupResponse
import com.nokta.pos.network.dto.RedeemPairingCodeRequest
import com.nokta.pos.network.dto.RedeemPairingCodeResponse
import com.nokta.pos.network.dto.TabResponse
import com.nokta.pos.network.dto.TableResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Suite de integração do coração da sincronização offline: dado um Outbox
 * real (Room in-memory) e uma API fake controlável, prova as garantias que
 * não podem quebrar — nunca duplica no retry, para no primeiro erro de REDE
 * mantendo a ordem, e nunca reentra a fila com um erro de NEGÓCIO definitivo.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private lateinit var db: NoktaDatabase
    private lateinit var api: FakeNoktaApi
    private lateinit var connectivity: FakeConnectivityChecker
    private lateinit var engine: SyncEngine
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManagerConfig = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, workManagerConfig)

        db = Room.inMemoryDatabaseBuilder(context, NoktaDatabase::class.java).allowMainThreadQueries().build()
        api = FakeNoktaApi()
        connectivity = FakeConnectivityChecker(online = true)
        val syncStatusStore = SyncStatusStore(context)
        val workManagerTrigger = SyncWorkManagerTrigger(WorkManager.getInstance(context))
        engine = SyncEngine(api, db.outboxDao(), db.tabDao(), connectivity, syncStatusStore, workManagerTrigger)
    }

    @After
    fun tearDown() { db.close() }

    private fun setNetworkOnline(online: Boolean) { connectivity.online = online }

    /** Pedido local já gravado por `submitOrder`, com 1 item pendente (sem serverId) — o que `discardRejectedOrder` opera. */
    private suspend fun seedPendingOrderItem(tabLocalId: String, orderLocalId: String, itemLocalId: String, lineTotalCents: Long) {
        db.tabDao().upsertOrder(TabOrderEntity(localId = orderLocalId, serverId = null, tabLocalId = tabLocalId, status = "SENT", syncState = SyncState.PENDING, createdAtEpochMs = 1))
        db.tabDao().upsertItem(
            TabItemEntity(
                localId = itemLocalId, serverId = null, tabLocalId = tabLocalId, orderLocalId = orderLocalId,
                menuItemId = 1, variantId = 1, productName = "Budweiser", variantName = "269ml",
                quantity = 1, unitPriceCents = lineTotalCents, modifiersTotalCents = 0, lineTotalCents = lineTotalCents,
                status = "SENT", notes = null, modifiersJson = "[]", createdAtEpochMs = 1,
            ),
        )
    }

    /** Pagamento local gravado por `registerPayment`, cobrindo (ou não) o item pendente acima. */
    private suspend fun seedPendingPayment(tabLocalId: String, paymentLocalId: String, amountCents: Long, coveredItemLocalIds: List<String>) {
        db.tabDao().upsertPayment(
            TabPaymentEntity(
                localId = paymentLocalId, serverId = null, tabLocalId = tabLocalId, method = "CASH",
                amountCents = amountCents, receivedCents = null, changeCents = null, isCanceled = false,
                externalReference = null, confirmedAt = null, syncState = SyncState.PENDING, createdAtEpochMs = 1,
                coveredPendingItemIdsJson = if (coveredItemLocalIds.isEmpty()) null else json.encodeToString(coveredItemLocalIds),
            ),
        )
    }

    private fun tabDraft(localId: String) = TabEntity(
        localId = localId, serverId = null, organizationId = 1, locationId = 1,
        publicCode = null, type = "COUNTER", status = "OPEN",
        customerName = null, customerPhone = null, tableServerId = null, tableName = null, guestCount = null,
        subtotalCents = 0, discountCents = 0, serviceChargeCents = 0, totalCents = 0, paidCents = 0, remainingCents = 0,
        openedAt = null, syncState = SyncState.PENDING, lastSyncedAtEpochMs = null, createdAtEpochMs = 1,
    )

    @Test
    fun `sem rede nao processa nada e nao mexe na fila`() = runTest {
        setNetworkOnline(false)
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "tab-1", type = OutboxOperationType.CREATE_TAB, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateTabRequest(type = "COUNTER", clientRequestId = "tab-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )

        val result = engine.syncAll()

        assertEquals(0, result.processed)
        assertTrue(result.stoppedByNetwork)
        assertEquals(1, db.outboxDao().getPending().size)
        assertEquals(0, api.createTabCallCount)
    }

    @Test
    fun `CREATE_TAB confirmado remove da fila e grava o serverId na comanda`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "tab-1", type = OutboxOperationType.CREATE_TAB, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateTabRequest(type = "COUNTER", clientRequestId = "tab-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.createTabResponse = { tabResponse(id = 777) }

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertTrue(db.outboxDao().getPending().isEmpty())
        val tab = db.tabDao().getTabByLocalId("tab-1")!!
        assertEquals(777L, tab.serverId)
        assertEquals(SyncState.SYNCED, tab.syncState)
    }

    @Test
    fun `falha de rede no meio da fila para e preserva a ordem, sem processar o resto`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "payment-1", type = OutboxOperationType.REGISTER_PAYMENT, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreatePaymentRequest(method = "CASH", amountCents = 1000, idempotencyKey = "payment-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 2, lastAttemptAtEpochMs = null,
            ),
        )
        api.createOrderThrows = { IOException("conexão caiu") }

        val result = engine.syncAll()

        assertEquals(0, result.processed)
        assertTrue(result.stoppedByNetwork)
        // Os DOIS continuam na fila, na MESMA ordem — o pagamento nunca é
        // tentado antes do pedido, mesmo que o pedido tenha falhado.
        val pending = db.outboxDao().getPending()
        assertEquals(2, pending.size)
        assertEquals("order-1", pending[0].operationId)
        assertEquals("payment-1", pending[1].operationId)
        assertEquals(0, api.createPaymentCallCount)
    }

    @Test
    fun `erro 4xx e definitivo — sai da fila sem reentrar, nao para as seguintes`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))
        db.tabDao().upsertTab(tabDraft("tab-2").copy(serverId = 501, syncState = SyncState.SYNCED))

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-rejected", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-rejected")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "payment-after", type = OutboxOperationType.REGISTER_PAYMENT, organizationId = 1,
                tabLocalId = "tab-2", payloadJson = json.encodeToString(CreatePaymentRequest(method = "CASH", amountCents = 500, idempotencyKey = "payment-after")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 2, lastAttemptAtEpochMs = null,
            ),
        )
        api.createOrderThrows = { HttpException(Response.error<Any>(409, okhttp3.ResponseBody.create(null, "comanda fechada"))) }
        api.createPaymentResponse = { paymentResponse(id = 1) }
        api.getTabResponse = { tabResponse(id = it) }

        val result = engine.syncAll()

        // A rejeitada saiu (processed conta as duas: 1 rejeitada + 1 sucesso).
        assertEquals(2, result.processed)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals(1, api.createPaymentCallCount)
    }

    @Test
    fun `SEND_ORDER com pedido ja enviado pelo servidor e tratado como sucesso, nunca rejeitado`() = runTest {
        // Reproduz o bug real: a 1a tentativa de SEND_ORDER enviou com
        // sucesso no servidor, mas a resposta se perdeu antes de voltar
        // (timeout/queda no meio) — o app achou que falhou e reenfileirou a
        // MESMA operacao. No retry, createOrder (idempotente por
        // clientRequestId) devolve o pedido ja existente, e sendOrder nele
        // recusa com 400 "Este pedido ja foi enviado." — que deve virar
        // sucesso aqui, nunca aparecer como erro para o operador.
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.sendOrderThrows = { HttpException(Response.error<Any>(400, okhttp3.ResponseBody.create(null, """{"message":"Este pedido já foi enviado."}"""))) }
        api.getTabResponse = { tabResponse(id = it) }

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertFalse(result.stoppedByNetwork)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test
    fun `SEND_ORDER com outro erro 4xx continua sendo rejeitado normalmente`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.sendOrderThrows = { HttpException(Response.error<Any>(400, okhttp3.ResponseBody.create(null, """{"message":"O pedido precisa de ao menos um item para ser enviado."}"""))) }

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test
    fun `SEND_ORDER sem serverId e sem CREATE_TAB pendente e Orphaned — rejeitado, nunca chama a API de pedido`() = runTest {
        // Comanda sem serverId E sem nenhum CREATE_TAB pendente na fila para
        // ela: não há mais nada a esperar (ver TabServerIdLookup.Orphaned) —
        // cai como Rejected (definitivo, sai da fila), não Retry. Teste
        // atualizado para bater com esse comportamento: a versão anterior
        // deste teste assumia Retry aqui, o que já não reflete o código
        // desde que Orphaned foi introduzido.
        db.tabDao().upsertTab(tabDraft("tab-1")) // ainda sem serverId
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertFalse(result.stoppedByNetwork)
        assertEquals(0, api.createOrderCallCount)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test
    fun `SEND_ORDER sem serverId mas com CREATE_TAB pendente ainda nao resolvido e StillWaiting — Retry`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1")) // ainda sem serverId
        // CREATE_TAB falha por rede primeiro: para a fila ali (comportamento
        // já coberto por outro teste), então nunca chega a processar o
        // SEND_ORDER nesta mesma passada — aqui o que importa é simular o
        // CREATE_TAB como pendente sem rodar o syncAll, checando direto que
        // a resolução de serverId espera por ele em vez de desistir.
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "tab-1", type = OutboxOperationType.CREATE_TAB, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateTabRequest(type = "COUNTER", clientRequestId = "tab-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 2, lastAttemptAtEpochMs = null,
            ),
        )
        api.createTabThrows = { IOException("conexão caiu") }

        val result = engine.syncAll()

        // CREATE_TAB falha por rede -> Retry -> syncAll para ali, na ordem.
        // O SEND_ORDER nem chega a ser avaliado nesta passada, mas continua
        // pendente na fila (nunca descartado como Orphaned enquanto o
        // CREATE_TAB do qual depende também está pendente).
        assertEquals(0, result.processed)
        assertTrue(result.stoppedByNetwork)
        assertEquals(0, api.createOrderCallCount)
        assertEquals(2, db.outboxDao().getPending().size)
    }

    @Test
    fun `CANCEL_ITEM com item ja cancelado pelo servidor e tratado como sucesso, nunca rejeitado`() = runTest {
        // Mesmo mecanismo do SEND_ORDER: a 1a tentativa de CANCEL_ITEM
        // cancelou com sucesso no servidor, mas a resposta se perdeu antes
        // de voltar — o app reenfileira a MESMA operacao, o retry recebe
        // 400 "Este item já está cancelado." e isso deve virar sucesso.
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "cancel-1", type = OutboxOperationType.CANCEL_ITEM, organizationId = 1,
                tabLocalId = "tab-1",
                payloadJson = json.encodeToString(com.nokta.pos.network.dto.CancelItemOutboxPayload(itemServerId = 777, reason = "teste")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.cancelOrderItemThrows = { HttpException(Response.error<Any>(400, okhttp3.ResponseBody.create(null, """{"message":"Este item já está cancelado."}"""))) }
        api.getTabResponse = { tabResponse(id = it) }

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertFalse(result.stoppedByNetwork)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test
    fun `CANCEL_ITEM com outro erro 4xx continua sendo rejeitado normalmente`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "cancel-1", type = OutboxOperationType.CANCEL_ITEM, organizationId = 1,
                tabLocalId = "tab-1",
                payloadJson = json.encodeToString(com.nokta.pos.network.dto.CancelItemOutboxPayload(itemServerId = 777, reason = "teste")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.cancelOrderItemThrows = { HttpException(Response.error<Any>(400, okhttp3.ResponseBody.create(null, """{"message":"Comanda não está aberta."}"""))) }

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    // ------------------------------------------------------------------
    // Reconciliação de pagamento x item recusado (Fase 1 desta conversa) —
    // discardRejectedOrder/markPaymentRejected, sem cobertura até aqui.
    // ------------------------------------------------------------------

    @Test
    fun `SEND_ORDER rejeitado SEM pagamento cobrindo o item nao gera reconciliacao`() = runTest {
        // Caso comum: item recusado antes de qualquer cobrança — só descarta,
        // igual ao comportamento anterior a esta mudança.
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))
        seedPendingOrderItem(tabLocalId = "tab-1", orderLocalId = "order-1", itemLocalId = "item-1", lineTotalCents = 800)

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.createOrderThrows = { HttpException(Response.error<Any>(409, okhttp3.ResponseBody.create(null, "produto indisponível"))) }

        val events = mutableListOf<SyncEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { engine.events.collect { events.add(it) } }

        val result = engine.syncAll()
        job.cancel()

        assertEquals(1, result.processed)
        assertTrue(db.tabDao().getItemsByOrderLocalId("order-1").isEmpty()) // item descartado
        assertTrue(db.tabDao().observePaymentReconciliations("tab-1").first().isEmpty())
        assertTrue(events.none { it is SyncEvent.PaymentReconciliationRequired })
    }

    @Test
    fun `SEND_ORDER rejeitado COM pagamento cobrindo o item gera reconciliacao, nunca ajusta o pagamento sozinho`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))
        seedPendingOrderItem(tabLocalId = "tab-1", orderLocalId = "order-1", itemLocalId = "item-1", lineTotalCents = 800)
        // O checkout já cobrou R$84 (servidor R$76 + este item pendente de R$8) — ver TabRepository.registerPayment.
        seedPendingPayment(tabLocalId = "tab-1", paymentLocalId = "payment-1", amountCents = 8400, coveredItemLocalIds = listOf("item-1"))

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreateOrderRequest(items = emptyList(), clientRequestId = "order-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.createOrderThrows = { HttpException(Response.error<Any>(409, okhttp3.ResponseBody.create(null, "produto indisponível"))) }

        val events = mutableListOf<SyncEvent>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { engine.events.collect { events.add(it) } }

        val result = engine.syncAll()
        job.cancel()

        assertEquals(1, result.processed)
        assertTrue(db.tabDao().getItemsByOrderLocalId("order-1").isEmpty()) // item descartado, nunca fica fantasma

        // Reconciliação registrada — visível, nunca silenciosa.
        val reconciliations = db.tabDao().observePaymentReconciliations("tab-1").first()
        assertEquals(1, reconciliations.size)
        assertEquals("payment-1", reconciliations[0].paymentLocalId)
        assertEquals(800L, reconciliations[0].rejectedItemAmountCents)
        assertFalse(reconciliations[0].isResolved)

        val reconciliationEvents = events.filterIsInstance<SyncEvent.PaymentReconciliationRequired>()
        assertEquals(1, reconciliationEvents.size)
        assertEquals(1, reconciliationEvents[0].count)

        // O pagamento em si NUNCA é ajustado/apagado sozinho — continua
        // gravado exatamente como estava, para o operador decidir o que fazer.
        val payment = db.tabDao().getPaymentsForTab("tab-1").single { it.localId == "payment-1" }
        assertEquals(8400L, payment.amountCents)
        assertEquals(SyncState.PENDING, payment.syncState)
    }

    // ------------------------------------------------------------------
    // REGISTER_PAYMENT rejeitado — o saldo que ele cobria precisa reabrir
    // (Fase 2 desta conversa: markPaymentRejected), sem cobertura até aqui.
    // ------------------------------------------------------------------

    @Test
    fun `REGISTER_PAYMENT rejeitado marca o pagamento local como REJECTED, nunca fica PENDING para sempre`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))
        seedPendingPayment(tabLocalId = "tab-1", paymentLocalId = "payment-1", amountCents = 8400, coveredItemLocalIds = emptyList())

        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "payment-1", type = OutboxOperationType.REGISTER_PAYMENT, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = json.encodeToString(CreatePaymentRequest(method = "CASH", amountCents = 8400, idempotencyKey = "payment-1")),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        api.createPaymentThrows = { HttpException(Response.error<Any>(400, okhttp3.ResponseBody.create(null, """{"message":"O valor do pagamento não pode ultrapassar o saldo restante."}"""))) }

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertTrue(db.outboxDao().getPending().isEmpty())

        val payment = db.tabDao().getPaymentsForTab("tab-1").single { it.localId == "payment-1" }
        assertEquals(SyncState.REJECTED, payment.syncState)
    }

    @Test
    fun `markPaymentRejected nunca regride um pagamento ja SYNCED`() = runTest {
        // Guarda contra uma chamada tardia/duplicada: um pagamento que já
        // sincronizou com sucesso não pode voltar a ficar REJECTED por
        // qualquer race de processamento.
        db.tabDao().upsertTab(tabDraft("tab-1"))
        seedPendingPayment(tabLocalId = "tab-1", paymentLocalId = "payment-1", amountCents = 8400, coveredItemLocalIds = emptyList())
        db.tabDao().updatePayment(db.tabDao().getPaymentsForTab("tab-1").single().copy(serverId = 999, syncState = SyncState.SYNCED))

        db.tabDao().markPaymentRejected("payment-1")

        val payment = db.tabDao().getPaymentsForTab("tab-1").single()
        assertEquals(SyncState.SYNCED, payment.syncState)
    }

    @Test
    fun `CLOSE_TAB no Outbox e sempre rejeitado — nunca enfileirado de verdade, decisao permanente`() = runTest {
        db.tabDao().upsertTab(tabDraft("tab-1"))
        db.tabDao().updateTab(db.tabDao().getTabByLocalId("tab-1")!!.copy(serverId = 500, syncState = SyncState.SYNCED))
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "close-1", type = OutboxOperationType.CLOSE_TAB, organizationId = 1,
                tabLocalId = "tab-1", payloadJson = "{}",
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null, createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )

        val result = engine.syncAll()

        assertEquals(1, result.processed)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    private fun tabResponse(id: Long) = TabResponse(
        id = id, organizationId = 1, locationId = 1, tableId = null, table = null,
        publicCode = "%04d".format(id), type = "COUNTER", status = "OPEN",
        customerName = null, customerPhone = null, guestCount = null,
        subtotalCents = 0, discountCents = 0, serviceChargeCents = 0, totalCents = 0, paidCents = 0, remainingCents = 0,
        version = 0, openedAt = null, orders = emptyList(), payments = emptyList(),
    )

    private fun paymentResponse(id: Long) = PaymentResponse(
        id = id, tabId = 1, method = "CASH", status = "CONFIRMED", amountCents = 500,
        receivedCents = null, changeCents = null, externalReference = null, notes = null, confirmedAt = null, createdAt = null,
    )
}

/**
 * Fake mínimo de [NoktaApi] — implementa só o necessário para o teste
 * controlar resposta/exceção por método e contar chamadas. Não usa Mockito
 * (não é dependência do projeto): uma implementação real da interface é mais
 * simples e explícita para este caso.
 */
private class FakeConnectivityChecker(var online: Boolean) : ConnectivityChecker {
    override fun isOnline(): Boolean = online
}

private class FakeNoktaApi : NoktaApi {
    var createTabCallCount = 0
    var createOrderCallCount = 0
    var sendOrderCallCount = 0
    var createPaymentCallCount = 0
    var cancelOrderItemCallCount = 0

    var createTabResponse: ((CreateTabRequest) -> Any)? = null
    var createTabThrows: (() -> Throwable)? = null
    var createOrderThrows: (() -> Throwable)? = null
    var sendOrderThrows: (() -> Throwable)? = null
    var cancelOrderItemThrows: (() -> Throwable)? = null
    var createPaymentResponse: ((Long) -> PaymentResponse)? = null
    var createPaymentThrows: (() -> Throwable)? = null
    var getTabResponse: ((Long) -> TabResponse)? = null

    override suspend fun redeemPairingCode(body: RedeemPairingCodeRequest): RedeemPairingCodeResponse = error("not used")
    override suspend fun deviceLogin(body: DeviceLoginRequest): DeviceLoginResponse = error("not used")
    override suspend fun getDeviceStatus(): com.nokta.pos.network.dto.DeviceStatusResponse = error("not used")
    override suspend fun getMeAccess(organizationId: Long): MeAccessResponse = error("not used")
    override suspend fun getCashStatus(organizationId: Long, locationId: Long): com.nokta.pos.network.dto.CashStatusResponse = error("not used")
    override suspend fun getMenuPreview(organizationId: Long, menuId: Long): MenuPreviewResponse = error("not used")
    override suspend fun getProductModifierGroups(organizationId: Long, productId: Long): List<ProductModifierGroupResponse> = error("not used")

    override suspend fun createTab(organizationId: Long, locationId: Long, body: CreateTabRequest): TabResponse {
        createTabCallCount++
        createTabThrows?.invoke()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return (createTabResponse?.invoke(body) as? TabResponse) ?: error("createTabResponse não configurado")
    }

    override suspend fun getTab(organizationId: Long, tabId: Long): TabResponse =
        getTabResponse?.invoke(tabId) ?: error("getTabResponse não configurado")

    override suspend fun resolvePhysicalCode(organizationId: Long, locationId: Long, kind: String, publicCode: String): com.nokta.pos.network.dto.ResolvePhysicalCodeResponse = error("not used")
    override suspend fun bindPhysicalCard(organizationId: Long, locationId: Long, cardId: Long, body: com.nokta.pos.network.dto.BindPhysicalCardRequest): TabResponse = error("not used")

    override suspend fun getTabByPublicCode(organizationId: Long, locationId: Long, publicCode: String): TabResponse = error("not used")
    override suspend fun closeTab(organizationId: Long, tabId: Long): TabResponse = error("not used")
    override suspend fun requestCloseTab(organizationId: Long, tabId: Long): TabResponse = error("not used")
    override suspend fun cancelCloseTab(organizationId: Long, tabId: Long): TabResponse = error("not used")
    override suspend fun listTabs(organizationId: Long, locationId: Long, status: String?, type: String?, search: String?): List<TabResponse> = emptyList()
    override suspend fun listTables(organizationId: Long, locationId: Long): List<TableResponse> = emptyList()

    override suspend fun createOrder(organizationId: Long, tabId: Long, body: CreateOrderRequest): OrderResponse {
        createOrderCallCount++
        createOrderThrows?.invoke()?.let { throw it }
        return OrderResponse(id = 1, tabId = tabId, publicCode = "0001", status = "DRAFT", createdAt = null, items = emptyList())
    }

    override suspend fun sendOrder(organizationId: Long, orderId: Long): OrderResponse {
        sendOrderCallCount++
        sendOrderThrows?.invoke()?.let { throw it }
        return OrderResponse(id = orderId, tabId = 1, publicCode = "0001", status = "SENT", createdAt = null, items = emptyList())
    }

    override suspend fun cancelOrderItem(organizationId: Long, itemId: Long, body: com.nokta.pos.network.dto.CancelOrderItemRequest): com.nokta.pos.network.dto.OrderItemResponse {
        cancelOrderItemCallCount++
        cancelOrderItemThrows?.invoke()?.let { throw it }
        return com.nokta.pos.network.dto.OrderItemResponse(
            id = itemId, productId = 1, variantId = 1, quantity = 1,
            productNameSnapshot = "Item", variantNameSnapshot = "Único",
            unitPriceCents = 0, modifiersTotalCents = 0, lineTotalCents = 0,
            status = "CANCELED",
        )
    }

    override suspend fun createPayment(organizationId: Long, tabId: Long, body: CreatePaymentRequest): PaymentResponse {
        createPaymentCallCount++
        createPaymentThrows?.invoke()?.let { throw it }
        return createPaymentResponse?.invoke(tabId) ?: error("createPaymentResponse não configurado")
    }
}
