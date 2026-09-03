package com.nokta.pos.ui.checkout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.CancelItemOutcome
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabItem
import com.nokta.pos.common.Money
import com.nokta.pos.network.humanizedApiMessage
import com.nokta.pos.payment.domain.PartialValidation
import com.nokta.pos.payment.domain.PaymentProvider
import com.nokta.pos.payment.domain.PaymentRequest
import com.nokta.pos.payment.domain.PaymentResult
import com.nokta.pos.payment.domain.PosPaymentMethod
import com.nokta.pos.payment.domain.SplitCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class PaymentUiMethod { CASH, PIX, DEBIT_CARD, CREDIT_CARD }

/** Como o operador escolheu o valor a cobrar agora. */
enum class AmountMode {
    FULL,      // tudo que falta
    SPLIT,     // dividir o restante entre N pessoas, cobrar 1 parte
}

data class CheckoutUiState(
    val tab: Tab? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedMethod: PaymentUiMethod = PaymentUiMethod.CREDIT_CARD,
    val installments: Int = 1,
    val amountMode: AmountMode = AmountMode.FULL,
    val splitPeople: Int = 2,
    /**
     * Sobrescreve a divisão igualitária SÓ para a cobrança da vez, quando o
     * operador escolhe "Editar valor" no card de divisão (ex.: um cliente
     * quer pagar R$ 20 e o outro os R$ 2 restantes, em vez de 50/50). Nunca
     * persiste entre pessoas: cada nova parte volta a sugerir a divisão
     * igual do que ainda resta — ver [setSplitPeople]/[register], que sempre
     * zeram este campo.
     */
    val manualSplitAmountCents: Long? = null,
    val receivedCents: Long? = null,
    val isProcessingPayment: Boolean = false,
    val paymentMessage: String? = null,
    val tabClosed: Boolean = false,
    /** Cobrança aprovada no cartão mas ainda não registrada — permite retry seguro. */
    val pendingRegistration: PendingRegistration? = null,
    /** Modo de edição inline do resumo do pedido (+/−/remover por item). */
    val isEditingOrder: Boolean = false,
    /**
     * Ajuste de quantidade aguardando motivo do operador — tanto o "−" que
     * reduz 1 unidade quanto a lixeira que remove o item inteiro passam por
     * aqui. Nunca é aplicado sem motivo: é o caminho mais rápido de mexer
     * numa comanda ("lancei 5, eram 4"), e por isso o de maior risco de
     * fraude/erro não rastreado se ficasse silencioso.
     */
    val pendingQuantityAdjustment: PendingQuantityAdjustment? = null,
) {
    /**
     * Valor que será cobrado nesta operação — inclui consumo ainda pendente
     * de sincronização deste terminal (ver `Tab.remainingWithPending`).
     *
     * Isso NÃO reabre risco de sobrecobrança entre terminais: a parte
     * confirmada pelo servidor (`tab.remaining`) continua sendo sempre a
     * releitura mais recente da API (`refresh()`/`observeTab`), então se
     * outra maquininha já registrou um pagamento no meio, ela aparece aqui
     * igual antes. A parte pendente (`pendingConsumption`) só existe no Room
     * DESTE terminal — nenhum outro terminal a enxerga, então não há como
     * dois terminais cobrarem em dobro o mesmo pendente.
     */
    val amountToCharge: Money
        get() {
            val remaining = tab?.remainingWithPending ?: Money.ZERO
            return when (amountMode) {
                AmountMode.FULL -> remaining
                // manualSplitAmountCents (ver doc do campo) sobrescreve a
                // sugestão igualitária só para a cobrança da vez — ex.: uma
                // pessoa paga R$ 20 e a outra os R$ 2 restantes, em vez de
                // forçar 50/50. validatePartial (usado em `validation`
                // abaixo) já rejeita um valor manual acima do saldo, então
                // não precisa de clamp aqui.
                AmountMode.SPLIT -> manualSplitAmountCents?.let(::Money)
                    ?: SplitCalculator.splitRemaining(remaining, splitPeople).firstOrNull() ?: Money.ZERO
            }
        }

    val splitParts: List<Money>
        get() = if (amountMode == AmountMode.SPLIT && tab != null) {
            SplitCalculator.splitRemaining(tab.remainingWithPending, splitPeople)
        } else emptyList()

    val change: Money?
        get() = receivedCents?.let { SplitCalculator.change(amountToCharge, Money(it)) }

    val validation: PartialValidation
        get() = tab?.let { SplitCalculator.validatePartial(amountToCharge, it.remainingWithPending) } ?: PartialValidation.Valid

    val canCharge: Boolean
        get() = validation is PartialValidation.Valid &&
            !isProcessingPayment &&
            (selectedMethod != PaymentUiMethod.CASH || receivedCents == null || receivedCents >= amountToCharge.cents)

    /**
     * Depois deste pagamento a conta fica quitada? Decide o texto do botão.
     * Usa `remainingWithPending` (não `tab.isFullyPaid`, que é só o saldo
     * OFICIAL do servidor) — sem isso, cobrar o valor cheio com pendente não
     * fecharia a comanda automaticamente até o item sincronizar de verdade.
     */
    val settlesTab: Boolean
        get() = tab != null && amountToCharge.cents >= tab.remainingWithPending.cents
}

