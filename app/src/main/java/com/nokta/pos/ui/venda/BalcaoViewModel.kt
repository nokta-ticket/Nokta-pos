package com.nokta.pos.ui.venda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.cart.Cart
import com.nokta.pos.comanda.data.OperationRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.PaymentProvider
import com.nokta.pos.payment.domain.PaymentRequest
import com.nokta.pos.payment.domain.PaymentResult
import com.nokta.pos.payment.domain.PosPaymentMethod
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
) {
    val total: Money get() = cart.total
    val changeDue: Money?
        get() = receivedCents?.takeIf { it > total.cents }?.let { Money(it - total.cents) }
    val canConfirmCash: Boolean
        get() = selectedMethod != PosPaymentOption.CASH || receivedCents == null || receivedCents >= total.cents
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
    private val operationRepository: OperationRepository,
    private val authRepository: AuthRepository,
    private val paymentProvider: PaymentProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(BalcaoUiState())
    val state: StateFlow<BalcaoUiState> = _state

    /** Mesma chave em toda tentativa desta venda — impede pagamento duplicado no retry. */
    private var paymentIdempotencyKey: String = UUID.randomUUID().toString()
    private var orderClientRequestId: String = UUID.randomUUID().toString()

    /** Comanda já aberta numa tentativa anterior que falhou depois — reaproveitada, nunca duplicada. */
    private var openedTabId: Long? = null

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
        _state.value = _state.value.copy(stage = BalcaoStage.CART, errorMessage = null, statusMessage = null)
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
                PosPaymentOption.CASH, PosPaymentOption.PIX -> {
                    // Sem intermediário financeiro: registra direto. PIX aqui é
                    // o PIX conferido fora do terminal (QR do estabelecimento);
                    // a Nokta não confirma liquidação, só registra o que o
                    // operador declara ter recebido.
                    finalizeSale(
                        organizationId = organizationId,
                        locationId = locationId,
                        method = current.selectedMethod.name,
                        amount = current.total,
                        receivedCents = current.receivedCents,
                        externalReference = null,
                    )
                }
                PosPaymentOption.DEBIT_CARD, PosPaymentOption.CREDIT_CARD -> {
                    chargeCardThenFinalize(organizationId, locationId, current)
                }
            }
        }
    }

    private suspend fun chargeCardThenFinalize(
        organizationId: Long,
        locationId: Long,
        current: BalcaoUiState,
    ) {
        val method = if (current.selectedMethod == PosPaymentOption.DEBIT_CARD) {
            PosPaymentMethod.DEBIT_CARD
        } else {
            PosPaymentMethod.CREDIT_CARD
        }

        _state.value = _state.value.copy(statusMessage = "Aguardando o cartão…")

        val result = paymentProvider.startPayment(
            PaymentRequest(
                // tabId 0: no balcão a comanda ainda nem existe quando
                // cobramos. O provider usa isto só para rastrear a tentativa
                // localmente; a Cielo não conhece o conceito de comanda.
                tabId = openedTabId ?: 0L,
                amount = current.total,
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
        // tentar de novo sem deixar comanda pela metade.
        _state.value = _state.value.copy(
            isProcessing = false,
            stage = BalcaoStage.CART,
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
            val tabId = openedTabId ?: operationRepository.openTab(
                organizationId = organizationId,
                locationId = locationId,
                type = TabType.COUNTER,
            ).id.also { openedTabId = it }

            operationRepository.submitOrder(
                organizationId = organizationId,
                tabId = tabId,
                lines = _state.value.cart.lines.map { it.toOrderLine() },
                clientRequestId = orderClientRequestId,
            )

            val paidTab = operationRepository.registerPayment(
                organizationId = organizationId,
                tabId = tabId,
                method = method,
                amount = amount,
                idempotencyKey = paymentIdempotencyKey,
                receivedCents = receivedCents,
                externalReference = externalReference,
            )

            // Fechar é o passo final. Com a trava de itens pendentes desligada
            // (default), uma bebida entregue na hora não impede o fechamento.
            if (paidTab.isFullyPaid) {
                runCatching { operationRepository.closeTab(organizationId, tabId) }
            }
            paidTab
        }.onSuccess { tab ->
            approvedAwaitingRegistration = null
            _state.value = _state.value.copy(
                isProcessing = false,
                stage = BalcaoStage.DONE,
                statusMessage = null,
                awaitingRegistrationRetry = false,
                tab = tab,
            )
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
                    "Cartão aprovado, mas falhou ao salvar no sistema (${e.message}). Toque em 'Tentar salvar de novo' — o cliente não será cobrado outra vez."
                } else {
                    humanizeError(e.message)
                },
            )
        }
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
        openedTabId = null
        approvedAwaitingRegistration = null
        _state.value = BalcaoUiState()
    }
}
