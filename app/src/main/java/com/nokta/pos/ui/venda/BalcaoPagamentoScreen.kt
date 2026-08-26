package com.nokta.pos.ui.venda

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pix
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
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
import com.nokta.pos.cart.CartLine
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen
import com.nokta.pos.ui.theme.NoktaBorder
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaInkSoft
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface

private object Dim {
    val ScreenPad = 16.dp
    val CardRadius = 14.dp
    val SectionGap = 18.dp
}

private val PageGray = Color(0xFFF7F6FA)
private val SelectedTint = Color(0xFFF7F1FE)
private val FieldBorder = Color(0xFFE7E4EF)

/**
 * Pagamento da venda de balcão. Uma tela só: escolher forma, confirmar,
 * pronto. Não há passo intermediário de "comanda" nem de "checkout" — no
 * balcão o cliente está esperando de pé (item 6 e 10 do brief).
 *
 * Mesmo visual do checkout de comanda/mesa ([com.nokta.pos.ui.checkout.CheckoutScreen])
 * para consistência entre os dois fluxos de pagamento do app — "Editar
 * pedido" aqui só volta ao carrinho (`onBack` já chama `BalcaoViewModel.backToCart`,
 * que devolve ao cardápio com o carrinho intacto, editável via +/-/remover).
 */
