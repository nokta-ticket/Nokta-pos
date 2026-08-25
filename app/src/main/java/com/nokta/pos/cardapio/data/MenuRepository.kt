package com.nokta.pos.cardapio.data

import com.nokta.pos.cardapio.domain.Menu
import com.nokta.pos.cardapio.domain.MenuCategory
import com.nokta.pos.cardapio.domain.MenuProduct
import com.nokta.pos.cardapio.domain.MenuVariant
import com.nokta.pos.cardapio.domain.ModifierGroup
import com.nokta.pos.cardapio.domain.ModifierOption
import com.nokta.pos.common.Money
import com.nokta.pos.data.local.dao.MenuDao
import com.nokta.pos.data.local.entity.MenuCategoryEntity
import com.nokta.pos.data.local.entity.MenuEntity
import com.nokta.pos.data.local.entity.MenuProductEntity
import com.nokta.pos.data.local.entity.MenuProductWithVariants
import com.nokta.pos.data.local.entity.MenuVariantEntity
import com.nokta.pos.data.local.entity.ModifierGroupEntity
import com.nokta.pos.data.local.entity.ModifierGroupWithOptions
import com.nokta.pos.data.local.entity.ModifierOptionEntity
import com.nokta.pos.network.NoktaApi
import com.nokta.pos.network.dto.MenuPreviewResponse
import com.nokta.pos.network.dto.ProductModifierGroupResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cardápio: o Room é a fonte de verdade que a UI lê — nunca mais "se tiver
 * cache, desserializa o JSON; senão, espera rede" (a versão anterior, em
 * DataStore). O fluxo é sempre API → Room → UI via Flow.
 *
 * Sincronização incremental de verdade: o backend expõe `menu.updatedAt`
 * (o instante da última mudança real em categoria ou produto). Antes de
 * regravar o cardápio inteiro, comparamos com o `fetchedAtEpochMs` salvo —
 * se o servidor não tem nada mais novo que a última sincronização, a
 * chamada não reescreve nada (evita I/O de disco desnecessário a cada
 * reconexão, sem inventar um mecanismo de ETag).
 */
