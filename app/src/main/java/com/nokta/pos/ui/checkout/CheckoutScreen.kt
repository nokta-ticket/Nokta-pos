package com.nokta.pos.ui.checkout

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
private val SplitCardBg = Color(0xFFF9F4FE)
private val FieldBorder = Color(0xFFE7E4EF)
private val DangerRed = Color(0xFFDC2626)

/**
 * Pagamento de comanda/mesa: total, ou dividido igualmente entre pessoas.
 *
 * Layout portado de um mockup de referência (2ª rodada — edição inline do
 * pedido, SplitCard com indicador de progresso). A lógica de dados/regras
 * continua 100% em [CheckoutViewModel] (valor sempre vem de `tab.remaining`
 * do servidor, cartão aprovado + falha de registro nunca gera nova cobrança,
 * etc.).
 *
 * "Editar pedido" agora edita de verdade: +/− lança/cancela+relança a linha
 * (não existe "editar quantidade" no backend — só cancelar item inteiro),
 * lixeira sempre pede confirmação, "−" com quantidade 1 também pede
 * confirmação antes de sumir. Sem seção de "outro valor" — só Tudo/Dividir,
 * como no mockup.
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

    // Mesmo comportamento da seta de voltar do topo (ver onBackPressed) —
    // gesto/botão físico de voltar do Android não deve deixar a mesa presa
    // em "Fechando a conta" por um caminho diferente da seta na tela.
    androidx.activity.compose.BackHandler { viewModel.onBackPressed(onBack) }

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
            state.tab != null -> CheckoutContent(state = state, viewModel = viewModel, onBack = { viewModel.onBackPressed(onBack) })
        }

        state.paymentMessage?.let {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = viewModel::clearMessage) { Text("Ok") } },
            ) { Text(it) }
        }

        state.pendingRemoveItem?.let { item ->
            RemoveItemDialog(item = item, onConfirm = viewModel::confirmRemoveItem, onDismiss = viewModel::dismissRemoveItem)
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

            OrderSummaryCard(
                tab = tab,
                editing = state.isEditingOrder,
                onToggleEdit = viewModel::toggleEditOrder,
                onIncreaseItem = viewModel::increaseItem,
                onDecreaseItem = viewModel::decreaseItem,
                onRemoveItem = viewModel::requestRemoveItem,
            )
        }

        Column(Modifier.padding(horizontal = Dim.ScreenPad)) {

            Spacer(Modifier.height(Dim.SectionGap))

            SectionLabel("Quanto cobrar agora")
            Spacer(Modifier.height(10.dp))

            ChargeModeSelector(mode = state.amountMode, onChange = viewModel::setAmountMode)

            AnimatedVisibility(visible = state.amountMode == AmountMode.SPLIT) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    SplitCard(state = state, onSetPeople = viewModel::setSplitPeople)
                }
            }

            if (state.amountMode == AmountMode.FULL) {
                Spacer(Modifier.height(12.dp))
                AmountField(amount = state.amountToCharge)
            }

            // A conta já quitada (isFullyPaid) nunca mostra este aviso —
            // depois do último pagamento, remaining vira 0 e amountToCharge
            // também, o que tecnicamente é "inválido" para uma PRÓXIMA
            // cobrança; mas nesse momento a tela está prestes a navegar pra
            // fora (closeTab em andamento, ver CheckoutViewModel.register),
            // então mostrar "Informe um valor maior que zero" seria um flash
            // de erro sobre um pagamento que na verdade já deu certo.
            if (!tab.isFullyPaid) {
                (state.validation as? PartialValidation.Invalid)?.let {
                    Spacer(Modifier.height(12.dp))
                    PosInlineWarning(it.reason, tone = PosBadgeTone.DANGER)
                }
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

            if (state.selectedMethod == PaymentUiMethod.CASH) {
                Spacer(Modifier.height(Dim.SectionGap))
                SectionLabel("Valor recebido")
                Spacer(Modifier.height(10.dp))

                var showAmountDialog by remember { mutableStateOf(false) }
                val suggestions = cashSuggestionsFor(state.amountToCharge)
                val isCustom = state.receivedCents != null && state.receivedCents !in suggestions

                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AmountChip(label = "Exato", selected = state.receivedCents == null, onClick = { viewModel.setReceived(null) }, modifier = Modifier.weight(1f))
                    suggestions.forEach { cents ->
                        AmountChip(
                            label = Money(cents).formatBRL().removePrefix("R$ "),
                            selected = state.receivedCents == cents,
                            onClick = { viewModel.setReceived(cents) },
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
                        onConfirm = { cents -> viewModel.setReceived(cents); showAmountDialog = false },
                        onDismiss = { showAmountDialog = false },
                    )
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

            if (state.pendingRegistration != null) {
                ConfirmButton(text = "Tentar salvar de novo", loading = state.isProcessingPayment, onClick = viewModel::retryPendingRegistration)
            } else {
                ChargeButton(
                    amount = state.amountToCharge,
                    settlesTab = state.settlesTab,
                    split = state.amountMode == AmountMode.SPLIT,
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

/* --------------------------- Resumo pedido (edição inline) ----------- */

