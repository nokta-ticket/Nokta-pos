package com.nokta.pos.ui.comanda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabItem
import com.nokta.pos.comanda.domain.TabPayment
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.ui.components.PosBadge
import com.nokta.pos.ui.components.PosBadgeTone
import com.nokta.pos.ui.components.PosEmptyState
import com.nokta.pos.ui.components.PosInlineWarning
import com.nokta.pos.ui.components.PosLoading
import com.nokta.pos.ui.theme.MoneyGreen
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface

/* =========================================================================
 *  AJUSTES RÁPIDOS — mexa só aqui para calibrar a tela
 * ========================================================================= */
private object Dim {
    val ScreenPad = 16.dp
    val CardRadius = 10.dp
    val FieldRadius = 10.dp
    val ButtonRadius = 10.dp

    val QtyColumn = 40.dp
    val UnitColumn = 68.dp
    val TotalColumn = 74.dp
    val ActionColumn = 30.dp

    val RowPadH = 14.dp
    val BottomBarHeight = 54.dp
}

private val PageGray = Color(0xFFF5F4F8)
private val TableHeaderBg = Color(0xFFF7F6FA)
private val LineColor = Color(0xFFEFEDF5)
private val FieldBorder = Color(0xFFE7E4EF)

/**
 * Comanda/mesa aberta: quem é, o que consumiu, quanto pagou, quanto falta.
 *
 * O saldo restante é o número maior da tela porque é ele que decide a próxima
 * ação do garçom. Itens pendentes de preparo aparecem com o status ao lado —
 * informativo, nunca impedindo cobrar (item 14). Busca filtra só a lista já
 * carregada (client-side, por nome do produto) — não é uma feature de
 * backend nova, só ajuda a achar um item numa comanda grande.
 */
@Composable
fun ComandaScreen(
    tabId: String,
    onAddProducts: () -> Unit,
    onCheckout: () -> Unit,
    onBack: () -> Unit,
    viewModel: ComandaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    Box(Modifier.fillMaxSize().background(NoktaSurface)) {
        when {
            state.isLoading && state.tab == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PosLoading(label = "Carregando comanda…")
            }
            state.error != null && state.tab == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PosEmptyState(
                    title = "Não foi possível abrir",
                    description = state.error!!,
                    actionText = "Tentar de novo",
                    onAction = viewModel::refresh,
                )
            }
            state.tab != null -> ComandaContent(
                tab = state.tab!!,
                query = query,
                onQueryChange = { query = it },
                canAddItems = state.access.canCreateOrders,
                canTakePayments = state.access.canTakePayments,
                canCancelItems = state.access.canManageTabs || state.access.canCreateOrders,
                isClosing = state.isClosing,
                onCancelItem = viewModel::askCancelItem,
                onRemoveDraftItem = viewModel::removeDraftItem,
                onAddProducts = onAddProducts,
                onCheckout = onCheckout,
                onCloseTab = viewModel::closeTab,
                onRequestClose = viewModel::requestClose,
                onCancelClose = viewModel::cancelClose,
                onBack = onBack,
            )
        }

        state.actionMessage?.let {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = viewModel::clearActionMessage) { Text("Ok") } },
            ) { Text(it) }
        }
    }

    state.itemPendingCancel?.let { item ->
        CancelItemDialog(
            item = item,
            isProcessing = state.isCancelingItem,
            onConfirm = viewModel::confirmCancelItem,
            onDismiss = viewModel::dismissCancelItem,
        )
    }
}