@Singleton
class MenuRepository @Inject constructor(
    private val api: NoktaApi,
    private val menuDao: MenuDao,
) {
    /** A UI observa isto — nunca chama a rede diretamente nem espera por ela. */
    fun observeMenu(menuId: Long): Flow<Menu?> =
        combine(menuDao.observeMenu(menuId), menuDao.observeCategories(menuId), menuDao.observeProductsWithVariants(menuId)) {
            menuEntity, categories, products ->
            if (menuEntity == null) return@combine null
            assembleMenu(menuEntity, categories, products)
        }

    /** Leitura pontual, sem esperar rede — usada por telas que precisam de um snapshot único. */
    suspend fun getCachedMenu(menuId: Long): Menu? {
        val menuEntity = menuDao.getMenu(menuId) ?: return null
        val categories = menuDao.observeCategories(menuId).first()
        val products = menuDao.observeProductsWithVariants(menuId).first()
        return assembleMenu(menuEntity, categories, products)
    }

    private fun assembleMenu(
        menuEntity: MenuEntity,
        categories: List<MenuCategoryEntity>,
        products: List<MenuProductWithVariants>,
    ): Menu {
        val productsByCategory = products.groupBy { it.product.categoryId }
        return Menu(
            menuId = menuEntity.menuId,
            name = menuEntity.nome,
            categories = categories.map { cat ->
                MenuCategory(
                    id = cat.categoryId,
                    name = cat.nome,
                    products = (productsByCategory[cat.categoryId] ?: emptyList()).map { it.toDomain() },
                )
            },
        )
    }

    /**
     * Garante que o cardápio local está atualizado, e devolve o resultado
     * pronto para exibição — chamada pela tela de cardápio ao abrir e pela
     * pré-carga da Home. NUNCA lança por falta de rede se já existe cardápio
     * salvo: uma [IOException] é engolida em silêncio, porque o Room já tem
     * o que a tela precisa mostrar.
     *
     * Lança só quando não há NENHUM cardápio local ainda (primeira vez deste
     * terminal, sem rede) — nesse caso não existe dado nenhum para servir.
     */
    suspend fun ensureMenuSynced(organizationId: Long, menuId: Long, forceRefresh: Boolean = false) {
        val local = menuDao.getMenu(menuId)
        try {
            val response = api.getMenuPreview(organizationId, menuId)
            val remoteUpdatedAt = response.menu.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            // Sem timestamp comparável no servidor (cardápio novo, sem nada
            // ainda) ou sem nada salvo localmente: grava sempre. Com os dois
            // presentes, só regrava se o servidor tiver algo mais novo — essa
            // é a sincronização incremental real, nunca reescreve à toa.
            // `forceRefresh` (puxão manual do operador) ignora essa comparação.
            val shouldWrite = forceRefresh || local == null || remoteUpdatedAt == null || remoteUpdatedAt.toEpochMilli() > local.fetchedAtEpochMs
            if (shouldWrite) writeMenu(organizationId, menuId, response)
        } catch (e: IOException) {
            if (local == null) throw e
            // Já existe cardápio local — a tela funciona com o que tem.
        }
    }

    private suspend fun writeMenu(organizationId: Long, menuId: Long, response: MenuPreviewResponse) {
        val now = System.currentTimeMillis()
        val categories = response.menu.categories.map {
            MenuCategoryEntity(categoryId = it.id, menuId = menuId, nome = it.nome, displayOrder = 0)
        }
        val products = response.menu.categories.flatMap { cat ->
            cat.items.map { item ->
                MenuProductEntity(
                    menuItemId = item.id,
                    categoryId = cat.id,
                    menuId = menuId,
                    productId = item.productId,
                    nome = item.nome,
                    descricao = item.descricao,
                    imageUrl = item.imageUrl,
                    available = item.available,
                    displayOrder = 0,
                )
            }
        }
        val variants = response.menu.categories.flatMap { cat ->
            cat.items.flatMap { item ->
                item.prices.map { price ->
                    MenuVariantEntity(
                        variantId = price.variantId,
                        menuItemId = item.id,
                        nome = price.variantNome,
                        priceCents = price.effectivePriceCents,
                    )
                }
            }
        }
        menuDao.replaceMenu(
            menu = MenuEntity(menuId = menuId, organizationId = organizationId, nome = response.menu.nome, fetchedAtEpochMs = now),
            categories = categories,
            products = products,
            variants = variants,
        )
    }

    /**
     * Adicionais de um produto. Lê o Room primeiro; se vazio, busca a rede
     * (bloqueando, porque não há outra fonte possível na primeira vez).
     * Sempre tenta atualizar em segundo plano depois de servir o que tem —
     * mesmo raciocínio do cardápio.
     */
    suspend fun getModifierGroups(organizationId: Long, productId: Long): List<ModifierGroup> {
        val cached = menuDao.getModifierGroups(productId)
        if (cached.isNotEmpty()) {
            refreshModifiersInBackground(organizationId, productId)
            return cached.map { it.toDomain() }
        }
        val response = api.getProductModifierGroups(organizationId, productId)
        runCatching { writeModifiers(productId, response) }
        return response.map { link ->
            ModifierGroup(
                id = link.modifierGroupId,
                name = link.group.nome,
                required = link.required,
                minSelect = link.minSelect,
                maxSelect = link.maxSelect,
                options = link.group.options.map { ModifierOption(it.id, it.nome, Money(it.priceCents)) },
            )
        }
    }

    private suspend fun refreshModifiersInBackground(organizationId: Long, productId: Long) {
        runCatching {
            val response = api.getProductModifierGroups(organizationId, productId)
            writeModifiers(productId, response)
        }
    }

    private suspend fun writeModifiers(productId: Long, response: List<ProductModifierGroupResponse>) {
        val now = System.currentTimeMillis()
        val groups = response.map {
            ModifierGroupEntity(
                productId = productId,
                modifierGroupId = it.modifierGroupId,
                nome = it.group.nome,
                required = it.required,
                minSelect = it.minSelect,
                maxSelect = it.maxSelect,
                fetchedAtEpochMs = now,
            )
        }
        val optionsByIndex = response.mapIndexed { index, link ->
            index to link.group.options.map { opt ->
                ModifierOptionEntity(groupRowId = 0, modifierOptionId = opt.id, nome = opt.nome, priceCents = opt.priceCents)
            }
        }.toMap()
        menuDao.replaceModifierGroups(productId, groups, optionsByIndex)
    }
}

private fun MenuProductWithVariants.toDomain(): MenuProduct = MenuProduct(
    menuItemId = product.menuItemId,
    productId = product.productId,
    name = product.nome,
    description = product.descricao,
    imageUrl = product.imageUrl,
    available = product.available,
    variants = variants.map { MenuVariant(it.variantId, it.nome, Money(it.priceCents)) },
)

private fun ModifierGroupWithOptions.toDomain(): ModifierGroup = ModifierGroup(
    id = group.modifierGroupId,
    name = group.nome,
    required = group.required,
    minSelect = group.minSelect,
    maxSelect = group.maxSelect,
    options = options.map { ModifierOption(it.modifierOptionId, it.nome, Money(it.priceCents)) },
)
