package com.nokta.pos.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.access.OperatorAccess
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.cardapio.data.MenuRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.data.local.dao.OutboxDao
import com.nokta.pos.payment.cielo.CieloDeepLinkPaymentProvider
import com.nokta.pos.payment.cielo.PendingCieloAttempt
import com.nokta.pos.sync.ConnectivityMonitor
import com.nokta.pos.sync.SyncEngine
import com.nokta.pos.sync.SyncEvent
import com.nokta.pos.sync.SyncStatusStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Modo de operação da unidade, definido no dashboard. Só decide o que a Home
 * DESTACA — o app suporta os três fluxos sempre, em qualquer modo (item 3 do
 * brief: um único POS para balcão, mesa e comanda).
 */
enum class OperationMode {
    TABLE_SERVICE, COUNTER_SERVICE, MIXED;

    companion object {
        fun parse(raw: String?): OperationMode = entries.firstOrNull { it.name == raw } ?: MIXED
    }
}

/**
 * Situação do terminal em relação ao servidor. São estados distintos de
 * propósito: "sem internet" e "tenho venda por enviar" pedem reações
 * diferentes do operador, e juntá-los num único "offline" esconderia
 * justamente o caso perigoso — ficar sem rede COM venda na fila.
 */
enum class ConnectionState {
    /** Com rede e nada por enviar. */
    ONLINE,

    /** Enviando a fila agora. */
    SYNCING,

    /** Com rede, mas ainda há operação por enviar (falha anterior). */
    PENDING,

    /** Sem rede e nada na fila — pode operar, tudo que fez já subiu. */
    OFFLINE,

    /** Sem rede E com venda na fila: não desligue o terminal. */
    OFFLINE_PENDING,
}

