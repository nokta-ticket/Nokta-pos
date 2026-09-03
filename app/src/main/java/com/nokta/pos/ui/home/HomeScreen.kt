package com.nokta.pos.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.BuildConfig
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.NoktaFooter
import com.nokta.pos.ui.components.OnResumeEffect
import com.nokta.pos.ui.components.PosInlineWarning

/* =========================================================================
 *  HOME DO POS — versão técnica/quadrada (redesign 2026-09, fiel ao arquivo
 *  de referência enviado pelo usuário). Cantos retos, rótulos em caixa alta
 *  espaçada e azul como cor de ação.
 *
 *  Esta tela é só o INVÓLUCRO VISUAL — ver [HomeScreen] para o ponto que
 *  carrega o ViewModel de verdade e [HomeContent] para a composição
 *  conectada a [HomeUiState]/callbacks reais. Toda a lógica (permissões,
 *  sync, caixa, diálogos) é a mesma de antes; só a apresentação mudou.
 * ========================================================================= */

private object Dim {
    val ScreenPad = 20.dp
    val BoxRadius = 5.dp
    val PillHeight = 30.dp
    val HeaderButton = 40.dp
    val QuickCardHeight = 128.dp
    val OpCardHeight = 182.dp
    val RowHeight = 58.dp
}

private val Blue = Color(0xFF0B57F0)
private val Ink = Color(0xFF11141A)
private val InkSoft = Color(0xFF3A4048)
private val Gray = Color(0xFF6E7681)
private val GrayLight = Color(0xFF9AA1AA)
private val GhostText = Color(0xFFC3C9D1)
private val Line = Color(0xFFE3E7EC)
private val PanelBg = Color(0xFFF4F7FB)
private val GreenDot = Color(0xFF16A34A)
private val Surface = Color(0xFFFFFFFF)

/**
 * Home operacional. Só ações — nada de faturamento, gráficos ou indicadores
 * gerenciais (isso é o dashboard, não o POS).
 *
 * Ações sem permissão ficam esmaecidas e inertes, com o motivo à vista, em vez
 * de sumirem (o operador perguntaria "cadê?") ou de deixarem tocar e tomar 403.
 */
@Composable
fun HomeScreen(
    onNovaVenda: () -> Unit,
    onMesas: () -> Unit,
    onComandas: () -> Unit,
    onAbertas: () -> Unit,
    onHistorico: () -> Unit,
    onOpenTab: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Mesmo bug já corrigido em Comandas/Abertas/Mesas: a Home fica na
    // pilha de navegação, e o badge "Abertas" (openTabsCount) só refletia
    // dado fresco depois de visitar outra tela que sincronizasse o Room —
    // nunca a própria Home. Ver HomeViewModel.refreshOpenTabs.
    OnResumeEffect(viewModel::refreshOpenTabs)

    state.pendingPaymentAttempt?.let { attempt ->
        PendingPaymentDialog(
            attempt = attempt,
            onOpenTab = { viewModel.resolvePendingAttemptTabLocalId(attempt.tabId, onOpenTab) },
            onDismiss = viewModel::dismissPendingAttempt,
        )
        return
    }

    HomeContent(
        state = state,
        onNovaVenda = onNovaVenda,
        onMesas = onMesas,
        onComandas = onComandas,
        onAbertas = onAbertas,
        onHistorico = onHistorico,
        onLogout = { viewModel.requestLogout(onLogout) },
        onConfirmLogoutOffline = { viewModel.confirmLogoutOffline(onLogout) },
        onDismissLogoutConfirmation = viewModel::dismissLogoutConfirmation,
        onDismissCashClosedToast = viewModel::dismissCashClosedToast,
        onOpenCashWarning = viewModel::openCashWarningDialog,
        onDismissCashWarning = viewModel::dismissCashWarningDialog,
        onDismissSyncRejection = viewModel::dismissSyncRejection,
        onDismissPaymentReconciliationMessage = viewModel::dismissPaymentReconciliationMessage,
    )
}

