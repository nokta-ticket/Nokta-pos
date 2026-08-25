package com.nokta.pos.ui.comanda

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.access.OperatorAccess
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.OperationRepository
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
 * Todos os valores vêm do servidor a cada leitura — nunca somamos itens
 * localmente para dizer quanto falta pagar. Duas maquininhas podem estar na
 * mesma comanda ao mesmo tempo, e o backend é a única fonte de verdade
 * (`lockTab` + `recalculateTabTotals` a cada mutação).
 */
@HiltViewModel
class ComandaViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val operationRepository: OperationRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    val tabId: Long = savedStateHandle.get<Long>("tabId") ?: error("tabId ausente")

    private val _state = MutableStateFlow(ComandaUiState(access = authRepository.currentAccess()))
    val state: StateFlow<ComandaUiState> = _state

    init { refresh() }

    fun refresh() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { operationRepository.getTab(organizationId, tabId) }
                .onSuccess { tab ->
                    _state.value = _state.value.copy(
                        tab = tab,
                        isLoading = false,
                        access = authRepository.currentAccess(),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Não foi possível carregar a comanda.",
                    )
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
     */
    fun confirmCancelItem(reason: String) {
        val item = _state.value.itemPendingCancel ?: return
        val organizationId = authRepository.currentOrganizationId() ?: return
        if (reason.isBlank()) return

        _state.value = _state.value.copy(isCancelingItem = true)
        viewModelScope.launch {
            runCatching { operationRepository.cancelItem(organizationId, item.id, reason.trim()) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        isCancelingItem = false,
                        itemPendingCancel = null,
                        actionMessage = "Item cancelado.",
                    )
                    refresh()
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
     */
    fun closeTab() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val tab = _state.value.tab ?: return
        if (!tab.isFullyPaid) return

        _state.value = _state.value.copy(isClosing = true)
        viewModelScope.launch {
            runCatching { operationRepository.closeTab(organizationId, tab.id) }
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
