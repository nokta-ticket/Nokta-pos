package com.nokta.pos.ui.mesa

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen
import com.nokta.pos.ui.theme.NoktaInk
import com.nokta.pos.ui.theme.NoktaMuted
import com.nokta.pos.ui.theme.NoktaMutedSoft
import com.nokta.pos.ui.theme.NoktaPurple
import com.nokta.pos.ui.theme.NoktaPurpleBright
import com.nokta.pos.ui.theme.NoktaSurface
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Mesa não é uma venda — é um consumo aberto que pode receber vários
 * lançamentos ao longo do atendimento. Central de operação com 2 ações
 * claras ("Abrir mesa"/"Consultar mesa") e a lista "Mesas em atendimento"
 * sempre visível. Não existe cadastro prévio de mesa: o garçom digita o
 * número que estiver na mesa física e o backend resolve/cria na hora (ver
 * MesasViewModel).
 */
@Composable
fun MesasScreen(
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MesasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Esta tela fica na pilha de navegação enquanto o garçom abre uma
    // comanda e volta — sem recarregar em ON_RESUME, "Mesas em atendimento"
    // mostrava total/itens desatualizados até sair pra Home e reentrar.
    // Só em CENTRAL (onde a lista aparece) — Abrir/Consultar tem sua própria
    // resolução local contra o snapshot já carregado.
    OnResumeEffect { if (state.mode == MesasMode.CENTRAL) viewModel.searchOpenTabs() }

    // Dentro de Abrir/Consultar, o botão físico de voltar retorna à central
    // (nunca sai da tela de Mesas direto) — mesmo padrão de NovaVendaScreen.
    BackHandler(enabled = state.mode != MesasMode.CENTRAL) {
        viewModel.backToCentral()
    }

    when (state.mode) {
        MesasMode.CENTRAL -> CentralScreen(state = state, viewModel = viewModel, onOpenTab = onOpenTab, onBack = onBack)
        MesasMode.ABRIR -> NumeroMesaScreen(
            title = "Abrir mesa",
            question = "Qual é o número da mesa?",
            confirmText = "Abrir mesa",
            state = state,
            viewModel = viewModel,
            onOpenTab = onOpenTab,
            occupiedTitle = "Esta mesa já possui um atendimento aberto.",
            occupiedAction = "Consultar mesa",
            // Digitar o número em ABRIR já É a intenção de abrir — sem mesa
            // ocupada, MesasViewModel.confirmQuery já dispara openByName
            // sozinho (ver comentário lá). Aqui só evita mostrar a tela de
            // resultado ("Mesa X" + botão) no instante entre confirmar e a
            // navegação acontecer, que seria uma 2ª confirmação redundante.
            skipResultWhenNotOccupied = true,
            skipResultWhenOccupied = false,
        )
        MesasMode.CONSULTAR -> NumeroMesaScreen(
            title = "Consultar mesa",
            question = "Qual é o número da mesa?",
            confirmText = "Consultar",
            state = state,
            viewModel = viewModel,
            onOpenTab = onOpenTab,
            occupiedTitle = null,
            occupiedAction = "Ver consumo",
            skipResultWhenNotOccupied = false,
            // Digitar o número em Consultar já É a intenção de ver — mesa
            // ocupada entra direto no consumo (MesasViewModel.confirmQuery
            // já disparou openExisting sozinho), sem a tela "Mesa X" +
            // botão "Ver consumo" a mais. Mesa vazia continua pedindo
            // confirmação antes de virar "Abrir mesa" (mudança de intenção).
            skipResultWhenOccupied = true,
        )
    }
}

/* ------------------------------ Central ------------------------------ */

private object CentralDim {
    val ScreenPad = 16.dp
    val TopBarHeight = 56.dp
    val TitleSize = 21.sp
    val ShortcutRadius = 12.dp
    val ShortcutHeight = 66.dp
    val ShortcutCircle = 28.dp
    val FieldHeight = 38.dp
    val FieldRadius = 10.dp
    val CardRadius = 10.dp
    val TableIconBox = 42.dp
}

