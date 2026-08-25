package com.nokta.pos.ui.comanda

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.access.OperatorAccess
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.CancelItemOutcome
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComandaUiState(
    val tab: Tab? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val access: OperatorAccess = OperatorAccess.PERMISSIVE,
    val itemPendingCancel: TabItem? = null,
    val isCancelingItem: Boolean = false,
    val actionMessage: String? = null,
    val isClosing: Boolean = false,
    val closed: Boolean = false,
)

/**
 * Detalhe da comanda/mesa: consumo, pagamentos e saldo.
 *
 * Offline-first: a tela OBSERVA o Room continuamente (nunca "carrega uma vez
 * e espera resposta") — qualquer escrita local (item lançado, pagamento
 * registrado por esta tela ou por outra) aparece na hora. `tab.syncState`
 * (ver ComandaModels.kt) diz à UI se o que está sendo mostrado já foi
 * confirmado pelo servidor ou é a melhor estimativa local pendente — os
 * totais em si nunca são recalculados aqui, sempre vêm do Room (que por sua
 * vez reflete o servidor quando sincronizado).
 */
@HiltViewModel
class ComandaViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    val tabLocalId: String = savedStateHandle.get<String>("tabId") ?: error("tabId ausente")

    private val _state = MutableStateFlow(ComandaUiState(access = authRepository.currentAccess()))
    val state: StateFlow<ComandaUiState> = _state

    init {
        observeTab()
        refresh()
    }

    private fun observeTab() {
        viewModelScope.launch {
            tabRepository.observeTab(tabLocalId).collect { tab ->
                _state.value = _state.value.copy(tab = tab, isLoading = tab == null && _state.value.error == null)
            }
        }
    }

    /** Puxão explícito contra o servidor (pull-to-refresh) — nunca a única fonte da tela. */
    fun refresh() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.getTab(organizationId, tabLocalId) }
                .onSuccess { _state.value = _state.value.copy(isLoading = false, error = null, access = authRepository.currentAccess()) }
                .onFailure { e ->
                    // Erro só é bloqueante se ainda não há nada no Room para mostrar.
                    if (_state.value.tab == null) {
                        _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Não foi possível carregar a comanda.")
                    } else {
                        _state.value = _state.value.copy(isLoading = false)
                    }
                }
        }
    }

    fun askCancelItem(item: TabItem) { _state.value = _state.value.copy(itemPendingCancel = item) }
    fun dismissCancelItem() { _state.value = _state.value.copy(itemPendingCancel = null) }
    fun clearActionMessage() { _state.value = _state.value.copy(actionMessage = null) }

    /**
     * Cancela um item lançado por engano. O backend nunca apaga da ledger:
     * grava CANCELED com autor e motivo e recalcula o total. Por isso o
     * motivo é obrigatório aqui também — sem ele a auditoria fica cega.
     *
     * Cancelamento exige rede (é uma escrita de auditoria contra o servidor,
     * nunca inventada localmente) — se o item ainda nem foi confirmado pelo
     * servidor (lançado offline, na fila), ele é removido do rascunho local
     * em vez de "cancelado" (ver [TabRepository.cancelItem]).
     */
    fun confirmCancelItem(reason: String) {
        val item = _state.value.itemPendingCancel ?: return
        val organizationId = authRepository.currentOrganizationId() ?: return
        if (reason.isBlank()) return

        _state.value = _state.value.copy(isCancelingItem = true)
        viewModelScope.launch {
            runCatching { tabRepository.cancelItem(organizationId, item.localId, reason.trim()) }
                .onSuccess { outcome ->
                    val message = when (outcome) {
                        CancelItemOutcome.Success -> "Item cancelado."
                        CancelItemOutcome.RemovedLocalDraft -> "Item removido (ainda não havia sido confirmado)."
                        CancelItemOutcome.OfflineNotSupported -> "Sem conexão — tente cancelar novamente quando a internet voltar."
                        CancelItemOutcome.NotFound -> "Item não encontrado."
                    }
                    _state.value = _state.value.copy(isCancelingItem = false, itemPendingCancel = null, actionMessage = message)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isCancelingItem = false,
                        itemPendingCancel = null,
                        actionMessage = e.message ?: "Não foi possível cancelar o item.",
                    )
                }
        }
    }

    /**
     * Fecha a comanda já quitada. Só aparece quando `remaining` é zero — o
     * backend recusaria qualquer outra tentativa, e mostrar um botão que
     * sempre falha seria pior que não mostrar.
     *
     * Exige que a comanda já esteja sincronizada (tenha `serverId`) — fechar
     * é uma operação terminal que precisa da confirmação do servidor, nunca
     * enfileirada offline.
     */
    fun closeTab() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val tab = _state.value.tab ?: return
        if (!tab.isFullyPaid) return

        _state.value = _state.value.copy(isClosing = true)
        viewModelScope.launch {
            runCatching { tabRepository.closeTab(organizationId, tabLocalId) }
                .onSuccess { _state.value = _state.value.copy(isClosing = false, closed = true) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isClosing = false,
                        actionMessage = e.message ?: "Não foi possível fechar a comanda.",
                    )
                }
        }
    }
}
