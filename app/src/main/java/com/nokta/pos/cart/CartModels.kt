package com.nokta.pos.cart

import com.nokta.pos.common.Money
import com.nokta.pos.comanda.domain.OrderLine
import com.nokta.pos.comanda.domain.OrderLineModifier
import java.util.UUID

data class CartLineModifier(
    val modifierGroupId: Long,
    val modifierOptionId: Long,
    val name: String,
    val priceCents: Long,
)

/**
 * Um item do carrinho, ainda não enviado ao servidor. Cada linha tem um id
 * local próprio (localId) — permite dois "Hambúrguer" no carrinho com
 * adicionais diferentes sem colidir (seção 15 do PRD: "dois hambúrgueres
 * podem ter configurações diferentes").
 */
data class CartLine(
    val localId: String = UUID.randomUUID().toString(),
    val menuItemId: Long,
    val variantId: Long,
    val productName: String,
    val variantName: String,
    val unitPrice: Money,
    val quantity: Int,
    val notes: String? = null,
    val modifiers: List<CartLineModifier> = emptyList(),
) {
    val modifiersTotal: Money get() = Money(modifiers.sumOf { it.priceCents })
    val lineTotal: Money get() = (unitPrice + modifiersTotal) * quantity

    fun toOrderLine(): OrderLine = OrderLine(
        menuItemId = menuItemId,
        variantId = variantId,
        quantity = quantity,
        notes = notes,
        modifiers = modifiers.map { OrderLineModifier(it.modifierGroupId, it.modifierOptionId) },
    )
}

data class Cart(val lines: List<CartLine> = emptyList()) {
    val isEmpty get() = lines.isEmpty()
    val itemCount get() = lines.sumOf { it.quantity }
    val total: Money get() = Money.sum(lines.map { it.lineTotal })

    fun add(line: CartLine): Cart = copy(lines = lines + line)
    fun remove(localId: String): Cart = copy(lines = lines.filterNot { it.localId == localId })
    fun updateQuantity(localId: String, quantity: Int): Cart {
        if (quantity <= 0) return remove(localId)
        return copy(lines = lines.map { if (it.localId == localId) it.copy(quantity = quantity) else it })
    }
    fun clear(): Cart = Cart()
}
