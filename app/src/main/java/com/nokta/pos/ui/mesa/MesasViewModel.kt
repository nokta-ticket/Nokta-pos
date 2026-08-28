package com.nokta.pos.ui.mesa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.comanda.domain.VenueTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer
import javax.inject.Inject

/**
 * A tela de Mesas tem 3 modos, nunca misturados na mesma tela — reflete as
 * duas ações principais do briefing ("Abrir mesa" / "Consultar mesa") mais o
 * estado inicial (central de operação, com a lista "Em atendimento" sempre
 * visível). Trocar de modo nunca perde o que já foi carregado (tables/
 * openTabs continuam no estado raiz).
 */
enum class MesasMode { CENTRAL, ABRIR, CONSULTAR }

data class MesasUiState(
    val mode: MesasMode = MesasMode.CENTRAL,
    val tables: List<VenueTable> = emptyList(),
    /** Mesas com comanda aberta (OPEN/CLOSING/PAYMENT_IN_PROGRESS) — fonte real da lista "Em atendimento" (traz contagem de itens, que VenueTable não tem). */
    val openTabs: List<Tab> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Número digitado dentro do fluxo Abrir/Consultar — nunca usado fora deles (a lista "Em atendimento" não filtra por isto). */
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

    /** Nenhuma mesa cadastrada bate com o número digitado — mostra o resultado só quando isto for claramente uma mesa real. */
    val queryMatchesNoTable: Boolean get() = query.isNotBlank() && matchingTable == null && tables.any { it.active }
}

private fun String.normalize(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()

/**
 * Central de operação de Mesas — duas ações claras ("Abrir mesa"/"Consultar
 * mesa") mais a lista "Mesas em atendimento" sempre visível. Mesa física
 * (VenueTable) e atendimento/consumo (Tab) continuam entidades separadas
 * (ver ComandaModels.kt): o número da mesa nunca é usado como identificador
 * de venda, só para localizar o atendimento aberto (ou decidir abrir um
 * novo) — a mesma mesa pode ter vários atendimentos ao longo do dia, só
 * nunca mais de um aberto ao mesmo tempo (garantido pelo backend).
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

    /** Lista "Mesas em atendimento" — sempre a mesma busca (sem filtro de texto), independente do modo atual da tela. */
    private fun searchOpenTabs() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.searchOpenTabs(organizationId, locationId, search = null, type = TabType.TABLE) }
                .onSuccess { tabs -> _state.value = _state.value.copy(openTabs = tabs) }
        }
    }

    fun openAbrirMesa() {
        _state.value = _state.value.copy(mode = MesasMode.ABRIR, query = "", error = null)
    }

    fun openConsultarMesa() {
        _state.value = _state.value.copy(mode = MesasMode.CONSULTAR, query = "", error = null)
    }

    fun backToCentral() {
        _state.value = _state.value.copy(mode = MesasMode.CENTRAL, query = "", error = null)
        searchOpenTabs()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /**
     * Entra numa mesa. Se já houver comanda aberta, abre ela; se não, abre uma
     * comanda nova para aquela mesa e entra. Usada tanto por "Abrir mesa"
     * (o garçom espera criar um atendimento novo) quanto por "Consultar mesa"
     * (o garçom espera achar um atendimento já existente) — a MESMA função,
     * porque o resultado real nunca muda por causa da intenção do garçom: se
     * já existe atendimento, sempre entra nele (nunca cria um segundo); a UI
     * de cada modo é quem escolhe a mensagem/botão certos para cada caso (ver
     * MesasScreen — NoOpenTabOrOccupiedContent).
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