/**
 * Texto do aviso de caixa fechado — mesmo texto no toast e no dialog reaberto
 * pelo sino. Caixa fechado impede LANÇAR ITEM NOVO, nunca receber: as mesas e
 * comandas que já estão abertas continuam podendo ser pagas normalmente, em
 * qualquer forma de pagamento. O aviso nunca deve sugerir que o recebimento
 * está parado.
 */
private const val CASH_CLOSED_MESSAGE =
    "Caixa fechado nesta unidade — não é possível lançar novos itens até um gerente abrir o caixa no painel (Operação › Caixa). As mesas e comandas já abertas continuam podendo ser pagas normalmente."

/**
 * Conteúdo visual da Home, sem ViewModel.
 *
 * Separado para poder ser pré-visualizado com estados fixos (ver
 * `HomePreviewActivity`, sourceSet de debug) — o preview mostra exatamente o
 * que roda em produção, não uma cópia que envelhece.
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    onNovaVenda: () -> Unit,
    onMesas: () -> Unit,
    onComandas: () -> Unit,
    onAbertas: () -> Unit,
    onHistorico: () -> Unit,
    onLogout: () -> Unit,
    onConfirmLogoutOffline: () -> Unit = {},
    onDismissLogoutConfirmation: () -> Unit = {},
    onDismissCashClosedToast: () -> Unit = {},
    onOpenCashWarning: () -> Unit = {},
    onDismissCashWarning: () -> Unit = {},
    onDismissSyncRejection: () -> Unit = {},
    onDismissPaymentReconciliationMessage: () -> Unit = {},
) {
    val access = state.access

    // Toast de 5s só na primeira vez que o fechamento é detectado nesta
    // sessão da Home — depois disso o aviso só volta se o operador tocar
    // no sino (ver hasCashWarning/cashWarningDialogOpen).
    LaunchedEffect(state.hasCashWarning, state.cashClosedToastShown) {
        if (state.hasCashWarning && !state.cashClosedToastShown) {
            kotlinx.coroutines.delay(5000)
            onDismissCashClosedToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Surface)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = Dim.ScreenPad)) {

                Spacer(Modifier.height(22.dp))

                Header(
                    userName = state.operatorName ?: "operador",
                    unitName = state.locationName,
                    showCashBell = state.requiresCashSession,
                    cashWarningActive = state.hasCashWarning,
                    onCashBellClick = onOpenCashWarning,
                    onLogout = onLogout,
                )

                Spacer(Modifier.height(18.dp))

                StatusRow(state = state)

                if (!access.canTakePayments) {
                    Spacer(Modifier.height(16.dp))
                    PosInlineWarning(
                        "Seu perfil não registra pagamentos. Você lança itens; o fechamento é do caixa.",
                    )
                }

                Spacer(Modifier.height(16.dp))

                QuickSaleCard(enabled = access.canSellAtCounter, onClick = onNovaVenda)

                Spacer(Modifier.height(24.dp))

                SectionLabel("OPERAÇÕES")

                Spacer(Modifier.height(10.dp))

                val tables: @Composable () -> OperationCardSpec = {
                    OperationCardSpec(
                        index = "01",
                        icon = Icons.Outlined.TableRestaurant,
                        title = "Mesas",
                        subtitle = "Consumo e lançamento de itens",
                        enabled = access.canViewTables,
                        onClick = onMesas,
                    )
                }
                val tabs: @Composable () -> OperationCardSpec = {
                    OperationCardSpec(
                        index = "02",
                        icon = Icons.Outlined.Receipt,
                        title = "Comandas",
                        subtitle = "Por pulseira ou cartão",
                        enabled = access.canViewTabs,
                        onClick = onComandas,
                    )
                }

                // Balcão puro: comanda antes de mesa (mesa quase não é usada).
                val (first, second) = if (state.highlightTables) tables() to tabs() else tabs() to tables()

                Row(Modifier.height(IntrinsicSize.Min)) {
                    OperationCard(spec = first, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Line),
                    )
                    OperationCard(spec = second, modifier = Modifier.weight(1f))
                }

                if (access.canViewTabs) {
                    Spacer(Modifier.height(26.dp))
                    SectionLabel("REGISTROS")
                    Spacer(Modifier.height(6.dp))
                    RecordRow(
                        icon = Icons.Outlined.MenuBook,
                        title = "Abertas",
                        subtitle = when (state.openTabsCount) {
                            null -> "Mesas e comandas abertas"
                            0 -> "Nada em aberto"
                            else -> "Mesas e comandas abertas"
                        },
                        badgeCount = state.openTabsCount?.takeIf { it > 0 },
                        onClick = onAbertas,
                    )
                    RecordRow(
                        icon = Icons.Outlined.Schedule,
                        title = "Histórico",
                        subtitle = "Ver vendas encerradas",
                        badgeCount = null,
                        onClick = onHistorico,
                    )
                }

                Spacer(Modifier.height(30.dp))
            }

            // Fora do padding horizontal da coluna acima de propósito: o
            // rodapé ocupa a largura inteira da tela, sem a margem de 20dp
            // que os cards usam.
            NoktaFooter(
                modifier = Modifier.fillMaxWidth(),
                // Sem sufixo de estágio (-mvp etc.) na tela — feio para o
                // operador; o BuildConfig.VERSION_NAME completo continua
                // disponível para diagnóstico/logs.
                appVersion = BuildConfig.VERSION_NAME.substringBefore("-"),
            )
        }

        // Toast de 5s, só na primeira detecção do fechamento nesta sessão da
        // Home — dispensável a qualquer momento pelo X. Depois de dispensado
        // (pelo X ou pelo tempo), o aviso só reaparece via sino no cabeçalho.
        if (state.hasCashWarning && !state.cashClosedToastShown) {
            CashClosedToast(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                onDismiss = onDismissCashClosedToast,
            )
        }

        if (state.cashWarningDialogOpen) {
            CashWarningDialog(onDismiss = onDismissCashWarning)
        }

        // Diálogo (não toast): o item já saiu da comanda, e o operador precisa
        // ver o motivo antes de continuar — um aviso que some sozinho deixaria
        // o consumo desaparecer sem explicação nenhuma.
        state.syncRejectionMessage?.let { reason ->
            SyncRejectionDialog(reason = reason, onDismiss = onDismissSyncRejection)
        }

        // Distinto do aviso acima: aqui já existia dinheiro cobrado do
        // cliente contando com o item recusado. Fechar este diálogo não
        // resolve nada — a divergência fica registrada e visível na tela da
        // comanda até alguém revisar; isto é só o alerta imediato.
        state.paymentReconciliationMessage?.let { message ->
            PaymentReconciliationDialog(message = message, onDismiss = onDismissPaymentReconciliationMessage)
        }

        if (state.logoutConfirmationOpen) {
            LogoutConfirmationDialog(
                onConfirm = onConfirmLogoutOffline,
                onDismiss = onDismissLogoutConfirmation,
            )
        }
    }
}

/* ------------------------------ Diálogos ----------------------------- */