@Composable
private fun ComandaContent(
    tab: Tab,
    query: String,
    onQueryChange: (String) -> Unit,
    canAddItems: Boolean,
    canTakePayments: Boolean,
    canCancelItems: Boolean,
    isClosing: Boolean,
    onCancelItem: (TabItem) -> Unit,
    onRemoveDraftItem: (TabItem) -> Unit,
    onAddProducts: () -> Unit,
    onCheckout: () -> Unit,
    onCloseTab: () -> Unit,
    onRequestClose: () -> Unit,
    onCancelClose: () -> Unit,
    onBack: () -> Unit,
) {
    val filteredItems = if (query.isBlank()) {
        tab.items
    } else {
        tab.items.filter { it.productName.contains(query, ignoreCase = true) }
    }
    val activePayments = tab.payments.filterNot { it.isCanceled }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = tab.displayName, subtitle = tab.customerName, onBack = onBack)

        Column(Modifier.padding(horizontal = Dim.ScreenPad)) {
            BalanceCard(tab)

            when (tab.status) {
                TabStatus.CLOSING -> {
                    Spacer(Modifier.height(12.dp))
                    PosInlineWarning("Fechando a conta — consumo travado, ainda sem pagamento registrado.")
                }
                TabStatus.PAYMENT_IN_PROGRESS -> {
                    Spacer(Modifier.height(12.dp))
                    PosInlineWarning("Recebendo pagamento — consumo travado até quitar ou cancelar o pagamento.")
                }
                else -> Unit
            }

            Spacer(Modifier.height(12.dp))
            SearchField(query = query, onQueryChange = onQueryChange, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }

        // ---------- Consumo ----------
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = Dim.ScreenPad)
                .clip(RoundedCornerShape(Dim.CardRadius))
                .border(1.dp, LineColor, RoundedCornerShape(Dim.CardRadius)),
        ) {
            ConsumptionHeader()

            if (filteredItems.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (tab.items.isEmpty()) "Nenhum item lançado ainda." else "Nenhum item encontrado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NoktaMutedSoft,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(filteredItems, key = { it.id }) { item ->
                        Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = Dim.RowPadH).background(LineColor))
                        ItemRow(
                            item = item,
                            canCancel = canCancelItems && !item.status.isCanceled,
                            onCancel = { if (item.canRemoveAsDraft) onRemoveDraftItem(item) else onCancelItem(item) },
                        )
                    }
                }
            }

            if (activePayments.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = Dim.RowPadH).background(LineColor))
                Column(Modifier.padding(horizontal = Dim.RowPadH, vertical = 12.dp)) {
                    Text("Pagamentos", style = MaterialTheme.typography.titleMedium, color = NoktaInk)
                    activePayments.forEach { PaymentRow(it) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        BottomActions(
            tab = tab,
            canAddItems = canAddItems,
            canTakePayments = canTakePayments,
            isClosing = isClosing,
            onAddProducts = onAddProducts,
            onCheckout = onCheckout,
            onCloseTab = onCloseTab,
            onRequestClose = onRequestClose,
            onCancelClose = onCancelClose,
        )
    }
}

/* ------------------------------ Top bar ----------------------------- */

@Composable
private fun TopBar(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = NoktaInk, modifier = Modifier.size(24.dp))
        }

        Spacer(Modifier.width(6.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = NoktaInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = NoktaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ---------------------------- Card do total ------------------------- */

@Composable
private fun BalanceCard(tab: Tab) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(Dim.CardRadius))
            .background(PageGray),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(NoktaPurpleBright))

        Column(Modifier.padding(18.dp).fillMaxWidth()) {
            Text(
                // "Quitada" só faz sentido depois de já ter havido pagamento
                // (paid > 0) — numa mesa o consumo acontece o atendimento
                // inteiro e só é cobrado no fim; mostrar "Quitada" com
                // remaining=0 assim que a mesa abre (sem nenhum pagamento
                // ainda) sugeria erroneamente que algo já tinha sido pago.
                text = if (tab.isFullyPaid && tab.paid.isPositive()) "QUITADA" else "TOTAL DE CONSUMO",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
                color = NoktaMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = tab.remaining.formatBRL(),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                color = if (tab.isFullyPaid && tab.paid.isPositive()) MoneyGreen else NoktaInk,
            )

            Spacer(Modifier.height(10.dp))

            MoneyLine("Total", tab.total.formatBRL())
            if (tab.discount.isPositive()) MoneyLine("Desconto", tab.discount.formatBRL())
            if (tab.serviceCharge.isPositive()) MoneyLine("Serviço", tab.serviceCharge.formatBRL())
            if (tab.paid.isPositive()) MoneyLine("Pago", tab.paid.formatBRL(), color = MoneyGreen)

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.FormatListBulleted,
                    contentDescription = null,
                    tint = NoktaPurpleBright,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                val count = tab.activeItems.size
                Text(
                    text = if (count == 1) "1 item" else "$count itens",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NoktaPurpleBright,
                )
            }
        }
    }
}

@Composable
private fun MoneyLine(label: String, value: String, color: Color = NoktaMuted) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = NoktaMuted)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/* ------------------------------- Busca ------------------------------ */

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(Dim.FieldRadius))
            .background(NoktaSurface)
            .border(1.dp, FieldBorder, RoundedCornerShape(Dim.FieldRadius))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Buscar no consumo", fontSize = 14.sp, color = NoktaMutedSoft, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = NoktaInk),
                cursorBrush = SolidColor(NoktaPurple),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ------------------------------ Tabela ------------------------------ */

