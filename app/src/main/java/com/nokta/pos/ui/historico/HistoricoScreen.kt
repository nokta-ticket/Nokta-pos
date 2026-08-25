package com.nokta.pos.ui.historico

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.comanda.domain.Tab
import com.nokta.pos.comanda.domain.TabType
import com.nokta.pos.ui.components.PosEmptyState
import com.nokta.pos.ui.components.PosLoading
import com.nokta.pos.ui.components.PosTopBar
import com.nokta.pos.ui.theme.*

/**
 * Vendas encerradas recentemente. Consulta operacional, não relatório: o
 * operador vem aqui para lembrar o que acabou de fechar, não para analisar
 * desempenho.
 */
@Composable
fun HistoricoScreen(
    onOpenTab: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: HistoricoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = NoktaBackground,
        topBar = {
            PosTopBar(
                title = "Histórico",
                subtitle = if (state.tabs.isNotEmpty()) {
                    "${state.tabs.size} ${if (state.tabs.size == 1) "venda" else "vendas"} · ${state.total.formatBRL()}"
                } else null,
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && state.tabs.isEmpty() -> PosLoading(label = "Carregando…")

                state.error != null && state.tabs.isEmpty() -> PosEmptyState(
                    title = "Não foi possível carregar",
                    description = state.error!!,
                    actionText = "Tentar de novo",
                    onAction = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.isEmpty -> PosEmptyState(
                    title = "Nenhuma venda encerrada",
                    description = "As vendas concluídas aparecem aqui assim que a primeira for fechada.",
                    icon = Icons.Outlined.Schedule,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.tabs, key = { it.id }) { tab ->
                        HistoricoRow(tab = tab, onClick = { onOpenTab(tab.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricoRow(tab: Tab, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NoktaSurface)
            .border(1.dp, NoktaBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = tab.displayName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = NoktaInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    tab.customerName?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                    append(
                        when (tab.type) {
                            TabType.COUNTER -> "Balcão"
                            TabType.TABLE -> "Mesa"
                            TabType.INDIVIDUAL -> "Comanda"
                        },
                    )
                    val count = tab.activeItems.sumOf { it.quantity }
                    if (count > 0) append(" · $count ${if (count == 1) "item" else "itens"}")
                },
                fontSize = 12.5.sp,
                color = NoktaMutedSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = tab.total.formatBRL(),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = NoktaInk,
        )
    }
}
