package com.nokta.pos.payment.fake

import com.nokta.pos.payment.domain.CancelPaymentRequest
import com.nokta.pos.payment.domain.CancelPaymentResult
import com.nokta.pos.payment.domain.PaymentProvider
import com.nokta.pos.payment.domain.PaymentRequest
import com.nokta.pos.payment.domain.PaymentResult

/**
 * Stub determinístico para testes unitários de Checkout/ViewModel — nunca
 * depende da Cielo de verdade (seção 49 do PRD). O outcome é escolhido pelo
 * teste via [nextResult]; por padrão aprova.
 */
class FakePaymentProvider : PaymentProvider {

    var nextResult: ((PaymentRequest) -> PaymentResult)? = null
    val startedRequests = mutableListOf<PaymentRequest>()

    override suspend fun startPayment(request: PaymentRequest): PaymentResult {
        startedRequests.add(request)
        return nextResult?.invoke(request) ?: PaymentResult.Approved(
            attemptId = request.attemptId,
            amount = request.amount,
            providerTransactionId = "FAKE-${request.attemptId}",
            authorizationCode = "000000",
            brand = "Visa",
            maskedCardNumber = "424242-4242",
            installments = request.installments,
        )
    }

    override suspend fun cancelPayment(request: CancelPaymentRequest): CancelPaymentResult =
        CancelPaymentResult(request.originalAttemptId, succeeded = true)
}
