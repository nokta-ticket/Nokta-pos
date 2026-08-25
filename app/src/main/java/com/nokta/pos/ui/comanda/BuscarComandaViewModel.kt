package com.nokta.pos.ui.comanda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.OperationRepository
import com.nokta.pos.comanda.domain.Tab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BuscarComandaUiState(
    val query: String = "",
    val results: List<Tab> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
)

/**
 * Consulta de comanda por número ou nome — o caminho que substitui o QR Code
 * (item 3/9 do brief: um garçom com maquininha não escaneia, ele digita
 * "123" ou "João").
 *
 * O mesmo campo serve para os dois porque o backend já busca em `publicCode`
 * OU `customerName` — o operador não precisa escolher o tipo de busca antes.
 *
 * A lista de comandas abertas aparece antes de qualquer digitação: na prática
 * o garçom quase sempre quer uma das poucas comandas do momento, e ver a
 * lista é mais rápido que lembrar o número.
 */
@HiltViewModel
class BuscarComandaViewModel @Inject constructor(
    private val operationRepository: OperationRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BuscarComandaUiState())
    val state: StateFlow<BuscarComandaUiState> = _state

    private var searchJob: Job? = null

    init { search("") }

    /** Debounce: o operador digita rápido; sem isso seria uma chamada por tecla. */
    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            search(query)
        }
    }

    fun searchNow() {
        searchJob?.cancel()
        search(_state.value.query)
    }

    private fun search(query: String) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return

        _state.value = _state.value.copy(isSearching = true, error = null)
        viewModelScope.launch {
            runCatching { operationRepository.searchOpenTabs(organizationId, locationId, query) }
                .onSuccess { tabs ->
                    _state.value = _state.value.copy(
                        results = tabs,
                        isSearching = false,
                        hasSearched = true,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isSearching = false,
                        hasSearched = true,
                        error = e.message ?: "Não foi possível buscar comandas.",
                    )
                }
        }
    }
}