private val CentralPageBg = Color(0xFFF7F6FA)
private val CentralIconBoxBg = Color(0xFFF1ECFB)
private val CentralBadgeBg = Color(0xFFF1EAFD)
private val CentralLineColor = Color(0xFFEFEDF5)
private val CentralFieldBorder = Color(0xFFE7E4EF)

/**
 * Tela inicial de Mesas: os 2 atalhos ("Abrir mesa"/"Consultar mesa") mais a
 * lista "Mesas em atendimento", com busca local por número/nome — não uma
 * busca de backend nova, só filtra a lista já carregada em [MesasUiState.openTabs].
 */
@Composable
private fun CentralScreen(
    state: MesasUiState,
    viewModel: MesasViewModel,
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
) {
    var listQuery by remember { mutableStateOf("") }
    val filteredTabs = if (listQuery.isBlank()) {
        state.openTabs
    } else {
        state.openTabs.filter {
            it.displayName.contains(listQuery, ignoreCase = true) ||
                it.tableName?.contains(listQuery, ignoreCase = true) == true
        }
    }

    Column(Modifier.fillMaxSize().background(CentralPageBg)) {
        CentralTopBar(title = "Mesas", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = CentralDim.ScreenPad, end = CentralDim.ScreenPad, top = 14.dp, bottom = 24.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShortcutCard(
                        icon = Icons.Outlined.Add,
                        title = "Abrir mesa",
                        subtitle = "Iniciar um novo atendimento",
                        onClick = viewModel::openAbrirMesa,
                        modifier = Modifier.weight(1f),
                    )
                    ShortcutCard(
                        icon = Icons.Outlined.Search,
                        title = "Consultar mesa",
                        subtitle = "Ver o consumo de uma mesa",
                        onClick = viewModel::openConsultarMesa,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(18.dp))

                SearchField(
                    query = listQuery,
                    onQueryChange = { listQuery = it },
                    placeholder = "Buscar por número da mesa",
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(28.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Mesas em atendimento",
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                        color = NoktaInk,
                    )
                    if (!state.isLoading) CountBadge(count = filteredTabs.size)
                }

                Spacer(Modifier.height(14.dp))

                if (state.isLoading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        PosLoading(label = "Carregando mesas…")
                    }
                }
            }

            items(filteredTabs, key = { it.localId }) { tab ->
                OpenTableCard(tab = tab, onClick = { onOpenTab(tab.localId) })
                Spacer(Modifier.height(10.dp))
            }

            if (!state.isLoading && filteredTabs.isEmpty()) {
                item {
                    Text(
                        text = if (state.openTabs.isEmpty()) "Nenhuma mesa em atendimento." else "Nenhuma mesa encontrada.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        fontSize = 12.5.sp,
                        color = NoktaMutedSoft,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CentralTopBar(title: String, onBack: () -> Unit) {
    Column(Modifier.background(NoktaSurface)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(CentralDim.TopBarHeight).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = NoktaInk, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(text = title, fontSize = CentralDim.TitleSize, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = NoktaInk)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CentralLineColor))
    }
}

@Composable
private fun ShortcutCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(CentralDim.ShortcutHeight)
            .clip(RoundedCornerShape(CentralDim.ShortcutRadius))
            .background(NoktaSurface)
            .border(1.dp, CentralLineColor, RoundedCornerShape(CentralDim.ShortcutRadius))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(CentralDim.ShortcutCircle).clip(CircleShape).background(NoktaPurpleBright),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NoktaInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 11.sp, lineHeight = 15.sp, color = NoktaMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        Icon(imageVector = ChevronRightThin, contentDescription = null, tint = NoktaInk, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(CentralDim.FieldHeight)
            .clip(RoundedCornerShape(CentralDim.FieldRadius))
            .background(NoktaSurface)
            .border(1.dp, CentralFieldBorder, RoundedCornerShape(CentralDim.FieldRadius))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Outlined.Search, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = placeholder, fontSize = 12.5.sp, color = NoktaMutedSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.5.sp, color = NoktaInk),
                cursorBrush = SolidColor(NoktaPurple),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CentralBadgeBg).padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = if (count == 1) "1 mesa" else "$count mesas",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = NoktaPurpleBright,
        )
    }
}

