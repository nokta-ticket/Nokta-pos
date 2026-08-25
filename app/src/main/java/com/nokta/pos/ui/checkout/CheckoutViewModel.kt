package com.nokta.pos.ui.checkout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.comanda.data.OperationRepository
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.common.Money
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
    CUSTOM,    // valor digitado
}

data class CheckoutUiState(
    val tab: Tab? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedMethod: PaymentUiMethod = PaymentUiMethod.CREDIT_CARD,
    val installments: Int = 1,
    val amountMode: AmountMode = AmountMode.FULL,
    val splitPeople: Int = 2,
    val customAmountCents: Long = 0,
    val receivedCents: Long? = null,
    val isProcessingPayment: Boolean = false,
    val paymentMessage: String? = null,
    val tabClosed: Boolean = false,
    /** Cobrança aprovada no cartão mas ainda não registrada — permite retry seguro. */
    val pendingRegistration: PendingRegistration? = null,
) {
    /** Valor que será cobrado nesta operação. */
    val amountToCharge: Money
        get() {
            val remaining = tab?.remaining ?: Money.ZERO
            return when (amountMode) {
                AmountMode.FULL -> remaining
                AmountMode.SPLIT -> SplitCalculator.splitRemaining(remaining, splitPeople).firstOrNull() ?: Money.ZERO
                AmountMode.CUSTOM -> Money(customAmountCents)
            }
        }

    val splitParts: List<Money>
        get() = if (amountMode == AmountMode.SPLIT && tab != null) {
            SplitCalculator.splitRemaining(tab.remaining, splitPeople)
        } else emptyList()

    val change: Money?
        get() = receivedCents?.let { SplitCalculator.change(amountToCharge, Money(it)) }

    val validation: PartialValidation
        get() = tab?.let { SplitCalculator.validatePartial(amountToCharge, it.remaining) } ?: PartialValidation.Valid

    val canCharge: Boolean
        get() = validation is PartialValidation.Valid &&
            !isProcessingPayment &&
            (selectedMethod != PaymentUiMethod.CASH || receivedCents == null || receivedCents >= amountToCharge.cents)

    /** Depois deste pagamento a conta fica quitada? Decide o texto do botão. */
    val settlesTab: Boolean
        get() = tab != null && amountToCharge.cents >= tab.remaining.cents
}

/** Cobrança já capturada na adquirente, aguardando registro no Nokta. */
data class PendingRegistration(
    val attemptId: String,
    val amount: Money,
    val method: String,
    val externalReference: String?,
)

