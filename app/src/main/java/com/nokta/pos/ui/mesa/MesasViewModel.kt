package com.nokta.pos.ui.mesa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
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
 * visível). Trocar de modo nunca perde o que já foi carregado (openTabs
 * continua no estado raiz).
 *
 * IMPORTANTE: não existe cadastro prévio de mesa. O garçom só digita o
 * número da mesa em que o cliente sentou — a mesa é criada (ou reaproveitada,
 * se já existir uma com o mesmo nome) pelo backend na hora de abrir a
 * comanda (ver VenueTabsService.resolveOrCreateTableByName). O dashboard só
 * reflete depois o que o POS já criou; ele NUNCA é o lugar onde a mesa
 * "precisa" ser cadastrada antes de poder ser usada aqui.
 */
enum class MesasMode { CENTRAL, ABRIR, CONSULTAR }

data class MesasUiState(
    val mode: MesasMode = MesasMode.CENTRAL,
    /** Comandas de mesa em atendimento (OPEN/CLOSING/PAYMENT_IN_PROGRESS) — fonte real da lista "Em atendimento" e da resolução local por número dentro de Abrir/Consultar. */
    val openTabs: List<Tab> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Número digitado dentro do fluxo Abrir/Consultar — nunca usado fora deles. */
    val query: String = "",
    val confirmedQuery: String? = null,
    val isOpening: Boolean = false,
) {
    /** Comanda de mesa já aberta cujo nome bate com o número confirmado — resolução 100% local (Room), funciona offline. */
    val matchingOpenTab: Tab?
        get() {
            val q = confirmedQuery?.trim() ?: return null
            if (q.isBlank()) return null
            return openTabs.filter { it.type == TabType.TABLE }.firstOrNull { it.tableName?.trim().equals(q, ignoreCase = true) }
                ?: openTabs.filter { it.type == TabType.TABLE }
                    .firstOrNull { it.tableName?.trim()?.normalize()?.contains(q.normalize()) == true }
        }
}

private fun String.normalize(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()

/**
 * Central de operação de Mesas — duas ações claras ("Abrir mesa"/"Consultar
 * mesa") mais a lista "Mesas em atendimento" sempre visível. O número da
 * mesa nunca é usado como identificador de venda — é só o texto livre que o
 * backend usa para achar ou criar a mesa física correspondente.
 */
@HiltViewModel
class MesasViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MesasUiState())
    val state: StateFlow<MesasUiState> = _state

    init {
        searchOpenTabs()
    }

    /** Lista "Mesas em atendimento" — sempre a mesma busca, independente do modo atual da tela. Tenta a rede e cai para o Room se offline. */
    fun searchOpenTabs() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.searchOpenTabs(organizationId, locationId, search = null, type = TabType.TABLE) }
                .onSuccess { tabs -> _state.value = _state.value.copy(openTabs = tabs, isLoading = false) }
                .onFailure { _state.value = _state.value.copy(isLoading = false) }
        }
    }

    fun openAbrirMesa() {
        _state.value = _state.value.copy(mode = MesasMode.ABRIR, query = "", confirmedQuery = null, error = null)
    }

    fun openConsultarMesa() {
        _state.value = _state.value.copy(mode = MesasMode.CONSULTAR, query = "", confirmedQuery = null, error = null)
    }

    fun backToCentral() {
        _state.value = _state.value.copy(mode = MesasMode.CENTRAL, query = "", confirmedQuery = null, error = null)
        searchOpenTabs()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /**
     * Confirma o número digitado — resolve localmente contra as comandas já
     * em atendimento (funciona offline).
     *
     * Digitar o número já É a intenção de ver o resultado — nunca mostra uma
     * tela intermediária pedindo confirmação de novo quando dá pra agir
     * direto:
     *  - ABRIR sem mesa ocupada: abre direto ([openByName]).
     *  - CONSULTAR com mesa já ocupada: entra direto no consumo
     *    ([openExisting]) — "consultar" já é ver, não precisa de um botão
     *    "Ver consumo" a mais.
     * Só cai na tela de resultado quando a intenção muda de verdade: ABRIR
     * numa mesa já ocupada (o garçom precisa saber disso antes de ir pra
     * "Consultar mesa", ver MesasScreen.ResultContent/occupiedTitle) ou
     * CONSULTAR uma mesa vazia (pede confirmação antes de virar uma
     * abertura, que é uma ação diferente da que foi pedida).
     */
    fun confirmQuery(onOpened: (String) -> Unit) {
        val trimmed = _state.value.query.trim()
        _state.value = _state.value.copy(confirmedQuery = trimmed, error = null)
        val mode = _state.value.mode
        val matching = _state.value.matchingOpenTab
        if (mode == MesasMode.ABRIR && matching == null) {
            openByName(trimmed, onOpened)
        } else if (mode == MesasMode.CONSULTAR && matching != null) {
            openExisting(matching, onOpened)
        }
    }

    /**
     * Abre a mesa pelo número confirmado — usada tanto por "Abrir mesa"
     * quanto por "Consultar mesa" quando ainda não há atendimento local
     * conhecido com este nome (a UI de cada modo decide o texto/botão certos,
     * ver MesasScreen). Se já existir comanda aberta com este nome (mesmo que
     * este terminal não soubesse ainda — outra maquininha abriu), o backend
     * devolve essa comanda em vez de criar uma segunda.
     */
    fun openByName(name: String, onOpened: (String) -> Unit) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank() || _state.value.isOpening) return

        _state.value = _state.value.copy(isOpening = true, error = null)
        viewModelScope.launch {
            runCatching {
                tabRepository.openTab(
                    organizationId = organizationId,
                    locationId = locationId,
                    type = TabType.TABLE,
                    tableName = trimmed,
                )
            }.onSuccess { tab ->
                _state.value = _state.value.copy(isOpening = false)
                onOpened(tab.localId)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isOpening = false,
                    error = e.message ?: "Não foi possível abrir a mesa.",
                )
                searchOpenTabs()
            }
        }
    }

    /** Entra direto numa comanda já conhecida localmente (ver matchingOpenTab) — sem chamada de rede. */
    fun openExisting(tab: Tab, onOpened: (String) -> Unit) {
        onOpened(tab.localId)
    }
}
