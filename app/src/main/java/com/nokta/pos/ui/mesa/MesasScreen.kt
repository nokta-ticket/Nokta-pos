package com.nokta.pos.ui.mesa

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.comanda.domain.VenueTable
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen

/**
 * Mesa não é uma venda — é um consumo aberto que pode receber vários
 * lançamentos ao longo do atendimento. Central de operação com 2 ações
 * claras ("Abrir mesa"/"Consultar mesa") e a lista "Mesas em atendimento"
 * sempre visível — nunca um mapa de salão livre/ocupado (isso é do
 * dashboard), o garçom só quer atender a mesa 12.
 */
@Composable
fun MesasScreen(
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MesasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

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
            confirmText = "Continuar",
            state = state,
            viewModel = viewModel,
            onOpenTab = onOpenTab,
            emptyTabTitle = "Nenhum atendimento aberto.",
            emptyTabAction = "Iniciar atendimento",
            occupiedTitle = "Esta mesa já possui um atendimento aberto.",
            occupiedAction = "Consultar mesa",
        )
        MesasMode.CONSULTAR -> NumeroMesaScreen(
            title = "Consultar mesa",
            question = "Qual é o número da mesa?",
            confirmText = "Consultar",
            state = state,
            viewModel = viewModel,
            onOpenTab = onOpenTab,
            emptyTabTitle = "Nenhum atendimento aberto para esta mesa.",
            emptyTabAction = "Abrir mesa",
            occupiedTitle = null,
            occupiedAction = "Ver consumo",
        )
    }
}

/* ------------------------------ Central ------------------------------ */

@Composable
private fun CentralScreen(
    state: MesasUiState,
    viewModel: MesasViewModel,
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { PosTopBar(title = "Mesas", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ActionCard(
                icon = Icons.Filled.AddCircle,
                title = "Abrir mesa",
                description = "Iniciar um novo atendimento para uma mesa",
                onClick = viewModel::openAbrirMesa,
            )
            Spacer(Modifier.height(12.dp))
            ActionCard(
                icon = Icons.Filled.Search,
                title = "Consultar mesa",
                description = "Ver o consumo de uma mesa em atendimento",
                onClick = viewModel::openConsultarMesa,
            )

            Spacer(Modifier.height(28.dp))
            Text("Mesas em atendimento", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (state.isLoading) {
                PosLoading(label = "Carregando mesas…")
            } else if (state.openTabs.isEmpty()) {
                Text(
                    "Nenhum atendimento aberto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Column {
                    state.openTabs.forEach { tab ->
                        TabRow(tab = tab, onClick = { onOpenTab(tab.localId) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* --------------------------- Abrir/Consultar --------------------------- */

/**
 * Tela de input numérico compartilhada por "Abrir mesa" e "Consultar mesa"
 * — a lógica de resolução (existe ou não atendimento aberto) é a mesma nos
 * dois casos (ver [MesasViewModel.openTable]); só o texto/ação de cada
 * estado muda conforme a intenção do garçom.
 */
@Composable
private fun NumeroMesaScreen(
    title: String,
    question: String,
    confirmText: String,
    state: MesasUiState,
    viewModel: MesasViewModel,
    onOpenTab: (String) -> Unit,
    emptyTabTitle: String,
    emptyTabAction: String,
    occupiedTitle: String?,
    occupiedAction: String,
) {
    Scaffold(
        topBar = { PosTopBar(title = title, onBack = viewModel::backToCentral) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.query.isBlank() -> NumberEntry(question = question, confirmText = confirmText, viewModel = viewModel)
                state.tables.none { it.active } -> PosEmptyState(
                    title = "Nenhuma mesa cadastrada",
                    description = "As mesas são cadastradas no dashboard, em Operação › Mesas.",
                    icon = Icons.Filled.TableRestaurant,
                )
                state.matchingTable != null -> {
                    val table = state.matchingTable!!
                    ResultContent(
                        table = table,
                        openingTableId = state.openingTableId,
                        emptyTabTitle = emptyTabTitle,
                        emptyTabAction = emptyTabAction,
                        occupiedTitle = occupiedTitle,
                        occupiedAction = occupiedAction,
                        onOpen = { viewModel.openTable(table, onOpenTab) },
                    )
                }
                state.queryMatchesNoTable -> PosEmptyState(
                    title = "Mesa não encontrada",
                    description = "Nenhuma mesa corresponde a \"${state.query}\".",
                    icon = Icons.Filled.Search,
                )
            }
            state.error?.let {
                PosInlineWarning(it, tone = PosBadgeTone.DANGER, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun NumberEntry(question: String, confirmText: String, viewModel: MesasViewModel) {
    var localValue by remember { mutableStateOf("") }
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
            onClick = { viewModel.setQuery(localValue) },
        )
    }
}

/**
 * Resultado após informar o número — cobre os 2 estados pedidos (sem
 * consumo / já ocupada), com texto e ação dependendo se veio de "Abrir
 * mesa" ou "Consultar mesa".
 */
@Composable
private fun ResultContent(
    table: VenueTable,
    openingTableId: Long?,
    emptyTabTitle: String,
    emptyTabAction: String,
    occupiedTitle: String?,
    occupiedAction: String,
    onOpen: () -> Unit,
) {
    val isOpening = openingTableId == table.id
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mesa ${table.name}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        if (table.isOccupied) {
            table.openTabStatus?.let { StatusBadgeRow(it) }
            Spacer(Modifier.height(8.dp))
            table.openTabRemaining?.let {
                Text(
                    it.formatBRL(),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (it.isZeroOrNegative()) MoneyGreen else MaterialTheme.colorScheme.onSurface,
                )
            }
            table.openTabCustomerName?.takeIf { it.isNotBlank() }?.let {
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
                emptyTabTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PosPrimaryButton(text = emptyTabAction, onClick = onOpen, loading = isOpening)
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

/* --------------------------- Linha da lista --------------------------- */

@Composable
private fun TabRow(tab: Tab, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tab.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${tab.activeItems.size} ${if (tab.activeItems.size == 1) "item" else "itens"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                tab.remaining.formatBRL(),
                style = MaterialTheme.typography.titleMedium,
                color = if (tab.isFullyPaid) MoneyGreen else MaterialTheme.colorScheme.onSurface,
            )
            when (tab.status) {
                TabStatus.CLOSING, TabStatus.PAYMENT_IN_PROGRESS -> PosBadge("Fechando", PosBadgeTone.WARNING)
                else -> if (tab.hasPartialPayment) {
                    PosBadge("Parcial", PosBadgeTone.WARNING)
                } else if (tab.isFullyPaid) {
                    PosBadge("Pago", PosBadgeTone.SUCCESS)
                }
            }
        }
    }
}