/** Cobrança já capturada na adquirente, aguardando registro no Nokta. */
data class PendingRegistration(
    val attemptId: String,
    val amount: Money,
    val method: String,
    val externalReference: String?,
)

/**
 * `willRemoveCompletely`: `true` quando o ajuste zera a quantidade (lixeira,
 * ou "−" na última unidade) — só muda o texto mostrado ao operador, a
 * exigência de motivo é a mesma nos dois casos.
 */
data class PendingQuantityAdjustment(val item: TabItem, val willRemoveCompletely: Boolean)

/**
 * Fechamento de comanda/mesa com pagamento total, parcial ou dividido.
 *
 * Duas garantias que não podem ser quebradas:
 *  1. O valor a cobrar é `tab.remainingWithPending`: a parte confirmada
 *     (`tab.remaining`) sai SEMPRE da releitura mais recente do servidor — se
 *     outra maquininha registrou um pagamento no meio, o garçom nunca cobra a
 *     mais por causa dela. A parte pendente (`pendingConsumption`) é só o
 *     consumo que ESTE terminal ainda não sincronizou, invisível a qualquer
 *     outro terminal, então nunca é contada em dobro entre dois aparelhos.
 *  2. Cartão aprovado + falha de registro NUNCA vira nova cobrança: a
 *     tentativa fica guardada em `pendingRegistration` e o retry reenvia a
 *     mesma `idempotencyKey`, que o backend reconhece.
 *
 * Se algum item pendente cobrado aqui for recusado depois pelo servidor, o
 * `SyncEngine` gera um registro de reconciliação visível na comanda em vez de
 * ajustar o pagamento sozinho — ver `PaymentReconciliationEntity`.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
    private val paymentProvider: PaymentProvider,
) : ViewModel() {

    val tabLocalId: String = savedStateHandle.get<String>("tabId") ?: error("tabId ausente")

    private val _state = MutableStateFlow(CheckoutUiState())
    val state: StateFlow<CheckoutUiState> = _state

    init {
        observeTab()
        refresh()
    }

    private fun observeTab() {
        viewModelScope.launch {
            tabRepository.observeTab(tabLocalId).collect { tab ->
                if (tab != null) {
                    _state.value = _state.value.copy(tab = tab, isLoading = false)
                }
            }
        }
    }

    /** Puxão explícito contra o servidor — a tela já é alimentada pelo Room via [observeTab]. */
    fun refresh() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.getTab(organizationId, tabLocalId) }
                .onFailure { e ->
                    if (_state.value.tab == null) {
                        _state.value = _state.value.copy(isLoading = false, error = e.humanizedApiMessage("Erro ao carregar comanda."))
                    } else {
                        _state.value = _state.value.copy(isLoading = false)
                    }
                }
        }
    }

    /**
     * Botão de voltar do topo (seta ao lado do "NOKTA"). Se a comanda chegou
     * aqui pelo fluxo "Fechar a conta" (status CLOSING) e nenhum pagamento
     * já foi registrado, desistir do pagamento não deve deixar a mesa presa
     * travada — reabre a comanda (CLOSING -> OPEN) antes de voltar, para o
     * garçom não precisar entrar de novo na comanda só para "Cancelar
     * fechamento" manualmente. PAYMENT_IN_PROGRESS (já existe pagamento
     * confirmado) nunca é revertido aqui — só volta a navegação, como antes.
     */
    fun onBackPressed(onNavigateBack: () -> Unit) {
        val organizationId = authRepository.currentOrganizationId()
        val tab = _state.value.tab
        if (organizationId == null || tab?.status != com.nokta.pos.comanda.domain.TabStatus.CLOSING) {
            onNavigateBack()
            return
        }

        viewModelScope.launch {
            // Falha (ex.: sem rede) nunca bloqueia a navegação — o pior caso
            // é a mesa continuar em CLOSING, exatamente como já seria sem
            // esta melhoria; o garçom sempre pode cancelar manualmente depois.
            runCatching { tabRepository.cancelCloseTab(organizationId, tabLocalId) }
            onNavigateBack()
        }
    }

    fun selectMethod(method: PaymentUiMethod) {
        _state.value = _state.value.copy(selectedMethod = method, installments = 1, receivedCents = null)
    }

    fun setInstallments(installments: Int) {
        _state.value = _state.value.copy(installments = installments.coerceIn(1, 12))
    }

    fun setAmountMode(mode: AmountMode) {
        _state.value = _state.value.copy(amountMode = mode, receivedCents = null, manualSplitAmountCents = null)
    }

    fun setSplitPeople(people: Int) {
        // Mudar o número de pessoas invalida qualquer valor manual já
        // digitado para a divisão anterior — volta a sugerir igual, como se
        // o operador tivesse acabado de entrar no modo Dividir.
        _state.value = _state.value.copy(splitPeople = people.coerceIn(2, 20), receivedCents = null, manualSplitAmountCents = null)
    }

    /** "Editar valor" no card de divisão: sobrescreve a parte da vez com um valor digitado pelo operador. */
    fun setManualSplitAmount(cents: Long) {
        _state.value = _state.value.copy(manualSplitAmountCents = cents, receivedCents = null)
    }

    fun setReceived(cents: Long?) { _state.value = _state.value.copy(receivedCents = cents) }

    fun clearMessage() { _state.value = _state.value.copy(paymentMessage = null) }

    fun toggleEditOrder() {
        if (_state.value.isProcessingPayment) return
        _state.value = _state.value.copy(isEditingOrder = !_state.value.isEditingOrder)
    }

    /** "+": lança mais 1 unidade do mesmo item como pedido novo. */
    fun increaseItem(item: TabItem) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            runCatching { tabRepository.increaseItemQuantity(organizationId, item) }
                .onFailure { e -> _state.value = _state.value.copy(paymentMessage = e.humanizedApiMessage("Não foi possível adicionar o item.")) }
        }
    }

    /**
     * "−": sempre pede motivo antes de aplicar, com quantidade > 1 ou não —
     * é o caminho mais rápido de corrigir uma comanda ("lancei 5, eram 4") e
     * por isso o de maior risco se ficasse sem confirmação nem auditoria.
     */
    fun decreaseItem(item: TabItem) {
        _state.value = _state.value.copy(
            pendingQuantityAdjustment = PendingQuantityAdjustment(item, willRemoveCompletely = item.quantity <= 1),
        )
    }

    /** Lixeira: sempre remove o item inteiro, mesmo motivo obrigatório do "−". */
    fun requestRemoveItem(item: TabItem) {
        _state.value = _state.value.copy(
            pendingQuantityAdjustment = PendingQuantityAdjustment(item, willRemoveCompletely = true),
        )
    }

    fun dismissQuantityAdjustment() {
        _state.value = _state.value.copy(pendingQuantityAdjustment = null)
    }

    /**
     * `reason` vem sempre do operador (diálogo na tela) — nunca um texto
     * fixo. É o que a auditoria/prevenção de fraude depende para distinguir
     * "digitei errado" de "cliente devolveu" de "item veio errado do bar".
     */
    fun confirmQuantityAdjustment(reason: String) {
        val pending = _state.value.pendingQuantityAdjustment ?: return
        if (reason.isBlank()) return
        _state.value = _state.value.copy(pendingQuantityAdjustment = null)
        applyDecrease(pending.item, reason.trim())
    }

    private fun applyDecrease(item: TabItem, reason: String) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            val outcome = tabRepository.decreaseItemQuantity(organizationId, item, reason)
            if (outcome == CancelItemOutcome.QueuedOffline) {
                _state.value = _state.value.copy(paymentMessage = "Sem conexão: item ajustado localmente, será sincronizado quando a internet voltar.")
            } else if (outcome == CancelItemOutcome.NotFound) {
                _state.value = _state.value.copy(paymentMessage = "Item não encontrado — pode já ter sido removido.")
            }
        }
    }

    fun charge() {
        val tab = _state.value.tab ?: return
        val organizationId = authRepository.currentOrganizationId() ?: return
        val amount = _state.value.amountToCharge
        if (!_state.value.canCharge || amount.isZeroOrNegative()) return

        _state.value = _state.value.copy(isProcessingPayment = true, paymentMessage = null)

        viewModelScope.launch {
            when (_state.value.selectedMethod) {
                // Nenhuma checagem de caixa aqui, em nenhum método: fechar o
                // caixa impede LANÇAR ITEM NOVO, nunca receber uma comanda que
                // já estava aberta (o backend recusa o lançamento, nunca o
                // pagamento). O pagamento é conciliado no caixa em que o
                // atendimento nasceu, mesmo que esse caixa já tenha fechado.
                PaymentUiMethod.CASH -> register(
                    organizationId, tab, "CASH", amount,
                    receivedCents = _state.value.receivedCents,
                    externalReference = null,
                    attemptId = UUID.randomUUID().toString(),
                )
                // PIX passa pelo mesmo deep link da Cielo Smart que
                // débito/crédito — é o PIX cobrado dentro do terminal (QR
                // gerado pela própria Cielo), com confirmação real da
                // adquirente antes de registrar o pagamento na comanda.
                PaymentUiMethod.PIX, PaymentUiMethod.DEBIT_CARD, PaymentUiMethod.CREDIT_CARD ->
                    chargeViaCielo(organizationId, tab, amount)
            }
        }
    }

    private suspend fun chargeViaCielo(organizationId: Long, tab: Tab, amount: Money) {
        val method = when (_state.value.selectedMethod) {
            PaymentUiMethod.DEBIT_CARD -> PosPaymentMethod.DEBIT_CARD
            PaymentUiMethod.PIX -> PosPaymentMethod.PIX
            else -> PosPaymentMethod.CREDIT_CARD
        }

        val result = paymentProvider.startPayment(
            PaymentRequest(
                tabId = tab.id,
                amount = amount,
                method = method,
                installments = if (method == PosPaymentMethod.CREDIT_CARD) _state.value.installments else 0,
            ),
        )

        when (result) {
            is PaymentResult.Approved -> register(
                organizationId, tab, method.name, result.amount,
                receivedCents = null,
                externalReference = result.providerTransactionId,
                attemptId = result.attemptId,
            )
            is PaymentResult.Declined -> fail("Pagamento recusado: ${result.reason}")
            is PaymentResult.Cancelled -> fail("Pagamento cancelado.")
            is PaymentResult.Failed -> fail(result.errorMessage)
            is PaymentResult.Unknown -> fail(
                "Não foi possível confirmar o resultado do pagamento. Verifique o extrato do terminal antes de tentar de novo.",
            )
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(isProcessingPayment = false, paymentMessage = message)
    }

    /**
     * O backend nunca recusa pagamento por caixa fechado (fechar o caixa
     * impede lançar item novo, nunca receber uma comanda já aberta), então
     * não existe mais nada específico de caixa pra traduzir aqui — a
     * mensagem do servidor já basta.
     */
    private fun humanizeError(raw: String?): String = raw ?: "Falha ao registrar o pagamento."

    private suspend fun register(
        organizationId: Long,
        tab: Tab,
        method: String,
        amount: Money,
        receivedCents: Long?,
        externalReference: String?,
        attemptId: String,
    ) {
        runCatching {
            tabRepository.registerPayment(
                organizationId = organizationId,
                tabLocalId = tabLocalId,
                method = method,
                amount = amount,
                idempotencyKey = attemptId,
                receivedCents = receivedCents,
                externalReference = externalReference,
            )
        }.onSuccess { updated ->
            // `isFullyPaid` usa SÓ o saldo oficial do servidor (`remaining`),
            // nunca `remainingWithPending` — de propósito: fechar a comanda
            // (`closeTab`) exige `serverId` e é sempre síncrono contra o
            // total ATUAL do servidor (ver TabRepository.closeTab); fechar
            // antes do item pendente sincronizar arriscaria o backend recusar
            // por total desatualizado, ou pior, fechar uma comanda cujo
            // consumo real o servidor ainda nem conhece. Cobrar o valor
            // completo (com pendente) e a comanda continuar "PAYMENT_IN_
            // PROGRESS" por alguns instantes até a fila do Outbox drenar é o
            // comportamento correto, não um bug — `isSettledLocally` é quem
            // decide a mensagem, senão "Faltam R$X" mentiria que falta cobrar
            // algo que na verdade já foi cobrado e só está sincronizando.
            _state.value = _state.value.copy(
                tab = updated,
                isProcessingPayment = false,
                pendingRegistration = null,
                receivedCents = null,
                // Depois de um pagamento parcial, o padrão volta a ser "tudo
                // que falta" — normalmente a próxima pessoa paga o resto.
                // manualSplitAmountCents zerado junto: se o operador voltar
                // pra Dividir para a próxima pessoa, a sugestão volta a ser
                // igualitária sobre o que ainda resta, não o valor manual da
                // pessoa anterior.
                amountMode = AmountMode.FULL,
                manualSplitAmountCents = null,
                paymentMessage = when {
                    updated.isFullyPaid -> "Conta quitada."
                    updated.isSettledLocally -> "Pagamento registrado. Sincronizando os últimos itens antes de fechar."
                    else -> "Pagamento registrado. Faltam ${updated.remainingWithPending.formatBRL()}."
                },
            )
            if (updated.isFullyPaid) closeTab(organizationId, updated)
        }.onFailure { e ->
            val approvedOnCard = externalReference != null
            _state.value = _state.value.copy(
                isProcessingPayment = false,
                pendingRegistration = if (approvedOnCard) {
                    PendingRegistration(attemptId, amount, method, externalReference)
                } else null,
                paymentMessage = if (approvedOnCard) {
                    "Cartão aprovado, mas falhou ao salvar (${e.humanizedApiMessage()}). Toque em 'Tentar salvar de novo' — o cliente não será cobrado outra vez."
                } else {
                    humanizeError(e.humanizedApiMessage())
                },
            )
        }
    }

    /** Retry do registro de uma cobrança já aprovada — mesma chave, nunca recobra. */
    fun retryPendingRegistration() {
        val pending = _state.value.pendingRegistration ?: return
        val tab = _state.value.tab ?: return
        val organizationId = authRepository.currentOrganizationId() ?: return

        _state.value = _state.value.copy(isProcessingPayment = true, paymentMessage = null)
        viewModelScope.launch {
            register(
                organizationId, tab, pending.method, pending.amount,
                receivedCents = null,
                externalReference = pending.externalReference,
                attemptId = pending.attemptId,
            )
        }
    }

    private suspend fun closeTab(organizationId: Long, tab: Tab) {
        runCatching { tabRepository.closeTab(organizationId, tabLocalId) }
            .onSuccess { _state.value = _state.value.copy(tab = it, tabClosed = true) }
        // Falha ao fechar não é erro do pagamento: o dinheiro está registrado
        // e a comanda pode ser encerrada depois, na tela da comanda.
    }
}
