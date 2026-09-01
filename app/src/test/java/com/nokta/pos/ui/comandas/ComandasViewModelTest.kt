package com.nokta.pos.ui.comandas

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.data.local.NoktaDatabase
import com.nokta.pos.device.DeviceCredentialsStore
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.BindPhysicalCardRequest
import com.nokta.pos.network.dto.PhysicalCardResponse
import com.nokta.pos.network.dto.ResolvePhysicalCodeResponse
import com.nokta.pos.network.dto.TabResponse
import com.nokta.pos.session.DeviceEvents
import com.nokta.pos.sync.ConnectivityChecker
import com.nokta.pos.sync.SyncEngine
import com.nokta.pos.sync.SyncStatusStore
import com.nokta.pos.sync.SyncWorkManagerTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

/**
 * Cobre a árvore de decisão da tela "Comandas" (pulseira/cartão físico):
 * pulseira sempre abre direto; cartão vinculado abre direto; cartão
 * disponível pede vinculação; sem rede nunca chama a API (ver doc de
 * TabRepository.resolvePhysicalCode — deliberadamente online-only).
 */
@RunWith(RobolectricTestRunner::class)
class ComandasViewModelTest {

    private lateinit var db: NoktaDatabase
    private lateinit var api: FakeComandasApi
    private lateinit var connectivity: FakeConnectivityChecker
    private lateinit var viewModel: ComandasViewModel

    @Before
    fun setUp() {
        // viewModelScope usa Dispatchers.Main por padrão — sem isto, o
        // viewModelScope.launch de resolve()/confirmBindForm() nunca roda
        // dentro da janela do runTest do teste, e as asserções checam estado
        // desatualizado. UnconfinedTestDispatcher executa a coroutine
        // imediatamente, sem precisar de advanceUntilIdle() manual.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManagerConfig = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, workManagerConfig)

        db = Room.inMemoryDatabaseBuilder(context, NoktaDatabase::class.java).allowMainThreadQueries().build()
        api = FakeComandasApi()
        connectivity = FakeConnectivityChecker(online = true)
        val syncStatusStore = SyncStatusStore(context)
        val workManagerTrigger = SyncWorkManagerTrigger(WorkManager.getInstance(context))
        val syncEngine = SyncEngine(api, db.outboxDao(), db.tabDao(), connectivity, syncStatusStore, workManagerTrigger)
        val tabRepository = TabRepository(api, db.tabDao(), db.outboxDao(), syncEngine)

        val credentialsStore = DeviceCredentialsStore(context)
        credentialsStore.saveSession(jwt = "jwt", userId = 1, userName = "Operador", role = "WAITER", organizationId = 10, locationId = 20)
        val authRepository = AuthRepository(api, credentialsStore, DeviceEvents())

        viewModel = ComandasViewModel(tabRepository, authRepository, connectivity)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /**
     * Room (mesmo in-memory) usa seu próprio executor de thread REAL —
     * `TestCoroutineScheduler`/`UnconfinedTestDispatcher` só controlam o
     * dispatcher `Main`, nunca esse I/O real. `viewModelScope.launch` (que
     * `resolve()`/`confirmBindForm()` disparam) então roda numa thread de
     * verdade, fora do controle do `runTest` — checar o estado logo depois
     * de chamar o método é uma corrida real. Faz polling curto até a
     * condição valer, com timeout — nunca um `Thread.sleep` fixo torcendo
     * pra dar tempo.
     */
    private fun awaitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        if (!condition()) fail("condição não satisfeita dentro de ${timeoutMs}ms")
    }

    @Test
    fun `pulseira nova abre direto sem pedir cliente`() {
        api.resolveResponse = { ResolvePhysicalCodeResponse(kind = "TAB", tab = fakeTab(id = 100, publicCode = "037", type = "WRISTBAND")) }

        var openedTabLocalId: String? = null
        viewModel.selectKind(ComandaKind.WRISTBAND)
        viewModel.setCode("037")
        viewModel.resolve(onOpenTab = { openedTabLocalId = it })

        awaitUntil { openedTabLocalId != null }
        assertNull("pulseira nunca deve pedir vinculação de cliente", viewModel.state.value.bindForm)
    }

