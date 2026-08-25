package com.nokta.pos.ui.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pix
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.PartialValidation
import com.nokta.pos.ui.components.*

/**
 * Pagamento de comanda/mesa: total, parcial ou dividido.
 *
 * O operador escolhe QUANTO cobrar antes de escolher COMO — é a ordem real
 * da conversa na mesa ("vamos dividir em 3" vem antes de "no crédito").
 */
@Composable
fun CheckoutScreen(
    tabId: String,
    onTabClosed: () -> Unit,
    onBack: () -> Unit,
    viewModel: CheckoutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.tabClosed) { if (state.tabClosed) onTabClosed() }

    Scaffold(
        topBar = {
            PosTopBar(
                title = "Pagamento",
                subtitle = state.tab?.let { "Falta ${it.remaining.formatBRL()}" },
                onBack = if (state.isProcessingPayment) null else onBack,
            )
        },
        bottomBar = {
            state.tab?.let {
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.padding(20.dp)) {
                        state.change?.let { change ->
                            MoneyRow("Troco", change, emphasized = true, positive = true)
                            Spacer(Modifier.height(12.dp))
                        }
                        if (state.pendingRegistration != null) {
                            PosPrimaryButton(
                                text = "Tentar salvar de novo",
                                onClick = viewModel::retryPendingRegistration,
                                loading = state.isProcessingPayment,
                            )
                        } else {
                            PosPrimaryButton(
                                text = if (state.settlesTab) {
                                    "Cobrar ${state.amountToCharge.formatBRL()} e quitar"
                                } else {
                                    "Cobrar ${state.amountToCharge.formatBRL()}"
                                },
                                onClick = viewModel::charge,
                                enabled = state.canCharge,
                                loading = state.isProcessingPayment,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && state.tab == null -> PosLoading(label = "Carregando…")
                state.error != null && state.tab == null -> PosEmptyState(
                    title = "Não foi possível carregar",
                    description = state.error!!,
                    actionText = "Tentar de novo",
                    onAction = viewModel::refresh,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.tab != null -> CheckoutContent(state = state, viewModel = viewModel)
            }

            state.paymentMessage?.let {
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = { TextButton(onClick = viewModel::clearMessage) { Text("Ok") } },
                ) { Text(it) }
            }
        }
    }
}

@Composable
private fun CheckoutContent(state: CheckoutUiState, viewModel: CheckoutViewModel) {
    val tab = state.tab ?: return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(18.dp)) {
                MoneyRow("Total da conta", tab.total)
                if (tab.paid.isPositive()) {
                    Spacer(Modifier.height(6.dp))
                    MoneyRow("Já pago", tab.paid, positive = true)
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                MoneyRow("Falta pagar", tab.remaining, emphasized = true)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Quanto cobrar agora", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountModeChip("Tudo", state.amountMode == AmountMode.FULL, Modifier.weight(1f)) {
                viewModel.setAmountMode(AmountMode.FULL)
            }
            AmountModeChip("Dividir", state.amountMode == AmountMode.SPLIT, Modifier.weight(1f)) {
                viewModel.setAmountMode(AmountMode.SPLIT)
            }
            AmountModeChip("Outro valor", state.amountMode == AmountMode.CUSTOM, Modifier.weight(1f)) {
                viewModel.setAmountMode(AmountMode.CUSTOM)
            }
        }

        when (state.amountMode) {
            AmountMode.SPLIT -> SplitSection(state = state, onSetPeople = viewModel::setSplitPeople)
            AmountMode.CUSTOM -> CustomAmountSection(
                cents = state.customAmountCents,
                remaining = tab.remaining,
                onChange = viewModel::setCustomAmount,
            )
            AmountMode.FULL -> Unit
        }

        (state.validation as? PartialValidation.Invalid)?.let {
            Spacer(Modifier.height(12.dp))
            PosInlineWarning(it.reason, tone = PosBadgeTone.DANGER)
        }

        Spacer(Modifier.height(24.dp))
        Text("Forma de pagamento", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        val methods = listOf(
            Triple(PaymentUiMethod.CREDIT_CARD, "Crédito", Icons.Filled.CreditCard),
            Triple(PaymentUiMethod.DEBIT_CARD, "Débito", Icons.Filled.CreditCard),
            Triple(PaymentUiMethod.PIX, "Pix", Icons.Filled.Pix),
            Triple(PaymentUiMethod.CASH, "Dinheiro", Icons.Filled.Payments),
        )
        methods.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (option, label, icon) ->
                    MethodTile(
                        label = label,
                        icon = icon,
                        selected = state.selectedMethod == option,
                        enabled = !state.isProcessingPayment,
                        onClick = { viewModel.selectMethod(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.selectedMethod == PaymentUiMethod.CREDIT_CARD) {
            Spacer(Modifier.height(4.dp))
            Text("Parcelas", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 4).forEach { n ->
                    FilterChip(
                        selected = state.installments == n,
                        onClick = { viewModel.setInstallments(n) },
                        label = { Text(if (n == 1) "À vista" else "${n}x") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
            }
        }

        if (state.selectedMethod == PaymentUiMethod.CASH) {
            Spacer(Modifier.height(16.dp))
            Text("Valor recebido", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.receivedCents == null,
                    onClick = { viewModel.setReceived(null) },
                    label = { Text("Exato") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                cashSuggestionsFor(state.amountToCharge).forEach { cents ->
                    FilterChip(
                        selected = state.receivedCents == cents,
                        onClick = { viewModel.setReceived(cents) },
                        label = { Text(Money(cents).formatBRL().removePrefix("R$ ")) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun AmountModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.heightIn(min = 50.dp),
    )
}

/**
 * Divisão igual. Mostra as partes exatas (com o centavo extra nas primeiras)
 * para o garçom saber quanto pedir a cada pessoa sem calcular nada.
 */
@Composable
private fun SplitSection(state: CheckoutUiState, onSetPeople: (Int) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text("Entre quantas pessoas?", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onSetPeople(state.splitPeople - 1) },
            enabled = state.splitPeople > 2,
            modifier = Modifier.size(56.dp),
        ) { Text("−", style = MaterialTheme.typography.headlineSmall) }

        Text(
            "${state.splitPeople}",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.widthIn(min = 80.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        FilledTonalIconButton(
            onClick = { onSetPeople(state.splitPeople + 1) },
            modifier = Modifier.size(56.dp),
        ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
    }

    Spacer(Modifier.height(14.dp))
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            state.splitParts.forEachIndexed { index, part ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pessoa ${index + 1}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        part.formatBRL(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Cobrando a parte da pessoa 1 agora. As demais ficam no saldo.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Valor livre — para "vou pagar 50 agora e o resto depois". */
@Composable
private fun CustomAmountSection(cents: Long, remaining: Money, onChange: (Long) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(Money(cents).formatBRL(), style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(12.dp))
    PosNumpad(
        onDigit = { digit -> onChange(cents * 10 + (digit - '0')) },
        onBackspace = { onChange(cents / 10) },
    )
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = { onChange(remaining.cents) }) { Text("Usar o saldo total (${remaining.formatBRL()})") }
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
            .heightIn(min = 88.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.5.dp, border, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Notas redondas acima do valor — como o cliente entrega o dinheiro. */
private fun cashSuggestionsFor(amount: Money): List<Long> =
    listOf(2_000L, 5_000L, 10_000L, 20_000L).filter { it > amount.cents }.take(3)
