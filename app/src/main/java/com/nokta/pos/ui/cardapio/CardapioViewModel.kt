package com.nokta.pos.ui.cardapio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokta.pos.auth.AuthRepository
import com.nokta.pos.cardapio.data.MenuRepository
import com.nokta.pos.cardapio.domain.Menu
import com.nokta.pos.cardapio.domain.MenuProduct
import com.nokta.pos.cardapio.domain.ModifierGroup
import com.nokta.pos.cart.Cart
import com.nokta.pos.cart.CartLine
import com.nokta.pos.cart.CartLineModifier
import com.nokta.pos.comanda.data.OperationRepository
import com.nokta.pos.sync.OutboxOperation
import com.nokta.pos.sync.OutboxOrderLine
import com.nokta.pos.sync.OutboxOrderLineModifier
import com.nokta.pos.sync.OutboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.Normalizer
import java.util.UUID
import javax.inject.Inject

/** Estado local de configuração de um item antes de ir pro carrinho — variante escolhida, quantidade, adicionais marcados. */
data class ProductDetailState(
    val product: MenuProduct,
    val selectedVariantId: Long,
    val quantity: Int = 1,
    val notes: String = "",
    val modifierGroups: List<ModifierGroup> = emptyList(),
    val selectedOptionIds: Set<Long> = emptySet(),
    val isLoadingModifiers: Boolean = false,
) {
    val selectedVariant get() = product.variants.firstOrNull { it.variantId == selectedVariantId }

    /**
     * Grupos obrigatórios que ainda não têm escolha. Bloqueia o "adicionar"
     * para o operador não lançar um item incompleto que a cozinha não
     * consegue preparar (ex.: combo sem escolher o energético).
     */
    val unmetRequiredGroups: List<ModifierGroup>
        get() = modifierGroups.filter { group ->
            group.required && group.options.none { it.id in selectedOptionIds }
        }

    val canAddToCart get() = unmetRequiredGroups.isEmpty() && selectedVariant != null
}

data class CardapioUiState(
    val menu: Menu? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val cart: Cart = Cart(),
    val productDetail: ProductDetailState? = null,
    val isSubmittingOrder: Boolean = false,
    val submitError: String? = null,
    val query: String = "",
    val selectedCategoryId: Long? = null,
    val isOffline: Boolean = false,
) {
    /**
     * Produtos exibidos. A busca ignora acento e maiúscula porque o operador
     * digita com pressa ("caipi" precisa achar "Caipirinha"), e busca também
     * na descrição — é lá que costuma estar o que compõe um combo.
     */
    val visibleCategories: List<VisibleCategory>
        get() {
            val menu = menu ?: return emptyList()
            val normalizedQuery = query.normalizeForSearch()
            return menu.categories
                .filter { selectedCategoryId == null || it.id == selectedCategoryId }
                .map { category ->
                    val products = if (normalizedQuery.isBlank()) {
                        category.products
                    } else {
                        category.products.filter { product ->
                            product.name.normalizeForSearch().contains(normalizedQuery) ||
                                product.description?.normalizeForSearch()?.contains(normalizedQuery) == true
                        }
                    }
                    VisibleCategory(category.id, category.name, products)
                }
                .filter { it.products.isNotEmpty() }
        }
}

data class VisibleCategory(val id: Long, val name: String, val products: List<MenuProduct>)

private fun String.normalizeForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .trim()

/**
 * Cardápio + carrinho. Serve DOIS contextos com o mesmo código:
 *  - `tabId` presente → lançar itens numa comanda/mesa existente.
 *  - `tabId` ausente  → venda de balcão, onde a comanda só nasce na hora de
 *    cobrar (ver BalcaoViewModel) e aqui só montamos o carrinho.
 *
 * O cardápio nunca é recriado: consome o mesmo `GET menus/:id/preview` que o
 * cardápio digital do dashboard, com o cache local que já existia.
 */