@Composable
private fun ConsumptionHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().background(TableHeaderBg).padding(horizontal = Dim.RowPadH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderLabel("ITEM", Modifier.weight(1f), TextAlign.Start)
        HeaderLabel("QTD.", Modifier.width(Dim.QtyColumn), TextAlign.Center)
        HeaderLabel("UNIT.", Modifier.width(Dim.UnitColumn), TextAlign.End)
        HeaderLabel("TOTAL", Modifier.width(Dim.TotalColumn), TextAlign.End)
        Spacer(Modifier.width(Dim.ActionColumn))
    }
}

@Composable
private fun HeaderLabel(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = NoktaMuted,
        textAlign = align,
        maxLines = 1,
    )
}

@Composable
private fun ItemRow(item: TabItem, canCancel: Boolean, onCancel: () -> Unit) {
    val canceled = item.status.isCanceled
    Row(
        modifier = Modifier.fillMaxWidth().background(NoktaSurface).padding(horizontal = Dim.RowPadH, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.productName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (canceled) NoktaMutedSoft else NoktaInk,
                textDecoration = if (canceled) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.variantName.isNotBlank() && item.variantName != item.productName) {
                Text(item.variantName, fontSize = 12.sp, color = NoktaMutedSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Adicionais e observação: é aqui que vive "8 tradicionais e 2
            // melancia". Sem isto o pedido chega incompleto na cozinha.
            item.detailLine?.let {
                Text(it, fontSize = 12.sp, color = NoktaMutedSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            PosBadge(
                item.status.label,
                when {
                    canceled -> PosBadgeTone.DANGER
                    item.status.isDelivered -> PosBadgeTone.SUCCESS
                    else -> PosBadgeTone.NEUTRAL
                },
            )
        }

        Text(
            text = item.quantity.toString(),
            modifier = Modifier.width(Dim.QtyColumn),
            fontSize = 14.sp,
            color = NoktaInk,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        Text(
            text = item.unitPrice.formatBRL(),
            modifier = Modifier.width(Dim.UnitColumn),
            fontSize = 12.5.sp,
            color = NoktaMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
        )

        Text(
            text = item.lineTotal.formatBRL(),
            modifier = Modifier.width(Dim.TotalColumn),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (canceled) NoktaMutedSoft else NoktaInk,
            textDecoration = if (canceled) TextDecoration.LineThrough else null,
            textAlign = TextAlign.End,
            maxLines = 1,
        )

        Box(
            modifier = Modifier.width(Dim.ActionColumn),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (canCancel) {
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        // Remover (rascunho, sem motivo) e Cancelar (já
                        // lançado, motivo obrigatório) são ações diferentes —
                        // o rótulo do dialog já deixa isso claro.
                        contentDescription = if (item.canRemoveAsDraft) "Remover" else "Cancelar",
                        tint = NoktaPurpleBright,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentRow(payment: TabPayment) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(18.dp), tint = MoneyGreen)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(payment.method.label, fontSize = 14.sp, color = NoktaInk)
            payment.change?.takeIf { it.isPositive() }?.let {
                Text("Troco ${it.formatBRL()}", fontSize = 12.sp, color = NoktaMutedSoft)
            }
        }
        Text(payment.amount.formatBRL(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MoneyGreen)
    }
}

/* --------------------------- Ações inferiores ------------------------ */

@Composable
private fun BottomActions(
    tab: Tab,
    canAddItems: Boolean,
    canTakePayments: Boolean,
    isClosing: Boolean,
    onAddProducts: () -> Unit,
    onCheckout: () -> Unit,
    onCloseTab: () -> Unit,
    onRequestClose: () -> Unit,
    onCancelClose: () -> Unit,
) {
    // Mesa (TabType.TABLE) só cobra depois de "fechar a conta" — o consumo
    // acontece o atendimento inteiro e o pagamento é sempre o passo final,
    // então mostrar "Pagar R$ X" junto de "Fechar a conta" enquanto a mesa
    // ainda está em consumo é confuso (sugere que dá para pagar a qualquer
    // momento). Balcão/individual (venda direta, sem esse conceito de
    // "atendimento em andamento") continuam cobrando direto, sem essa etapa.
    val hidePayWhileOpen = tab.type == TabType.TABLE && tab.isEditable && !tab.isFullyPaid
    val showPayButton = canTakePayments && !hidePayWhileOpen

    Column(Modifier.fillMaxWidth().background(NoktaSurface).padding(horizontal = Dim.ScreenPad)) {
        if (tab.status == TabStatus.CLOSING) {
            SecondaryActionRow(text = "CANCELAR FECHAMENTO", onClick = onCancelClose)
            Spacer(Modifier.height(10.dp))
        }

        // "Fechar a conta" trava o consumo (OPEN -> CLOSING) antes de cobrar.
        // Para mesa é a única forma de chegar ao pagamento (ver
        // requiresExplicitClose acima); para balcão/individual continua
        // sendo opcional, já que o botão de pagar já aparece direto.
        if (canTakePayments && tab.isEditable && !tab.isFullyPaid) {
            SecondaryActionRow(text = "FECHAR A CONTA", onClick = onRequestClose)
            Spacer(Modifier.height(10.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            if (canAddItems && tab.isEditable) {
                Row(
                    modifier = Modifier
                        .weight(0.44f)
                        .height(Dim.BottomBarHeight)
                        .clip(RoundedCornerShape(Dim.ButtonRadius))
                        .background(NoktaSurface)
                        .border(1.dp, NoktaPurpleBright, RoundedCornerShape(Dim.ButtonRadius))
                        .clickable(onClick = onAddProducts)
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = NoktaPurpleBright, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "ADICIONAR ITENS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                        color = NoktaPurpleBright,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }

            // Enquanto a mesa está em consumo e só "Fechar a conta" está
            // visível acima, a caixa principal fica de fora — ter as duas
            // juntas ("Fechar a conta" + "Pagar") é exatamente a confusão
            // que motivou essa mudança. Nos demais casos (balcão, mesa já
            // fechando/quitada, ou sem permissão de cobrar) a caixa
            // principal sempre aparece, como antes.
            if (!hidePayWhileOpen) {
                val primaryWeight = if (canAddItems && tab.isEditable) 0.56f else 1f
                when {
                    tab.isFullyPaid && tab.isOccupying -> PrimaryActionBox(
                        modifier = Modifier.weight(primaryWeight),
                        text = "ENCERRAR COMANDA",
                        enabled = !isClosing,
                        onClick = onCloseTab,
                    )
                    showPayButton -> PrimaryActionBox(
                        modifier = Modifier.weight(primaryWeight),
                        text = "PAGAR • ${tab.remaining.formatBRL()}",
                        onClick = onCheckout,
                    )
                    else -> Box(Modifier.weight(primaryWeight).height(Dim.BottomBarHeight), contentAlignment = Alignment.Center) {
                        Text(
                            "Chame o caixa para fechar",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = NoktaMutedSoft,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryActionRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dim.BottomBarHeight)
            .clip(RoundedCornerShape(Dim.ButtonRadius))
            .background(NoktaSurface)
            .border(1.dp, FieldBorder, RoundedCornerShape(Dim.ButtonRadius))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, color = NoktaInk)
    }
}

@Composable
private fun PrimaryActionBox(modifier: Modifier = Modifier, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(Dim.BottomBarHeight)
            .clip(RoundedCornerShape(Dim.ButtonRadius))
            .background(if (enabled) NoktaPurpleBright else NoktaMutedSoft)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * Cancelar item exige motivo — o backend torna obrigatório e a auditoria
 * depende dele para explicar por que a conta mudou.
 */
@Composable
private fun CancelItemDialog(
    item: TabItem,
    isProcessing: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val suggestions = listOf("Pedido errado", "Cliente desistiu", "Item indisponível")

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text("Cancelar item") },
        text = {
            Column {
                Text("${item.quantity}x ${item.productName}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "O item continua registrado no histórico com o motivo — nada é apagado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                suggestions.forEach { suggestion ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == suggestion, onClick = { reason = suggestion })
                        Text(suggestion, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(
                    value = if (reason in suggestions) "" else reason,
                    onValueChange = { reason = it },
                    label = { Text("Outro motivo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason) },
                enabled = reason.isNotBlank() && !isProcessing,
            ) { Text(if (isProcessing) "Cancelando…" else "Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isProcessing) { Text("Voltar") } },
    )
}
