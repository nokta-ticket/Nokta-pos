package com.nokta.pos.ui.cardapio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nokta.pos.cardapio.domain.MenuProduct
import com.nokta.pos.cart.Cart
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.*

/**
 * Cardápio de lançamento. Mesma fonte de dados do cardápio digital público
 * (nada foi recriado) — o que muda é a densidade: aqui o objetivo é achar e
 * lançar um item em segundos, não navegar por uma vitrine.
 *
 * Serve os dois contextos: com `tabId` lança na comanda; sem `tabId` monta o
 * carrinho da venda de balcão e devolve pelo `onCartConfirmed`.
 */
@Composable
fun CardapioScreen(
    onDone: () -> Unit,
    title: String = "Cardápio",
    subtitle: String? = null,
    confirmLabel: String = "Enviar pedido",
    onCartConfirmed: ((Cart) -> Unit)? = null,
    viewModel: CardapioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { PosTopBar(title = title, subtitle = subtitle, onBack = onDone) },
        bottomBar = {
            if (!state.cart.isEmpty) {
                CartBar(
                    cart = state.cart,
                    label = confirmLabel,
                    isSubmitting = state.isSubmittingOrder,
                    onConfirm = {
                        if (onCartConfirmed != null) onCartConfirmed(state.cart)
                        else viewModel.submitOrder(onDone)
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            state.menu?.categories?.takeIf { it.size > 1 }?.let { categories ->
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedCategoryId == null,
                            onClick = { viewModel.selectCategory(null) },
                            label = { Text("Tudo") },
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                    items(categories) { category ->
                        FilterChip(
                            selected = state.selectedCategoryId == category.id,
                            onClick = { viewModel.selectCategory(category.id) },
                            label = { Text(category.name) },
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> PosLoading(label = "Carregando cardápio…")
                    state.error != null -> PosEmptyState(
                        title = "Não foi possível carregar",
                        description = state.error!!,
                        actionText = "Tentar de novo",
                        onAction = { viewModel.loadMenu(forceRefresh = true) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                    state.visibleCategories.isEmpty() -> PosEmptyState(
                        title = "Nenhum produto encontrado",
                        description = if (state.query.isBlank()) {
                            "Este cardápio ainda não tem produtos disponíveis."
                        } else {
                            "Nada encontrado para \"${state.query}\"."
                        },
                        icon = Icons.Filled.Search,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        state.visibleCategories.forEach { category ->
                            item(key = "cat-${category.id}") {
                                Text(
                                    category.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                            items(category.products, key = { "p-${it.menuItemId}" }) { product ->
                                ProductRow(product = product, onClick = { viewModel.openProduct(product) })
                            }
                        }
                    }
                }

                state.submitError?.let {
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        action = { TextButton(onClick = viewModel::clearSubmitError) { Text("Ok") } },
                    ) { Text(it) }
                }
            }
        }
    }

    state.productDetail?.let { detail ->
        ProductDetailSheet(
            detail = detail,
            onDismiss = viewModel::closeProductDetail,
            onSelectVariant = viewModel::selectVariant,
            onQuantityChange = viewModel::setQuantity,
            onNotesChange = viewModel::setNotes,
            onToggleOption = viewModel::toggleOption,
            onAdd = viewModel::addProductDetailToCart,
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Buscar produto") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Limpar")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * Linha de produto. Foto à esquerda (reconhecimento rápido), nome e descrição
 * no meio, preço à direita. Produto esgotado fica visível mas apagado e não
 * clicável — sumir da lista faria o operador procurar algo que não existe.
 */
@Composable
private fun ProductRow(product: MenuProduct, onClick: () -> Unit) {
    val available = product.available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clickable(enabled = available, onClick = onClick)
            .alpha(if (available) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (product.imageUrl != null) {
            AsyncImage(
                model = product.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            product.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (!available) {
                Spacer(Modifier.height(4.dp))
                PosBadge("Esgotado", PosBadgeTone.DANGER)
            }
        }
        Spacer(Modifier.width(12.dp))
        product.defaultVariant?.let { variant ->
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    variant.price.formatBRL(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (product.variants.size > 1) {
                    Text(
                        "${product.variants.size} opções",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
}

/** Barra fixa do carrinho — sempre visível assim que há item, com o total. */
@Composable
private fun CartBar(cart: Cart, label: String, isSubmitting: Boolean, onConfirm: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${cart.itemCount} ${if (cart.itemCount == 1) "item" else "itens"}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(cart.total.formatBRL(), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = onConfirm,
                enabled = !isSubmitting,
                modifier = Modifier.heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Configuração do item: variante, quantidade, adicionais e observação.
 *
 * A observação é o que preserva o pedido real do cliente — "combo com 10
 * energéticos, 8 tradicionais e 2 melancia" só existe aqui (item 7). Grupos
 * obrigatórios sem escolha bloqueiam o botão com o motivo dito na tela.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductDetailSheet(
    detail: ProductDetailState,
    onDismiss: () -> Unit,
    onSelectVariant: (Long) -> Unit,
    onQuantityChange: (Int) -> Unit,
    onNotesChange: (String) -> Unit,
    onToggleOption: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(detail.product.name, style = MaterialTheme.typography.headlineSmall)
            detail.product.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (detail.product.variants.size > 1) {
                Spacer(Modifier.height(20.dp))
                Text("Tamanho", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                detail.product.variants.forEach { variant ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable { onSelectVariant(variant.variantId) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = detail.selectedVariantId == variant.variantId,
                            onClick = { onSelectVariant(variant.variantId) },
                        )
                        Text(variant.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(variant.price.formatBRL(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (detail.isLoadingModifiers) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            detail.modifierGroups.forEach { group ->
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    if (group.required) PosBadge("Obrigatório", PosBadgeTone.WARNING)
                }
                Spacer(Modifier.height(4.dp))
                group.options.forEach { option ->
                    val selected = option.id in detail.selectedOptionIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable { onToggleOption(option.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected, onCheckedChange = { onToggleOption(option.id) })
                        Text(option.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (option.price.isPositive()) {
                            Text("+ ${option.price.formatBRL()}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = detail.notes,
                onValueChange = onNotesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Observação") },
                placeholder = { Text("Ex.: 8 tradicionais e 2 melancia, sem cebola…") },
                minLines = 2,
                shape = MaterialTheme.shapes.medium,
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                QuantityStepper(
                    quantity = detail.quantity,
                    onChange = onQuantityChange,
                )
                Spacer(Modifier.width(16.dp))
                val unit = detail.selectedVariant?.price ?: Money.ZERO
                val extras = Money(
                    detail.modifierGroups
                        .flatMap { it.options }
                        .filter { it.id in detail.selectedOptionIds }
                        .sumOf { it.price.cents },
                )
                Text(
                    ((unit + extras) * detail.quantity).formatBRL(),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            detail.unmetRequiredGroups.firstOrNull()?.let { group ->
                Spacer(Modifier.height(12.dp))
                PosInlineWarning("Escolha uma opção em \"${group.name}\" para continuar.")
            }

            Spacer(Modifier.height(16.dp))
            PosPrimaryButton(
                text = "Adicionar",
                onClick = onAdd,
                enabled = detail.canAddToCart,
            )
        }
    }
}

@Composable
private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onChange(quantity - 1) },
            enabled = quantity > 1,
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.Filled.Remove, contentDescription = "Menos") }
        Text(
            quantity.toString(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.widthIn(min = 52.dp).padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledTonalIconButton(
            onClick = { onChange(quantity + 1) },
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Mais") }
    }
}
