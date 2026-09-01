package com.nokta.pos.ui.abertas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AbertasUiState(
    val tabs: List<Tab> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Card "Abertas" da Home: TUDO que está fisicamente em atendimento agora,
 * junto numa lista só — mesa, cartão físico e pulseira (as duas últimas já
 * abertas por algum garçom; um número de pulseira nunca cadastrado
 * previamente não aparece aqui, só depois da 1ª consulta que o cria — ver
 * VenueTabsService.resolveByPhysicalCode).
 *
 * Sem filtro de tipo em [TabRepository.searchOpenTabs] de propósito — é
 * exatamente essa mistura que diferencia esta tela das listas "Em
 * atendimento" já existentes em Mesas (só TABLE) e Comandas (só
 * WRISTBAND/cartão físico, cada aba isolada).
 */
@HiltViewModel
class AbertasViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AbertasUiState())
    val state: StateFlow<AbertasUiState> = _state

    init {
        load()
    }

    fun load() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val tabs = tabRepository.searchOpenTabs(organizationId, locationId)
            _state.value = _state.value.copy(isLoading = false, tabs = tabs)
        }
    }
}