data class HomeUiState(
    val operatorName: String? = null,
    val operatorRole: String? = null,
    val locationName: String? = null,
    val access: OperatorAccess = OperatorAccess.PERMISSIVE,
    val operationMode: OperationMode = OperationMode.MIXED,
    val pendingPaymentAttempt: PendingCieloAttempt? = null,
    val pendingSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val isOnline: Boolean = true,
    /** Instante da última vez que a fila ficou vazia (epoch ms). */
    val lastSyncAt: Long? = null,
    /** Mesas e comandas ainda abertas nesta unidade. `null` = ainda carregando. */
    val openTabsCount: Int? = null,
    /**
     * Se há caixa aberto na unidade agora. `null` = ainda não sabemos (carregando,
     * sem rede, ou a unidade não exige caixa aberto para pagar — ver
     * [requiresCashSession]) — nesse estado a Home nunca mostra aviso, para não
     * arriscar um falso positivo.
     */
    val isCashOpen: Boolean? = null,
    /** Config da unidade (device-login) — só faz sentido avisar se isto for true. */
    val requiresCashSession: Boolean = true,
    /**
     * Toast de "caixa fechado" já mostrado nesta sessão da Home — aparece uma
     * vez (5s, dispensável no X) quando [isCashOpen] vira `false`; depois disso
     * o aviso continua acessível só pelo sino no cabeçalho, nunca de novo como
     * toast (evita reaparecer sozinho a cada `refresh()`/volta à tela).
     */
    val cashClosedToastShown: Boolean = false,
    /** Sino do cabeçalho tocado — reabre o aviso completo sob demanda. */
    val cashWarningDialogOpen: Boolean = false,
    /**
     * "Sair" tocado enquanto offline — confirmação com aviso forte antes de
     * derrubar a sessão do operador (ver [HomeViewModel.requestLogout]).
     * Nunca aparece online: ali o próximo operador consegue logar de volta
     * na hora, então o logout acontece direto, sem fricção.
     */
    val logoutConfirmationOpen: Boolean = false,
    /**
     * Operação que o servidor RECUSOU ao sincronizar (ver
     * SyncEngine.OperationRejected) — ex.: item lançado offline numa comanda
     * cujo caixa fechou nesse meio tempo. O item some da comanda (nunca vira
     * fantasma), mas o operador precisa saber POR QUÊ: sem isto, um item
     * simplesmente desaparecia da tela sem explicação nenhuma.
     */
    val syncRejectionMessage: String? = null,
) {
    /** Existe algo para o sino badge mostrar. */
    val hasCashWarning: Boolean get() = isCashOpen == false
    /** Mesas primeiro em serviço de mesa; balcão continua disponível em todo modo. */
    val highlightTables: Boolean get() = operationMode != OperationMode.COUNTER_SERVICE

    val connection: ConnectionState
        get() = when {
            isSyncing -> ConnectionState.SYNCING
            !isOnline && pendingSyncCount > 0 -> ConnectionState.OFFLINE_PENDING
            !isOnline -> ConnectionState.OFFLINE
            pendingSyncCount > 0 -> ConnectionState.PENDING
            else -> ConnectionState.ONLINE
        }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val cieloProvider: CieloDeepLinkPaymentProvider,
    private val outboxDao: OutboxDao,
    private val syncEngine: SyncEngine,
    private val syncStatusStore: SyncStatusStore,
    private val connectivityMonitor: ConnectivityMonitor,
    private val tabRepository: TabRepository,
    private val menuRepository: MenuRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(
            operatorName = authRepository.currentOperatorName(),
            operatorRole = authRepository.currentOperatorRole(),
            locationName = authRepository.locationName(),
            access = authRepository.currentAccess(),
            operationMode = OperationMode.parse(authRepository.operationMode()),
            isOnline = connectivityMonitor.isOnline(),
            requiresCashSession = authRepository.requiresOpenCashSessionForPayments(),
        ),
    )
    val state: StateFlow<HomeUiState> = _state

    init {
        // Seção 24/47 do PRD: se o app foi reaberto com um pagamento Cielo
        // sem resultado conhecido, bloqueia a operação normal até o gerente
        // resolver (confirmar manualmente com o extrato do terminal e
        // descartar, ou tentar de novo).
        viewModelScope.launch {
            _state.value = _state.value.copy(pendingPaymentAttempt = cieloProvider.recoverPendingAttempt())
        }

        // Contador de operações pendentes fica sempre visível — o operador
        // precisa saber que ainda há venda não sincronizada antes de encerrar
        // o turno e desligar a maquininha.
        viewModelScope.launch {
            outboxDao.observePendingCount().collect { count ->
                _state.value = _state.value.copy(pendingSyncCount = count)
            }
        }

        viewModelScope.launch {
            syncStatusStore.lastSyncAt.collect { at ->
                _state.value = _state.value.copy(lastSyncAt = at)
            }
        }

        // A rede voltando é o gatilho natural para esvaziar a fila: o operador
        // não deveria precisar abrir a Home de novo para isso acontecer.
        viewModelScope.launch {
            connectivityMonitor.observe().collect { online ->
                val wasOffline = !_state.value.isOnline
                _state.value = _state.value.copy(isOnline = online)
                if (online && wasOffline) syncPending()
            }
        }

        // Recusa definitiva do servidor durante a sincronização: o item já foi
        // removido da comanda (SyncEngine), mas o operador só descobriria pelo
        // sumiço se não avisássemos aqui — a Home é a tela que ele sempre
        // alcança depois de uma sincronização em background.
        viewModelScope.launch {
            syncEngine.events.collect { event ->
                if (event is SyncEvent.OperationRejected) {
                    _state.value = _state.value.copy(syncRejectionMessage = event.reason)
                }
            }
        }

        syncPending()
        loadOpenTabsCount()
        refreshOpenTabs()
        loadCashStatus()
        warmUpMenuCache()
    }

    /**
     * Consulta se há caixa aberto ANTES do operador montar qualquer pedido —
     * sem isso, ele só descobria na hora de cobrar, depois de já ter lançado
     * tudo (P4 do plano de evolução). Sem custo se a unidade não exige caixa
     * aberto para pagar (`requiresCashSession=false`): a Home nunca precisa
     * saber disso nesse caso.
     */
    private fun loadCashStatus() {
        if (!_state.value.requiresCashSession) return
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            val isOpen = tabRepository.isCashOpen(organizationId, locationId)
            _state.value = _state.value.copy(isCashOpen = isOpen)
        }
    }

    /**
     * Pré-carrega o cardápio assim que a Home abre, antes de o operador tocar
     * em "Nova venda".
     *
     * O ajuste 2 do brief era exatamente este: a primeira chamada HTTPS de
     * cada processo paga o custo de handshake TLS sozinha (o resto do app
     * reusa a mesma conexão depois) — medido em produção, ~400ms depois de
     * aquecida, vários segundos na primeira vez. Sem isso, quem pagava esse
     * custo era o operador parado na frente do cliente na primeira venda do
     * turno. Agora ele é pago aqui, em silêncio, enquanto a Home ainda está
     * sendo lida — e `CardapioViewModel` observa o Room continuamente, então
     * isto não duplica chamada nenhuma quando "Nova venda" for aberta de
     * verdade: o cardápio já vai estar sincronizado (ou o Room já sabe que
     * a rede falhou e segue com o que já tinha).
     */
    private fun warmUpMenuCache() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val menuId = authRepository.mainMenuId() ?: return
        viewModelScope.launch {
            runCatching { menuRepository.ensureMenuSynced(organizationId, menuId) }
        }
    }

    /**
     * Quantas mesas/comandas seguem abertas — observa o Room continuamente
     * (nunca uma leitura única): reflete tanto o que veio do servidor quanto
     * comandas abertas offline neste terminal, sem precisar de rede.
     *
     * Só isso NÃO basta pra ficar correto: o Room só é alimentado por uma
     * busca de rede real (searchOpenTabs), e a Home nunca disparava a sua
     * própria — o número ficava desatualizado até o operador visitar
     * Comandas/Mesas/Abertas (que chamam searchOpenTabs) e voltar. Por isso
     * [refreshOpenTabs] existe: dispara essa busca sempre que a Home volta a
     * ficar visível (ver HomeScreen, OnResumeEffect), mantendo o Flow aqui
     * como está, só garantindo que ele tem dado fresco pra refletir.
     */
    fun loadOpenTabsCount() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            tabRepository.observeOpenTabsCount(organizationId, locationId).collect { count ->
                _state.value = _state.value.copy(openTabsCount = count)
            }
        }
    }

    /** Ver comentário de [loadOpenTabsCount] — busca de rede que mantém o Room (e portanto o contador) em dia. */
    fun refreshOpenTabs() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch { tabRepository.searchOpenTabs(organizationId, locationId) }
    }

    /**
     * Sobe o que ficou pendente. Chamado ao abrir a Home e ao voltar para ela
     * — os dois momentos em que o operador está parado e a rede pode ter
     * voltado. Silencioso quando não há nada na fila. Isto é só um atalho
     * imediato: a sincronização de verdade é automática mesmo sem a Home
     * aberta (WorkManager + [SyncTriggerCoordinator], ver NoktaPosApplication).
     */
    fun syncPending() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSyncing = true)
            val result = syncEngine.syncAll()
            _state.value = _state.value.copy(
                isSyncing = false,
                syncMessage = if (result.processed > 0) {
                    "${result.processed} ${if (result.processed == 1) "operação enviada" else "operações enviadas"}."
                } else null,
            )
        }
    }

    fun clearSyncMessage() { _state.value = _state.value.copy(syncMessage = null) }

    /** Rechecado ao voltar para a Home — papel pode ter mudado no dashboard. */
    fun refresh() {
        _state.value = _state.value.copy(
            operatorName = authRepository.currentOperatorName(),
            operatorRole = authRepository.currentOperatorRole(),
            locationName = authRepository.locationName(),
            access = authRepository.currentAccess(),
            operationMode = OperationMode.parse(authRepository.operationMode()),
        )
        authRepository.currentOrganizationId()?.let { orgId ->
            viewModelScope.launch {
                authRepository.refreshAccess(orgId)
                _state.value = _state.value.copy(access = authRepository.currentAccess())
            }
        }
        // A contagem em si já é observada continuamente (loadOpenTabsCount,
        // chamado uma vez no init) — aqui só pedimos um refresh contra o
        // servidor para o Room não ficar defasado enquanto o operador estava
        // fora da Home.
        val organizationId = authRepository.currentOrganizationId()
        val locationId = authRepository.currentLocationId()
        if (organizationId != null && locationId != null) {
            viewModelScope.launch { tabRepository.searchOpenTabs(organizationId, locationId) }
        }
        loadCashStatus()
    }

    /** Resolve o `Tab.id` (Long) de uma tentativa de pagamento pendente para o `localId` que a navegação usa. */
    fun resolvePendingAttemptTabLocalId(tabId: Long, onResolved: (String) -> Unit) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            tabRepository.localIdForTabId(organizationId, tabId)?.let(onResolved)
        }
    }

    fun dismissPendingAttempt() {
        viewModelScope.launch {
            cieloProvider.discardPendingAttempt()
            _state.value = _state.value.copy(pendingPaymentAttempt = null)
        }
    }

    /** Aviso de operação recusada na sincronização dispensado pelo operador. */
    fun dismissSyncRejection() {
        _state.value = _state.value.copy(syncRejectionMessage = null)
    }

    /** Toast de caixa fechado dispensado (pelo X ou pelo tempo) — não reaparece sozinho. */
    fun dismissCashClosedToast() {
        _state.value = _state.value.copy(cashClosedToastShown = true)
    }

    /** Toque no sino: reabre o aviso completo. */
    fun openCashWarningDialog() {
        _state.value = _state.value.copy(cashWarningDialogOpen = true)
    }

    fun dismissCashWarningDialog() {
        _state.value = _state.value.copy(cashWarningDialogOpen = false)
    }

    /**
     * Online: desloga direto — o próximo operador consegue autenticar contra
     * o backend imediatamente, sem risco de deixar o terminal preso.
     *
     * Offline: um `deviceLogin` novo é impossível sem rede (precisa validar
     * senha contra o servidor), então sair agora deixaria o terminal SEM
     * NENHUM operador até a conexão voltar. Isso é uma decisão consciente do
     * operador, não um bug — por isso não é bloqueado, só confirmado com
     * aviso explícito da consequência (ver [LogoutConfirmationDialog]).
     */
    fun requestLogout(onLoggedOut: () -> Unit) {
        if (!_state.value.isOnline) {
            _state.value = _state.value.copy(logoutConfirmationOpen = true)
            return
        }
        authRepository.logoutOperator()
        onLoggedOut()
    }

    fun confirmLogoutOffline(onLoggedOut: () -> Unit) {
        _state.value = _state.value.copy(logoutConfirmationOpen = false)
        authRepository.logoutOperator()
        onLoggedOut()
    }

    fun dismissLogoutConfirmation() {
        _state.value = _state.value.copy(logoutConfirmationOpen = false)
    }
}
