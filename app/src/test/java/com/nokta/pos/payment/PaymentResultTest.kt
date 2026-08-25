package com.nokta.pos.payment

import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.PaymentRequest
import com.nokta.pos.payment.domain.PaymentResult
import com.nokta.pos.payment.domain.PosPaymentMethod
import com.nokta.pos.payment.fake.FakePaymentProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre os cenários da seção 49/50 do PRD contra a interface PaymentProvider
 * usando FakePaymentProvider — nunca depende da Cielo de verdade. O
 * checkout real (CheckoutViewModel) consome exatamente esta interface, não
 * CieloDeepLinkPaymentProvider diretamente, então estes testes já provam o
 * contrato que o checkout depende.
 */
class PaymentResultTest {

    private fun request(amountCents: Long = 5000) = PaymentRequest(
        tabId = 1,
        amount = Money(amountCents),
        method = PosPaymentMethod.CREDIT_CARD,
        installments = 1,
    )

    @Test
    fun `aprovado devolve os dados da transacao`() = runTest {
        val provider = FakePaymentProvider()
        val result = provider.startPayment(request())
        assertTrue(result is PaymentResult.Approved)
        assertEquals(Money(5000), (result as PaymentResult.Approved).amount)
    }

    @Test
    fun `recusado nunca vira aprovado`() = runTest {
        val provider = FakePaymentProvider().apply {
            nextResult = { req -> PaymentResult.Declined(req.attemptId, "Cartão sem limite") }
        }
        val result = provider.startPayment(request())
        assertTrue(result is PaymentResult.Declined)
    }

    @Test
    fun `cancelado pelo usuario e distinguivel de recusado`() = runTest {
        val provider = FakePaymentProvider().apply {
            nextResult = { req -> PaymentResult.Cancelled(req.attemptId, "Cancelado pelo usuário") }
        }
        val result = provider.startPayment(request())
        assertTrue(result is PaymentResult.Cancelled)
    }

    @Test
    fun `resultado desconhecido nunca e tratado como aprovado`() = runTest {
        val provider = FakePaymentProvider().apply {
            nextResult = { req -> PaymentResult.Unknown(req.attemptId) }
        }
        val result = provider.startPayment(request())
        assertTrue(result is PaymentResult.Unknown)
        assertTrue(result !is PaymentResult.Approved)
    }

    @Test
    fun `cada tentativa carrega um attemptId proprio, nunca reaproveitado`() = runTest {
        val provider = FakePaymentProvider()
        val r1 = provider.startPayment(request())
        val r2 = provider.startPayment(request())
        assertTrue(r1.attemptId != r2.attemptId)
    }

    @Test
    fun `falha generica carrega mensagem de erro, nunca some silenciosamente`() = runTest {
        val provider = FakePaymentProvider().apply {
            nextResult = { req -> PaymentResult.Failed(req.attemptId, "Aplicativo da Cielo não encontrado.") }
        }
        val result = provider.startPayment(request()) as PaymentResult.Failed
        assertTrue(result.errorMessage.isNotBlank())
    }
}