@Composable
fun BalcaoPagamentoScreen(
    state: BalcaoUiState,
    onSelectMethod: (PosPaymentOption) -> Unit,
    onSetInstallments: (Int) -> Unit,
    onSetReceived: (Long?) -> Unit,
    onSetSplitPeople: (Int?) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(NoktaSurface).verticalScroll(rememberScrollState())) {

        TopBar(onBack = if (state.isProcessing) null else onBack)

        Column(
            Modifier.fillMaxWidth().background(PageGray).padding(horizontal = Dim.ScreenPad).padding(top = 8.dp, bottom = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Pagamento", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = NoktaInk)
                    Spacer(Modifier.height(4.dp))
                    Text("Balcão", fontSize = 13.sp, color = NoktaMuted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total", fontSize = 12.5.sp, color = NoktaMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(state.total.formatBRL(), fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = NoktaInk)
                }
            }

            Spacer(Modifier.height(16.dp))

            OrderSummaryCard(lines = state.cart.lines, total = state.total, onEditOrder = onBack)
        }

        Column(Modifier.padding(horizontal = Dim.ScreenPad)) {

            state.errorMessage?.let { message ->
                Spacer(Modifier.height(Dim.SectionGap))
                PosInlineWarning(message, tone = PosBadgeTone.DANGER)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismissError) { Text("Entendi") }
            }

            Spacer(Modifier.height(Dim.SectionGap))
            SectionLabel("Quanto cobrar agora")
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountModeChip("Tudo", state.splitPeople == null, Modifier.weight(1f)) { onSetSplitPeople(null) }
                AmountModeChip("Dividir", state.splitPeople != null, Modifier.weight(1f)) { onSetSplitPeople(state.splitPeople ?: 2) }
            }

            state.splitPeople?.let { people ->
                SplitSection(people = people, paidParts = state.paidParts, remaining = state.remaining, onSetPeople = onSetSplitPeople)
            }

            Spacer(Modifier.height(12.dp))
            AmountField(amount = state.amountToCharge)

            Spacer(Modifier.height(Dim.SectionGap))
            SectionLabel("Forma de pagamento")
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosPaymentOption.entries.forEach { method ->
                    MethodCard(
                        method = method,
                        selected = method == state.selectedMethod,
                        enabled = !state.isProcessing,
                        onClick = { onSelectMethod(method) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.selectedMethod == PosPaymentOption.CREDIT_CARD) {
                Spacer(Modifier.height(Dim.SectionGap))
                SectionLabel("Parcelas")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4).forEach { n ->
                        AmountChip(
                            label = if (n == 1) "À vista" else "${n}x",
                            selected = state.installments == n,
                            onClick = { onSetInstallments(n) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (state.selectedMethod == PosPaymentOption.CASH) {
                Spacer(Modifier.height(Dim.SectionGap))
                SectionLabel("Valor recebido")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AmountChip(label = "Exato", selected = state.receivedCents == null, onClick = { onSetReceived(null) }, modifier = Modifier.weight(1f))
                    cashSuggestions(state.amountToCharge).forEach { cents ->
                        AmountChip(
                            label = Money(cents).formatBRL().removePrefix("R$ "),
                            selected = state.receivedCents == cents,
                            onClick = { onSetReceived(cents) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                state.changeDue?.let { change ->
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Troco", fontSize = 13.sp, color = NoktaMuted)
                        Text(change.formatBRL(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NoktaPurple)
                    }
                }
            }

            Spacer(Modifier.height(Dim.SectionGap))

            ConfirmButton(
                text = when {
                    state.awaitingRegistrationRetry -> "Tentar salvar de novo"
                    state.selectedMethod == PosPaymentOption.CASH ->
                        "Confirmar recebimento" + (state.partLabel?.let { " ($it)" } ?: "")
                    else -> "Cobrar ${state.amountToCharge.formatBRL()}" + (state.partLabel?.let { " — $it" } ?: "")
                },
                enabled = state.canConfirmCash,
                loading = state.isProcessing,
                onClick = onConfirm,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

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

@Composable
private fun OrderSummaryCard(lines: List<CartLine>, total: Money, onEditOrder: () -> Unit) {
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

            if (lines.isEmpty()) {
                Text("Nenhum item no carrinho.", fontSize = 13.sp, color = NoktaMutedSoft, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                lines.forEach { line ->
                    OrderItemRow(line)
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(NoktaBorder))

        Row(
            modifier = Modifier.fillMaxWidth().background(PageGray).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Total", modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
            Text(total.formatBRL(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NoktaPurple)
        }
    }
}

@Composable
private fun OrderItemRow(line: CartLine) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(PageGray),
            contentAlignment = Alignment.Center,
        ) {
            Text("${line.quantity}x", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaInkSoft)
        }

        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(line.productName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk)
            val detail = buildList {
                if (line.variantName.isNotBlank()) add(line.variantName)
                if (line.modifiers.isNotEmpty()) add(line.modifiers.joinToString(", ") { it.name })
                line.notes?.takeIf { it.isNotBlank() }?.let { add("Obs.: $it") }
            }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(detail, fontSize = 11.5.sp, color = NoktaMutedSoft, maxLines = 1)
            }
        }

        Spacer(Modifier.width(8.dp))
        Text(line.lineTotal.formatBRL(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
    }
}

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
private fun AmountField(amount: Money) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(12.dp)).background(NoktaSurface).border(1.dp, FieldBorder, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(amount.formatBRL(), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = NoktaInk)
    }
}

/**
 * Divisão igual — mostra quanto cada pessoa deve, e quantas partes já foram
 * cobradas. O valor cobrado AGORA (`amountToCharge`) é sempre a parte de 1
 * pessoa dividindo o que ainda falta pelas pessoas que ainda não pagaram —
 * nunca o total original recalculado, senão a última parte ficaria errada
 * quando as partes anteriores não fecham exato por causa do centavo.
 */
@Composable
private fun SplitSection(people: Int, paidParts: Int, remaining: Money, onSetPeople: (Int?) -> Unit) {
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onSetPeople((people - 1).coerceAtLeast(paidParts + 1)) }, enabled = people > 2 && people > paidParts + 1, modifier = Modifier.size(48.dp)) {
            Text("−", style = MaterialTheme.typography.headlineSmall)
        }
        Text("$people pessoas", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onSetPeople(people + 1) }, modifier = Modifier.size(48.dp)) {
            Text("+", style = MaterialTheme.typography.headlineSmall)
        }
    }

    Spacer(Modifier.height(12.dp))
    val peopleLeft = (people - paidParts).coerceAtLeast(1)
    val parts = com.nokta.pos.payment.domain.SplitCalculator.splitRemaining(remaining, peopleLeft)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PageGray).padding(14.dp)) {
        parts.forEachIndexed { index, part ->
            val personNumber = paidParts + index + 1
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (personNumber <= paidParts) "Pessoa $personNumber (paga)" else "Pessoa $personNumber",
                    fontSize = 13.sp,
                    color = NoktaMuted,
                )
                Text(part.formatBRL(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (index == 0) NoktaPurple else NoktaInk)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Cobrando a parte da próxima pessoa agora.", fontSize = 11.sp, color = NoktaMutedSoft)
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
private fun MethodCard(method: PosPaymentOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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

private fun PosPaymentOption.icon(): ImageVector = when (this) {
    PosPaymentOption.CREDIT_CARD -> Icons.Filled.CreditCard
    PosPaymentOption.DEBIT_CARD -> Icons.Filled.CreditCard
    PosPaymentOption.PIX -> Icons.Filled.Pix
    PosPaymentOption.CASH -> Icons.Filled.Payments
}

private fun PosPaymentOption.label(): String = when (this) {
    PosPaymentOption.CREDIT_CARD -> "Crédito"
    PosPaymentOption.DEBIT_CARD -> "Débito"
    PosPaymentOption.PIX -> "Pix"
    PosPaymentOption.CASH -> "Dinheiro"
}

@Composable
private fun ConfirmButton(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
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
