package com.nokta.pos.ui.venda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pix
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen

/**
 * Pagamento da venda de balcão. Uma tela só: escolher forma, confirmar,
 * pronto. Não há passo intermediário de "comanda" nem de "checkout" — no
 * balcão o cliente está esperando de pé (item 6 e 10 do brief).
 */
@Composable
fun BalcaoPagamentoScreen(
    state: BalcaoUiState,
    onSelectMethod: (PosPaymentOption) -> Unit,
    onSetInstallments: (Int) -> Unit,
    onSetReceived: (Long?) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
) {
    Scaffold(
        topBar = {
            PosTopBar(
                title = "Pagamento",
                subtitle = "Total ${state.total.formatBRL()}",
                onBack = if (state.isProcessing) null else onBack,
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.padding(20.dp)) {
                    state.changeDue?.let { change ->
                        MoneyRow("Troco", change, emphasized = true, positive = true)
                        Spacer(Modifier.height(12.dp))
                    }
                    PosPrimaryButton(
                        text = when {
                            state.isProcessing -> state.statusMessage ?: "Processando…"
                            // Cartão já aprovado e registro pendente: o texto
                            // deixa claro que isto NÃO cobra de novo.
                            state.awaitingRegistrationRetry -> "Tentar salvar de novo"
                            state.selectedMethod == PosPaymentOption.CASH -> "Confirmar recebimento"
                            else -> "Cobrar ${state.total.formatBRL()}"
                        },
                        loading = state.isProcessing,
                        enabled = state.canConfirmCash,
                        onClick = onConfirm,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            state.errorMessage?.let { message ->
                PosInlineWarning(message, tone = PosBadgeTone.DANGER)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismissError) { Text("Entendi") }
                Spacer(Modifier.height(12.dp))
            }

            Text("Forma de pagamento", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            val methods = listOf(
                Triple(PosPaymentOption.CREDIT_CARD, "Crédito", Icons.Filled.CreditCard),
                Triple(PosPaymentOption.DEBIT_CARD, "Débito", Icons.Filled.CreditCard),
                Triple(PosPaymentOption.PIX, "Pix", Icons.Filled.Pix),
                Triple(PosPaymentOption.CASH, "Dinheiro", Icons.Filled.Payments),
            )

            methods.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (option, label, icon) ->
                        MethodTile(
                            label = label,
                            icon = icon,
                            selected = state.selectedMethod == option,
                            enabled = !state.isProcessing,
                            onClick = { onSelectMethod(option) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (state.selectedMethod == PosPaymentOption.CREDIT_CARD) {
                Spacer(Modifier.height(8.dp))
                Text("Parcelas", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4).forEach { n ->
                        FilterChip(
                            selected = state.installments == n,
                            onClick = { onSetInstallments(n) },
                            label = { Text(if (n == 1) "À vista" else "${n}x") },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        )
                    }
                }
            }

            if (state.selectedMethod == PosPaymentOption.CASH) {
                Spacer(Modifier.height(16.dp))
                CashReceivedSection(
                    total = state.total,
                    receivedCents = state.receivedCents,
                    onSetReceived = onSetReceived,
                )
            }
        }
    }
}

@Composable
private fun MethodTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .heightIn(min = 92.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(if (selected) 2.dp else 1.5.dp, border, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Valor recebido em dinheiro. Atalhos com as notas mais comuns evitam digitar
 * — o troco aparece sozinho, o operador nunca faz a conta (item 12).
 */
@Composable
private fun CashReceivedSection(
    total: Money,
    receivedCents: Long?,
    onSetReceived: (Long?) -> Unit,
) {
    Text("Valor recebido", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    val suggestions = remember(total) { cashSuggestions(total) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = receivedCents == null,
            onClick = { onSetReceived(null) },
            label = { Text("Exato") },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        )
        suggestions.forEach { cents ->
            FilterChip(
                selected = receivedCents == cents,
                onClick = { onSetReceived(cents) },
                label = { Text(Money(cents).formatBRL().removePrefix("R$ ")) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
        }
    }
}

/**
 * Sugere as próximas notas redondas acima do total (R$ 50, R$ 100...). É como
 * o cliente entrega o dinheiro na prática.
 */
internal fun cashSuggestions(total: Money): List<Long> {
    val notes = listOf(2_000L, 5_000L, 10_000L, 20_000L)
    return notes.filter { it > total.cents }.take(3)
}

/** Confirmação final — grande, verde, sem ambiguidade: pode liberar o cliente. */
@Composable
fun BalcaoConcluidoScreen(total: Money, change: Money?, onNewSale: () -> Unit, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(88.dp),
            tint = MoneyGreen,
        )
        Spacer(Modifier.height(20.dp))
        Text("Venda concluída", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            total.formatBRL(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        if (change != null && change.isPositive()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Troco", style = MaterialTheme.typography.bodyMedium)
                    Text(change.formatBRL(), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        PosPrimaryButton(text = "Nova venda", onClick = onNewSale)
        Spacer(Modifier.height(12.dp))
        PosSecondaryButton(text = "Voltar ao início", onClick = onHome)
    }
}
