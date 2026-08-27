package com.nokta.pos.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.PosInlineWarning
import com.nokta.pos.ui.theme.*

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
    onHistorico: () -> Unit,
    onOpenTab: (String) -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

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
        onHistorico = onHistorico,
        onLogout = { viewModel.requestLogout(onLogout) },
        onConfirmLogoutOffline = { viewModel.confirmLogoutOffline(onLogout) },
        onDismissLogoutConfirmation = viewModel::dismissLogoutConfirmation,
        onDismissCashClosedToast = viewModel::dismissCashClosedToast,
        onOpenCashWarning = viewModel::openCashWarningDialog,
        onDismissCashWarning = viewModel::dismissCashWarningDialog,
    )
}

/** Texto do aviso de caixa fechado — mesmo texto no toast e no dialog reaberto pelo sino. */
private const val CASH_CLOSED_MESSAGE =
    "Caixa fechado nesta unidade — pagamentos serão recusados até um gerente abrir o caixa no painel (Operação › Caixa). Você ainda pode lançar pedidos normalmente."

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
    onHistorico: () -> Unit,
    onLogout: () -> Unit,
    onConfirmLogoutOffline: () -> Unit = {},
    onDismissLogoutConfirmation: () -> Unit = {},
    onDismissCashClosedToast: () -> Unit = {},
    onOpenCashWarning: () -> Unit = {},
    onDismissCashWarning: () -> Unit = {},
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

    // Scroll + `fillMaxHeight` no conteúdo: numa tela alta (o caso das
    // maquininhas) o conteúdo ocupa a altura inteira e a assinatura ancora no
    // rodapé em vez de flutuar no meio do vazio; numa tela curta, rola
    // normalmente em vez de espremer os cards.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NoktaBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LocalConfiguration.current.screenHeightDp.dp)
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

                Spacer(Modifier.height(28.dp))

                StatusRow(state = state)

                if (!access.canTakePayments) {
                    Spacer(Modifier.height(20.dp))
                    PosInlineWarning(
                        "Seu perfil não registra pagamentos. Você lança itens; o fechamento é do caixa.",
                    )
                }

                Spacer(Modifier.height(24.dp))

                NewSaleCard(enabled = access.canSellAtCounter, onClick = onNovaVenda)

                Spacer(Modifier.height(18.dp))

                val tables: @Composable RowScope.() -> Unit = {
                    BigActionCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.TableRestaurant,
                        title = "Mesas",
                        subtitle = "Consultar consumo\ne lançar itens",
                        enabled = access.canViewTables,
                        onClick = onMesas,
                    )
                }
                val tabs: @Composable RowScope.() -> Unit = {
                    BigActionCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.ReceiptLong,
                        title = "Comandas",
                        subtitle = "Consultar por número\nou cliente",
                        enabled = access.canViewTabs,
                        onClick = onComandas,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Balcão puro: comanda antes de mesa (mesa quase não é usada).
                    if (state.highlightTables) { tables(); tabs() } else { tabs(); tables() }
                }

                if (access.canViewTabs) {
                    Spacer(Modifier.height(18.dp))
                    ShortcutsRow(
                        openTabsCount = state.openTabsCount,
                        onOpenTabs = onComandas,
                        onHistory = onHistorico,
                    )
                }

                // Espaço flexível reduzido: o respiro entre os blocos acima já
                // foi aumentado para usar melhor a altura da tela, então o vão
                // antes da assinatura não precisa mais concentrar tanto vazio
                // sozinho. min menor (16dp) porque telas curtas já ganham mais
                // respiro dos Spacers fixos de cima.
                Spacer(Modifier.heightIn(min = 16.dp).weight(1f))

                NoktaWordmark(modifier = Modifier.fillMaxWidth())
            }
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

            if (showCashBell) {
                CashBellButton(active = cashWarningActive, onClick = onCashBellClick)
                Spacer(Modifier.width(8.dp))
            }

            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NoktaSurface)
                    .border(1.dp, NoktaBorderStrong, RoundedCornerShape(12.dp))
                    .clickable(onClick = onLogout)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Logout,
                    contentDescription = null,
                    tint = NoktaInkSoft,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Sair", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NoktaInkSoft)
            }
        }

        // A unidade ocupa uma linha própria, com a largura inteira: ao lado do
        // botão "Sair" ela perdia ~72dp e um nome comum de rede ("Nokta Bar ·
        // Unidade Barra da Tijuca") era abreviado sem necessidade. Sobra
        // espaço vertical de sobra nesta tela; largura é que era escassa.
        unitName?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = NoktaMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Sino de avisos operacionais (hoje só caixa fechado). Badge vermelho só
 * quando há algo pendente — sem badge, o sino fica neutro (mesmo visual do
 * botão "Sair" ao lado) para não competir por atenção à toa.
 */