/** Toast auto-dispensável (chamador cuida do timer) com botão de fechar manual. */
@Composable
private fun CashClosedToast(modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.BoxRadius))
            .background(Color(0xFFFDF3E3))
            .border(1.dp, Color(0xFFB45309).copy(alpha = 0.25f), RoundedCornerShape(Dim.BoxRadius))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Caixa fechado nesta unidade. Toque no sino para saber mais.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB45309),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "×",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB45309),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CashWarningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Entendi") }
        },
        title = { Text("Caixa fechado") },
        text = { Text(CASH_CLOSED_MESSAGE) },
    )
}

/**
 * Uma operação lançada offline foi RECUSADA pelo servidor ao sincronizar
 * (ex.: item lançado numa comanda cujo caixa fechou nesse meio tempo).
 *
 * O item já foi removido da comanda pelo SyncEngine — nunca fica como
 * fantasma de R$ 0,00 —, mas sumir sem explicação seria pior que o próprio
 * fantasma: o operador precisa saber que aquele consumo não existe mais, e
 * por quê, para poder refazer ou avisar o cliente.
 */
@Composable
private fun SyncRejectionDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Entendi") }
        },
        title = { Text("Lançamento não aceito") },
        text = { Text("$reason\n\nO item foi removido da comanda. Se ainda for necessário, lance de novo.") },
    )
}

