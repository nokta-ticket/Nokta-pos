package com.nokta.pos.ui.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pix
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabItem
import com.nokta.pos.common.Money
import com.nokta.pos.payment.domain.PartialValidation
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.NoktaBorder
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaInkSoft
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface

/* =========================================================================
 *  MEDIDAS — mesmo padrão compacto do cardápio
 * ========================================================================= */
private object Dim {
    val ScreenPad = 16.dp
    val CardRadius = 14.dp
    val SectionGap = 18.dp
}

private val PageGray = Color(0xFFF7F6FA)
private val SelectedTint = Color(0xFFF7F1FE)
private val FieldBorder = Color(0xFFE7E4EF)

/**
 * Pagamento de comanda/mesa: total, parcial ou dividido.
 *
 * Layout portado de um mockup de referência — a lógica de dados/regras
 * continua 100% em [CheckoutViewModel] (valor sempre vem de `tab.remaining`
 * do servidor, cartão aprovado + falha de registro nunca gera nova cobrança,
 * etc.). "Editar pedido" não reimplementa edição de item: só volta para a
 * tela de Comanda (`onBack`), que já tem "Adicionar itens" para o cardápio
 * e o cancelamento de item existente — evita duplicar essas ações aqui.
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

    Box(Modifier.fillMaxSize().background(NoktaSurface)) {
        when {
            state.isLoading && state.tab == null -> PosLoading(label = "Carregando…")
            state.error != null && state.tab == null -> Column(Modifier.fillMaxSize()) {
                TopBar(onBack = onBack)
                PosEmptyState(
                    title = "Não foi possível carregar",
                    description = state.error!!,
                    actionText = "Tentar de novo",
                    onAction = viewModel::refresh,
                    modifier = Modifier.weight(1f),
                )
            }
            state.tab != null -> CheckoutContent(state = state, viewModel = viewModel, onBack = onBack)
        }

        state.paymentMessage?.let {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = viewModel::clearMessage) { Text("Ok") } },
            ) { Text(it) }
        }
    }
}

@Composable
private fun CheckoutContent(state: CheckoutUiState, viewModel: CheckoutViewModel, onBack: () -> Unit) {
    val tab = state.tab ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        TopBar(onBack = if (state.isProcessingPayment) null else onBack)

        // ---------- Cabeçalho + resumo ----------
        Column(
            Modifier
                .fillMaxWidth()
                .background(PageGray)
                .padding(horizontal = Dim.ScreenPad)
                .padding(top = 8.dp, bottom = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Pagamento", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = NoktaInk)
                    Spacer(Modifier.height(4.dp))
                    Text("${tab.displayName}  ·  Falta ${tab.remaining.formatBRL()}", fontSize = 13.sp, color = NoktaMuted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total", fontSize = 12.5.sp, color = NoktaMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(tab.total.formatBRL(), fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = NoktaInk)
                }
            }

            Spacer(Modifier.height(16.dp))

            OrderSummaryCard(tab = tab, onEditOrder = onBack)
        }

        Column(Modifier.padding(horizontal = Dim.ScreenPad)) {

            Spacer(Modifier.height(Dim.SectionGap))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Quanto cobrar agora", Modifier.weight(1f))
                SplitButton(onClick = { viewModel.setAmountMode(AmountMode.SPLIT) })
            }
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountModeChip("Tudo", state.amountMode == AmountMode.FULL, Modifier.weight(1f)) { viewModel.setAmountMode(AmountMode.FULL) }
                AmountModeChip("Dividir", state.amountMode == AmountMode.SPLIT, Modifier.weight(1f)) { viewModel.setAmountMode(AmountMode.SPLIT) }
                AmountModeChip("Outro valor", state.amountMode == AmountMode.CUSTOM, Modifier.weight(1f)) { viewModel.setAmountMode(AmountMode.CUSTOM) }
            }

            Spacer(Modifier.height(12.dp))
            AmountField(amount = state.amountToCharge)

            when (state.amountMode) {
                AmountMode.SPLIT -> SplitSection(state = state, onSetPeople = viewModel::setSplitPeople)
                AmountMode.CUSTOM -> CustomAmountSection(cents = state.customAmountCents, remaining = tab.remaining, onChange = viewModel::setCustomAmount)
                AmountMode.FULL -> Unit
            }

            (state.validation as? PartialValidation.Invalid)?.let {
                Spacer(Modifier.height(12.dp))
                PosInlineWarning(it.reason, tone = PosBadgeTone.DANGER)
            }

            Spacer(Modifier.height(Dim.SectionGap))
            SectionLabel("Forma de pagamento")
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentUiMethod.entries.forEach { method ->
                    MethodCard(
                        method = method,
                        selected = method == state.selectedMethod,
                        enabled = !state.isProcessingPayment,
                        onClick = { viewModel.selectMethod(method) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.selectedMethod == PaymentUiMethod.CREDIT_CARD) {
                Spacer(Modifier.height(Dim.SectionGap))
                SectionLabel("Parcelas")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4).forEach { n ->
                        AmountChip(
                            label = if (n == 1) "À vista" else "${n}x",
                            selected = state.installments == n,
                            onClick = { viewModel.setInstallments(n) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (state.selectedMethod == PaymentUiMethod.CASH) {
                Spacer(Modifier.height(Dim.SectionGap))
                SectionLabel("Valor recebido")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AmountChip(label = "Exato", selected = state.receivedCents == null, onClick = { viewModel.setReceived(null) }, modifier = Modifier.weight(1f))
                    cashSuggestionsFor(state.amountToCharge).forEach { cents ->
                        AmountChip(
                            label = Money(cents).formatBRL().removePrefix("R$ "),
                            selected = state.receivedCents == cents,
                            onClick = { viewModel.setReceived(cents) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                state.change?.let { change ->
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Troco", fontSize = 13.sp, color = NoktaMuted)
                        Text(change.formatBRL(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NoktaPurple)
                    }
                }
            }

            Spacer(Modifier.height(Dim.SectionGap))

            SplitInfoCard(onClick = { viewModel.setAmountMode(AmountMode.SPLIT) })

            Spacer(Modifier.height(18.dp))

            if (state.pendingRegistration != null) {
                ConfirmButton(text = "Tentar salvar de novo", loading = state.isProcessingPayment, onClick = viewModel::retryPendingRegistration)
            } else {
                ConfirmButton(
                    text = if (state.settlesTab) "Cobrar ${state.amountToCharge.formatBRL()} e quitar" else "Cobrar ${state.amountToCharge.formatBRL()}",
                    enabled = state.canCharge,
                    loading = state.isProcessingPayment,
                    onClick = viewModel::charge,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ------------------------------ Top bar ----------------------------- */

