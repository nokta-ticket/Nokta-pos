package com.nokta.pos.cardapio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nokta.pos.cardapio.domain.Menu
import com.nokta.pos.cardapio.domain.MenuCategory
import com.nokta.pos.cardapio.domain.MenuProduct
import com.nokta.pos.cardapio.domain.MenuVariant
import com.nokta.pos.cardapio.domain.ModifierGroup
import com.nokta.pos.cardapio.domain.ModifierOption
import com.nokta.pos.common.Money
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.MenuPreviewResponse
import com.nokta.pos.network.dto.ProductModifierGroupResponse
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.menuCacheDataStore by preferencesDataStore(name = "menu_cache")

/**
 * Cardápio: uma chamada de rede por sessão de trabalho (nunca por clique de
 * produto — seção 11 do PRD), cacheado localmente em disco. Ao abrir a Home,
 * o app tenta rede primeiro; se falhar (sem sinal no meio do salão), cai
 * pro cache — nunca trava numa tela de erro por falta de internet passageira.
 * Grupos de modificadores são buscados sob demanda por produto (não vêm no
 * preview do cardápio) e cacheados separadamente, mesma estratégia.
 */
@Singleton
class MenuRepository @Inject constructor(
    private val context: Context,
    private val api: NoktaApi,
) {
    private val menuKey = stringPreferencesKey("cached_menu_json")
    private val menuIdKey = stringPreferencesKey("cached_menu_id")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getMenu(organizationId: Long, menuId: Long, forceRefresh: Boolean = false): Menu {
        if (!forceRefresh) {
            val cached = readCache(menuId)
            if (cached != null) {
                refreshInBackground(organizationId, menuId)
                return cached
            }
        }
        val response = api.getMenuPreview(organizationId, menuId)
        writeCache(menuId, response)
        return response.toDomain(menuId)
    }

    private suspend fun refreshInBackground(organizationId: Long, menuId: Long) {
        runCatching {
            val response = api.getMenuPreview(organizationId, menuId)
            writeCache(menuId, response)
        }
        // Falha silenciosa de propósito: o cache já foi servido ao chamador,
        // esta é só uma atualização oportunista para a PRÓXIMA leitura.
    }

    private suspend fun readCache(requestedMenuId: Long): Menu? {
        val prefs = context.menuCacheDataStore.data.first()
        val cachedMenuId = prefs[menuIdKey]?.toLongOrNull() ?: return null
        if (cachedMenuId != requestedMenuId) return null
        val raw = prefs[menuKey] ?: return null
        return runCatching { json.decodeFromString<MenuPreviewResponse>(raw).toDomain(requestedMenuId) }.getOrNull()
    }

    private suspend fun writeCache(menuId: Long, response: MenuPreviewResponse) {
        context.menuCacheDataStore.edit {
            it[menuKey] = json.encodeToString(response)
            it[menuIdKey] = menuId.toString()
        }
    }

    /**
     * Adicionais de um produto. Cacheados por produto porque o operador
     * precisa deles no meio do salão, onde a rede cai — sem cache, um combo
     * que exige escolha (energético da garrafa) ficaria impossível de lançar
     * offline. Rede primeiro (dado fresco), cache como rede de segurança.
     */
    suspend fun getModifierGroups(organizationId: Long, productId: Long): List<ModifierGroup> {
        val fromNetwork = runCatching { api.getProductModifierGroups(organizationId, productId) }
        fromNetwork.getOrNull()?.let { response ->
            runCatching { writeModifierCache(productId, response) }
            return response.toDomainGroups()
        }
        return readModifierCache(productId)?.toDomainGroups() ?: throw (fromNetwork.exceptionOrNull() ?: IllegalStateException("Sem adicionais"))
    }

    private fun modifierKey(productId: Long) = stringPreferencesKey("cached_modifiers_$productId")

    private suspend fun writeModifierCache(productId: Long, response: List<ProductModifierGroupResponse>) {
        context.menuCacheDataStore.edit { it[modifierKey(productId)] = json.encodeToString(response) }
    }

    private suspend fun readModifierCache(productId: Long): List<ProductModifierGroupResponse>? {
        val raw = context.menuCacheDataStore.data.first()[modifierKey(productId)] ?: return null
        return runCatching { json.decodeFromString<List<ProductModifierGroupResponse>>(raw) }.getOrNull()
    }
}

private fun List<ProductModifierGroupResponse>.toDomainGroups(): List<ModifierGroup> = map { link ->
    ModifierGroup(
        id = link.modifierGroupId,
        name = link.group.nome,
        required = link.required,
        minSelect = link.minSelect,
        maxSelect = link.maxSelect,
        options = link.group.options.map { ModifierOption(it.id, it.nome, Money(it.priceCents)) },
    )
}

private fun MenuPreviewResponse.toDomain(menuId: Long): Menu = Menu(
    menuId = menuId,
    name = menu.nome,
    categories = menu.categories.map { category ->
        MenuCategory(
            id = category.id,
            name = category.nome,
            products = category.items.map { item ->
                MenuProduct(
                    menuItemId = item.id,
                    productId = item.productId,
                    name = item.nome,
                    description = item.descricao,
                    imageUrl = item.imageUrl,
                    available = item.available,
                    variants = item.prices.map { MenuVariant(it.variantId, it.variantNome, Money(it.effectivePriceCents)) },
                )
            },
        )
    },
)
