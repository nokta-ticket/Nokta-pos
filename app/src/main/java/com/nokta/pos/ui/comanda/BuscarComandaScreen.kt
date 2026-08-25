package com.nokta.pos.ui.comanda

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.ui.components.*
import com.nokta.pos.ui.theme.MoneyGreen

/**
 * Busca de comanda. Um campo só (número ou nome) e a lista das comandas
 * abertas logo abaixo — sem QR, sem escolher tipo de busca.
 */
@Composable
fun BuscarComandaScreen(
    onOpenTab: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BuscarComandaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { PosTopBar(title = "Comandas", onBack = onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Número da comanda ou nome") },
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.titleMedium,
            )

            state.error?.let {
                PosInlineWarning(it, tone = PosBadgeTone.DANGER, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
            }

            when {
                state.isSearching && state.results.isEmpty() -> PosLoading(label = "Buscando…")
                state.results.isEmpty() && state.hasSearched -> PosEmptyState(
                    title = if (state.query.isBlank()) "Nenhuma comanda aberta" else "Nada encontrado",
                    description = if (state.query.isBlank()) {
                        "As comandas abertas aparecem aqui assim que forem criadas."
                    } else {
                        "Nenhuma comanda aberta com \"${state.query}\"."
                    },
                    icon = Icons.Filled.Receipt,
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.results, key = { it.localId }) { tab ->
                        TabSearchRow(tab = tab, onClick = { onOpenTab(tab.localId) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TabSearchRow(tab: Tab, onClick: () -> Unit) {
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
            tab.customerName?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                tab.remaining.formatBRL(),
                style = MaterialTheme.typography.titleMedium,
                color = if (tab.isFullyPaid) MoneyGreen else MaterialTheme.colorScheme.onSurface,
            )
            if (tab.hasPartialPayment) {
                PosBadge("Parcial", PosBadgeTone.WARNING)
            } else if (tab.isFullyPaid) {
                PosBadge("Pago", PosBadgeTone.SUCCESS)
            }
        }
    }
}
