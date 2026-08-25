package com.nokta.pos.network.dto

import kotlinx.serialization.Serializable

/**
 * Shape espelha exatamente `buildMenuResponse`/`mapItemWithPrices`
 * (nokta-api src/venue/menu/services/venue-menu-public.service.ts +
 * common/venue-menu-item-pricing.util.ts) — mesmo formato usado pelo preview
 * autenticado do dashboard (GET .../menus/:menuId/preview), reaproveitado
 * aqui.
 */
@Serializable
data class MenuVariantPriceResponse(
    val variantId: Long,
    val variantNome: String,
    val basePriceCents: Long,
    val overridePriceCents: Long? = null,
    val effectivePriceCents: Long,
)

@Serializable
data class MenuItemResponse(
    val id: Long, // menuItemId (VenueMenuItem.id — vínculo produto↔cardápio)
    val productId: Long, // VenueProduct.id — usado para buscar modifier-groups
    val nome: String,
    val descricao: String? = null,
    val imageUrl: String? = null,
    val available: Boolean,
    val prices: List<MenuVariantPriceResponse> = emptyList(),
)

@Serializable
data class MenuCategoryResponse(
    val id: Long,
    val nome: String,
    val descricao: String? = null,
    val imageUrl: String? = null,
    val items: List<MenuItemResponse> = emptyList(),
)

@Serializable
data class MenuBody(
    val nome: String,
    val descricao: String? = null,
    /**
     * Instante da última mudança relevante (categoria ou produto) — o app
     * compara com o que já tem salvo antes de rebaixar o cardápio inteiro.
     * Nulo em cardápio recém-criado sem nada ainda.
     */
    val updatedAt: String? = null,
    val categories: List<MenuCategoryResponse> = emptyList(),
)

@Serializable
data class MenuPreviewResponse(
    val organizationName: String,
    val menu: MenuBody,
)

// ---- Adicionais/variações de um produto — buscados sob demanda ao abrir o
// detalhe do item no carrinho (nunca embutidos na carga inicial do
// cardápio, que não os inclui — ver GET products/:productId/modifier-groups).

@Serializable
data class ModifierOptionResponse(
    val id: Long,
    val nome: String,
    val priceCents: Long,
)

@Serializable
data class ModifierGroupResponse(
    val id: Long,
    val nome: String,
    val options: List<ModifierOptionResponse> = emptyList(),
)

/**
 * Shape real de VenueProductModifierGroupsService.list: o vínculo
 * produto↔grupo (required/minSelect/maxSelect) com `group` aninhado (dados
 * do VenueModifierGroup + suas options) — nunca achatado.
 */
@Serializable
data class ProductModifierGroupResponse(
    val id: Long, // id do vínculo VenueProductModifierGroup
    val modifierGroupId: Long,
    val required: Boolean,
    val minSelect: Int,
    val maxSelect: Int? = null,
    val group: ModifierGroupResponse,
)
