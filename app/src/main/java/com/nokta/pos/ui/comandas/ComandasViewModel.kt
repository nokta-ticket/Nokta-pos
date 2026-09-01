package com.nokta.pos.ui.comandas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.network.humanizedApiMessage
import com.nokta.pos.sync.ConnectivityChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ComandaKind(val label: String, val apiValue: String) {
    WRISTBAND("Pulseira", "WRISTBAND"),
    CARD("Cartão físico", "CARD"),
}

/** Formulário de vinculação de cliente a um cartão AVAILABLE — nome+telefone sempre obrigatórios (mesma regra da comanda comum). */
data class BindCardFormState(
    val cardId: Long,
    val publicCode: String,
    val name: String = "",
    val phone: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val isValid: Boolean get() = name.trim().length >= 2 && phone.trim().length >= 8
}

data class ComandasUiState(
    val selectedKind: ComandaKind = ComandaKind.WRISTBAND,
    val code: String = "",
    val isResolving: Boolean = false,
    val error: String? = null,
    val bindForm: BindCardFormState? = null,
    val openTabs: List<Tab> = emptyList(),
    val isLoadingOpenTabs: Boolean = false,
)

/**
 * Tela "Comandas" — fluxo simplificado por pulseira/cartão físico (substitui
 * o antigo formulário de mesa/comanda genérico). O garçom digita só o número
 * + o tipo; o backend decide o resultado (ver
 * TabRepository.resolvePhysicalCode):
 *  - Pulseira: sempre abre o detalhamento direto, criando o atendimento na
 *    hora se este número ainda não tiver nenhum (nunca pede cliente).
 *  - Cartão: se já vinculado, abre direto; se disponível, mostra o
 *    formulário de vinculação (nome+telefone) antes de abrir.
 *
 * Deliberadamente ONLINE-ONLY (ver doc de TabRepository.resolvePhysicalCode)
 * — ao contrário do resto do POS (offline-first), decidir "esta pulseira já
 * tem atendimento?"/"este cartão está disponível?" exige o servidor como
 * fonte de verdade. Checa conectividade ANTES de chamar a API, pra mostrar
 * um aviso claro em vez de deixar a chamada estourar sem contexto.
 */
@HiltViewModel
class ComandasViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
    private val connectivityChecker: ConnectivityChecker,
) : ViewModel() {

    private val _state = MutableStateFlow(ComandasUiState())
    val state: StateFlow<ComandasUiState> = _state

    init {
        loadOpenTabs()
    }

    fun selectKind(kind: ComandaKind) {
        _state.value = _state.value.copy(selectedKind = kind, code = "", error = null, openTabs = emptyList())
        loadOpenTabs()
    }

    /**
     * Lista "Em atendimento" da tela, filtrada pelo tipo selecionado.
     * Pulseira: TabType.WRISTBAND filtra certinho no próprio backend. Cartão
     * físico nasce INDIVIDUAL no backend (mesmo tipo de uma comanda avulsa
     * aberta por outro meio) — por isso o filtro extra local por
     * `isPhysicalCard` (ver TabResponse.physicalCard/TabEntity.isPhysicalCard),
     * senão a aba "Cartão físico" misturaria comandas que não vieram de
     * cartão nenhum.
     */
    fun loadOpenTabs() {
        val organizationId = authRepository.currentOrganizationId()
        val locationId = authRepository.currentLocationId()
        if (organizationId == null || locationId == null) return

        val kind = _state.value.selectedKind
        _state.value = _state.value.copy(isLoadingOpenTabs = true)
        viewModelScope.launch {
            val tabs = when (kind) {
                ComandaKind.WRISTBAND -> tabRepository.searchOpenTabs(organizationId, locationId, type = TabType.WRISTBAND)
                ComandaKind.CARD -> tabRepository.searchOpenTabs(organizationId, locationId, type = TabType.INDIVIDUAL)
                    .filter { it.isPhysicalCard }
            }
            // selectedKind pode ter mudado enquanto a busca corria (troca
            // rápida de aba) — nunca sobrescreve a lista com o resultado da
            // consulta anterior, que já não corresponde à aba visível.
            if (_state.value.selectedKind == kind) {
                _state.value = _state.value.copy(isLoadingOpenTabs = false, openTabs = tabs)
            }
        }
    }

    fun setCode(code: String) {
        _state.value = _state.value.copy(code = code.filter { it.isLetterOrDigit() }, error = null)
    }

    fun resolve(onOpenTab: (String) -> Unit) {
        val current = _state.value
        val code = current.code.trim()
        if (code.isEmpty() || current.isResolving) return

        val organizationId = authRepository.currentOrganizationId()
        val locationId = authRepository.currentLocationId()
        if (organizationId == null || locationId == null) {
            _state.value = current.copy(error = "Sessão inválida — faça login novamente.")
            return
        }
        if (!connectivityChecker.isOnline()) {
            _state.value = current.copy(error = "Sem conexão — não é possível abrir pulseira/cartão agora. Tente novamente com internet.")
            return
        }

        _state.value = current.copy(isResolving = true, error = null)
        viewModelScope.launch {
            runCatching {
                tabRepository.resolvePhysicalCode(organizationId, locationId, current.selectedKind.apiValue, code)
            }.onSuccess { result ->
                when (result) {
                    is TabRepository.PhysicalCodeResult.OpenedTab -> {
                        _state.value = ComandasUiState(selectedKind = current.selectedKind)
                        onOpenTab(result.tab.localId)
                    }
                    is TabRepository.PhysicalCodeResult.CardAvailable -> {
                        _state.value = current.copy(
                            isResolving = false,
                            bindForm = BindCardFormState(cardId = result.cardId, publicCode = result.publicCode),
                        )
                    }
                }
            }.onFailure { e ->
                _state.value = current.copy(isResolving = false, error = e.humanizedApiMessage("Não foi possível consultar este número."))
            }
        }
    }

    fun dismissBindForm() {
        _state.value = _state.value.copy(bindForm = null, code = "")
    }

    fun setBindFormName(name: String) {
        _state.value = _state.value.copy(bindForm = _state.value.bindForm?.copy(name = name, error = null))
    }

    fun setBindFormPhone(phone: String) {
        _state.value = _state.value.copy(bindForm = _state.value.bindForm?.copy(phone = phone, error = null))
    }

    fun confirmBindForm(onOpenTab: (String) -> Unit) {
        val form = _state.value.bindForm ?: return
        if (!form.isValid || form.isSaving) return

        val organizationId = authRepository.currentOrganizationId()
        val locationId = authRepository.currentLocationId()
        if (organizationId == null || locationId == null) return
        if (!connectivityChecker.isOnline()) {
            _state.value = _state.value.copy(bindForm = form.copy(error = "Sem conexão — tente novamente com internet."))
            return
        }

        _state.value = _state.value.copy(bindForm = form.copy(isSaving = true, error = null))
        viewModelScope.launch {
            runCatching {
                tabRepository.bindPhysicalCard(organizationId, locationId, form.cardId, form.name.trim(), form.phone.trim())
            }.onSuccess { tab: Tab ->
                _state.value = ComandasUiState(selectedKind = ComandaKind.CARD)
                onOpenTab(tab.localId)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    bindForm = form.copy(isSaving = false, error = e.humanizedApiMessage("Não foi possível vincular o cartão.")),
                )
            }
        }
    }
}