@HiltViewModel
class CardapioViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val menuRepository: MenuRepository,
    private val operationRepository: OperationRepository,
    private val authRepository: AuthRepository,
    private val outboxRepository: OutboxRepository,
) : ViewModel() {

    /** -1 = venda de balcão (ainda não existe comanda). */
    val tabId: Long? = savedStateHandle.get<Long>("tabId")?.takeIf { it > 0 }

    private val _state = MutableStateFlow(CardapioUiState())
    val state: StateFlow<CardapioUiState> = _state

    init { loadMenu() }

    fun loadMenu(forceRefresh: Boolean = false) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        // Resolvido no login (backend informa qual cardápio é o principal da
        // unidade) — antes era um id fixo `1L` que só funcionava por acaso.
        val menuId = authRepository.mainMenuId()
        if (menuId == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Nenhum cardápio principal definido para esta unidade. Configure no dashboard.",
            )
            return
        }

        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { menuRepository.getMenu(organizationId, menuId, forceRefresh) }
                .onSuccess { menu ->
                    _state.value = _state.value.copy(menu = menu, isLoading = false, isOffline = false)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Erro ao carregar cardápio.",
                    )
                }
        }
    }

    fun setQuery(query: String) { _state.value = _state.value.copy(query = query) }

    fun selectCategory(categoryId: Long?) { _state.value = _state.value.copy(selectedCategoryId = categoryId) }

    fun openProduct(product: MenuProduct) {
        val defaultVariant = product.defaultVariant ?: return
        _state.value = _state.value.copy(
            productDetail = ProductDetailState(
                product = product,
                selectedVariantId = defaultVariant.variantId,
                isLoadingModifiers = true,
            ),
        )
        val organizationId = authRepository.currentOrganizationId() ?: return
        viewModelScope.launch {
            // Falha aqui (offline, por exemplo) não impede vender: cai para
            // lista vazia de adicionais em vez de travar o item.
            val groups = runCatching { menuRepository.getModifierGroups(organizationId, product.productId) }
                .getOrDefault(emptyList())
            _state.value = _state.value.copy(
                productDetail = _state.value.productDetail?.copy(modifierGroups = groups, isLoadingModifiers = false),
            )
        }
    }

    fun closeProductDetail() { _state.value = _state.value.copy(productDetail = null) }

    fun selectVariant(variantId: Long) {
        _state.value = _state.value.copy(productDetail = _state.value.productDetail?.copy(selectedVariantId = variantId))
    }

    fun setQuantity(quantity: Int) {
        if (quantity < 1) return
        _state.value = _state.value.copy(productDetail = _state.value.productDetail?.copy(quantity = quantity))
    }

    fun setNotes(notes: String) {
        _state.value = _state.value.copy(productDetail = _state.value.productDetail?.copy(notes = notes))
    }

    /**
     * Marca/desmarca um adicional respeitando `maxSelect` do grupo. Num grupo
     * de escolha única (maxSelect = 1), escolher a segunda opção troca a
     * primeira em vez de recusar o toque — é o que o operador espera.
     */
    fun toggleOption(optionId: Long) {
        val detail = _state.value.productDetail ?: return
        val group = detail.modifierGroups.firstOrNull { g -> g.options.any { it.id == optionId } } ?: return
        val current = detail.selectedOptionIds

        val newSelection = when {
            optionId in current -> current - optionId
            group.maxSelect == 1 -> {
                val otherIdsInGroup = group.options.map { it.id }.toSet()
                current - otherIdsInGroup + optionId
            }
            group.maxSelect != null && current.count { id -> group.options.any { it.id == id } } >= group.maxSelect -> current
            else -> current + optionId
        }
        _state.value = _state.value.copy(productDetail = detail.copy(selectedOptionIds = newSelection))
    }

    fun addProductDetailToCart() {
        val detail = _state.value.productDetail ?: return
        if (!detail.canAddToCart) return
        val variant = detail.selectedVariant ?: return

        val selectedModifiers = detail.modifierGroups.flatMap { group ->
            group.options.filter { it.id in detail.selectedOptionIds }
                .map { option -> CartLineModifier(group.id, option.id, option.name, option.price.cents) }
        }

        val line = CartLine(
            menuItemId = detail.product.menuItemId,
            variantId = variant.variantId,
            productName = detail.product.name,
            variantName = variant.name,
            unitPrice = variant.price,
            quantity = detail.quantity,
            notes = detail.notes.ifBlank { null },
            modifiers = selectedModifiers,
        )
        _state.value = _state.value.copy(cart = _state.value.cart.add(line), productDetail = null)
    }

    fun removeCartLine(localId: String) {
        _state.value = _state.value.copy(cart = _state.value.cart.remove(localId))
    }

    fun updateCartLineQuantity(localId: String, quantity: Int) {
        _state.value = _state.value.copy(cart = _state.value.cart.updateQuantity(localId, quantity))
    }

    fun clearSubmitError() { _state.value = _state.value.copy(submitError = null) }

    /**
     * Envia o carrinho para a comanda. Só existe no contexto de comanda/mesa —
     * a venda de balcão fecha o próprio fluxo (BalcaoViewModel), porque lá o
     * pedido e o pagamento acontecem na mesma ação.
     *
     * `clientRequestId` é gerado UMA vez por tentativa e reaproveitado em
     * retry: um duplo toque ou uma rede instável nunca duplica o pedido.
     */
    fun submitOrder(onDone: () -> Unit) {
        val organizationId = authRepository.currentOrganizationId() ?: return
        val targetTabId = tabId ?: return
        val cart = _state.value.cart
        if (cart.isEmpty) return

        _state.value = _state.value.copy(isSubmittingOrder = true, submitError = null)
        val clientRequestId = pendingClientRequestId ?: UUID.randomUUID().toString().also { pendingClientRequestId = it }

        viewModelScope.launch {
            runCatching {
                operationRepository.submitOrder(organizationId, targetTabId, cart.lines.map { it.toOrderLine() }, clientRequestId)
            }
                .onSuccess {
                    pendingClientRequestId = null
                    _state.value = _state.value.copy(isSubmittingOrder = false, cart = Cart())
                    onDone()
                }
                .onFailure { e ->
                    if (e is IOException) {
                        // Sem rede: o pedido vai para a fila com o MESMO
                        // clientRequestId e sobe quando a conexão voltar. O
                        // garçom não pode ficar parado no meio do salão
                        // esperando sinal para lançar uma cerveja.
                        outboxRepository.enqueue(
                            OutboxOperation.SubmitOrder(
                                id = UUID.randomUUID().toString(),
                                organizationId = organizationId,
                                createdAtEpochMs = System.currentTimeMillis(),
                                tabId = targetTabId,
                                clientRequestId = clientRequestId,
                                lines = cart.lines.map { line ->
                                    val orderLine = line.toOrderLine()
                                    OutboxOrderLine(
                                        menuItemId = orderLine.menuItemId,
                                        variantId = orderLine.variantId,
                                        quantity = orderLine.quantity,
                                        notes = orderLine.notes,
                                        modifiers = orderLine.modifiers.map {
                                            OutboxOrderLineModifier(it.modifierGroupId, it.modifierOptionId, it.quantity)
                                        },
                                    )
                                },
                            ),
                        )
                        pendingClientRequestId = null
                        _state.value = _state.value.copy(
                            isSubmittingOrder = false,
                            cart = Cart(),
                            submitError = "Sem conexão — o pedido foi salvo e será enviado assim que a rede voltar.",
                        )
                        onDone()
                    } else {
                        // Erro de negócio (comanda fechada, item indisponível):
                        // enfileirar não resolveria. Mantém o clientRequestId
                        // para um retry manual não duplicar.
                        _state.value = _state.value.copy(
                            isSubmittingOrder = false,
                            submitError = e.message ?: "Não foi possível enviar o pedido.",
                        )
                    }
                }
        }
    }

    private var pendingClientRequestId: String? = null
}
