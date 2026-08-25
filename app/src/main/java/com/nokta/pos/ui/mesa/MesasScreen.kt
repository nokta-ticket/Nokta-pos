package com.nokta.pos.ui.mesa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.VenueTable
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen

/**
 * Mesas em grade. Verde = livre, roxo = ocupada com o valor consumido à
 * vista: o garçom vê o salão inteiro numa olhada e toca direto na mesa que
 * precisa atender.
 */
@Composable
fun MesasScreen(
    onOpenTab: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: MesasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            PosTopBar(
                title = "Mesas",
                subtitle = if (state.tables.isNotEmpty()) {
                    "${state.occupiedCount} de ${state.tables.count { it.active }} ocupadas"
                } else null,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.tables.size > 8) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Buscar mesa ou cliente") },
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
                )
            }

            state.error?.let {
                PosInlineWarning(it, tone = PosBadgeTone.DANGER, modifier = Modifier.padding(16.dp))
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
                state.visibleTables.isEmpty() -> PosEmptyState(
                    title = "Nada encontrado",
                    description = "Nenhuma mesa corresponde a \"${state.query}\".",
                    icon = Icons.Filled.Search,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.visibleTables, key = { it.id }) { table ->
                        TableCard(
                            table = table,
                            isOpening = state.openingTableId == table.id,
                            onClick = { viewModel.openTable(table, onOpenTab) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCard(table: VenueTable, isOpening: Boolean, onClick: () -> Unit) {
    val occupied = table.isOccupied
    val container = if (occupied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val border = if (occupied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .heightIn(min = 116.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .border(if (occupied) 2.dp else 1.5.dp, border, MaterialTheme.shapes.medium)
            .clickable(enabled = !isOpening, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(table.name, style = MaterialTheme.typography.titleLarge)
            if (isOpening) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                PosBadge(
                    if (occupied) "Ocupada" else "Livre",
                    if (occupied) PosBadgeTone.NEUTRAL else PosBadgeTone.SUCCESS,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (occupied) {
            table.openTabCustomerName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            table.openTabRemaining?.let { remaining ->
                Text(
                    remaining.formatBRL(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (remaining.isZeroOrNegative()) MoneyGreen else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (remaining.isZeroOrNegative()) "Pago" else "em aberto",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                table.capacity?.let { "$it lugares" } ?: "Tocar para abrir",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
