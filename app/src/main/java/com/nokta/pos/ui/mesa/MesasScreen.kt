package com.nokta.pos.ui.mesa

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabStatus
import com.nokta.pos.comanda.domain.VenueTable
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen

/**
 * Mesa não é uma venda — é um consumo aberto que pode receber vários
 * lançamentos ao longo do atendimento (ver briefing do módulo). O foco desta
 * tela é achar RÁPIDO a mesa pelo número, não navegar por uma grade de
 * ocupação: isso é gestão de salão (dashboard), o garçom só quer atender a
 * mesa 12.
 */
@Composable
fun MesasScreen(
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MesasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { PosTopBar(title = "Mesas", onBack = onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Número da mesa") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Limpar")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.titleMedium,
            )

            state.error?.let {
                PosInlineWarning(it, tone = PosBadgeTone.DANGER, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            when {
                state.isLoading -> PosLoading(label = "Carregando mesas…")
                state.tables.none { it.active } -> PosEmptyState(
                    title = "Nenhuma mesa cadastrada",
                    description = "As mesas são cadastradas no dashboard, em Operação › Mesas.",
                    icon = Icons.Filled.TableRestaurant,
                    actionText = "Atualizar",
                    onAction = viewModel::load,
                )
                // Mesa digitada existe: com consumo mostra o resumo e um
                // botão para entrar; sem consumo mostra "Nenhum consumo
                // aberto para esta mesa" + "Iniciar atendimento" (item 5 do
                // briefing) — nunca abre a comanda direto no toque, nos dois
                // casos o mesmo composable decide o texto certo.
                state.matchingTable != null -> {
                    val table = state.matchingTable!!
                    NoOpenTabOrOccupiedContent(
                        table = table,
                        openingTableId = state.openingTableId,
                        onOpen = { viewModel.openTable(table, onOpenTab) },
                    )
                }
                state.queryMatchesNoTable -> PosEmptyState(
                    title = "Mesa não encontrada",
                    description = "Nenhuma mesa corresponde a \"${state.query}\".",
                    icon = Icons.Filled.Search,
                )
                else -> EmAtendimentoList(tabs = state.openTabs, onOpenTab = onOpenTab)
            }
        }
    }
}

/**
 * Conteúdo mostrado ao digitar/selecionar uma mesa específica — cobre os
 * dois casos do briefing (seções 5 e 6) com o mesmo composable: sem consumo
 * mostra "Iniciar atendimento"; com consumo mostra o resumo e entra direto.
 */
@Composable
private fun NoOpenTabOrOccupiedContent(table: VenueTable, openingTableId: Long?, onOpen: () -> Unit) {
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
            Spacer(Modifier.height(20.dp))
            PosPrimaryButton(text = "Ver consumo", onClick = onOpen, loading = isOpening)
        } else {
            Text(
                "Nenhum consumo aberto para esta mesa.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PosPrimaryButton(text = "Iniciar atendimento", onClick = onOpen, loading = isOpening)
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

/**
 * Lista de mesas com consumo aberto — só aparece sem nenhum número digitado.
 * Vem de [Tab] (searchOpenTabs, tipo TABLE), não de [VenueTable]: traz
 * contagem de itens, que a mesa sozinha não tem.
 */
@Composable
private fun EmAtendimentoList(tabs: List<Tab>, onOpenTab: (String) -> Unit) {
    if (tabs.isEmpty()) {
        PosEmptyState(
            title = "Nenhuma mesa em atendimento",
            description = "Digite o número de uma mesa acima para começar.",
            icon = Icons.Filled.TableRestaurant,
        )
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Em atendimento", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(tabs, key = { it.localId }) { tab ->
            TabRow(tab = tab, onClick = { onOpenTab(tab.localId) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun TabRow(tab: Tab, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