@Composable
private fun TopBar(onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().background(NoktaSurface).padding(start = 10.dp, end = Dim.ScreenPad, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).let { if (onBack != null) it.clickable(onClick = onBack) else it },
            contentAlignment = Alignment.Center,
        ) {
            if (onBack != null) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = NoktaInk, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("NOKTA", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = NoktaInk)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = NoktaInk)
}

/* --------------------------- Resumo pedido -------------------------- */

@Composable
private fun OrderSummaryCard(tab: Tab, onEditOrder: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.CardRadius)).background(NoktaSurface).border(1.dp, NoktaBorder, RoundedCornerShape(Dim.CardRadius)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Resumo do pedido", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEditOrder).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Editar pedido", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NoktaPurple)
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            if (tab.activeItems.isEmpty()) {
                Text("Nenhum item lançado ainda.", fontSize = 13.sp, color = NoktaMutedSoft, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                tab.activeItems.forEach { item ->
                    OrderItemRow(item)
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(NoktaBorder))
            Spacer(Modifier.height(12.dp))

            TotalsRow("Subtotal", tab.subtotal.formatBRL())
            if (tab.discount.isPositive()) {
                Spacer(Modifier.height(8.dp))
                TotalsRow("Desconto", "− ${tab.discount.formatBRL()}")
            }
            if (tab.serviceCharge.isPositive()) {
                Spacer(Modifier.height(8.dp))
                TotalsRow(
                    label = "Taxa de serviço" + (tab.serviceChargeRateLabel?.let { " ($it)" } ?: ""),
                    value = tab.serviceCharge.formatBRL(),
                )
            }

            Spacer(Modifier.height(12.dp))
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(NoktaBorder))

        Row(
            modifier = Modifier.fillMaxWidth().background(PageGray).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Total", modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
            Text(tab.total.formatBRL(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NoktaPurple)
        }
    }
}

@Composable
private fun OrderItemRow(item: TabItem) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(PageGray),
            contentAlignment = Alignment.Center,
        ) {
            Text("${item.quantity}x", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaInkSoft)
        }

        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(item.productName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk)
            item.detailLine?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 11.5.sp, color = NoktaMutedSoft, maxLines = 1)
            }
        }

        Spacer(Modifier.width(8.dp))
        Text(item.lineTotal.formatBRL(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
    }
}

@Composable
private fun TotalsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 12.5.sp, color = NoktaMuted)
        Spacer(Modifier.weight(1f))
        Text(text = value, fontSize = 12.5.sp, color = NoktaInkSoft)
    }
}

/* ---------------------------- Valor a cobrar ------------------------- */

