package com.nokta.pos.payment.domain

import com.nokta.pos.common.Money
import java.util.UUID

/** Forma de pagamento operacional — espelha VenuePaymentMethod do backend (nokta-api). */
enum class PosPaymentMethod {
    CASH,
    PIX,
    DEBIT_CARD,
    CREDIT_CARD,
}

/**
 * Uma tentativa de pagamento, identificada por [attemptId] — a mesma
 * referência interna atravessa: comanda → provider (Cielo) → registro final
 * em VenuePayment.idempotencyKey. Nunca reaproveitar o mesmo attemptId para
 * duas cobranças distintas (ver seção 25 do PRD, idempotência).
 */
data class PaymentRequest(
    val attemptId: String = UUID.randomUUID().toString(),
    val tabId: Long,
    val amount: Money,
    val method: PosPaymentMethod,
    /** Só relevante para CREDIT_CARD parcelado — 0/1 = à vista. */
    val installments: Int = 0,
)

data class CancelPaymentRequest(
    val originalAttemptId: String,
    val providerTransactionId: String?,
    val amount: Money,
)

/**
 * Resultado normalizado de uma tentativa de pagamento — nunca expõe tipos da
 * Cielo para o resto do app (Checkout/ViewModel só conhecem este sealed
 * class). Ver seção 20 do PRD: "intent enviado" NUNCA é Approved — só chega
 * aqui depois que o provider decodificou o callback oficial da adquirente.
 */
sealed class PaymentResult {
    abstract val attemptId: String

    data class Approved(
        override val attemptId: String,
        val amount: Money,
        /** Referência da transação na adquirente (NSU/cieloCode) — vira VenuePayment.externalReference. */
        val providerTransactionId: String,
        val authorizationCode: String?,
        val brand: String?,
        val maskedCardNumber: String?,
        val installments: Int,
    ) : PaymentResult()

    data class Declined(
        override val attemptId: String,
        val reason: String,
    ) : PaymentResult()

    data class Cancelled(
        override val attemptId: String,
        val reason: String,
    ) : PaymentResult()

    data class Failed(
        override val attemptId: String,
        val errorMessage: String,
    ) : PaymentResult()

    /**
     * Estado desconhecido — timeout, app da Cielo não respondeu, processo
     * interrompido antes do callback chegar. NUNCA tratado como aprovado
     * (seção 23 do PRD). Fica pendente de recuperação/consulta manual; a UI
     * mostra "Verificando..." e nunca libera uma nova cobrança automática
     * para a mesma comanda enquanto este estado não for resolvido (seção 24).
     */
    data class Unknown(
        override val attemptId: String,
    ) : PaymentResult()
}

data class CancelPaymentResult(
    val attemptId: String,
    val succeeded: Boolean,
    val errorMessage: String? = null,
)

/**
 * Fronteira entre o domínio de pagamento da Nokta e a adquirente. O
 * Checkout/ViewModel dependem só desta interface — nunca de
 * CieloDeepLinkPaymentProvider diretamente. Trocar de adquirente no futuro
 * significa escrever uma nova implementação, zero mudança no checkout.
 */
interface PaymentProvider {
    suspend fun startPayment(request: PaymentRequest): PaymentResult
    suspend fun cancelPayment(request: CancelPaymentRequest): CancelPaymentResult
}
