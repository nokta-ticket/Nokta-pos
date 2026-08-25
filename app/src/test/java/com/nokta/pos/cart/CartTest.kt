package com.nokta.pos.cart

import com.nokta.pos.common.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CartTest {

    private fun line(unitPriceCents: Long, quantity: Int, modifiers: List<CartLineModifier> = emptyList()) = CartLine(
        menuItemId = 1,
        variantId = 1,
        productName = "Hambúrguer",
        variantName = "Único",
        unitPrice = Money(unitPriceCents),
        quantity = quantity,
        modifiers = modifiers,
    )

    @Test
    fun `carrinho vazio tem total zero`() {
        assertTrue(Cart().isEmpty)
        assertEquals(Money.ZERO, Cart().total)
    }

    @Test
    fun `adicionar item soma ao total`() {
        val cart = Cart().add(line(3000, 2))
        assertEquals(Money(6000), cart.total)
        assertEquals(2, cart.itemCount)
    }

    @Test
    fun `dois itens do mesmo produto com adicionais diferentes nao colidem`() {
        val l1 = line(3000, 1, listOf(CartLineModifier(1, 1, "Queijo", 500)))
        val l2 = line(3000, 1, listOf(CartLineModifier(1, 2, "Bacon", 700)))
        val cart = Cart().add(l1).add(l2)

        assertEquals(2, cart.lines.size)
        assertEquals(Money(3500 + 3700), cart.total)
    }

    @Test
    fun `atualizar quantidade para zero remove a linha`() {
        val l = line(1000, 1)
        val cart = Cart().add(l).updateQuantity(l.localId, 0)
        assertTrue(cart.isEmpty)
    }

    @Test
    fun `remover item por localId nao afeta outros`() {
        val l1 = line(1000, 1)
        val l2 = line(2000, 1)
        val cart = Cart().add(l1).add(l2).remove(l1.localId)

        assertEquals(1, cart.lines.size)
        assertEquals(l2.localId, cart.lines.first().localId)
    }

    @Test
    fun `linha com modificadores calcula total com adicionais multiplicado pela quantidade`() {
        val l = line(3000, 3, listOf(CartLineModifier(1, 1, "Queijo", 500)))
        // (3000 + 500) * 3 = 10500
        assertEquals(Money(10500), l.lineTotal)
    }
}
