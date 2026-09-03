package com.nokta.pos.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.BuildConfig
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.NoktaFooter
import com.nokta.pos.ui.components.OnResumeEffect
import com.nokta.pos.ui.components.PosInlineWarning
import com.nokta.pos.ui.theme.*

/**
 * Fonte monoespaçada para rótulos "técnicos" (unidade, seção, status) do
 * redesign 2026-09 — mesma linguagem visual de um terminal de pagamento.
 * Usa a mono do sistema; nunca foi empacotada uma fonte própria.
 */
private val MonoFamily = FontFamily.Monospace

/**
 * Home operacional. Só ações — nada de faturamento, gráficos ou indicadores
 * gerenciais (isso é o dashboard, não o POS).
 *
 * A composição segue o design aprovado: cabeçalho enxuto, status discreto,
 * "Nova venda" como bloco de marca em gradiente, e as duas consultas (Mesas /
 * Comandas) lado a lado. Todos os caminhos aparecem sempre em qualquer modo de
 * operação — um bar que trabalha por mesa ainda vende no balcão; o modo só
 * decide a ORDEM das duas ações secundárias.
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

    // Sem scroll de propósito: tudo (incluindo o rodapé) precisa caber na
    // tela inicial de uma vez. O bloco de conteúdo usa `weight(1f)` para
    // consumir o espaço sobrando e o rodapé fica sempre ancorado embaixo,
    // nunca abaixo de uma dobra.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NoktaBackground),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 20.dp),
            ) {
                Header(
                    userName = state.operatorName ?: "operador",
                    unitName = state.locationName,
                    onLogout = onLogout,
                    showCashBell = state.requiresCashSession,
                    cashWarningActive = state.hasCashWarning,
                    onCashBellClick = onOpenCashWarning,
                )

                Spacer(Modifier.height(20.dp))

                StatusRow(state = state)

                if (!access.canTakePayments) {
                    Spacer(Modifier.height(16.dp))
                    PosInlineWarning(
                        "Seu perfil não registra pagamentos. Você lança itens; o fechamento é do caixa.",
                    )
                }

                Spacer(Modifier.height(20.dp))

                NewSaleCard(enabled = access.canSellAtCounter, onClick = onNovaVenda)

                Spacer(Modifier.height(24.dp))

                SectionLabel("OPERAÇÕES")

                val tables: @Composable RowScope.() -> Unit = {
                    BigActionCard(
                        modifier = Modifier.weight(1f),
                        index = "01",
                        icon = Icons.Outlined.TableRestaurant,
                        title = "Mesas",
                        subtitle = "Consumo e\nlançamento de itens",
                        enabled = access.canViewTables,
                        onClick = onMesas,
                    )
                }
                val tabs: @Composable RowScope.() -> Unit = {
                    BigActionCard(
                        modifier = Modifier.weight(1f),
                        index = "02",
                        icon = Icons.Outlined.ReceiptLong,
                        title = "Comandas",
                        subtitle = "Por pulseira\nou cartão",
                        enabled = access.canViewTabs,
                        onClick = onComandas,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    // Balcão puro: comanda antes de mesa (mesa quase não é usada).
                    if (state.highlightTables) { tables(); tabs() } else { tabs(); tables() }
                }

                if (access.canViewTabs) {
                    Spacer(Modifier.height(24.dp))
                    SectionLabel("REGISTROS")
                    RegistrosList(
                        openTabsCount = state.openTabsCount,
                        onOpenTabs = onAbertas,
                        onHistory = onHistorico,
                    )
                }

                // Espaço flexível reduzido: o respiro entre os blocos acima já
                // foi aumentado para usar melhor a altura da tela, então o vão
                // antes da assinatura não precisa mais concentrar tanto vazio
                // sozinho. min menor (16dp) porque telas curtas já ganham mais
                // respiro dos Spacers fixos de cima.
                Spacer(Modifier.heightIn(min = 16.dp).weight(1f))
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

/** Toast auto-dispensável (chamador cuida do timer) com botão de fechar manual. */
@Composable
private fun CashClosedToast(modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WarningAmberLight)
            .border(1.dp, WarningAmber.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Caixa fechado nesta unidade. Toque no sino para saber mais.",
            style = MaterialTheme.typography.bodyMedium,
            color = WarningAmber,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "×",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = WarningAmber,
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

/* ------------------------------ Header ------------------------------ */

/**
 * O nome do operador é o único texto forte aqui; a unidade é contexto. O papel
 * (WAITER/CASHIER) não aparece: é informação administrativa que não muda nada
 * no que o operador faz nesta tela.
 *
 * Redesign 2026-09: tiles quadrados de ícone (sino + sair) no lugar dos pills
 * antigos, e a unidade vira um rótulo mono/uppercase em azul — mesma
 * linguagem visual "instrumento" do restante da tela nova.
 */
@Composable
private fun Header(
    userName: String,
    unitName: String?,
    onLogout: () -> Unit,
    showCashBell: Boolean = false,
    cashWarningActive: Boolean = false,
    onCashBellClick: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Olá, $userName",
                modifier = Modifier.weight(1f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = NoktaInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showCashBell) {
                    IconTile(
                        icon = Icons.Filled.Notifications,
                        badge = cashWarningActive,
                        contentDescription = if (cashWarningActive) "Avisos (caixa fechado)" else "Avisos",
                        onClick = onCashBellClick,
                    )
                }
                IconTile(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Sair",
                    onClick = onLogout,
                )
            }
        }

        // A unidade ocupa uma linha própria, com a largura inteira: ao lado do
        // botão "Sair" ela perdia ~72dp e um nome comum de rede ("Nokta Bar ·
        // Unidade Barra da Tijuca") era abreviado sem necessidade. Sobra
        // espaço vertical de sobra nesta tela; largura é que era escassa.
        unitName?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it.uppercase(),
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                lineHeight = 15.sp,
                color = NoktaPurple,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Tile quadrado de ícone — usado tanto para o sino de avisos quanto para
 * "Sair". Fiel ao mockup: só borda fina (sem fundo/clip próprio), badge
 * ciano fixo (não muda de cor por estado) quando há algo pendente.
 */