@Composable
private fun AmountModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) NoktaPurpleBright else NoktaSurface)
            .border(1.dp, if (selected) NoktaPurpleBright else FieldBorder, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) Color.White else NoktaInkSoft, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun AmountChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) NoktaPurpleBright else NoktaSurface)
            .border(1.dp, if (selected) NoktaPurpleBright else FieldBorder, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) Color.White else NoktaInkSoft, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun AmountField(amount: Money) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(12.dp)).background(NoktaSurface).border(1.dp, FieldBorder, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(amount.formatBRL(), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = NoktaInk)
    }
}

/** Divisão igual. Mostra as partes exatas para o garçom saber quanto pedir a cada pessoa sem calcular nada. */
@Composable
private fun SplitSection(state: CheckoutUiState, onSetPeople: (Int) -> Unit) {
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onSetPeople(state.splitPeople - 1) }, enabled = state.splitPeople > 2, modifier = Modifier.size(48.dp)) {
            Text("−", style = MaterialTheme.typography.headlineSmall)
        }
        Text("${state.splitPeople} pessoas", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onSetPeople(state.splitPeople + 1) }, modifier = Modifier.size(48.dp)) {
            Text("+", style = MaterialTheme.typography.headlineSmall)
        }
    }

    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PageGray).padding(14.dp),
    ) {
        state.splitParts.forEachIndexed { index, part ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pessoa ${index + 1}", fontSize = 13.sp, color = NoktaMuted)
                Text(part.formatBRL(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (index == 0) NoktaPurple else NoktaInk)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Cobrando a parte da pessoa 1 agora. As demais ficam no saldo.", fontSize = 11.sp, color = NoktaMutedSoft)
    }
}

/** Valor livre — para "vou pagar 50 agora e o resto depois". */
@Composable
private fun CustomAmountSection(cents: Long, remaining: Money, onChange: (Long) -> Unit) {
    Spacer(Modifier.height(14.dp))
    PosNumpad(onDigit = { digit -> onChange(cents * 10 + (digit - '0')) }, onBackspace = { onChange(cents / 10) })
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { onChange(remaining.cents) }) { Text("Usar o saldo total (${remaining.formatBRL()})") }
}

/* ------------------------ Forma de pagamento ------------------------ */

@Composable
private fun MethodCard(method: PaymentUiMethod, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) SelectedTint else NoktaSurface)
                .border(width = if (selected) 1.5.dp else 1.dp, color = if (selected) NoktaPurpleBright else FieldBorder, shape = RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(method.icon(), contentDescription = null, tint = if (selected) NoktaPurple else NoktaInkSoft, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(method.label(), fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) NoktaInk else NoktaInkSoft, maxLines = 1)
        }

        if (selected) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(NoktaPurple),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = "Selecionado", tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private fun PaymentUiMethod.icon(): ImageVector = when (this) {
    PaymentUiMethod.CREDIT_CARD -> Icons.Filled.CreditCard
    PaymentUiMethod.DEBIT_CARD -> Icons.Filled.CreditCard
    PaymentUiMethod.PIX -> Icons.Filled.Pix
    PaymentUiMethod.CASH -> Icons.Filled.Payments
}

private fun PaymentUiMethod.label(): String = when (this) {
    PaymentUiMethod.CREDIT_CARD -> "Crédito"
    PaymentUiMethod.DEBIT_CARD -> "Débito"
    PaymentUiMethod.PIX -> "Pix"
    PaymentUiMethod.CASH -> "Dinheiro"
}

/* ------------------------------ Rodapé ------------------------------ */

@Composable
private fun SplitButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(NoktaSurface).border(1.dp, FieldBorder, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Groups, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text("Dividir por pessoas", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaPurple)
    }
}

@Composable
private fun SplitInfoCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(PageGray).border(1.dp, FieldBorder, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Groups, contentDescription = null, tint = NoktaInkSoft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Dividir por pessoas", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk)
            Spacer(Modifier.height(2.dp))
            Text("Facilite o pagamento dividindo o total entre as pessoas.", fontSize = 11.5.sp, color = NoktaMutedSoft, maxLines = 1)
        }
    }
}

@Composable
private fun ConfirmButton(text: String, enabled: Boolean = true, loading: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) NoktaPurpleBright else FieldBorder)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = if (enabled) Color.White else NoktaMutedSoft, textAlign = TextAlign.Center)
        }
    }
}

/** Notas redondas acima do valor — como o cliente entrega o dinheiro. */
private fun cashSuggestionsFor(amount: Money): List<Long> =
    listOf(2_000L, 5_000L, 10_000L, 20_000L).filter { it > amount.cents }.take(3)

// Aliases locais para não colidir com androidx.compose.foundation.shape.CircleShape/RoundedCornerShape já usados por outra tela deste módulo com import estrela.
private val CircleShape = androidx.compose.foundation.shape.CircleShape
private fun RoundedCornerShape(radius: androidx.compose.ui.unit.Dp) = RoundedCornerShape(radius)
