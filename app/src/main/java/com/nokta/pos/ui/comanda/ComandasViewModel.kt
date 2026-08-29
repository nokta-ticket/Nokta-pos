package com.nokta.pos.ui.comanda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ComandaTab { OPEN, CLOSED }

/**
 * Formulário de nova comanda: nome completo e telefone SEMPRE obrigatórios
 * (decisão de produto — nunca existe comanda com "cliente não informado").
 * Sem validação de formato de telefone aqui: o operador digita como o
 * cliente falar, o backend não impõe máscara própria para este campo.
 */
data class NovaComandaFormState(
    val visible: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val isValid: Boolean get() = name.trim().length >= 2 && phone.trim().length >= 8
}

data class ComandasUiState(
    val selectedTab: ComandaTab = ComandaTab.OPEN,
    val query: String = "",
    val openTabs: List<Tab> = emptyList(),
    val closedTabs: List<Tab> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val form: NovaComandaFormState = NovaComandaFormState(),
) {
    private fun List<Tab>.filteredBy(query: String): List<Tab> {
        val q = query.trim()
        if (q.isBlank()) return this
        return filter {
            it.publicCode.contains(q, ignoreCase = true) ||
                it.customerName?.contains(q, ignoreCase = true) == true
        }
    }

    val visibleTabs: List<Tab>
        get() = when (selectedTab) {
            ComandaTab.OPEN -> openTabs.filteredBy(query)
            ComandaTab.CLOSED -> closedTabs.filteredBy(query)
        }
}

/**
 * Tela "Comandas": só comandas de cliente (TabType.INDIVIDUAL) — mesa é
 * outro fluxo (MesasScreen) e balcão nem passa por aqui (venda direta, ver
 * NovaVendaScreen). Duas abas (Abertas/Encerradas), busca por código/nome, e
 * criação de comanda nova exigindo nome completo + telefone do cliente antes
 * de abrir (nunca "cliente não informado" — decisão explícita de produto).
 */
@HiltViewModel
class ComandasViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ComandasUiState())
    val state: StateFlow<ComandasUiState> = _state

    private var searchJob: Job? = null

    init {
        load()
    }

    fun selectTab(tab: ComandaTab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            load()
        }
    }

    fun load() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        val query = _state.value.query.trim().takeIf { it.isNotEmpty() }

        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val open = tabRepository.searchOpenTabs(organizationId, locationId, search = query, type = TabType.INDIVIDUAL)
                val closed = tabRepository.listRecentClosedTabs(organizationId, locationId)
                    .filter { it.type == TabType.INDIVIDUAL }
                open to closed
            }.onSuccess { (open, closed) ->
                _state.value = _state.value.copy(openTabs = open, closedTabs = closed, isLoading = false)
            }.onFailure { e ->
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Não foi possível carregar as comandas.")
            }
        }
    }

    fun openNovaComandaForm() {
        _state.value = _state.value.copy(form = NovaComandaFormState(visible = true))
    }

    fun dismissNovaComandaForm() {
        _state.value = _state.value.copy(form = NovaComandaFormState(visible = false))
    }

    fun setFormName(name: String) {
        _state.value = _state.value.copy(form = _state.value.form.copy(name = name, error = null))
    }

    fun setFormPhone(phone: String) {
        _state.value = _state.value.copy(form = _state.value.form.copy(phone = phone, error = null))
    }

    /**
     * Cria a comanda só depois de validar nome+telefone — o botão de
     * confirmar já fica desabilitado enquanto `form.isValid` é falso, esta
     * checagem aqui é a segunda barreira (nunca confiar só na UI).
     */
    fun confirmNovaComanda(onOpened: (String) -> Unit) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        val form = _state.value.form
        if (!form.isValid || form.isSaving) return

        _state.value = _state.value.copy(form = form.copy(isSaving = true, error = null))
        viewModelScope.launch {
            runCatching {
                tabRepository.openTab(
                    organizationId = organizationId,
                    locationId = locationId,
                    type = TabType.INDIVIDUAL,
                    customerName = form.name.trim(),
                    customerPhone = form.phone.trim(),
                )
            }.onSuccess { tab ->
                _state.value = _state.value.copy(form = NovaComandaFormState(visible = false))
                onOpened(tab.localId)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    form = form.copy(isSaving = false, error = e.message ?: "Não foi possível abrir a comanda."),
                )
            }
        }
    }
}
