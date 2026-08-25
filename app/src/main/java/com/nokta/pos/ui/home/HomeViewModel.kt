package com.nokta.pos.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.access.OperatorAccess
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.OperationRepository
import com.nokta.pos.payment.cielo.CieloDeepLinkPaymentProvider
import com.nokta.pos.payment.cielo.PendingCieloAttempt
import com.nokta.pos.sync.ConnectivityMonitor
import com.nokta.pos.sync.OutboxRepository
import com.nokta.pos.sync.SyncOutcome
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
) {
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
    private val outboxRepository: OutboxRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val operationRepository: OperationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(
            operatorName = authRepository.currentOperatorName(),
            operatorRole = authRepository.currentOperatorRole(),
            locationName = authRepository.locationName(),
            access = authRepository.currentAccess(),
            operationMode = OperationMode.parse(authRepository.operationMode()),
            isOnline = connectivityMonitor.isOnline(),
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
            outboxRepository.pendingCount.collect { count ->
                _state.value = _state.value.copy(pendingSyncCount = count)
            }
        }

        viewModelScope.launch {
            outboxRepository.lastSyncAt.collect { at ->
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

        syncPending()
        loadOpenTabsCount()
    }

    /**
     * Quantas mesas/comandas seguem abertas. Falha em silêncio (fica `null` e
     * o atalho some) — é informação de apoio, e uma queda de rede não pode
     * transformar a Home num amontoado de mensagens de erro.
     */
    fun loadOpenTabsCount() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            runCatching { operationRepository.searchOpenTabs(organizationId, locationId) }
                .onSuccess { tabs -> _state.value = _state.value.copy(openTabsCount = tabs.size) }
        }
    }

    /**
     * Sobe o que ficou pendente. Chamado ao abrir a Home e ao voltar para ela
     * — os dois momentos em que o operador está parado e a rede pode ter
     * voltado. Silencioso quando não há nada na fila.
     */
    fun syncPending() {
        viewModelScope.launch {
            if (outboxRepository.peekAll().isEmpty()) return@launch
            _state.value = _state.value.copy(isSyncing = true)
            val results = outboxRepository.syncAll()
            val rejected = results.count { it.second is SyncOutcome.Rejected }
            val synced = results.count { it.second is SyncOutcome.Success }
            _state.value = _state.value.copy(
                isSyncing = false,
                syncMessage = when {
                    rejected > 0 -> "$rejected ${if (rejected == 1) "operação foi recusada" else "operações foram recusadas"} pelo servidor. Confira as comandas."
                    synced > 0 -> "$synced ${if (synced == 1) "operação enviada" else "operações enviadas"}."
                    else -> null
                },
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
        loadOpenTabsCount()
    }

    fun dismissPendingAttempt() {
        viewModelScope.launch {
            cieloProvider.discardPendingAttempt()
            _state.value = _state.value.copy(pendingPaymentAttempt = null)
        }
    }

    fun logout() = authRepository.logoutOperator()
}