@Composable
private fun IconTile(
    icon: ImageVector,
    contentDescription: String?,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .border(1.dp, NoktaInk.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = NoktaInk,
            modifier = Modifier.size(15.dp),
        )
        if (badge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(7.dp)
                    .background(NoktaAccentBlue, RoundedCornerShape(50)),
            )
        }
    }
}

/* ---------------------------- Status row ---------------------------- */

/**
 * Conexão, sincronização e caixa — deliberadamente discreto. O operador
 * precisa saber que a máquina está funcionando, mas isso nunca pode competir
 * com "Nova venda".
 *
 * Redesign 2026-09: chips lado a lado (estilo mockup) em vez de pill +
 * detalhe à direita. Mesma fonte de estado de sempre (fila de sincronização,
 * não ping de rede) — só a apresentação mudou. O chip de caixa é novo:
 * usa [HomeUiState.isCashOpen] (já carregado por HomeViewModel.loadCashStatus)
 * e só aparece quando a unidade exige caixa aberto para pagar
 * ([HomeUiState.requiresCashSession]) — sem isso, `isCashOpen` fica sempre
 * `null` e não há nada de útil para mostrar (nunca escrito como "fechado" por
 * engano).
 */
@Composable
private fun StatusRow(state: HomeUiState) {
    val connection = state.connection

    val dotColor = when (connection) {
        ConnectionState.ONLINE -> NoktaOnline
        ConnectionState.SYNCING -> NoktaAccentBlue
        ConnectionState.PENDING -> WarningAmber
        ConnectionState.OFFLINE -> AlertRed
        ConnectionState.OFFLINE_PENDING -> AlertRed
    }

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
        // mensagem foca só nisso, sem misturar com "última sincronização"
        // (as duas ideias juntas soam contraditórias: "sem conexão —
        // sincronizado agora").
        ConnectionState.OFFLINE -> "SYNC · SEM REDE"
        // Com fila, o risco real é desligar o terminal com venda presa nele.
        // "Operações", não "vendas": uma única venda pode gerar várias
        // entradas na fila (abrir comanda, lançar pedido, registrar
        // pagamento, fechar) — contar isso como "vendas" engana o operador
        // sobre quantas vendas de fato ficaram presas.
        ConnectionState.OFFLINE_PENDING -> "SYNC · $pending PENDENTE"
    }

    // Só os estados que exigem atenção puxam cor; os demais ficam cinza para
    // não competir com a ação principal.
    val syncColor = when (connection) {
        ConnectionState.PENDING -> WarningAmber
        ConnectionState.OFFLINE, ConnectionState.OFFLINE_PENDING -> AlertRed
        else -> NoktaMutedSoft
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        StatusChip(label = statusLabel, textColor = NoktaInk, dotColor = dotColor)
        StatusChip(label = syncLabel, textColor = syncColor, icon = Icons.Filled.Sync)

        state.isCashOpen?.takeIf { state.requiresCashSession }?.let { cashOpen ->
            StatusChip(
                label = if (cashOpen) "CAIXA ABERTO" else "CAIXA FECHADO",
                textColor = Color.White,
                bg = if (cashOpen) NoktaPurple else AlertRed,
                icon = Icons.Filled.PointOfSale,
            )
        }
    }
}

/** Chip de status: borda fina por padrão, ou preenchido quando [bg] é passado (ex.: "CAIXA ABERTO"). */
@Composable
private fun StatusChip(
    label: String,
    textColor: Color,
    bg: Color = Color.Transparent,
    dotColor: Color? = null,
    icon: ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .then(if (bg == Color.Transparent) Modifier.border(1.dp, NoktaInk.copy(alpha = 0.12f)) else Modifier)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        dotColor?.let {
            Box(modifier = Modifier.size(6.dp).background(it, RoundedCornerShape(50)))
        }
        icon?.let {
            Icon(it, contentDescription = null, tint = textColor, modifier = Modifier.size(10.dp))
        }
        Text(label, fontFamily = MonoFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = textColor)
    }
}