@Composable
private fun OrderSummaryCard(
    tab: Tab,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onIncreaseItem: (TabItem) -> Unit,
    onDecreaseItem: (TabItem) -> Unit,
    onRemoveItem: (TabItem) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.CardRadius)).background(NoktaSurface).border(1.dp, NoktaBorder, RoundedCornerShape(Dim.CardRadius)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Resumo do pedido", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleEdit).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (editing) "Concluir" else "Editar pedido", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NoktaPurple)
                    Spacer(Modifier.width(5.dp))
                    Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(6.dp))

            if (tab.activeItems.isEmpty()) {
                Text("Nenhum item lançado ainda.", fontSize = 13.sp, color = NoktaMutedSoft, modifier = Modifier.padding(vertical = 18.dp).fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                tab.activeItems.forEach { item ->
                    OrderItemRow(
                        item = item,
                        editing = editing,
                        onIncrease = { onIncreaseItem(item) },
                        onDecrease = { onDecreaseItem(item) },
                        onRemove = { onRemoveItem(item) },
                    )
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
private fun OrderItemRow(item: TabItem, editing: Boolean, onIncrease: () -> Unit, onDecrease: () -> Unit, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editing) {
            QuantityStepper(quantity = item.quantity, onIncrease = onIncrease, onDecrease = onDecrease)
        } else {
            Box(
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(PageGray),
                contentAlignment = Alignment.Center,
            ) {
                Text("${item.quantity}x", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaInkSoft)
            }
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

        if (editing) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remover ${item.productName}", tint = DangerRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Stepper compacto usado no modo de edição do resumo. */
@Composable
private fun QuantityStepper(quantity: Int, onIncrease: () -> Unit, onDecrease: () -> Unit) {
    Row(
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(SelectedTint).border(1.dp, Color(0xFFE6DBFA), RoundedCornerShape(9.dp)),
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
private fun RemoveItemDialog(item: TabItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remover ${item.productName}?") },
        text = { Text("Esse item será cancelado da comanda. Essa ação fica registrada na auditoria e não pode ser desfeita.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remover", color = DangerRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun TotalsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 12.5.sp, color = NoktaMuted)
        Spacer(Modifier.weight(1f))
        Text(text = value, fontSize = 12.5.sp, color = NoktaInkSoft)
    }
}

/* ---------------------------- Quanto cobrar --------------------------- */

@Composable
private fun ChargeModeSelector(mode: AmountMode, onChange: (AmountMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentButton(label = "Tudo", selected = mode == AmountMode.FULL, onClick = { onChange(AmountMode.FULL) }, modifier = Modifier.weight(1f))
        SegmentButton(label = "Dividir", selected = mode == AmountMode.SPLIT, onClick = { onChange(AmountMode.SPLIT) }, modifier = Modifier.weight(1f))
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

/** Contador de pessoas + card roxo grande com o valor da parte atual e progresso. */
@Composable
private fun SplitCard(state: CheckoutUiState, onSetPeople: (Int) -> Unit) {
    val currentPersonIndex = state.tab?.let { tab ->
        // Quantos pagamentos já registrados nesta comanda == quantas partes já foram cobradas.
        tab.payments.count { !it.isCanceled }
    } ?: 0
    val currentPerson = (currentPersonIndex + 1).coerceAtMost(state.splitPeople)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(icon = Icons.Outlined.Remove, enabled = state.splitPeople > 2, contentDescription = "Menos pessoas", onClick = { onSetPeople(state.splitPeople - 1) })
            Text(
                text = if (state.splitPeople == 1) "1 pessoa" else "${state.splitPeople} pessoas",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = NoktaInk,
                textAlign = TextAlign.Center,
            )
            RoundIconButton(icon = Icons.Outlined.Add, enabled = true, contentDescription = "Mais pessoas", onClick = { onSetPeople(state.splitPeople + 1) })
        }

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SplitCardBg)
                .border(1.dp, Color(0xFFDDCBFA), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("COBRANDO AGORA", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = NoktaPurpleBright)
            Spacer(Modifier.height(8.dp))
            Text(state.amountToCharge.formatBRL(), fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = NoktaInk)
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = NoktaPurpleBright, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = buildAnnotatedString {
                        append("Pessoa ")
                        withStyle(SpanStyle(color = NoktaPurpleBright, fontWeight = FontWeight.Bold)) { append(currentPerson.toString()) }
                        append(" de ")
                        withStyle(SpanStyle(color = NoktaPurpleBright, fontWeight = FontWeight.Bold)) { append(state.splitPeople.toString()) }
                    },
                    fontSize = 16.sp,
                    color = NoktaInkSoft,
                )
            }

            Spacer(Modifier.height(14.dp))
            StepIndicator(currentPerson = currentPerson, peopleCount = state.splitPeople)
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
                    .border(width = 1.dp, color = if (done) NoktaPurpleBright else Color(0xFFD5D2E0), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (done) Color.White else NoktaMutedSoft)
            }

            if (index < shown - 1) {
                Box(Modifier.width(34.dp).height(2.dp).background(if (number < currentPerson) NoktaPurpleBright else Color(0xFFDDD8EC)))
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
private fun ChargeButton(amount: Money, settlesTab: Boolean, split: Boolean, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
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
            Text(
                text = when {
                    split -> "Cobrar ${amount.formatBRL()}"
                    settlesTab -> "Cobrar ${amount.formatBRL()} e quitar"
                    else -> "Cobrar ${amount.formatBRL()}"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = if (enabled) Color.White else NoktaMutedSoft,
                maxLines = 1,
            )
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