/**
 * Um item recusado na sincronização já constava num pagamento cobrado do
 * cliente NESTE terminal — diferente de [SyncRejectionDialog] (item que
 * nunca chegou a ser cobrado). Fechar este diálogo é só reconhecer o
 * aviso: a divergência em si (PaymentReconciliationEntity) fica registrada
 * e visível na tela da comanda até alguém revisar de verdade.
 */
@Composable
private fun PaymentReconciliationDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Entendi") }
        },
        title = { Text("Divergência de pagamento") },
        text = { Text(message) },
    )
}

/**
 * Só aparece quando o operador toca "Sair" SEM rede (ver
 * [HomeViewModel.requestLogout]). Login novo exige validar senha contra o
 * backend — sem conexão, ninguém consegue entrar depois, então o terminal
 * fica sem operador até a rede voltar. Isto não bloqueia a saída (decisão do
 * operador é respeitada), só garante que ele sabe a consequência antes.
 */
@Composable
private fun LogoutConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sem conexão") },
        text = {
            Text(
                "Você está offline. Se sair agora, este terminal ficará sem operador até a conexão voltar.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Sair mesmo assim") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Continuar no caixa") }
        },
    )
}

/**
 * Um pagamento Cielo sem resultado confirmado trava a operação de propósito:
 * cobrar de novo sem checar o extrato pode cobrar o cliente duas vezes.
 */
@Composable
private fun PendingPaymentDialog(
    attempt: com.nokta.pos.payment.cielo.PendingCieloAttempt,
    onOpenTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pagamento não confirmado") },
        text = {
            Text(
                "Uma cobrança de ${Money(attempt.amountCents).formatBRL()} não teve resultado confirmado. " +
                    "Verifique no extrato do terminal se ela foi aprovada antes de continuar — " +
                    "nunca cobre de novo sem confirmar.",
            )
        },
        shape = RoundedCornerShape(20.dp),
        confirmButton = { TextButton(onClick = onOpenTab) { Text("Abrir comanda") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Já verifiquei") } },
    )
}

/* --------------------------- Header / status ------------------------ */

/**
 * O nome do operador é o único texto forte aqui; a unidade é contexto.
 * Fiel ao layout de referência: botões quadrados de borda fina, sino com
 * badge azul quando há aviso de caixa pendente.
 */
@Composable
private fun Header(
    userName: String,
    unitName: String?,
    showCashBell: Boolean,
    cashWarningActive: Boolean,
    onCashBellClick: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Olá, $userName",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            unitName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = Blue,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (showCashBell) {
            Box {
                SquareIconButton(
                    icon = Icons.Outlined.Notifications,
                    contentDescription = if (cashWarningActive) "Avisos (caixa fechado)" else "Avisos",
                    onClick = onCashBellClick,
                )
                if (cashWarningActive) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 2.dp)
                            .size(6.dp)
                            .background(Blue),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        SquareIconButton(
            icon = Icons.AutoMirrored.Outlined.Logout,
            contentDescription = "Sair",
            onClick = onLogout,
        )
    }
}

@Composable
private fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Dim.HeaderButton)
            .clip(RoundedCornerShape(Dim.BoxRadius))
            .background(Surface)
            .border(1.dp, Line, RoundedCornerShape(Dim.BoxRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Ink,
            modifier = Modifier.size(18.dp),
        )
    }
}

/* ---------------------------- Status row ---------------------------- */

/**
 * Conexão, sincronização e caixa — deliberadamente discreto. Mesma fonte de
 * estado de sempre (fila de sincronização, não ping de rede); só a
 * apresentação (pills) mudou. O pill de caixa só aparece quando a unidade
 * exige caixa aberto para pagar ([HomeUiState.requiresCashSession]).
 */
