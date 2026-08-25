package com.nokta.pos.ui.cardapio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nokta.pos.cardapio.domain.MenuProduct
import com.nokta.pos.cart.Cart
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.NoktaBackground
import com.nokta.pos.ui.theme.NoktaBorder
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface

/* =========================================================================
 *  MEDIDAS — layout compacto, grid de 2 colunas. Ajuste aqui para
 *  apertar/soltar a tela toda.
 * ========================================================================= */
private object Dim {
    val ScreenPad = 14.dp
    val GridGap = 10.dp
    val CardRadius = 16.dp
    val CardPad = 11.dp
    val ImageRatio = 1.75f
    val ActionSize = 38.dp
    val ChipHeight = 34.dp
    val SearchHeight = 46.dp
}

/**
 * Cardápio de lançamento. Mesma fonte de dados do cardápio digital público
 * (nada foi recriado) — o que muda é a densidade: aqui o objetivo é achar e
 * lançar um item em segundos, não navegar por uma vitrine.
 *
 * Serve os dois contextos: com `tabId` lança na comanda; sem `tabId` monta o
 * carrinho da venda de balcão e devolve pelo `onCartConfirmed`.
 *
 * Grid de 2 colunas com foto grande, chips de categoria e barra de carrinho
 * fixa — visual portado de um mockup de referência, sem tocar a lógica de
 * estado/dados (ainda vem 100% de [CardapioViewModel]/[com.nokta.pos.cardapio.data.MenuRepository]).
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

    // A grid nunca reseta o próprio scroll quando o conteúdo muda de tamanho
    // (LazyVerticalGrid não sabe "por que" a lista mudou) — sem isto, digitar
    // ou limpar a busca deixava a tela parada na posição de rolagem antiga,
    // sobre um resultado diferente do que está sendo mostrado agora.
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.query, state.selectedCategoryId) {
        gridState.scrollToItem(0)
    }

    Scaffold(
        topBar = { PosTopBar(title = title, subtitle = subtitle, onBack = onDone) },
        containerColor = NoktaBackground,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    modifier = Modifier.padding(horizontal = Dim.ScreenPad, vertical = 10.dp),
                )

                state.menu?.categories?.takeIf { it.size > 1 }?.let { categories ->
                    CategoryChipsRow(
                        categoryNames = categories.associate { it.id to it.name },
                        selectedId = state.selectedCategoryId,
                        onSelect = viewModel::selectCategory,
                    )
                    Spacer(Modifier.height(4.dp))
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
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Dim.ScreenPad,
                                end = Dim.ScreenPad,
                                top = 4.dp,
                                bottom = if (!state.cart.isEmpty) 110.dp else 24.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(Dim.GridGap),
                            verticalArrangement = Arrangement.spacedBy(Dim.GridGap),
                        ) {
                            state.visibleCategories.forEach { category ->
                                item(key = "cat-${category.id}", span = { GridItemSpan(2) }) {
                                    Text(
                                        category.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NoktaMuted,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                    )
                                }
                                items(category.products, key = { "p-${it.menuItemId}" }) { product ->
                                    ProductCard(product = product, onClick = { viewModel.openProduct(product) })
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

            if (!state.cart.isEmpty) {
                CartBar(
                    cart = state.cart,
                    label = confirmLabel,
                    isSubmitting = state.isSubmittingOrder,
                    onConfirm = {
                        if (onCartConfirmed != null) onCartConfirmed(state.cart)
                        else viewModel.submitOrder(onDone)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
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

/* ------------------------------ Busca ------------------------------- */

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dim.SearchHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF4F3F7))
            .border(1.dp, Color(0xFFEDEBF3), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(11.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Buscar produto", fontSize = 14.5.sp, color = NoktaMutedSoft)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.5.sp, color = NoktaInk),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(NoktaPurple),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Limpar",
                tint = NoktaMutedSoft,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onQueryChange("") },
            )
        }
    }
}

/* ---------------------------- Categorias ---------------------------- */

@Composable
private fun CategoryChipsRow(
    categoryNames: Map<Long, String>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dim.ScreenPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            CategoryChip(label = "Tudo", selected = selectedId == null, onClick = { onSelect(null) })
        }
        items(categoryNames.entries.toList(), key = { it.key }) { (id, name) ->
            CategoryChip(label = name, selected = selectedId == id, onClick = { onSelect(id) })
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(Dim.ChipHeight)
            .clip(RoundedCornerShape(50))
            .background(if (selected) NoktaPurple else Color(0xFFF4F3F7))
            .border(1.dp, if (selected) NoktaPurple else Color(0xFFEDEBF3), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.White else NoktaInk,
            maxLines = 1,
        )
    }
}

/* --------------------------- Card produto --------------------------- */

@Composable
private fun ProductCard(product: MenuProduct, onClick: () -> Unit) {
    val soldOut = !product.available

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Dim.CardRadius))
            .background(if (soldOut) Color(0xFFF7F6FA) else NoktaSurface)
            .border(1.dp, NoktaBorder, RoundedCornerShape(Dim.CardRadius))
            .clickable(enabled = !soldOut, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(Dim.ImageRatio)
                .background(Color(0xFF111111)),
        ) {
            if (product.imageUrl != null) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = product.name,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (soldOut) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (soldOut) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.35f)))
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF3A3A46).copy(alpha = 0.88f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text("ESGOTADO", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = Color.White)
                }
            }
        }

        Column(Modifier.padding(Dim.CardPad)) {
            Text(
                text = product.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = if (soldOut) NoktaMuted else NoktaInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            product.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = NoktaMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    product.defaultVariant?.let { variant ->
                        Text(
                            text = variant.price.formatBRL(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (soldOut) NoktaMutedSoft else NoktaPurple,
                        )
                        if (product.variants.size > 1) {
                            Text("${product.variants.size} opções", fontSize = 11.sp, color = NoktaMutedSoft)
                        }
                    }
                }

                if (!soldOut) {
                    Box(
                        modifier = Modifier
                            .size(Dim.ActionSize)
                            .clip(RoundedCornerShape(11.dp))
                            .background(NoktaPurple)
                            .clickable(onClick = onClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Adicionar", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/* ---------------------------- Barra carrinho ------------------------ */

@Composable
private fun CartBar(cart: Cart, label: String, isSubmitting: Boolean, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(NoktaPurple)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(21.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(NoktaPurpleBright)
                    .border(1.5.dp, NoktaPurple, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(cart.itemCount.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = "${cart.itemCount} ${if (cart.itemCount == 1) "item" else "itens"}",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(cart.total.formatBRL(), fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = Color.White)
        }

        Row(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White)
                .clickable(enabled = !isSubmitting, onClick = onConfirm)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NoktaPurple)
            } else {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
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
    // skipPartiallyExpanded: sem isto, o Material3 abre o sheet num estado
    // "parcialmente expandido" sempre que o conteúdo é alto (caso comum aqui,
    // com variantes + adicionais + observação) — o botão "Adicionar" e o
    // stepper de quantidade ficam cortados na primeira tela, exigindo um
    // arrasto manual pra aparecer. Forçando sempre o estado totalmente
    // expandido, o conteúdo mais alto que a tela ainda rola normalmente
    // dentro da Column, mas nunca abre já cortado.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                QuantityStepper(quantity = detail.quantity, onChange = onQuantityChange)
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
