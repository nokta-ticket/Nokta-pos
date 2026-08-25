package com.nokta.pos.ui.historico

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.OperationRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.common.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoricoUiState(
    val tabs: List<Tab> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && error == null && tabs.isEmpty()

    /**
     * Soma do que foi fechado. É a conferência que o operador faz de cabeça no
     * fim do turno ("quanto passou por aqui?") — não é indicador gerencial: são
     * as mesmas vendas listadas logo abaixo, somadas.
     */
    val total: Money get() = Money(tabs.sumOf { it.total.cents })
}

/**
 * Histórico operacional: as últimas vendas encerradas desta unidade.
 *
 * Existe para o operador consultar o que acabou de acontecer — "o que tinha
 * na comanda 12 que fechei agora?", "quanto deu a última venda?". Não é
 * relatório: sem gráfico, sem período, sem comparação. Isso é o dashboard.
 */
@HiltViewModel
class HistoricoViewModel @Inject constructor(
    private val operationRepository: OperationRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoricoUiState())
    val state: StateFlow<HistoricoUiState> = _state

    init { load() }

    fun load() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return

        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { operationRepository.listRecentClosedTabs(organizationId, locationId) }
                .onSuccess { tabs -> _state.value = HistoricoUiState(tabs = tabs, isLoading = false) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Não foi possível carregar o histórico.",
                    )
                }
        }
    }
}
