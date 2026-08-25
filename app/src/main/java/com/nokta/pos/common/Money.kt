package com.nokta.pos.common

import java.text.NumberFormat
import java.util.Locale

/**
 * Valor monetário em centavos (Long, nunca Float/Double — seção 14 do PRD:
 * arredondamento de ponto flutuante é inaceitável para dinheiro). Todo valor
 * que atravessa a API (amountCents, unitPriceCents, totalCents...) já vem em
 * centavos do backend — este tipo só formata para exibição.
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(cents + other.cents)
    operator fun minus(other: Money): Money = Money(cents - other.cents)
    operator fun times(quantity: Int): Money = Money(cents * quantity)

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    fun isPositive(): Boolean = cents > 0
    fun isZeroOrNegative(): Boolean = cents <= 0

    fun formatBRL(): String {
        val reais = cents / 100
        val centavos = kotlin.math.abs(cents % 100)
        val formatter = NumberFormat.getNumberInstance(Locale("pt", "BR"))
        return "R$ ${formatter.format(reais)},${centavos.toString().padStart(2, '0')}"
    }

    companion object {
        val ZERO = Money(0)
        fun ofCents(cents: Long) = Money(cents)
        fun sum(values: Collection<Money>): Money = Money(values.sumOf { it.cents })
    }
}