// openedAt vem em UTC (ISO-8601, padrão do backend) — SimpleDateFormat usa o
// fuso horário local do aparelho por padrão, que é o certo aqui (hora do
// operador no estabelecimento), então não fixamos TimeZone.
private val openedAtFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))

/**
 * Card de uma mesa em atendimento. Sem badge de status "Pago" — dentro de
 * "Mesas em atendimento" toda mesa está, por definição, em aberto; um
 * R$ 0,00 (mesa recém-aberta, sem item ainda) ao lado de "Pago" sugeriria
 * uma conta já quitada, o que nunca é o caso aqui (mesa só é paga ao
 * encerrar). Valor em cor neutra — verde fica reservado para pagamento
 * confirmado, nunca para o total em aberto de uma mesa.
 */
@Composable
private fun OpenTableCard(tab: Tab, onClick: () -> Unit) {
    val openedAtLabel = tab.openedAt?.let {
        runCatching { openedAtFormat.format(java.util.Date.from(java.time.Instant.parse(it))) }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CentralDim.CardRadius))
            .background(NoktaSurface)
            .border(1.dp, CentralLineColor, RoundedCornerShape(CentralDim.CardRadius))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(
                modifier = Modifier.size(CentralDim.TableIconBox).clip(RoundedCornerShape(8.dp)).background(CentralIconBoxBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Icons.Outlined.TableRestaurant, contentDescription = null, tint = NoktaPurpleBright, modifier = Modifier.size(20.dp))
            }
            if (tab.status == TabStatus.OPEN) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(5.dp).clip(CircleShape).background(NoktaPurpleBright),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(text = tab.displayName, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = NoktaInk, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    val count = tab.activeItemCount
                    append(if (count == 1) "1 item" else "$count itens")
                    openedAtLabel?.let { append("  •  Aberta às "); append(it) }
                },
                fontSize = 11.5.sp,
                color = NoktaMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = tab.remaining.formatBRL(),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = NoktaInk,
                maxLines = 1,
            )
            Spacer(Modifier.height(5.dp))
            when (tab.status) {
                TabStatus.CLOSING -> StatusPill("Fechando a conta")
                TabStatus.PAYMENT_IN_PROGRESS -> StatusPill("Recebendo pagamento")
                // hasPartialPayment só é possível aqui com paid>0 e remaining>0 —
                // "Pago" nunca aparece nesta lista (mesa só é quitada ao encerrar,
                // e nesse ponto ela sai de "em atendimento").
                else -> if (tab.hasPartialPayment) StatusPill("Pagamento parcial")
            }
        }

        Spacer(Modifier.width(6.dp))

        Icon(imageVector = ChevronRightThin, contentDescription = null, tint = NoktaMutedSoft, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CentralBadgeBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text = text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = NoktaPurpleBright, maxLines = 1)
    }
}

