package com.nokta.pos.common

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `soma e subtracao operam em centavos inteiros`() {
        val a = Money(1050) // R$ 10,50
        val b = Money(299)  // R$ 2,99
        assertEquals(Money(1349), a + b)
        assertEquals(Money(751), a - b)
    }

    @Test
    fun `multiplicacao por quantidade nunca perde precisao`() {
        // 3 unidades de R$ 33,33 = R$ 99,99 exato — um Double aqui arriscaria 99.99000000000001
        assertEquals(Money(9999), Money(3333) * 3)
    }

    @Test
    fun `formatBRL formata centavos com duas casas`() {
        assertEquals("R\$ 10,50", Money(1050).formatBRL())
        assertEquals("R\$ 0,05", Money(5).formatBRL())
        assertEquals("R\$ 100,00", Money(10000).formatBRL())
    }

    @Test
    fun `isPositive e isZeroOrNegative sao mutuamente exclusivos`() {
        assertEquals(true, Money(1).isPositive())
        assertEquals(false, Money(1).isZeroOrNegative())
        assertEquals(true, Money(0).isZeroOrNegative())
        assertEquals(true, Money(-1).isZeroOrNegative())
    }
}