    @Test
    fun `cartao ja vinculado abre direto`() {
        api.resolveResponse = { ResolvePhysicalCodeResponse(kind = "TAB", tab = fakeTab(id = 200, publicCode = "041", type = "INDIVIDUAL", customerName = "Maria")) }

        var openedTabLocalId: String? = null
        viewModel.selectKind(ComandaKind.CARD)
        viewModel.setCode("041")
        viewModel.resolve(onOpenTab = { openedTabLocalId = it })

        awaitUntil { openedTabLocalId != null }
        assertNull(viewModel.state.value.bindForm)
    }

    @Test
    fun `cartao disponivel pede vinculacao de cliente antes de abrir`() {
        api.resolveResponse = { ResolvePhysicalCodeResponse(kind = "CARD_AVAILABLE", card = PhysicalCardResponse(id = 55, publicCode = "073")) }

        var opened = false
        viewModel.selectKind(ComandaKind.CARD)
        viewModel.setCode("073")
        viewModel.resolve(onOpenTab = { opened = true })

        awaitUntil { viewModel.state.value.bindForm != null }
        assertTrue("não deveria abrir comanda antes de vincular cliente", !opened)
        val form = viewModel.state.value.bindForm
        assertNotNull("deveria mostrar o formulário de vinculação", form)
        assertEquals(55L, form!!.cardId)
        assertEquals("073", form.publicCode)
    }

    @Test
    fun `confirmar vinculacao de cartao abre a comanda`() {
        api.resolveResponse = { ResolvePhysicalCodeResponse(kind = "CARD_AVAILABLE", card = PhysicalCardResponse(id = 55, publicCode = "073")) }
        api.bindResponse = { fakeTab(id = 201, publicCode = "073", type = "INDIVIDUAL", customerName = "João") }

        viewModel.selectKind(ComandaKind.CARD)
        viewModel.setCode("073")
        viewModel.resolve(onOpenTab = {})
        awaitUntil { viewModel.state.value.bindForm != null }

        viewModel.setBindFormName("João da Silva")
        viewModel.setBindFormPhone("11999999999")

        var openedTabLocalId: String? = null
        viewModel.confirmBindForm(onOpenTab = { openedTabLocalId = it })

        awaitUntil { openedTabLocalId != null }
        assertEquals(1, api.bindCallCount)
    }

    @Test
    fun `sem rede nunca chama a api e mostra aviso`() {
        connectivity.online = false

        viewModel.selectKind(ComandaKind.WRISTBAND)
        viewModel.setCode("037")
        viewModel.resolve(onOpenTab = { throw AssertionError("não deveria abrir comanda offline") })

        // Síncrono de propósito (ver resolve()): a checagem de conectividade
        // roda ANTES de disparar o launch, então não há nada a aguardar aqui.
        assertEquals(0, api.resolveCallCount)
        assertNotNull("deveria mostrar aviso de sem conexão", viewModel.state.value.error)
    }

    @Test
    fun `pulseira ja em atendimento por outro garcom mostra erro`() {
        api.resolveThrows = {
            HttpException(Response.error<Any>(409, okhttp3.ResponseBody.create(null, "{\"message\":\"Esta pulseira já está em atendimento agora.\"}")))
        }

        viewModel.selectKind(ComandaKind.WRISTBAND)
        viewModel.setCode("037")
        viewModel.resolve(onOpenTab = { throw AssertionError("não deveria abrir comanda") })

        awaitUntil { viewModel.state.value.error != null }
        assertTrue(viewModel.state.value.error!!.contains("em atendimento"))
    }

    private fun fakeTab(id: Long, publicCode: String, type: String, customerName: String? = null): TabResponse =
        TabResponse(
            id = id,
            organizationId = 10,
            locationId = 20,
            publicCode = publicCode,
            type = type,
            status = "OPEN",
            customerName = customerName,
            subtotalCents = 0,
            discountCents = 0,
            serviceChargeCents = 0,
            totalCents = 0,
            paidCents = 0,
            remainingCents = 0,
            version = 0,
        )
}