@Composable
private fun CashBellButton(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NoktaSurface)
            .border(1.dp, NoktaBorderStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Notifications,
            contentDescription = if (active) "Avisos (caixa fechado)" else "Avisos",
            tint = if (active) WarningAmber else NoktaInkSoft,
            modifier = Modifier.size(18.dp),
        )
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(AlertRed),
            )
        }
    }
}

/* ---------------------------- Status row ---------------------------- */

/**
 * Conexão e sincronização — deliberadamente discreto. O operador precisa saber
 * que a máquina está funcionando, mas isso nunca pode competir com "Nova
 * venda".
 *
 * O estado real vem da fila de sincronização (outbox), não de um ping de rede:
 * o que importa para o operador não é "tem sinal", e sim "o que eu registrei
 * já subiu". Enquanto houver pendência, o rótulo fica âmbar e avisa; sem
 * pendência, verde e silencioso.
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
        ConnectionState.ONLINE -> "Online"
        ConnectionState.SYNCING -> "Sincronizando"
        ConnectionState.PENDING -> "Pendente"
        ConnectionState.OFFLINE, ConnectionState.OFFLINE_PENDING -> "Offline"
    }

    val pending = state.pendingSyncCount
    val detail = when (connection) {
        ConnectionState.ONLINE -> "Sincronizado agora"
        ConnectionState.SYNCING -> "Enviando dados…"
        ConnectionState.PENDING -> "$pending ${if (pending == 1) "operação" else "operações"} na fila"
        // Sem fila, o operador ainda precisa saber que está sem rede — a
        // mensagem foca só nisso, sem misturar com "última sincronização"
        // (as duas ideias juntas soam contraditórias: "sem conexão —
        // sincronizado agora").
        ConnectionState.OFFLINE -> "Sem sincronização"
        // Com fila, o risco real é desligar o terminal com venda presa nele.
        // "Operações", não "vendas": uma única venda pode gerar várias
        // entradas na fila (abrir comanda, lançar pedido, registrar
        // pagamento, fechar) — contar isso como "vendas" engana o operador
        // sobre quantas vendas de fato ficaram presas.
        ConnectionState.OFFLINE_PENDING ->
            "$pending ${if (pending == 1) "operação não enviada" else "operações não enviadas"}"
    }

    // Só os estados que exigem atenção puxam cor no texto auxiliar; os demais
    // ficam cinza para não competir com a ação principal.
    val detailColor = when (connection) {
        ConnectionState.PENDING -> WarningAmber
        ConnectionState.OFFLINE, ConnectionState.OFFLINE_PENDING -> AlertRed
        else -> NoktaMutedSoft
    }

    val detailIcon = when (connection) {
        ConnectionState.ONLINE -> Icons.Outlined.CheckCircle
        ConnectionState.SYNCING -> Icons.Outlined.Sync
        else -> Icons.Outlined.CloudOff
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(NoktaSurface)
                .border(1.dp, NoktaBorderStrong, CircleShape)
                .padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
            Text(statusLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = NoktaInkSoft)
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = detail,
            fontSize = 12.sp,
            color = detailColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = detailIcon,
            contentDescription = null,
            tint = detailColor,
            modifier = Modifier.size(15.dp),
        )
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
 * A ação dominante. É o único bloco em gradiente de marca da tela inteira —
 * é isso que a faz vencer sem precisar ser gigante, e o que impede o roxo de
 * virar preenchimento no resto da interface.
 */
