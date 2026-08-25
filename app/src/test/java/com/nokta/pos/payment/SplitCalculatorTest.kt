package com.nokta.pos.payment

import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.PartialValidation
import com.nokta.pos.payment.domain.SplitCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A invariante que todos estes testes protegem: a soma das partes é SEMPRE
 * exatamente o total. Um centavo perdido numa divisão vira uma conta que não
 * fecha, e o garçom descobriria isso na frente do cliente.
 */
class SplitCalculatorTest {

    @Test
    fun `divisao exata distribui igualmente`() {
        val parts = SplitCalculator.splitEqually(Money(10_000), 4)
        assertEquals(listOf(2_500L, 2_500L, 2_500L, 2_500L), parts.map { it.cents })
    }

    @Test
    fun `divisao com resto poe os centavos extras nas primeiras partes`() {
        // R$ 100,00 / 3 = 33,333... → 33,34 + 33,33 + 33,33
        val parts = SplitCalculator.splitEqually(Money(10_000), 3)
        assertEquals(listOf(3_334L, 3_333L, 3_333L), parts.map { it.cents })
    }

    @Test
    fun `soma das partes sempre bate com o total`() {
        // Varre uma faixa ampla: qualquer combinação de total e pessoas tem
        // que fechar na soma, sem exceção.
        for (totalCents in listOf(1L, 7L, 99L, 100L, 3_333L, 10_000L, 12_345L, 99_999L)) {
            for (people in 2..12) {
                val parts = SplitCalculator.splitEqually(Money(totalCents), people)
                assertEquals(
                    "total=$totalCents pessoas=$people",
                    totalCents,
                    parts.sumOf { it.cents },
                )
                assertEquals(people, parts.size)
            }
        }
    }

    @Test
    fun `divisao de valor menor que o numero de pessoas nao gera parte negativa`() {
        // R$ 0,02 entre 5 pessoas: duas pagam 1 centavo, três pagam zero.
        val parts = SplitCalculator.splitEqually(Money(2), 5)
        assertEquals(2L, parts.sumOf { it.cents })
        assertTrue(parts.all { it.cents >= 0 })
    }

    @Test
    fun `divisao por uma pessoa devolve o total inteiro`() {
        val parts = SplitCalculator.splitEqually(Money(4_567), 1)
        assertEquals(listOf(4_567L), parts.map { it.cents })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `divisao por zero pessoas e rejeitada`() {
        SplitCalculator.splitEqually(Money(1_000), 0)
    }

    // ---- Pagamento parcial ----

    @Test
    fun `pagamento parcial dentro do saldo e valido`() {
        val result = SplitCalculator.validatePartial(amount = Money(5_000), remaining = Money(10_000))
        assertTrue(result is PartialValidation.Valid)
    }

    @Test
    fun `pagamento igual ao saldo e valido`() {
        val result = SplitCalculator.validatePartial(amount = Money(10_000), remaining = Money(10_000))
        assertTrue(result is PartialValidation.Valid)
    }

    @Test
    fun `pagamento acima do saldo e recusado antes de ir para a rede`() {
        val result = SplitCalculator.validatePartial(amount = Money(10_001), remaining = Money(10_000))
        assertTrue(result is PartialValidation.Invalid)
    }

    @Test
    fun `pagamento de valor zero e recusado`() {
        val result = SplitCalculator.validatePartial(amount = Money(0), remaining = Money(10_000))
        assertTrue(result is PartialValidation.Invalid)
    }

    // ---- Troco ----

    @Test
    fun `troco e a diferenca entre recebido e devido`() {
        val change = SplitCalculator.change(amountDue = Money(3_750), received = Money(5_000))
        assertEquals(1_250L, change?.cents)
    }

    @Test
    fun `valor exato nao gera troco`() {
        assertNull(SplitCalculator.change(amountDue = Money(5_000), received = Money(5_000)))
    }

    @Test
    fun `recebido menor que o devido nunca gera troco negativo`() {
        assertNull(SplitCalculator.change(amountDue = Money(5_000), received = Money(3_000)))
    }

    // ---- Cenário do brief: conta de R$ 300 paga por 3 pessoas ----

    @Test
    fun `conta de 300 dividida em tres partes de 100`() {
        val parts = SplitCalculator.splitRemaining(Money(30_000), 3)
        assertEquals(listOf(10_000L, 10_000L, 10_000L), parts.map { it.cents })
    }

    @Test
    fun `pagamentos parciais sucessivos consomem o saldo ate zerar`() {
        // Simula o exemplo do brief: 100 + 50 + 150 numa conta de 300.
        var remaining = Money(30_000)
        listOf(10_000L, 5_000L, 15_000L).forEach { paymentCents ->
            val validation = SplitCalculator.validatePartial(Money(paymentCents), remaining)
            assertTrue("pagamento de $paymentCents deveria ser válido", validation is PartialValidation.Valid)
            remaining = Money(remaining.cents - paymentCents)
        }
        assertEquals(0L, remaining.cents)
    }
}