private val ChevronRightThin: ImageVector by lazy {
    ImageVector.Builder(name = "ChevronRightThin", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
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

/* --------------------------- Abrir/Consultar --------------------------- */

/**
 * Tela de input numérico compartilhada por "Abrir mesa" e "Consultar mesa".
 * Depois de confirmar o número: se já existe uma comanda em atendimento
 * (conhecida localmente) com este nome, mostra o consumo e deixa entrar
 * direto — sem chamada de rede. Senão, oferece abrir uma mesa nova com esse
 * número (o backend cria a mesa na hora, ver MesasViewModel.openByName) —
 * nunca exige que a mesa "exista" antes.
 */
@Composable
private fun NumeroMesaScreen(
    title: String,
    question: String,
    confirmText: String,
    state: MesasUiState,
    viewModel: MesasViewModel,
    onOpenTab: (String) -> Unit,
    occupiedTitle: String?,
    occupiedAction: String,
    skipResultWhenNotOccupied: Boolean,
    skipResultWhenOccupied: Boolean,
) {
    Scaffold(
        topBar = { PosTopBar(title = title, onBack = viewModel::backToCentral) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val confirmed = state.confirmedQuery
            when {
                confirmed.isNullOrBlank() -> NumberEntry(
                    question = question,
                    confirmText = confirmText,
                    initialValue = state.query,
                    onConfirm = { viewModel.setQuery(it); viewModel.confirmQuery(onOpenTab) },
                )
                // CONSULTAR com mesa já ocupada: MesasViewModel.confirmQuery
                // já disparou openExisting sozinho — mostra só um loading
                // breve em vez da tela "Mesa X" + botão "Ver consumo", que
                // seria um clique a mais pra ver o que o garçom já pediu.
                state.matchingOpenTab != null && skipResultWhenOccupied -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PosLoading(label = "Abrindo mesa $confirmed…")
                }
                state.matchingOpenTab != null -> {
                    val tab = state.matchingOpenTab!!
                    ResultContent(
                        tableLabel = confirmed,
                        occupiedTab = tab,
                        isOpening = state.isOpening,
                        occupiedTitle = occupiedTitle,
                        occupiedAction = occupiedAction,
                        onOpen = { viewModel.openExisting(tab, onOpenTab) },
                    )
                }
                // Erro em openByName cai aqui também (matchingOpenTab segue
                // null) — não mostra loading pra sempre, mostra o botão
                // "Abrir mesa" normal pra permitir retry manual.
                skipResultWhenNotOccupied && state.error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PosLoading(label = "Abrindo mesa $confirmed…")
                }
                else -> ResultContent(
                    tableLabel = confirmed,
                    occupiedTab = null,
                    isOpening = state.isOpening,
                    occupiedTitle = occupiedTitle,
                    occupiedAction = occupiedAction,
                    onOpen = { viewModel.openByName(confirmed, onOpenTab) },
                )
            }
            state.error?.let {
                PosInlineWarning(it, tone = PosBadgeTone.DANGER, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun NumberEntry(question: String, confirmText: String, initialValue: String, onConfirm: (String) -> Unit) {
    var localValue by remember { mutableStateOf(initialValue) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(question, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = localValue,
            onValueChange = { localValue = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Número da mesa") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(20.dp))
        PosPrimaryButton(
            text = confirmText,
            enabled = localValue.isNotBlank(),
            onClick = { onConfirm(localValue) },
        )
    }
}

/**
 * Resultado após informar o número — cobre os 2 estados pedidos (sem
 * consumo / já ocupada), com texto e ação dependendo se veio de "Abrir
 * mesa" ou "Consultar mesa". `occupiedTab == null` significa que nenhuma
 * comanda em atendimento é conhecida com este nome — nunca significa "mesa
 * não existe", já que mesa não é cadastro prévio.
 */
@Composable
private fun ResultContent(
    tableLabel: String,
    occupiedTab: Tab?,
    isOpening: Boolean,
    occupiedTitle: String?,
    occupiedAction: String,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mesa $tableLabel", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        if (occupiedTab != null) {
            StatusBadgeRow(occupiedTab.status)
            Spacer(Modifier.height(8.dp))
            Text(
                occupiedTab.remaining.formatBRL(),
                style = MaterialTheme.typography.displaySmall,
                color = if (occupiedTab.remaining.isZeroOrNegative()) MoneyGreen else MaterialTheme.colorScheme.onSurface,
            )
            occupiedTab.customerName?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            occupiedTitle?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            PosPrimaryButton(text = occupiedAction, onClick = onOpen, loading = isOpening)
        } else {
            Text(
                "Nenhum atendimento aberto para esta mesa.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PosPrimaryButton(text = "Abrir mesa", onClick = onOpen, loading = isOpening)
        }
    }
}

@Composable
private fun StatusBadgeRow(status: TabStatus) {
    when (status) {
        TabStatus.CLOSING -> PosBadge("Fechando a conta", PosBadgeTone.WARNING)
        TabStatus.PAYMENT_IN_PROGRESS -> PosBadge("Recebendo pagamento", PosBadgeTone.WARNING)
        else -> PosBadge("Em atendimento", PosBadgeTone.NEUTRAL)
    }
}

