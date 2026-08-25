package com.nokta.pos.payment.domain

import com.nokta.pos.common.Money

/**
 * Divisão de conta. Pura e sem I/O — o garçom nunca deve fazer conta de
 * cabeça (item 13 do brief), e essa aritmética precisa ser testável sem app,
 * sem rede e sem banco.
 *
 * Regra do centavo: dividir R$ 100,00 por 3 dá 33,333... Em vez de arredondar
 * cada parte (o que perderia ou criaria centavos e deixaria a conta sem
 * fechar), distribuímos o resto de 1 em 1 centavo entre as primeiras partes.
 * A soma das partes é SEMPRE exatamente igual ao total — invariante que os
 * testes garantem.
 */
object SplitCalculator {

    /**
     * Divide [total] em [people] partes iguais, distribuindo os centavos que
     * sobram. Ex.: R$ 100,00 ÷ 3 = [33,34 / 33,33 / 33,33].
     */
    fun splitEqually(total: Money, people: Int): List<Money> {
        require(people > 0) { "Número de pessoas deve ser maior que zero" }
        val base = total.cents / people
        val remainder = (total.cents % people).toInt()
        return List(people) { index ->
            Money(base + if (index < remainder) 1 else 0)
        }
    }

    /**
     * Quanto cada pessoa paga quando ainda faltam [remaining] e [people]
     * pessoas vão dividir o que sobrou. Usado quando parte da mesa já pagou.
     */
    fun splitRemaining(remaining: Money, people: Int): List<Money> = splitEqually(remaining, people)

    /**
     * Valida um pagamento parcial antes de mandar pro servidor. O backend
     * rejeita valor acima do saldo, mas checar aqui evita uma ida à rede (e
     * uma cobrança no cartão) que já sabemos que vai falhar.
     */
    fun validatePartial(amount: Money, remaining: Money): PartialValidation = when {
        amount.cents <= 0 -> PartialValidation.Invalid("Informe um valor maior que zero.")
        amount.cents > remaining.cents -> PartialValidation.Invalid(
            "O valor não pode passar do saldo restante (${remaining.formatBRL()}).",
        )
        else -> PartialValidation.Valid
    }

    /**
     * Troco de um pagamento em dinheiro. Recebido menor que o valor é erro do
     * operador (nunca troco negativo).
     */
    fun change(amountDue: Money, received: Money): Money? =
        if (received.cents <= amountDue.cents) null else Money(received.cents - amountDue.cents)
}

sealed class PartialValidation {
    data object Valid : PartialValidation()
    data class Invalid(val reason: String) : PartialValidation()
}