/**
 * "há 2 min", "há 1 h", "ontem". Precisão fina não ajuda o operador — o que
 * ele decide com isso é se pode fechar o turno, então a ordem de grandeza
 * basta.
 */
private fun relativeSince(epochMs: Long): String {
    val minutes = ((System.currentTimeMillis() - epochMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "agora"
        minutes < 60 -> "há $minutes min"
        minutes < 60 * 24 -> "há ${minutes / 60} h"
        else -> "há ${minutes / (60 * 24)} d"
    }
}

/* --------------------------- Nova venda ----------------------------- */

/**
 * A ação dominante. Redesign 2026-09: bloco flat (Electric Blue chapado, sem
 * gradiente) com o canto inferior-esquerdo recortado — é o único elemento
 * "de marca" fora do padrão retangular da tela, o que basta para vencer sem
 * precisar de gradiente/glow.
 */
@Composable
private fun NewSaleCard(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(notchedCornerShape(24.dp))
            .background(NoktaPurple)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(22.dp),
    ) {
        Column {
            Text(
                text = "AÇÃO RÁPIDA",
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = Color.White.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text("Nova venda", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Balcão · cobrar na hora", fontSize = 13.5.sp, color = Color.White.copy(alpha = 0.75f))
                }
                Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = Color.White)
            }
        }
    }
}

/**
 * Recorte de canto: topo e direita retos, o canto inferior-esquerdo é
 * cortado em diagonal por [notch]. Assinatura visual do redesign 2026-09
 * (card de "Nova venda"), no lugar do card 100% arredondado anterior.
 *
 * `Shape` (não `GenericShape`) porque `createOutline` recebe [Density] como
 * parâmetro nomeado de verdade — evita depender da assinatura pouco óbvia
 * do lambda de `GenericShape` (size, LayoutDirection), que não tem acesso a
 * densidade nenhuma.
 */
private fun notchedCornerShape(notch: Dp): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val n = with(density) { notch.toPx() }
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(n, size.height)
            lineTo(0f, size.height - n)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Rótulo de seção mono/uppercase — "OPERAÇÕES", "REGISTROS". */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = MonoFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        color = NoktaInk.copy(alpha = 0.35f),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/* -------------------------- Cards grandes --------------------------- */

/**
 * Redesign 2026-09: fundo Ice flat (sem borda/sombra), índice numerado no
 * canto ("01", "02") e call-to-action mono "ABRIR →" no lugar do chevron —
 * mesma linguagem "instrumento técnico" do resto da tela.
 */
@Composable
private fun BigActionCard(
    modifier: Modifier = Modifier,
    index: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 150.dp)
            .background(NoktaBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(16.dp),
    ) {
        Text(
            index,
            fontFamily = MonoFamily,
            fontSize = 10.sp,
            color = NoktaInk.copy(alpha = 0.25f),
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.dp, NoktaPurple.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.sp, color = NoktaMuted, modifier = Modifier.weight(1f))
            Text("ABRIR →", fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NoktaPurple)
        }
    }
}

/* ------------------------ Atalhos inferiores ------------------------ */

/**
 * Lista "REGISTROS": o que segue aberto e o que já foi fechado. Redesign
 * 2026-09: linhas com divisor fino no lugar do card com divisória central —
 * mesmo peso visual secundário de antes (é consulta de apoio, não caminho de
 * venda). A contagem de abertas some enquanto carrega (ou se a chamada
 * falhar): um número errado sobre quantas mesas estão em aberto é pior do
 * que número nenhum.
 */
@Composable
private fun RegistrosList(
    openTabsCount: Int?,
    onOpenTabs: () -> Unit,
    onHistory: () -> Unit,
) {
    Column {
        HorizontalDivider(color = NoktaInk.copy(alpha = 0.1f))
        RegistroRow(
            title = "Abertas",
            description = when (openTabsCount) {
                null -> "Mesas e comandas abertas"
                0 -> "Nada em aberto"
                else -> "Mesas e comandas abertas"
            },
            badgeCount = openTabsCount?.takeIf { it > 0 },
            onClick = onOpenTabs,
        )
        HorizontalDivider(color = NoktaInk.copy(alpha = 0.1f))
        RegistroRow(
            title = "Histórico",
            description = "Ver vendas encerradas",
            badgeCount = null,
            onClick = onHistory,
        )
        HorizontalDivider(color = NoktaInk.copy(alpha = 0.1f))
    }
}

@Composable
private fun RegistroRow(
    title: String,
    description: String,
    badgeCount: Int?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("»", color = NoktaPurple, fontFamily = MonoFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = NoktaInk)
                    badgeCount?.let {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(21.dp)
                                .clip(CircleShape)
                                .background(NoktaPurple),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(it.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                Text(description, fontSize = 12.sp, color = NoktaMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Filled.History, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(14.dp))
    }
}

/* ------------------------------ Diálogo ----------------------------- */

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
