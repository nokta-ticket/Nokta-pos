package com.nokta.pos.ui.comanda

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.OrderItemStatus
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabItem
import com.nokta.pos.comanda.domain.TabPayment
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen

/**
 * Comanda/mesa aberta: quem é, o que consumiu, quanto pagou, quanto falta.
 *
 * O saldo restante é o número maior da tela porque é ele que decide a próxima
 * ação do garçom. Itens pendentes de preparo aparecem com o status ao lado —
 * informativo, nunca impedindo cobrar (item 14).
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

    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    Scaffold(
        topBar = {
            PosTopBar(
                title = state.tab?.displayName ?: "Comanda",
                subtitle = state.tab?.customerName,
                onBack = onBack,
            )
        },
        bottomBar = {
            state.tab?.let { tab ->
                ComandaActionBar(
                    tab = tab,
                    canAddItems = state.access.canCreateOrders,
                    canTakePayments = state.access.canTakePayments,
                    isClosing = state.isClosing,
                    onAddProducts = onAddProducts,
                    onCheckout = onCheckout,
                    onCloseTab = viewModel::closeTab,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && state.tab == null -> PosLoading(label = "Carregando comanda…")
                state.error != null && state.tab == null -> PosEmptyState(
                    title = "Não foi possível abrir",
                    description = state.error!!,
                    actionText = "Tentar de novo",
                    onAction = viewModel::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.tab != null -> ComandaContent(
                    tab = state.tab!!,
                    canCancelItems = state.access.canManageTabs || state.access.canCreateOrders,
                    onCancelItem = viewModel::askCancelItem,
                )
            }

            state.actionMessage?.let {
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = { TextButton(onClick = viewModel::clearActionMessage) { Text("Ok") } },
                ) { Text(it) }
            }
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
private fun ComandaContent(tab: Tab, canCancelItems: Boolean, onCancelItem: (TabItem) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        BalanceCard(tab)

        if (tab.pendingItemCount > 0) {
            Spacer(Modifier.height(12.dp))
            PosInlineWarning(
                "${tab.pendingItemCount} ${if (tab.pendingItemCount == 1) "item ainda não entregue" else "itens ainda não entregues"} — " +
                    "isso não impede o pagamento.",
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("Consumo", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        if (tab.items.isEmpty()) {
            Text(
                "Nenhum item lançado ainda.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            tab.items.forEach { item ->
                ItemRow(item = item, canCancel = canCancelItems && !item.status.isCanceled, onCancel = { onCancelItem(item) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            }
        }

        val activePayments = tab.payments.filterNot { it.isCanceled }
        if (activePayments.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Pagamentos", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            activePayments.forEach { PaymentRow(it) }
        }

        Spacer(Modifier.height(120.dp))
    }
}

/** Cartão de saldo: total, pago e o que falta — o resumo que o garçom lê primeiro. */
@Composable
private fun BalanceCard(tab: Tab) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            MoneyRow("Total", tab.total)
            if (tab.discount.isPositive()) {
                Spacer(Modifier.height(6.dp))
                MoneyRow("Desconto", tab.discount)
            }
            if (tab.serviceCharge.isPositive()) {
                Spacer(Modifier.height(6.dp))
                MoneyRow("Serviço", tab.serviceCharge)
            }
            if (tab.paid.isPositive()) {
                Spacer(Modifier.height(6.dp))
                MoneyRow("Pago", tab.paid, positive = true)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            MoneyRow(
                if (tab.isFullyPaid) "Quitada" else "Falta pagar",
                tab.remaining,
                emphasized = true,
                positive = tab.isFullyPaid,
            )
        }
    }
}

@Composable
private fun ItemRow(item: TabItem, canCancel: Boolean, onCancel: () -> Unit) {
    val canceled = item.status.isCanceled
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "${item.quantity}x",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(38.dp),
            color = if (canceled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.productName,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (canceled) TextDecoration.LineThrough else null,
                color = if (canceled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (item.variantName.isNotBlank() && item.variantName != item.productName) {
                Text(
                    item.variantName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Adicionais e observação: é aqui que vive "8 tradicionais e 2
            // melancia". Sem isto o pedido chega incompleto na cozinha.
            item.detailLine?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PosBadge(
                    item.status.label,
                    when {
                        canceled -> PosBadgeTone.DANGER
                        item.status.isDelivered -> PosBadgeTone.SUCCESS
                        else -> PosBadgeTone.NEUTRAL
                    },
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                item.lineTotal.formatBRL(),
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (canceled) TextDecoration.LineThrough else null,
                color = if (canceled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (canCancel) {
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancelar")
                }
            }
        }
    }
}

@Composable
private fun PaymentRow(payment: TabPayment) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Payments,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MoneyGreen,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(payment.method.label, style = MaterialTheme.typography.bodyLarge)
            payment.change?.takeIf { it.isPositive() }?.let {
                Text(
                    "Troco ${it.formatBRL()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(payment.amount.formatBRL(), style = MaterialTheme.typography.titleMedium, color = MoneyGreen)
    }
}

@Composable
private fun ComandaActionBar(
    tab: Tab,
    canAddItems: Boolean,
    canTakePayments: Boolean,
    isClosing: Boolean,
    onAddProducts: () -> Unit,
    onCheckout: () -> Unit,
    onCloseTab: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp)) {
            when {
                // Quitada: só falta encerrar a comanda e liberar a mesa.
                tab.isFullyPaid && tab.isOpen -> PosPrimaryButton(
                    text = "Encerrar comanda",
                    onClick = onCloseTab,
                    loading = isClosing,
                )
                canTakePayments -> PosPrimaryButton(
                    text = "Pagar ${tab.remaining.formatBRL()}",
                    onClick = onCheckout,
                    icon = Icons.Filled.Payments,
                )
                else -> PosInlineWarning("Seu perfil não registra pagamentos. Chame o caixa para fechar.")
            }
            if (canAddItems && tab.isOpen) {
                Spacer(Modifier.height(10.dp))
                PosSecondaryButton(text = "Adicionar itens", onClick = onAddProducts, icon = Icons.Filled.Add)
            }
        }
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
