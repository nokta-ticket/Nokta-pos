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
import com.nokta.pos.data.local.entity.TabPaymentEntity
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.CreateOrderRequest
import com.nokta.pos.network.dto.CreateOrderItemRequest
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regressão de um BLOCKER financeiro encontrado na auditoria de homologação:
 * `SyncEngine.refreshTabSnapshot` apaga TODOS os pagamentos locais da comanda
 * (`deletePaymentsForTab`) e regrava só os que o servidor conhece.
 *
 * Um `REGISTER_PAYMENT` que ainda está na fila do Outbox (dinheiro já
 * recebido do cliente, servidor ainda não sabe) tem `serverId == null` e por
 * isso NÃO volta na resposta do servidor — era apagado do Room ao sincronizar
 * qualquer OUTRA operação da mesma comanda (ex.: um SEND_ORDER que veio
 * depois na fila, ou um CANCEL_ITEM).
 *
 * Consequências reais:
 *  1. `Tab.pendingPaymentsTotal` volta a zero -> `remainingWithPending`
 *     reabre o saldo já cobrado -> o operador pode cobrar o MESMO valor de
 *     novo (cobrança duplicada dentro do mesmo terminal).
 *  2. Se o `REGISTER_PAYMENT` for rejeitado depois, `markPaymentRejected`
 *     não encontra mais a linha — o dinheiro recebido some da tela sem
 *     nenhum rastro para o operador.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSnapshotPendingLossTest {

    private lateinit var db: NoktaDatabase
    private lateinit var api: SnapshotFakeApi
    private lateinit var engine: SyncEngine
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManagerConfig = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG).build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, workManagerConfig)

        db = Room.inMemoryDatabaseBuilder(context, NoktaDatabase::class.java).allowMainThreadQueries().build()
        api = SnapshotFakeApi()
        engine = SyncEngine(
            api,
            db.outboxDao(),
            db.tabDao(),
            object : ConnectivityChecker { override fun isOnline() = true },
            SyncStatusStore(context),
            SyncWorkManagerTrigger(WorkManager.getInstance(context)),
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `pagamento pendente no Outbox nunca e apagado ao sincronizar outra operacao da mesma comanda`() = runTest {
        // Comanda já sincronizada (tem serverId).
        db.tabDao().upsertTab(
            TabEntity(
                localId = "tab-1", serverId = 500, organizationId = 1, locationId = 1,
                publicCode = "0500", type = "COUNTER", status = "OPEN",
                customerName = null, customerPhone = null, tableServerId = null, tableName = null, guestCount = null,
                subtotalCents = 5000, discountCents = 0, serviceChargeCents = 0,
                totalCents = 5000, paidCents = 0, remainingCents = 5000,
                openedAt = null, syncState = SyncState.SYNCED, lastSyncedAtEpochMs = 1, createdAtEpochMs = 1,
            ),
        )

        // Dinheiro JÁ recebido do cliente, ainda na fila (offline no momento da cobrança).
        db.tabDao().upsertPayment(
            TabPaymentEntity(
                localId = "payment-pendente", serverId = null, tabLocalId = "tab-1", method = "CASH",
                amountCents = 5000, receivedCents = 5000, changeCents = 0, isCanceled = false,
                externalReference = null, confirmedAt = null,
                syncState = SyncState.PENDING, createdAtEpochMs = 2,
            ),
        )

        // Uma OUTRA operação da mesma comanda vem antes na fila e sincroniza
        // com sucesso — o que dispara refreshTabSnapshot.
        db.outboxDao().enqueue(
            OutboxEntity(
                operationId = "order-1", type = OutboxOperationType.SEND_ORDER, organizationId = 1,
                tabLocalId = "tab-1",
                payloadJson = json.encodeToString(
                    CreateOrderRequest(
                        items = listOf(CreateOrderItemRequest(menuItemId = 1, variantId = 1, quantity = 1, notes = null, modifiers = emptyList())),
                        clientRequestId = "order-1",
                    ),
                ),
                status = OutboxStatus.PENDING, retryCount = 0, lastError = null,
                createdAtEpochMs = 1, lastAttemptAtEpochMs = null,
            ),
        )
        // O servidor devolve a comanda SEM o pagamento pendente (ele nunca chegou lá).
        api.getTabResponse = { id ->
            TabResponse(
                id = id, organizationId = 1, locationId = 1, tableId = null, table = null,
                publicCode = "0500", type = "COUNTER", status = "OPEN",
                customerName = null, customerPhone = null, guestCount = null,
                subtotalCents = 5000, discountCents = 0, serviceChargeCents = 0,
                totalCents = 5000, paidCents = 0, remainingCents = 5000,
                version = 1, openedAt = null, orders = emptyList(), payments = emptyList(),
            )
        }

        engine.syncAll()

        // O pagamento pendente PRECISA continuar no Room: é dinheiro real já
        // recebido, e é ele que segura `remainingWithPending` para o operador
        // não cobrar o mesmo valor duas vezes.
        val payments = db.tabDao().getPaymentsForTab("tab-1")
        val pendente = payments.firstOrNull { it.localId == "payment-pendente" }
        assertNotNull(
            "Pagamento PENDING (dinheiro já recebido, ainda na fila) foi apagado por refreshTabSnapshot — saldo reabre e permite cobrança duplicada",
            pendente,
        )
        assertEquals(5000L, pendente!!.amountCents)
        assertEquals(SyncState.PENDING, pendente.syncState)
    }
}

/** Fake mínimo — só o que este cenário exercita. */
private class SnapshotFakeApi : NoktaApi {
    var getTabResponse: ((Long) -> TabResponse)? = null

    override suspend fun redeemPairingCode(body: RedeemPairingCodeRequest): RedeemPairingCodeResponse = error("not used")
    override suspend fun deviceLogin(body: DeviceLoginRequest): DeviceLoginResponse = error("not used")
    override suspend fun getDeviceStatus(): com.nokta.pos.network.dto.DeviceStatusResponse = error("not used")
    override suspend fun getMeAccess(organizationId: Long): MeAccessResponse = error("not used")
    override suspend fun getCashStatus(organizationId: Long, locationId: Long): com.nokta.pos.network.dto.CashStatusResponse = error("not used")
    override suspend fun getMenuPreview(organizationId: Long, menuId: Long): MenuPreviewResponse = error("not used")
    override suspend fun getProductModifierGroups(organizationId: Long, productId: Long): List<ProductModifierGroupResponse> = error("not used")
    override suspend fun createTab(organizationId: Long, locationId: Long, body: CreateTabRequest): TabResponse = error("not used")
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
    override suspend fun createOrder(organizationId: Long, tabId: Long, body: CreateOrderRequest): OrderResponse =
        OrderResponse(id = 1, tabId = tabId, publicCode = "0001", status = "DRAFT", createdAt = null, items = emptyList())
    override suspend fun sendOrder(organizationId: Long, orderId: Long): OrderResponse =
        OrderResponse(id = orderId, tabId = 1, publicCode = "0001", status = "SENT", createdAt = null, items = emptyList())
    override suspend fun cancelOrderItem(organizationId: Long, itemId: Long, body: com.nokta.pos.network.dto.CancelOrderItemRequest): com.nokta.pos.network.dto.OrderItemResponse = error("not used")
    override suspend fun createPayment(organizationId: Long, tabId: Long, body: CreatePaymentRequest): PaymentResponse = error("not used")
}
