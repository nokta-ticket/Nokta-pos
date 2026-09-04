package com.nokta.pos.ui.venda

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
private val SelectedTint = Color(0xFFEAF2FF)
private val SplitCardBg = Color(0xFFEAF2FF)
private val FieldBorder = Color(0xFFE7E4EF)
private val DangerRed = Color(0xFFDC2626)

/**
 * Pagamento da venda de balcão. Uma tela só: escolher forma, confirmar,
 * pronto. Não há passo intermediário de "comanda" nem de "checkout" — no
 * balcão o cliente está esperando de pé (item 6 e 10 do brief).
 *
 * Mesmo visual do checkout de comanda/mesa ([com.nokta.pos.ui.checkout.CheckoutScreen])
 * para consistência entre os dois fluxos. "Editar pedido" edita o carrinho
 * de verdade aqui (+/−/remover) — mais simples que o checkout de comanda
 * porque o carrinho é puramente local (nada foi enviado ao servidor ainda),
 * então não precisa cancelar/relançar nada, só mexer no `Cart` em memória.
 * Bloqueado (`canEditCart`) assim que alguma parte já foi cobrada — mudar o
 * total depois disso deixaria o valor já pago inconsistente.
 */
@Composable
fun BalcaoPagamentoScreen(
    state: BalcaoUiState,
    onSelectMethod: (PosPaymentOption) -> Unit,
    onSetReceived: (Long?) -> Unit,
    onSetSplitPeople: (Int?) -> Unit,
    onSetManualSplitAmount: (Long) -> Unit,
    onToggleEditCart: () -> Unit,
    onIncreaseLine: (CartLine) -> Unit,
    onDecreaseLine: (CartLine) -> Unit,
    onRemoveLine: (CartLine) -> Unit,
    onConfirmRemoveLine: () -> Unit,
    onDismissRemoveLine: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
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

                OrderSummaryCard(
                    lines = state.cart.lines,
                    total = state.total,
                    editing = state.isEditingCart,
                    editable = state.canEditCart,
                    onToggleEdit = onToggleEditCart,
                    onIncrease = onIncreaseLine,
                    onDecrease = onDecreaseLine,
                    onRemove = onRemoveLine,
                )
            }

            Column(Modifier.padding(horizontal = Dim.ScreenPad)) {

                state.errorMessage?.let { message ->
                    Spacer(Modifier.height(Dim.SectionGap))
                    PosInlineWarning(message, tone = state.errorTone)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismissError) { Text("Entendi") }
                }

                Spacer(Modifier.height(Dim.SectionGap))
                SectionLabel("Quanto cobrar agora")
                Spacer(Modifier.height(10.dp))

                ChargeModeSelector(splitting = state.splitPeople != null, onChange = { splitting -> onSetSplitPeople(if (splitting) (state.splitPeople ?: 2) else null) })

                AnimatedVisibility(visible = state.splitPeople != null) {
                    Column {
                        Spacer(Modifier.height(14.dp))
                        SplitCard(state = state, onSetPeople = onSetSplitPeople, onSetManualAmount = onSetManualSplitAmount)
                    }
                }

                if (state.splitPeople == null) {
                    Spacer(Modifier.height(12.dp))
                    AmountField(amount = state.amountToCharge)
                }

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

                if (state.selectedMethod == PosPaymentOption.CASH) {
                    Spacer(Modifier.height(Dim.SectionGap))
                    SectionLabel("Valor recebido")
                    Spacer(Modifier.height(10.dp))

                    var showAmountDialog by remember { mutableStateOf(false) }
                    val suggestions = cashSuggestions(state.amountToCharge)
                    val isCustom = state.receivedCents != null && state.receivedCents !in suggestions

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AmountChip(label = "Exato", selected = state.receivedCents == null, onClick = { onSetReceived(null) }, modifier = Modifier.weight(1f))
                        suggestions.forEach { cents ->
                            AmountChip(
                                label = Money(cents).formatBRL().removePrefix("R$ "),
                                selected = state.receivedCents == cents,
                                onClick = { onSetReceived(cents) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    AmountChip(
                        label = if (isCustom) "Outro valor: ${Money(state.receivedCents!!).formatBRL()}" else "Digitar outro valor",
                        selected = isCustom,
                        onClick = { showAmountDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showAmountDialog) {
                        ReceivedAmountDialog(
                            // Sempre começa zerado: pré-preencher com o total
                            // induziria o operador a confirmar sem realmente
                            // digitar o valor que o cliente entregou.
                            initialCents = if (isCustom) state.receivedCents!! else 0L,
                            onConfirm = { cents -> onSetReceived(cents); showAmountDialog = false },
                            onDismiss = { showAmountDialog = false },
                        )
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

                ChargeButton(
                    text = when {
                        state.awaitingRegistrationRetry -> "Tentar salvar de novo"
                        state.selectedMethod == PosPaymentOption.CASH ->
                            "Confirmar recebimento" + (state.partLabel?.let { " ($it)" } ?: "")
                        state.partLabel != null -> "Cobrar ${state.amountToCharge.formatBRL()} — ${state.partLabel}"
                        else -> "Cobrar ${state.amountToCharge.formatBRL()}"
                    },
                    enabled = state.canConfirmCash,
                    loading = state.isProcessing,
                    onClick = onConfirm,
                )

                Spacer(Modifier.height(24.dp))
            }
        }

        state.pendingRemoveLine?.let { line ->
            RemoveLineDialog(line = line, onConfirm = onConfirmRemoveLine, onDismiss = onDismissRemoveLine)
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
private fun OrderSummaryCard(
    lines: List<CartLine>,
    total: Money,
    editing: Boolean,
    editable: Boolean,
    onToggleEdit: () -> Unit,
    onIncrease: (CartLine) -> Unit,
    onDecrease: (CartLine) -> Unit,
    onRemove: (CartLine) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.CardRadius)).background(NoktaSurface).border(1.dp, NoktaBorder, RoundedCornerShape(Dim.CardRadius)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Resumo do pedido", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
                if (editable) {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleEdit).padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (editing) "Concluir" else "Editar pedido", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NoktaPurple)
                        Spacer(Modifier.width(5.dp))
                        Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (lines.isEmpty()) {
                Text("Nenhum item no carrinho.", fontSize = 13.sp, color = NoktaMutedSoft, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                lines.forEach { line ->
                    OrderItemRow(
                        line = line,
                        editing = editing && editable,
                        onIncrease = { onIncrease(line) },
                        onDecrease = { onDecrease(line) },
                        onRemove = { onRemove(line) },
                    )
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
private fun OrderItemRow(line: CartLine, editing: Boolean, onIncrease: () -> Unit, onDecrease: () -> Unit, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editing) {
            QuantityStepper(quantity = line.quantity, onIncrease = onIncrease, onDecrease = onDecrease)
        } else {
            Box(
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(PageGray),
                contentAlignment = Alignment.Center,
            ) {
                Text("${line.quantity}x", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaInkSoft)
            }
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

        if (editing) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remover ${line.productName}", tint = DangerRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun QuantityStepper(quantity: Int, onIncrease: () -> Unit, onDecrease: () -> Unit) {
    Row(
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(SelectedTint).border(1.dp, Color(0xFFB3D4FF), RoundedCornerShape(9.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clickable(onClick = onDecrease), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Remove, contentDescription = "Diminuir quantidade", tint = NoktaPurple, modifier = Modifier.size(15.dp))
        }
        Text(quantity.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NoktaInk, textAlign = TextAlign.Center, modifier = Modifier.width(18.dp))
        Box(Modifier.size(30.dp).clickable(onClick = onIncrease), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, contentDescription = "Aumentar quantidade", tint = NoktaPurple, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun RemoveLineDialog(line: CartLine, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remover ${line.productName}?") },
        text = { Text("Esse item será removido do carrinho.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remover", color = DangerRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ChargeModeSelector(splitting: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentButton(label = "Tudo", selected = !splitting, onClick = { onChange(false) }, modifier = Modifier.weight(1f))
        SegmentButton(label = "Dividir", selected = splitting, onClick = { onChange(true) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) NoktaPurpleBright else NoktaSurface)
            .border(width = 1.dp, color = if (selected) NoktaPurpleBright else FieldBorder, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else NoktaInkSoft)
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
 * Contador de pessoas + card com o valor da parte atual e progresso. O valor
 * cobrado AGORA é sempre a parte de 1 pessoa dividindo o que ainda falta
 * pelas pessoas que ainda não pagaram (`state.amountToCharge`), nunca o
 * total original recalculado — a menos que o operador tenha digitado um
 * valor manual para esta parte via "Editar valor" (ver
 * [BalcaoUiState.manualSplitAmountCents]: um cliente paga R$ 20 e o outro os
 * R$ 2 restantes, em vez de forçar divisão igual).
 */
@Composable
private fun SplitCard(state: BalcaoUiState, onSetPeople: (Int?) -> Unit, onSetManualAmount: (Long) -> Unit) {
    val people = state.splitPeople ?: 2
    val currentPerson = (state.paidParts + 1).coerceAtMost(people)
    var showManualAmountDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                icon = Icons.Outlined.Remove,
                enabled = people > 2 && people > state.paidParts + 1,
                contentDescription = "Menos pessoas",
                onClick = { onSetPeople((people - 1).coerceAtLeast(state.paidParts + 1)) },
            )
            Text(
                text = "$people pessoas",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = NoktaInk,
                textAlign = TextAlign.Center,
            )
            RoundIconButton(icon = Icons.Outlined.Add, enabled = true, contentDescription = "Mais pessoas", onClick = { onSetPeople(people + 1) })
        }

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SplitCardBg)
                .border(1.dp, Color(0xFF99C7FF), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("COBRANDO AGORA", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = NoktaPurpleBright)
            Spacer(Modifier.height(8.dp))
            Text(state.amountToCharge.formatBRL(), fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = NoktaInk)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showManualAmountDialog = true }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (state.manualSplitAmountCents != null) "Valor personalizado" else "Editar valor", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaPurple)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = NoktaPurpleBright, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = buildAnnotatedString {
                        append("Pessoa ")
                        withStyle(SpanStyle(color = NoktaPurpleBright, fontWeight = FontWeight.Bold)) { append(currentPerson.toString()) }
                        append(" de ")
                        withStyle(SpanStyle(color = NoktaPurpleBright, fontWeight = FontWeight.Bold)) { append(people.toString()) }
                    },
                    fontSize = 16.sp,
                    color = NoktaInkSoft,
                )
            }

            Spacer(Modifier.height(14.dp))
            StepIndicator(currentPerson = currentPerson, peopleCount = people)
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NoktaSurface).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(9.dp))
                Text("Após o pagamento, avançaremos para a próxima pessoa.", fontSize = 12.sp, color = NoktaMuted)
            }
        }
    }

    if (showManualAmountDialog) {
        ReceivedAmountDialog(
            // Sempre zerado, mesmo motivo do diálogo de "valor recebido" em
            // dinheiro: pré-preencher com a sugestão igualitária induziria o
            // operador a confirmar sem checar se é isso mesmo que os
            // clientes combinaram entre si.
            initialCents = 0L,
            title = "Valor desta pessoa",
            confirmLabel = "Definir valor",
            onConfirm = { cents -> onSetManualAmount(cents); showManualAmountDialog = false },
            onDismiss = { showManualAmountDialog = false },
        )
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, enabled: Boolean, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(46.dp).clip(CircleShape).background(if (enabled) SelectedTint else Color(0xFFF0EFF4)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) NoktaPurple else NoktaMutedSoft, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StepIndicator(currentPerson: Int, peopleCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
        val shown = minOf(peopleCount, 6)
        repeat(shown) { index ->
            val number = index + 1
            val done = number <= currentPerson

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (done) NoktaPurpleBright else Color.Transparent)
                    .border(width = 1.dp, color = if (done) NoktaPurpleBright else Color(0xFFD8DEE8), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (done) Color.White else NoktaMutedSoft)
            }

            if (index < shown - 1) {
                Box(Modifier.width(34.dp).height(2.dp).background(if (number < currentPerson) NoktaPurpleBright else Color(0xFFD8DEE8)))
            }
        }

        if (peopleCount > 6) {
            Spacer(Modifier.width(8.dp))
            Text("+${peopleCount - 6}", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = NoktaMutedSoft)
        }
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
private fun ChargeButton(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) NoktaPurpleBright else FieldBorder)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(Icons.Outlined.CreditCard, contentDescription = null, tint = if (enabled) Color.White else NoktaMutedSoft, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(11.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = if (enabled) Color.White else NoktaMutedSoft, maxLines = 1)
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
