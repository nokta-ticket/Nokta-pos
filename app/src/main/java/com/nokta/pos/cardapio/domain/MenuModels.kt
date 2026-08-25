package com.nokta.pos.cardapio.domain

import com.nokta.pos.common.Money

data class MenuVariant(val variantId: Long, val name: String, val price: Money)

data class MenuProduct(
    val menuItemId: Long,
    val productId: Long,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val available: Boolean,
    val variants: List<MenuVariant>,
) {
    /** Variante "principal" para exibição de preço no card — a mais barata, mesmo critério do cardápio digital. */
    val defaultVariant: MenuVariant? get() = variants.minByOrNull { it.price.cents }
}

data class MenuCategory(val id: Long, val name: String, val products: List<MenuProduct>)

data class Menu(val menuId: Long, val name: String, val categories: List<MenuCategory>)

data class ModifierOption(val id: Long, val name: String, val price: Money)

data class ModifierGroup(
    val id: Long,
    val name: String,
    val required: Boolean,
    val minSelect: Int,
    val maxSelect: Int?,
    val options: List<ModifierOption>,
)
