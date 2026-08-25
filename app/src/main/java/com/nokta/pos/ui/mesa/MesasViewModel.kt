package com.nokta.pos.ui.mesa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.comanda.domain.VenueTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer
import javax.inject.Inject

data class MesasUiState(
    val tables: List<VenueTable> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val openingTableId: Long? = null,
) {
    val visibleTables: List<VenueTable>
        get() {
            val q = query.trim().lowercase()
            val active = tables.filter { it.active }
            return if (q.isBlank()) active
            else active.filter { table ->
                table.name.lowercase().contains(q) ||
                    table.openTabCustomerName?.normalize()?.contains(q.normalize()) == true
            }
        }

    val occupiedCount get() = tables.count { it.isOccupied }
}

private fun String.normalize(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()

/**
 * Lista de mesas para operar — não é mapa de salão nem gestão de layout
 * (item 8: isso é do dashboard). O garçom quer: ver quais estão ocupadas,
 * quanto cada uma consumiu, e entrar numa delas para lançar ou cobrar.
 *
 * `GET tables` já devolve a comanda aberta de cada mesa, então a tela inteira
 * sai de uma chamada só — sem N+1 nem endpoint novo.
 */
@HiltViewModel
class MesasViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MesasUiState())
    val state: StateFlow<MesasUiState> = _state

    init {
        observeTables()
        load()
    }

    /** A tela observa o Room continuamente — mostra o último dado conhecido com aviso de idade se offline. */
    private fun observeTables() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            tabRepository.observeTables(organizationId, locationId).collect { tables ->
                _state.value = _state.value.copy(tables = tables, isLoading = false)
            }
        }
    }

    /** Puxão explícito contra o servidor — falha em silêncio na tela: o Room já mostra o último snapshot conhecido. */
    fun load() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            tabRepository.refreshTables(organizationId, locationId)
        }
    }

    fun setQuery(query: String) { _state.value = _state.value.copy(query = query) }

    /**
     * Entra numa mesa. Se já houver comanda aberta, abre ela; se não, abre uma
     * comanda nova para aquela mesa e entra — o garçom não deveria precisar
     * decidir "criar ou abrir", ele só quer atender a mesa 12.
     *
     * O índice único parcial do backend (`venue_tabs_one_open_per_table`)
     * garante que duas maquininhas tocando a mesma mesa ao mesmo tempo nunca
     * criam duas comandas: a segunda falha e relemos o estado real.
     */
    fun openTable(table: VenueTable, onOpened: (String) -> Unit) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return

        table.openTabId?.let { serverId ->
            viewModelScope.launch { onOpened(tabRepository.localIdForServerId(organizationId, locationId, serverId)) }
            return
        }

        if (_state.value.openingTableId != null) return

        _state.value = _state.value.copy(openingTableId = table.id, error = null)
        viewModelScope.launch {
            runCatching {
                tabRepository.openTab(
                    organizationId = organizationId,
                    locationId = locationId,
                    type = TabType.TABLE,
                    tableId = table.id,
                    tableName = table.name,
                )
            }.onSuccess { tab ->
                _state.value = _state.value.copy(openingTableId = null)
                onOpened(tab.localId)
            }.onFailure { e ->
                // Corrida com outro terminal: relê para pegar a comanda que o
                // outro acabou de abrir, em vez de insistir em criar.
                _state.value = _state.value.copy(
                    openingTableId = null,
                    error = e.message ?: "Não foi possível abrir a mesa.",
                )
                load()
            }
        }
    }
}
