package com.nokta.pos.ui.comanda

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.access.OperatorAccess
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.CancelItemOutcome
import com.nokta.pos.comanda.data.TabRepository
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
 * Offline-first: a tela OBSERVA o Room continuamente (nunca "carrega uma vez
 * e espera resposta") — qualquer escrita local (item lançado, pagamento
 * registrado por esta tela ou por outra) aparece na hora. `tab.syncState`
 * (ver ComandaModels.kt) diz à UI se o que está sendo mostrado já foi
 * confirmado pelo servidor ou é a melhor estimativa local pendente — os
 * totais em si nunca são recalculados aqui, sempre vêm do Room (que por sua
 * vez reflete o servidor quando sincronizado).
 */
@HiltViewModel
class ComandaViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    val tabLocalId: String = savedStateHandle.get<String>("tabId") ?: error("tabId ausente")

    private val _state = MutableStateFlow(ComandaUiState(access = authRepository.currentAccess()))
    val state: StateFlow<ComandaUiState> = _state

    /**
     * Contagem de itens ativos na última emissão observada DEPOIS que a
     * comanda já foi confirmada pelo menos uma vez contra o servidor (ver
     * [hasConfirmedOnce]) — usada só para detectar "um pedido novo acabou de
     * entrar" (ver observeTab) e avisar o garçom.
     *
     * `localIdForServerId`/navegação por serverId grava no Room um registro
     * MÍNIMO (0 itens) antes do primeiro `refresh()` trazer os dados reais —
     * o Room emite as duas versões em sequência (0 itens, depois N itens
     * pós-sync), o que pareceria um "aumento" sem nenhum item ter sido
     * lançado de verdade. Por isso a contagem só começa a ser rastreada
     * depois que `refresh()` já confirmou os dados pelo menos uma vez —
     * qualquer emissão do Room antes disso é só a comanda "aparecendo",
     * nunca um pedido novo.
     */
    private var lastKnownActiveItemCount: Int? = null
    private var hasConfirmedOnce = false

    init {
        observeTab()
        refresh()
    }

    private fun observeTab() {
        viewModelScope.launch {
            tabRepository.observeTab(tabLocalId).collect { tab ->
                val addedMessage = if (hasConfirmedOnce) {
                    val previousCount = lastKnownActiveItemCount
                    val currentCount = tab?.activeItems?.size
                    // Volta da tela de cardápio sem nenhum feedback de sucesso —
                    // o garçom só via a tela mudar, sem saber se o pedido
                    // realmente foi lançado. Detecta o aumento de itens (em vez
                    // de só reagir a onDone do cardápio) porque cobre também o
                    // caso de outro terminal/sync lançando pedido nesta mesma
                    // comanda enquanto ela está aberta aqui.
                    if (previousCount != null && currentCount != null && currentCount > previousCount) {
                        val added = currentCount - previousCount
                        if (added == 1) "Item adicionado ao pedido." else "$added itens adicionados ao pedido."
                    } else {
                        null
                    }
                } else {
                    null
                }
                lastKnownActiveItemCount = tab?.activeItems?.size

                _state.value = _state.value.copy(
                    tab = tab,
                    isLoading = tab == null && _state.value.error == null,
                    actionMessage = addedMessage ?: _state.value.actionMessage,
                )
            }
        }
    }

    /** Puxão explícito contra o servidor (pull-to-refresh) — nunca a única fonte da tela. */
    fun refresh() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.getTab(organizationId, tabLocalId) }
                .onSuccess {
                    // Só a partir daqui a contagem de itens reflete dados
                    // confirmados pelo servidor — ver hasConfirmedOnce acima.
                    // Ajusta lastKnownActiveItemCount para o valor recém
                    // confirmado (não o que a última emissão do Room possa
                    // ter deixado, que pode ainda ser o registro mínimo).
                    hasConfirmedOnce = true
                    lastKnownActiveItemCount = it.activeItems.size
                    _state.value = _state.value.copy(isLoading = false, error = null, access = authRepository.currentAccess())
                }
                .onFailure { e ->
                    // Erro só é bloqueante se ainda não há nada no Room para mostrar.
                    if (_state.value.tab == null) {
                        _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Não foi possível carregar a comanda.")
                    } else {
                        _state.value = _state.value.copy(isLoading = false)
                    }
                }
        }
    }

    /**
     * Remover (item ainda não confirmado pelo servidor, `serverId == null`) e
     * Cancelar (item já lançado) são ações DIFERENTES, não a mesma ação com
     * nome trocado: remover é edição de rascunho, sem rastro; cancelar reverte
     * algo que já foi de fato registrado, e por isso sempre exige motivo. Cada
     * botão de item já chama a função certa (ver [TabItem.canRemoveAsDraft]).
     */
    fun askCancelItem(item: TabItem) { _state.value = _state.value.copy(itemPendingCancel = item) }
    fun dismissCancelItem() { _state.value = _state.value.copy(itemPendingCancel = null) }
    fun clearActionMessage() { _state.value = _state.value.copy(actionMessage = null) }

    /** Remove um item ainda não enviado ao servidor — edição, não auditoria: sem motivo, sem confirmação. */
    fun removeDraftItem(item: TabItem) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.cancelItem(organizationId, item.localId, reason = "") }
                .onSuccess { _state.value = _state.value.copy(actionMessage = "Item removido.") }
                .onFailure { e -> _state.value = _state.value.copy(actionMessage = e.message ?: "Não foi possível remover o item.") }
            closeCounterTabIfZeroedByCancellation()
        }
    }

    /**
     * Cancela um item lançado por engano. O backend nunca apaga da ledger:
     * grava CANCELED com autor e motivo e recalcula o total. Por isso o
     * motivo é obrigatório aqui também — sem ele a auditoria fica cega.
     *
     * Cancelamento exige rede (é uma escrita de auditoria contra o servidor,
     * nunca inventada localmente) — se o item ainda nem foi confirmado pelo
     * servidor (lançado offline, na fila), ele é removido do rascunho local
     * em vez de "cancelado" (ver [TabRepository.cancelItem]).
     */
    fun confirmCancelItem(reason: String) {
        val item = _state.value.itemPendingCancel ?: return
        val organizationId = authRepository.currentOrganizationId() ?: return
        if (reason.isBlank()) return

        _state.value = _state.value.copy(isCancelingItem = true)
        viewModelScope.launch {
            runCatching { tabRepository.cancelItem(organizationId, item.localId, reason.trim()) }
                .onSuccess { outcome ->
                    val message = when (outcome) {
                        CancelItemOutcome.Success -> "Item cancelado."
                        CancelItemOutcome.RemovedLocalDraft -> "Item removido (ainda não havia sido confirmado)."
                        CancelItemOutcome.QueuedOffline -> "Item cancelado — será sincronizado quando a internet voltar."
                        CancelItemOutcome.NotFound -> "Item não encontrado."
                    }
                    _state.value = _state.value.copy(isCancelingItem = false, itemPendingCancel = null, actionMessage = message)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isCancelingItem = false,
                        itemPendingCancel = null,
                        actionMessage = e.message ?: "Não foi possível cancelar o item.",
                    )
                }
            closeCounterTabIfZeroedByCancellation()
        }
    }

    /**
     * Balcão não é comanda de mesa — não existe "deixar aberto esperando
     * alguém decidir" numa venda avulsa. Se o cancelamento zerou a conta
     * (nenhum item ativo restante, `paid == 0`) e é do tipo COUNTER, encerra
     * sozinho em vez de ficar "Pago R$0,00" parado em Abertas. Comanda de
     * MESA/INDIVIDUAL nunca fecha sozinha aqui — o operador pode estar só
     * ajustando itens antes de continuar o consumo da mesa.
     */
    private suspend fun closeCounterTabIfZeroedByCancellation() {
        // Lê o Room direto (não `_state.value.tab`): o cancelamento acabou de
        // persistir a mudança ali, e o StateFlow de `observeTab()` pode ainda
        // não ter re-emitido no exato instante em que este código roda.
        val tab = tabRepository.getCachedTab(tabLocalId) ?: return
        val allCanceled = tab.items.isNotEmpty() && tab.activeItems.isEmpty()
        if (tab.type != com.nokta.pos.comanda.domain.TabType.COUNTER) return
        if (!tab.isEditable || !allCanceled || tab.paid.isPositive()) return

        val organizationId = authRepository.currentOrganizationId() ?: return
        runCatching { tabRepository.closeTab(organizationId, tabLocalId) }
            .onSuccess { _state.value = _state.value.copy(closed = true) }
        // Falha aqui é silenciosa de propósito: o operador já viu a mensagem
        // do cancelamento em si; o pior caso é a comanda ficar em Abertas
        // como já ficava antes desta mudança, nunca um estado pior.
    }

    /**
     * Fecha a comanda já quitada. Só aparece quando `remaining` é zero — o
     * backend recusaria qualquer outra tentativa, e mostrar um botão que
     * sempre falha seria pior que não mostrar.
     *
     * Exige que a comanda já esteja sincronizada (tenha `serverId`) — fechar
     * é uma operação terminal que precisa da confirmação do servidor, nunca
     * enfileirada offline.
     */
    fun closeTab() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val tab = _state.value.tab ?: return
        if (!tab.isFullyPaid) return

        _state.value = _state.value.copy(isClosing = true)
        viewModelScope.launch {
            runCatching { tabRepository.closeTab(organizationId, tabLocalId) }
                .onSuccess { _state.value = _state.value.copy(isClosing = false, closed = true) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isClosing = false,
                        actionMessage = e.message ?: "Não foi possível fechar a comanda.",
                    )
                }
        }
    }

    /**
     * Início do fechamento explícito ("pedir a conta") — opcional, trava o
     * consumo (novos itens/cancelamento/desconto) antes de cobrar. Cobrar
     * direto sem passar por aqui continua funcionando (closeTab acima).
     */
    fun requestClose() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        if (_state.value.tab?.isEditable != true) return

        viewModelScope.launch {
            runCatching { tabRepository.requestCloseTab(organizationId, tabLocalId) }
                .onFailure { e ->
                    _state.value = _state.value.copy(actionMessage = e.message ?: "Não foi possível iniciar o fechamento.")
                }
        }
    }

    /** Desfaz requestClose() — volta a comanda para OPEN, editável de novo. */
    fun cancelClose() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        if (_state.value.tab?.status != com.nokta.pos.comanda.domain.TabStatus.CLOSING) return

        viewModelScope.launch {
            runCatching { tabRepository.cancelCloseTab(organizationId, tabLocalId) }
                .onFailure { e ->
                    _state.value = _state.value.copy(actionMessage = e.message ?: "Não foi possível cancelar o fechamento.")
                }
        }
    }
}