@Composable
private fun NewSaleCard(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(NoktaPurpleDarker, NoktaPurpleDeep, NoktaPurpleBright),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        // Círculos de luz no canto direito: dão profundidade ao bloco sem
        // sombra nem brilho exagerado.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = size.height * 0.95f,
                center = Offset(size.width * 0.88f, size.height * 0.52f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = size.height * 0.62f,
                center = Offset(size.width * 1.02f, size.height * 0.30f),
            )
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddShoppingCart,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(27.dp),
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    "Nova venda",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = Color.White,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Balcão · cobrar na hora",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }

            Icon(ChevronRightThin, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

/* -------------------------- Cards grandes --------------------------- */

@Composable
private fun BigActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .height(180.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(NoktaSurface)
            .border(1.dp, NoktaBorder, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(18.dp),
    ) {
        Icon(icon, contentDescription = null, tint = NoktaPurple, modifier = Modifier.size(30.dp))

        Spacer(Modifier.height(16.dp))

        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = NoktaInk)

        Spacer(Modifier.height(8.dp))

        Text(subtitle, fontSize = 13.sp, lineHeight = 19.sp, color = NoktaMuted)

        Spacer(Modifier.weight(1f))

        Icon(
            ChevronRightThin,
            contentDescription = null,
            tint = NoktaMutedSoft,
            modifier = Modifier.align(Alignment.End).size(20.dp),
        )
    }
}

/* ------------------------ Atalhos inferiores ------------------------ */

/**
 * Faixa de atalhos secundários: o que segue aberto e o que já foi fechado.
 *
 * Fica abaixo das ações principais e em peso visual menor de propósito — é
 * consulta de apoio, não caminho de venda. A contagem de abertas some
 * enquanto carrega (ou se a chamada falhar): um número errado sobre quantas
 * mesas estão em aberto é pior do que número nenhum.
 */
@Composable
private fun ShortcutsRow(
    openTabsCount: Int?,
    onOpenTabs: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NoktaSurface)
            .border(1.dp, NoktaBorder, RoundedCornerShape(16.dp))
            .height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onOpenTabs)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.MenuBook,
                        contentDescription = null,
                        tint = NoktaInkSoft,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Abertas", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk)
                    if (openTabsCount != null && openTabsCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(21.dp)
                                .clip(CircleShape)
                                .background(NoktaPurpleBright),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = openTabsCount.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (openTabsCount) {
                        null -> "Mesas e comandas abertas"
                        0 -> "Nada em aberto"
                        else -> "Mesas e comandas abertas"
                    },
                    fontSize = 11.5.sp,
                    color = NoktaMutedSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(Modifier.width(1.dp).height(44.dp).background(NoktaBorder))

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onHistory)
                .padding(start = 16.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = NoktaInkSoft,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Histórico", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NoktaInk)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ver vendas encerradas",
                    fontSize = 11.5.sp,
                    color = NoktaMutedSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                ChevronRightThin,
                contentDescription = null,
                tint = NoktaMutedSoft,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/* ------------------------------ Rodapé ------------------------------ */

@Composable
private fun NoktaWordmark(modifier: Modifier = Modifier) {
    // Trocar por Image(painterResource(R.drawable.logo_nokta)) quando o asset entrar.
    Text(
        text = "NOKTA",
        modifier = modifier,
        textAlign = TextAlign.Center,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 5.sp,
        color = NoktaInk,
    )
}

/* --------------------- Chevron fino (custom) ------------------------ */

/**
 * O chevron do Material é curto e grosso demais para este acabamento. Este é
 * mais fino e alongado — some visualmente até você procurar por ele, que é o
 * papel de um indicador de "entra aqui".
 */
private val ChevronRightThin: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChevronRightThin",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 10f,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(9f, 5.5f)
            lineTo(15.5f, 12f)
            lineTo(9f, 18.5f)
        }
    }.build()
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
