package com.nokta.pos.ui.venda

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.cart.Cart
import com.nokta.pos.comanda.data.TabRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.common.Money
import com.nokta.pos.network.humanizedApiMessage
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

enum class PosPaymentOption { CASH, PIX, DEBIT_CARD, CREDIT_CARD }

/**
 * Onde a venda de balcão está no seu ciclo. Existe para que uma falha no
 * meio (rede caiu depois de abrir a comanda, cartão recusado) tenha um ponto
 * de retomada claro em vez de recomeçar do zero e arriscar cobrar duas vezes.
 */
enum class BalcaoStage { CART, PAYING, DONE }

data class BalcaoUiState(
    val cart: Cart = Cart(),
    val stage: BalcaoStage = BalcaoStage.CART,
    val selectedMethod: PosPaymentOption = PosPaymentOption.CREDIT_CARD,
    val installments: Int = 1,
    val receivedCents: Long? = null,
    val isProcessing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val tab: Tab? = null,
    /**
     * Cobrança JÁ capturada no cartão cujo registro no Nokta falhou. Enquanto
     * true, o botão principal vira "tentar salvar de novo" e reusa a mesma
     * idempotencyKey — nunca dispara uma segunda cobrança.
     */
    val awaitingRegistrationRetry: Boolean = false,
    /**
     * Divisão por pessoas — puramente sobre COMO a cobrança é fatiada em
     * múltiplas transações; a venda continua sendo uma comanda COUNTER só,
     * fechada quando a última parte for paga (mesmo `finalizeSale` de
     * sempre). `null` = sem divisão, cobra o total numa tacada.
     */
    val splitPeople: Int? = null,
    /** Quantas partes já foram cobradas com sucesso nesta venda. */
    val paidParts: Int = 0,
    /** Modo de edição inline do resumo do pedido (+/−/remover por item). */
    val isEditingCart: Boolean = false,
    /** Linha do carrinho aguardando confirmação de remoção. */
    val pendingRemoveLine: com.nokta.pos.cart.CartLine? = null,
) {
    /** Editar o carrinho depois que algo já foi cobrado mudaria o total sob partes já pagas — nunca permitido. */
    val canEditCart: Boolean get() = paidParts == 0 && !awaitingRegistrationRetry
    val total: Money get() = cart.total

    /** Quanto ainda falta cobrar — total na primeira cobrança, ou o que sobrou depois de partes já pagas. */
    val remaining: Money get() = tab?.remaining ?: total

    /** Quanto cobrar AGORA: a parte de 1 pessoa (dividindo o que resta pelas pessoas que ainda faltam pagar), ou tudo de uma vez. */
    val amountToCharge: Money
        get() {
            val people = splitPeople ?: return remaining
            val peopleLeft = (people - paidParts).coerceAtLeast(1)
            return SplitCalculator.splitRemaining(remaining, peopleLeft).first()
        }

    val changeDue: Money?
        get() = receivedCents?.takeIf { it > amountToCharge.cents }?.let { Money(it - amountToCharge.cents) }
    val canConfirmCash: Boolean
        get() = selectedMethod != PosPaymentOption.CASH || receivedCents == null || receivedCents >= amountToCharge.cents

    /** Rótulo do botão de confirmar quando há divisão: "Cobrar parte 2 de 4". */
    val partLabel: String?
        get() = splitPeople?.let { people -> "parte ${paidParts + 1} de $people" }
}

/**
 * Venda de balcão: cliente pede, paga na hora e vai embora.
 *
 * O operador vê apenas "cardápio → carrinho → pagamento → concluído". Por
 * baixo, a operação ainda vira uma comanda `COUNTER` completa (abrir →
 * lançar pedido → registrar pagamento → fechar), porque é isso que mantém a
 * ledger, a auditoria e o financeiro idênticos aos de qualquer outra venda
 * (item 18 do brief: a simplicidade é da interface, nunca da contabilidade).
 * Nenhum endpoint novo foi criado para isso — a sequência usa exatamente os
 * mesmos endpoints do fluxo de comanda.
 *
 * Ordem deliberada: a cobrança no cartão acontece ANTES de qualquer registro
 * no Nokta. Se a Cielo recusar, nada foi criado e o operador pode tentar
 * outro método sem deixar comanda órfã. Só depois de aprovado é que
 * abrimos/lançamos/registramos — e aí a única falha possível é de registro
 * (nunca de cobrança duplicada), que fica explícita na tela com retry usando
 * a MESMA idempotencyKey.
 */
