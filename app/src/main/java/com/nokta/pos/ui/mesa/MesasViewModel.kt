package com.nokta.pos.ui.mesa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.comanda.domain.VenueTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer
import javax.inject.Inject

data class MesasUiState(
    val tables: List<VenueTable> = emptyList(),
    /** Mesas com comanda aberta (OPEN/CLOSING/PAYMENT_IN_PROGRESS) — fonte real da lista "Em atendimento" (traz contagem de itens, que VenueTable não tem). */
    val openTabs: List<Tab> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val openingTableId: Long? = null,
) {
    /** Mesa correspondente ao número digitado — busca "contains" (não paridade exata), mesas podem ter nome livre ("Varanda 3"). */
    val matchingTable: VenueTable?
        get() {
            val q = query.trim()
            if (q.isBlank()) return null
            return tables.filter { it.active }.firstOrNull { it.name.trim().equals(q, ignoreCase = true) }
                ?: tables.filter { it.active }.firstOrNull { it.name.normalize().contains(q.normalize()) }
        }

    /** Nenhuma mesa cadastrada bate com o número digitado — mostra "iniciar atendimento" só quando isto for claramente uma mesa real. */
    val queryMatchesNoTable: Boolean get() = query.isNotBlank() && matchingTable == null && tables.any { it.active }
}

private fun String.normalize(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()

/**
 * Localizar a mesa pelo número é o caminho principal (não é grade de
 * ocupação livre/ocupada — isso é gestão de salão, que é do dashboard).
 * "Em atendimento" mostra as mesas com consumo aberto, com itens e valor —
 * dados de [Tab] (via searchOpenTabs, tipo TABLE), não de [VenueTable]
 * (que só tem o total, sem contagem de itens).
 */
@HiltViewModel
class MesasViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MesasUiState())
    val state: StateFlow<MesasUiState> = _state

    private var searchJob: Job? = null

    init {
        observeTables()
        load()
        searchOpenTabs()
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

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            searchOpenTabs(query)
        }
    }

    private fun searchOpenTabs(query: String? = null) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.searchOpenTabs(organizationId, locationId, search = query, type = TabType.TABLE) }
                .onSuccess { tabs -> _state.value = _state.value.copy(openTabs = tabs) }
        }
    }

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