private class FakeConnectivityChecker(var online: Boolean) : ConnectivityChecker {
    override fun isOnline(): Boolean = online
}

/**
 * Fake mínima da API — só os métodos que este fluxo usa (resolvePhysicalCode,
 * bindPhysicalCard) têm comportamento controlável; o resto lança "not used",
 * mesmo padrão de FakeNoktaApi em SyncEngineTest.
 */
private class FakeComandasApi : NoktaApi {
    var resolveCallCount = 0
    var bindCallCount = 0
    var resolveResponse: (() -> ResolvePhysicalCodeResponse)? = null
    var resolveThrows: (() -> Throwable)? = null
    var bindResponse: (() -> TabResponse)? = null

    override suspend fun resolvePhysicalCode(organizationId: Long, locationId: Long, kind: String, publicCode: String): ResolvePhysicalCodeResponse {
        resolveCallCount++
        resolveThrows?.invoke()?.let { throw it }
        return resolveResponse?.invoke() ?: error("resolveResponse não configurado")
    }

    override suspend fun bindPhysicalCard(organizationId: Long, locationId: Long, cardId: Long, body: BindPhysicalCardRequest): TabResponse {
        bindCallCount++
        return bindResponse?.invoke() ?: error("bindResponse não configurado")
    }

    override suspend fun redeemPairingCode(body: com.nokta.pos.network.dto.RedeemPairingCodeRequest): com.nokta.pos.network.dto.RedeemPairingCodeResponse = error("not used")
    override suspend fun deviceLogin(body: com.nokta.pos.network.dto.DeviceLoginRequest): com.nokta.pos.network.dto.DeviceLoginResponse = error("not used")
    override suspend fun getDeviceStatus(): com.nokta.pos.network.dto.DeviceStatusResponse = error("not used")
    override suspend fun getMeAccess(organizationId: Long): com.nokta.pos.network.dto.MeAccessResponse = error("not used")
    override suspend fun getCashStatus(organizationId: Long, locationId: Long): com.nokta.pos.network.dto.CashStatusResponse = error("not used")
    override suspend fun getMenuPreview(organizationId: Long, menuId: Long): com.nokta.pos.network.dto.MenuPreviewResponse = error("not used")
    override suspend fun getProductModifierGroups(organizationId: Long, productId: Long): List<com.nokta.pos.network.dto.ProductModifierGroupResponse> = error("not used")
    override suspend fun createTab(organizationId: Long, locationId: Long, body: com.nokta.pos.network.dto.CreateTabRequest): TabResponse = error("not used")
    override suspend fun getTab(organizationId: Long, tabId: Long): TabResponse = error("not used")
    override suspend fun getTabByPublicCode(organizationId: Long, locationId: Long, publicCode: String): TabResponse = error("not used")
    override suspend fun closeTab(organizationId: Long, tabId: Long): TabResponse = error("not used")
    override suspend fun requestCloseTab(organizationId: Long, tabId: Long): TabResponse = error("not used")
    override suspend fun cancelCloseTab(organizationId: Long, tabId: Long): TabResponse = error("not used")
    override suspend fun listTabs(organizationId: Long, locationId: Long, status: String?, type: String?, search: String?): List<TabResponse> = error("not used")
    override suspend fun listTables(organizationId: Long, locationId: Long): List<com.nokta.pos.network.dto.TableResponse> = error("not used")
    override suspend fun createOrder(organizationId: Long, tabId: Long, body: com.nokta.pos.network.dto.CreateOrderRequest): com.nokta.pos.network.dto.OrderResponse = error("not used")
    override suspend fun sendOrder(organizationId: Long, orderId: Long): com.nokta.pos.network.dto.OrderResponse = error("not used")
    override suspend fun cancelOrderItem(organizationId: Long, itemId: Long, body: com.nokta.pos.network.dto.CancelOrderItemRequest): com.nokta.pos.network.dto.OrderItemResponse = error("not used")
    override suspend fun createPayment(organizationId: Long, tabId: Long, body: com.nokta.pos.network.dto.CreatePaymentRequest): com.nokta.pos.network.dto.PaymentResponse = error("not used")
}