@HiltViewModel
class BalcaoViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val tabRepository: TabRepository,
    private val authRepository: AuthRepository,
    private val paymentProvider: PaymentProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(BalcaoUiState())
    val state: StateFlow<BalcaoUiState> = _state

    /**
     * Mesma chave em toda tentativa da PARTE atual — impede pagamento
     * duplicado no retry. Guardadas em [SavedStateHandle] (sobrevive à
     * recriação do processo pelo Android, não só rotação de tela) — sem
     * isso, um processo morto no meio de "cartão aprovado, registro
     * pendente" perderia o rastro de que já existe uma cobrança feita,
     * arriscando um retry que abriria uma segunda comanda para o mesmo
     * dinheiro já capturado.
     *
     * Sem divisão, é uma única chave para a venda inteira (comportamento de
     * sempre). Com divisão, cada parte precisa da SUA PRÓPRIA chave — a
     * mesma chave em duas partes diferentes faria o backend enxergar a
     * segunda cobrança como retry da primeira e nunca registrar o pagamento
     * da segunda pessoa. `advanceToNextPart` gera uma chave nova ao avançar;
     * um retry da MESMA parte (falha de registro, cartão já aprovado) reusa
     * a que já está salva.
     */
    private var paymentIdempotencyKey: String
        get() = savedStateHandle.get<String>(KEY_PAYMENT_IDEMPOTENCY) ?: UUID.randomUUID().toString().also { savedStateHandle[KEY_PAYMENT_IDEMPOTENCY] = it }
        set(value) { savedStateHandle[KEY_PAYMENT_IDEMPOTENCY] = value }

    private var orderClientRequestId: String
        get() = savedStateHandle.get<String>(KEY_ORDER_CLIENT_REQUEST) ?: UUID.randomUUID().toString().also { savedStateHandle[KEY_ORDER_CLIENT_REQUEST] = it }
        set(value) { savedStateHandle[KEY_ORDER_CLIENT_REQUEST] = value }

    /** Comanda já aberta numa tentativa anterior que falhou depois — reaproveitada, nunca duplicada. */
    private var openedTabLocalId: String?
        get() = savedStateHandle.get<String>(KEY_OPENED_TAB_LOCAL_ID)
        set(value) { savedStateHandle[KEY_OPENED_TAB_LOCAL_ID] = value }

    /**
     * Cobrança já aprovada na adquirente aguardando registro. Guardada para
     * que o retry vá DIRETO ao registro, sem passar pela Cielo de novo — o
     * dinheiro já saiu da conta do cliente.
     */
    private var approvedAwaitingRegistration: ApprovedCharge? = null

    private data class ApprovedCharge(
        val method: String,
        val amount: Money,
        val externalReference: String?,
        val receivedCents: Long?,
    )

    fun setCart(cart: Cart) { _state.value = _state.value.copy(cart = cart) }

    fun goToPayment() {
        if (_state.value.cart.isEmpty) return
        _state.value = _state.value.copy(stage = BalcaoStage.PAYING, errorMessage = null)
    }

    /**
     * Volta ao carrinho. Só é seguro enquanto nada foi cobrado — depois de uma
     * comanda aberta (`openedTabId`) mantemos as chaves de idempotência para
     * que uma retomada reaproveite a mesma venda em vez de criar outra.
     */
    fun backToCart() {
        if (_state.value.isProcessing) return
        // Com cobrança aprovada e registro pendente, voltar ao carrinho
        // esconderia uma venda cobrada e não registrada. O único caminho é
        // concluir o registro.
        if (_state.value.awaitingRegistrationRetry) return
        // Com uma parte da divisão já paga, mudar o carrinho mudaria o total
        // depois que uma pessoa já pagou a parte dela sobre o valor antigo —
        // o dinheiro já registrado ficaria inconsistente com o novo total.
        if (_state.value.paidParts > 0) return
        _state.value = _state.value.copy(stage = BalcaoStage.CART, errorMessage = null, statusMessage = null)
    }

    fun toggleEditCart() {
        if (!_state.value.canEditCart) return
        _state.value = _state.value.copy(isEditingCart = !_state.value.isEditingCart)
    }

    /** "+": mais 1 unidade da mesma linha — carrinho puramente local, nunca precisa de rede/servidor. */
    fun increaseCartLine(line: com.nokta.pos.cart.CartLine) {
        if (!_state.value.canEditCart) return
        _state.value = _state.value.copy(cart = _state.value.cart.updateQuantity(line.localId, line.quantity + 1))
    }

    /**
     * "−": com quantidade > 1, diminui 1 unidade direto. Com quantidade 1,
     * pede confirmação antes de remover a linha por completo (mesmo padrão
     * do checkout de comanda) — mesmo sendo só carrinho local, sumir sem
     * avisar surpreende o operador no meio de montar o pedido.
     */
    fun decreaseCartLine(line: com.nokta.pos.cart.CartLine) {
        if (!_state.value.canEditCart) return
        if (line.quantity <= 1) {
            _state.value = _state.value.copy(pendingRemoveLine = line)
            return
        }
        _state.value = _state.value.copy(cart = _state.value.cart.updateQuantity(line.localId, line.quantity - 1))
    }

    fun requestRemoveCartLine(line: com.nokta.pos.cart.CartLine) {
        if (!_state.value.canEditCart) return
        _state.value = _state.value.copy(pendingRemoveLine = line)
    }

    fun dismissRemoveCartLine() {
        _state.value = _state.value.copy(pendingRemoveLine = null)
    }

    fun confirmRemoveCartLine() {
        val line = _state.value.pendingRemoveLine ?: return
        _state.value = _state.value.copy(cart = _state.value.cart.remove(line.localId), pendingRemoveLine = null)
    }

    fun selectMethod(method: PosPaymentOption) {
        _state.value = _state.value.copy(
            selectedMethod = method,
            installments = 1,
            receivedCents = null,
            errorMessage = null,
        )
    }

    fun setInstallments(installments: Int) {
        _state.value = _state.value.copy(installments = installments.coerceIn(1, 12))
    }

    /**
     * Liga/ajusta a divisão por pessoas. `null` desliga — volta a cobrar o
     * total numa tacada só. Puramente sobre COMO a cobrança é fatiada; nunca
     * muda o total da venda nem cria mais de uma comanda.
     */
    fun setSplitPeople(people: Int?) {
        _state.value = _state.value.copy(splitPeople = people?.coerceIn(2, 20), receivedCents = null)
    }

    /** Valor entregue pelo cliente em dinheiro — o troco é calculado, nunca digitado. */
    fun setReceived(cents: Long?) { _state.value = _state.value.copy(receivedCents = cents) }

    fun dismissError() { _state.value = _state.value.copy(errorMessage = null) }

    /**
     * Confirma o pagamento. A tela reage ao `stage` do estado (PAYING → DONE),
     * não a callback — assim uma recomposição no meio do processo nunca perde
     * o resultado nem dispara a navegação duas vezes.
     */
    fun confirmPayment() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val locationId = authRepository.currentLocationId() ?: return
        val current = _state.value
        if (current.cart.isEmpty || current.isProcessing) return

        _state.value = current.copy(isProcessing = true, errorMessage = null, stage = BalcaoStage.PAYING)

        // Cobrança já aprovada, só o registro falhou: repete o REGISTRO, nunca
        // a cobrança. Mesmas chaves de idempotência, mesma comanda.
        approvedAwaitingRegistration?.let { approved ->
            viewModelScope.launch {
                finalizeSale(
                    organizationId = organizationId,
                    locationId = locationId,
                    method = approved.method,
                    amount = approved.amount,
                    receivedCents = approved.receivedCents,
                    externalReference = approved.externalReference,
                )
            }
            return
        }

        viewModelScope.launch {
            when (current.selectedMethod) {
                PosPaymentOption.CASH -> {
                    // Sem intermediário financeiro: registra direto o que o
                    // operador declara ter recebido em espécie.
                    finalizeSale(
                        organizationId = organizationId,
                        locationId = locationId,
                        method = current.selectedMethod.name,
                        amount = current.amountToCharge,
                        receivedCents = current.receivedCents,
                        externalReference = null,
                    )
                }
                // PIX passa pelo mesmo deep link da Cielo Smart que
                // débito/crédito — é o PIX cobrado dentro do terminal
                // (QR gerado pela própria Cielo), com confirmação real da
                // adquirente antes de registrar a venda. Nunca o "PIX
                // declarado" de fora do terminal.
                PosPaymentOption.PIX, PosPaymentOption.DEBIT_CARD, PosPaymentOption.CREDIT_CARD -> {
                    chargeViaCieloThenFinalize(organizationId, locationId, current)
                }
            }
        }
    }

    private suspend fun chargeViaCieloThenFinalize(
        organizationId: Long,
        locationId: Long,
        current: BalcaoUiState,
    ) {
        val method = when (current.selectedMethod) {
            PosPaymentOption.DEBIT_CARD -> PosPaymentMethod.DEBIT_CARD
            PosPaymentOption.PIX -> PosPaymentMethod.PIX
            else -> PosPaymentMethod.CREDIT_CARD
        }

        _state.value = _state.value.copy(
            statusMessage = if (method == PosPaymentMethod.PIX) "Aguardando o Pix…" else "Aguardando o cartão…",
        )

        // tabId 0: no balcão a comanda ainda nem existe quando cobramos (ou,
        // se já existe de uma tentativa anterior, ainda não tem confirmação
        // do servidor). O provider usa isto só para rastrear a tentativa
        // localmente; a Cielo não conhece o conceito de comanda.
        val knownTabId = openedTabLocalId?.let { tabRepository.getCachedTab(it)?.id }
        val result = paymentProvider.startPayment(
            PaymentRequest(
                tabId = knownTabId ?: 0L,
                amount = current.amountToCharge,
                method = method,
                installments = if (method == PosPaymentMethod.CREDIT_CARD) current.installments else 0,
                attemptId = paymentIdempotencyKey,
            ),
        )

        when (result) {
            is PaymentResult.Approved -> finalizeSale(
                organizationId = organizationId,
                locationId = locationId,
                method = method.name,
                amount = result.amount,
                receivedCents = null,
                externalReference = result.providerTransactionId,
            )
            is PaymentResult.Declined -> failPayment("Pagamento recusado: ${result.reason}")
            is PaymentResult.Cancelled -> failPayment("Pagamento cancelado no terminal.")
            is PaymentResult.Failed -> failPayment(result.errorMessage)
            is PaymentResult.Unknown -> failPayment(
                "Não foi possível confirmar o resultado. Verifique o extrato do terminal antes de cobrar de novo.",
            )
        }
    }

    private fun failPayment(message: String) {
        // Nada foi criado no Nokta ainda — o operador pode trocar de método e
        // tentar de novo sem deixar comanda pela metade. Fica na própria tela
        // de Pagamento (nunca volta pro CART) porque é ela quem sabe exibir
        // `errorMessage` (PosInlineWarning) — voltar pro carrinho aqui
        // descartava o aviso sem o operador nunca vê-lo (bug real: um
        // pagamento recusado/sem credencial Cielo parecia "não fazer nada").
        _state.value = _state.value.copy(
            isProcessing = false,
            statusMessage = null,
            errorMessage = message,
        )
    }

    /**
     * Traduz a recusa do backend por caixa fechado.
     *
     * `requireOpenCashSessionForPayments` (ligado por padrão) faz o servidor
     * recusar QUALQUER pagamento enquanto não houver caixa aberto na unidade.
     * A mensagem crua ("É necessário abrir o caixa...") não diz ao garçom o
     * que fazer, e ele não tem como abrir caixa pelo POS — é ação de gerente
     * no dashboard. Aqui explicitamos isso.
     */
    private fun humanizeError(raw: String?): String {
        val message = raw ?: "Não foi possível registrar a venda."
        return if (message.contains("caixa", ignoreCase = true)) {
            "O caixa desta unidade está fechado, e o sistema exige caixa aberto para receber pagamentos. " +
                "Peça ao gerente para abrir o caixa no painel (Operação › Caixa)."
        } else {
            message
        }
    }

    /**
     * Materializa a venda no backend depois que o dinheiro já está garantido.
     * Cada passo é idempotente e reaproveita o que já deu certo numa tentativa
     * anterior — um retry nunca abre uma segunda comanda nem cobra de novo.
     */
    private suspend fun finalizeSale(
        organizationId: Long,
        locationId: Long,
        method: String,
        amount: Money,
        receivedCents: Long?,
        externalReference: String?,
    ) {
        _state.value = _state.value.copy(statusMessage = "Registrando a venda…")

        runCatching {
            // A comanda já existia ANTES desta chamada (2ª+ parte de um
            // split, ou retry de registro após cartão já aprovado) — nesses
            // casos o pedido já foi enviado e a comanda está PAYMENT_IN_
            // PROGRESS (total congelado para cobrança, ver
            // VenueOrdersService.assertTabEditable no backend), então
            // reenviar o pedido aqui sempre falha com 400 "Esta comanda não
            // está aberta para alterações no consumo". Só a 1ª chamada
            // (comanda recém-aberta agora) precisa/pode enviar o pedido.
            val tabAlreadyExisted = openedTabLocalId != null
            val tabLocalId = openedTabLocalId ?: tabRepository.openTab(
                organizationId = organizationId,
                locationId = locationId,
                type = TabType.COUNTER,
            ).localId.also { openedTabLocalId = it }

            if (!tabAlreadyExisted) {
                tabRepository.submitOrder(
                    organizationId = organizationId,
                    tabLocalId = tabLocalId,
                    lines = _state.value.cart.lines.map { it.toOrderLine() },
                    orderLocalId = orderClientRequestId,
                )
            }

            val paidTab = tabRepository.registerPayment(
                organizationId = organizationId,
                tabLocalId = tabLocalId,
                method = method,
                amount = amount,
                idempotencyKey = paymentIdempotencyKey,
                receivedCents = receivedCents,
                externalReference = externalReference,
            )

            // Fechar é o passo final. Com a trava de itens pendentes desligada
            // (default), uma bebida entregue na hora não impede o fechamento.
            // Só é possível com a comanda já sincronizada — se ainda está
            // offline, o fechamento fica para quando o SyncEngine confirmar
            // (a venda em si já está garantida: item lançado e pagamento
            // registrado, ambos no Room desde já).
            if (paidTab.isFullyPaid) {
                runCatching { tabRepository.closeTab(organizationId, tabLocalId) }
            }
            paidTab
        }.onSuccess { tab ->
            approvedAwaitingRegistration = null
            if (tab.isFullyPaid) {
                _state.value = _state.value.copy(
                    isProcessing = false,
                    stage = BalcaoStage.DONE,
                    statusMessage = null,
                    awaitingRegistrationRetry = false,
                    tab = tab,
                )
            } else {
                // Ainda falta gente pagar: volta para a tela de pagamento com
                // a próxima parte pronta — nunca fecha a venda no meio da
                // divisão, e nunca reusa a chave de idempotência da parte que
                // acabou de ser confirmada.
                advanceToNextPart()
                _state.value = _state.value.copy(
                    isProcessing = false,
                    stage = BalcaoStage.PAYING,
                    statusMessage = null,
                    awaitingRegistrationRetry = false,
                    receivedCents = null,
                    tab = tab,
                    paidParts = _state.value.paidParts + 1,
                )
            }
        }.onFailure { e ->
            // Cartão aprovado: guarda a cobrança para o retry ir direto ao
            // registro. Dinheiro/PIX não precisa disso — nada foi capturado
            // por terceiro, o operador pode simplesmente tentar de novo.
            if (externalReference != null) {
                approvedAwaitingRegistration = ApprovedCharge(method, amount, externalReference, receivedCents)
            }
            // O dinheiro pode já ter sido capturado (cartão aprovado) e a
            // falha ser só de registro. NUNCA cobrar de novo: o retry
            // reaproveita a mesma comanda e a mesma idempotencyKey.
            _state.value = _state.value.copy(
                isProcessing = false,
                stage = BalcaoStage.PAYING,
                statusMessage = null,
                awaitingRegistrationRetry = externalReference != null,
                errorMessage = if (externalReference != null) {
                    "Cartão aprovado, mas falhou ao salvar no sistema (${e.humanizedApiMessage()}). Toque em 'Tentar salvar de novo' — o cliente não será cobrado outra vez."
                } else {
                    humanizeError(e.humanizedApiMessage())
                },
            )
        }
    }

    /**
     * Gera uma chave de idempotência nova para a PRÓXIMA parte da divisão.
     * Nunca reaproveita a chave da parte que acabou de ser confirmada — isso
     * faria o backend enxergar a cobrança da 2ª pessoa como um retry da 1ª e
     * nunca registrar o segundo pagamento.
     */
    private fun advanceToNextPart() {
        paymentIdempotencyKey = UUID.randomUUID().toString()
    }

    /**
     * Recomeça uma venda nova, com chaves de idempotência novas.
     *
     * Recusa enquanto houver cobrança aprovada sem registro: zerar as chaves
     * nesse ponto tornaria impossível reconciliar o dinheiro já capturado.
     */
    fun reset() {
        if (_state.value.awaitingRegistrationRetry) return
        paymentIdempotencyKey = UUID.randomUUID().toString()
        orderClientRequestId = UUID.randomUUID().toString()
        openedTabLocalId = null
        approvedAwaitingRegistration = null
        _state.value = BalcaoUiState()
    }
}

private const val KEY_PAYMENT_IDEMPOTENCY = "balcao_payment_idempotency_key"
private const val KEY_ORDER_CLIENT_REQUEST = "balcao_order_client_request_id"
private const val KEY_OPENED_TAB_LOCAL_ID = "balcao_opened_tab_local_id"