/**
 * Fechamento de comanda/mesa com pagamento total, parcial ou dividido.
 *
 * Duas garantias que não podem ser quebradas:
 *  1. O valor a cobrar sai SEMPRE de `tab.remaining` vindo do servidor. Se
 *     outra maquininha registrou um pagamento no meio, a releitura reflete
 *     isso e o garçom nunca cobra a mais.
 *  2. Cartão aprovado + falha de registro NUNCA vira nova cobrança: a
 *     tentativa fica guardada em `pendingRegistration` e o retry reenvia a
 *     mesma `idempotencyKey`, que o backend reconhece.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val operationRepository: OperationRepository,
    private val authRepository: AuthRepository,
    private val paymentProvider: PaymentProvider,
) : ViewModel() {

    val tabId: Long = savedStateHandle.get<Long>("tabId") ?: error("tabId ausente")

    private val _state = MutableStateFlow(CheckoutUiState())
    val state: StateFlow<CheckoutUiState> = _state

    init { refresh() }

    fun refresh() {
        val organizationId = authRepository.currentOrganizationId() ?: return
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { operationRepository.getTab(organizationId, tabId) }
                .onSuccess { tab ->
                    _state.value = _state.value.copy(
                        tab = tab,
                        isLoading = false,
                        customAmountCents = if (_state.value.customAmountCents == 0L) tab.remaining.cents else _state.value.customAmountCents,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Erro ao carregar comanda.")
                }
        }
    }

    fun selectMethod(method: PaymentUiMethod) {
        _state.value = _state.value.copy(selectedMethod = method, installments = 1, receivedCents = null)
    }

    fun setInstallments(installments: Int) {
        _state.value = _state.value.copy(installments = installments.coerceIn(1, 12))
    }

    fun setAmountMode(mode: AmountMode) {
        _state.value = _state.value.copy(
            amountMode = mode,
            receivedCents = null,
            customAmountCents = if (mode == AmountMode.CUSTOM && _state.value.customAmountCents == 0L) {
                _state.value.tab?.remaining?.cents ?: 0L
            } else _state.value.customAmountCents,
        )
    }

    fun setSplitPeople(people: Int) {
        _state.value = _state.value.copy(splitPeople = people.coerceIn(2, 20), receivedCents = null)
    }

    fun setCustomAmount(cents: Long) {
        _state.value = _state.value.copy(customAmountCents = cents.coerceAtLeast(0), receivedCents = null)
    }

    fun setReceived(cents: Long?) { _state.value = _state.value.copy(receivedCents = cents) }

    fun clearMessage() { _state.value = _state.value.copy(paymentMessage = null) }

    fun charge() {
        val tab = _state.value.tab ?: return
        val organizationId = authRepository.currentOrganizationId() ?: return
        val amount = _state.value.amountToCharge
        if (!_state.value.canCharge || amount.isZeroOrNegative()) return

        _state.value = _state.value.copy(isProcessingPayment = true, paymentMessage = null)

        viewModelScope.launch {
            when (_state.value.selectedMethod) {
                PaymentUiMethod.CASH -> register(
                    organizationId, tab, "CASH", amount,
                    receivedCents = _state.value.receivedCents,
                    externalReference = null,
                    attemptId = UUID.randomUUID().toString(),
                )
                PaymentUiMethod.PIX -> register(
                    organizationId, tab, "PIX", amount,
                    receivedCents = null,
                    externalReference = null,
                    attemptId = UUID.randomUUID().toString(),
                )
                PaymentUiMethod.DEBIT_CARD, PaymentUiMethod.CREDIT_CARD -> chargeCard(organizationId, tab, amount)
            }
        }
    }

    private suspend fun chargeCard(organizationId: Long, tab: Tab, amount: Money) {
        val method = if (_state.value.selectedMethod == PaymentUiMethod.DEBIT_CARD) {
            PosPaymentMethod.DEBIT_CARD
        } else {
            PosPaymentMethod.CREDIT_CARD
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
            operationRepository.registerPayment(
                organizationId = organizationId,
                tabId = tab.id,
                method = method,
                amount = amount,
                idempotencyKey = attemptId,
                receivedCents = receivedCents,
                externalReference = externalReference,
            )
        }.onSuccess { updated ->
            _state.value = _state.value.copy(
                tab = updated,
                isProcessingPayment = false,
                pendingRegistration = null,
                receivedCents = null,
                // Depois de um pagamento parcial, o padrão volta a ser "tudo
                // que falta" — normalmente a próxima pessoa paga o resto.
                amountMode = AmountMode.FULL,
                paymentMessage = if (updated.isFullyPaid) "Conta quitada." else "Pagamento registrado. Faltam ${updated.remaining.formatBRL()}.",
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
                    "Cartão aprovado, mas falhou ao salvar (${e.message}). Toque em 'Tentar salvar de novo' — o cliente não será cobrado outra vez."
                } else {
                    e.message ?: "Falha ao registrar o pagamento."
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
        runCatching { operationRepository.closeTab(organizationId, tab.id) }
            .onSuccess { _state.value = _state.value.copy(tab = it, tabClosed = true) }
        // Falha ao fechar não é erro do pagamento: o dinheiro está registrado
        // e a comanda pode ser encerrada depois, na tela da comanda.
    }
}