@Composable
private fun StatusRow(state: HomeUiState) {
    val connection = state.connection

    val isOnlineLike = connection != ConnectionState.OFFLINE && connection != ConnectionState.OFFLINE_PENDING

    val statusLabel = when (connection) {
        ConnectionState.ONLINE -> "ONLINE"
        ConnectionState.SYNCING -> "SINCRONIZANDO"
        ConnectionState.PENDING -> "PENDENTE"
        ConnectionState.OFFLINE, ConnectionState.OFFLINE_PENDING -> "OFFLINE"
    }

    val pending = state.pendingSyncCount
    val syncLabel = when (connection) {
        ConnectionState.ONLINE -> "SYNC · AGORA"
        ConnectionState.SYNCING -> "SYNC · ENVIANDO"
        ConnectionState.PENDING -> "SYNC · $pending NA FILA"
        // Sem fila, o operador ainda precisa saber que está sem rede — a
        // mensagem foca só nisso, sem misturar com "última sincronização".
        ConnectionState.OFFLINE -> "SYNC · SEM REDE"
        // Com fila, o risco real é desligar o terminal com venda presa nele.
        ConnectionState.OFFLINE_PENDING -> "SYNC · $pending PENDENTE"
    }

    val syncColor = when (connection) {
        ConnectionState.PENDING -> Color(0xFFB45309)
        ConnectionState.OFFLINE, ConnectionState.OFFLINE_PENDING -> Color(0xFFD92D20)
        else -> Gray
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(
            label = statusLabel,
            leading = {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(if (isOnlineLike) GreenDot else Color(0xFFD92D20)),
                )
            },
        )
        StatusPill(
            label = syncLabel,
            textColor = syncColor,
            leading = {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = syncColor,
                    modifier = Modifier.size(12.dp),
                )
            },
        )
        state.isCashOpen?.takeIf { state.requiresCashSession }?.let { cashOpen ->
            StatusPill(
                label = if (cashOpen) "CAIXA ABERTO" else "CAIXA FECHADO",
                filled = true,
                filledBg = if (cashOpen) Blue else Color(0xFFD92D20),
            )
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    filled: Boolean = false,
    filledBg: Color = Blue,
    textColor: Color = Ink,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .height(Dim.PillHeight)
            .clip(RoundedCornerShape(Dim.BoxRadius))
            .background(if (filled) filledBg else Surface)
            .then(
                if (filled) Modifier
                else Modifier.border(1.dp, Line, RoundedCornerShape(Dim.BoxRadius)),
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = if (filled) Color.White else textColor,
            maxLines = 1,
        )
    }
}

/* ----------------------------- Nova venda --------------------------- */

@Composable
private fun QuickSaleCard(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dim.QuickCardHeight)
            .clip(CutCornerShape(bottomStart = 20.dp))
            .background(Blue)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Icon(
            imageVector = Icons.Outlined.ShoppingCart,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.10f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(140.dp),
        )

        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp, end = 88.dp),
        ) {
            Text(
                text = "AÇÃO RÁPIDA",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Nova venda",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Balcão · cobrar na hora",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
                .size(42.dp)
                .border(1.5.dp, Color.White, RoundedCornerShape(Dim.BoxRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/* ---------------------------- Operações ----------------------------- */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = GrayLight,
    )
}

private data class OperationCardSpec(
    val index: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun OperationCard(spec: OperationCardSpec, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(Dim.OpCardHeight)
            .background(PanelBg)
            .clickable(enabled = spec.enabled, onClick = spec.onClick)
            .alpha(if (spec.enabled) 1f else 0.5f)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Dim.BoxRadius))
                    .background(Surface)
                    .border(1.dp, Line, RoundedCornerShape(Dim.BoxRadius)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = Blue,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = spec.index,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = GhostText,
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = spec.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = Ink,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = spec.subtitle,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = Gray,
        )

        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ABRIR",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Blue,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = Blue,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/* ----------------------------- Registros ---------------------------- */

@Composable
private fun RecordRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeCount: Int?,
    onClick: () -> Unit,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Line),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dim.RowHeight)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Blue,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                        color = Ink,
                    )
                    badgeCount?.let {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(19.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Blue),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(it.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = GrayLight,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
