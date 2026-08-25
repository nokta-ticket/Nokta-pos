package com.nokta.pos.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nokta.pos.common.Money
import com.nokta.pos.ui.components.PosBadge
import com.nokta.pos.ui.components.PosBadgeTone
import com.nokta.pos.ui.components.PosActionCard
import com.nokta.pos.ui.components.PosInlineWarning

/**
 * Home operacional. Só ações — nada de faturamento, gráficos ou indicadores
 * gerenciais (item 4 do brief: isso é o dashboard, não o POS).
 *
 * A ordem dos cartões segue o modo de operação da unidade, mas TODOS os
 * caminhos aparecem sempre: um bar que trabalha por mesa ainda vende no
 * balcão, e um balcão ainda pode ter uma comanda. Esconder um fluxo por
 * configuração transformaria um POS único em três apps diferentes.
 *
 * Cartões que o operador não tem permissão de usar ficam desabilitados com o
 * motivo visível — melhor do que sumir (ele perguntaria "cadê?") ou do que
 * deixar tocar e tomar 403 do servidor.
 */
@Composable
fun HomeScreen(
    onNovaVenda: () -> Unit,
    onMesas: () -> Unit,
    onComandas: () -> Unit,
    onOpenTab: (Long) -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    state.pendingPaymentAttempt?.let { attempt ->
        PendingPaymentDialog(
            attempt = attempt,
            onOpenTab = { onOpenTab(attempt.tabId) },
            onDismiss = viewModel::dismissPendingAttempt,
        )
        return
    }

    val access = state.access

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Olá, ${state.operatorName ?: "operador"}",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    state.locationName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    state.operatorRole?.let { PosBadge(it) }
                }
            }
            TextButton(onClick = { viewModel.logout(); onLogout() }) { Text("Sair") }
        }

        Spacer(Modifier.height(28.dp))

        if (state.pendingSyncCount > 0) {
            PosInlineWarning(
                if (state.isSyncing) {
                    "Enviando ${state.pendingSyncCount} ${if (state.pendingSyncCount == 1) "operação" else "operações"}…"
                } else {
                    "${state.pendingSyncCount} ${if (state.pendingSyncCount == 1) "operação aguarda" else "operações aguardam"} conexão. " +
                        "Não desligue o terminal antes de sincronizar."
                },
            )
            Spacer(Modifier.height(16.dp))
        }

        if (!access.canTakePayments) {
            PosInlineWarning(
                "Seu perfil não registra pagamentos. Você pode lançar itens; o fechamento é feito pelo caixa.",
            )
            Spacer(Modifier.height(16.dp))
        }

        // Venda de balcão: a ação mais frequente e a mais rápida — cliente
        // pede, paga e vai embora. Fica em destaque no topo em qualquer modo.
        PosActionCard(
            title = "Nova venda",
            subtitle = "Balcão — cobrar na hora",
            icon = Icons.Filled.AddShoppingCart,
            emphasized = true,
            enabled = access.canSellAtCounter,
            onClick = onNovaVenda,
        )

        Spacer(Modifier.height(12.dp))

        val cards = buildList<@Composable () -> Unit> {
            add {
                PosActionCard(
                    title = "Mesas",
                    subtitle = "Consultar consumo e lançar itens",
                    icon = Icons.Filled.TableRestaurant,
                    enabled = access.canViewTables,
                    onClick = onMesas,
                )
            }
            add {
                PosActionCard(
                    title = "Comandas",
                    subtitle = "Buscar por número ou nome",
                    icon = Icons.Filled.Receipt,
                    enabled = access.canViewTabs,
                    onClick = onComandas,
                )
            }
        }
        // Serviço de balcão puro: comanda antes de mesa (mesa quase não é usada).
        val ordered = if (state.highlightTables) cards else cards.reversed()
        ordered.forEachIndexed { index, card ->
            card()
            if (index != ordered.lastIndex) Spacer(Modifier.height(12.dp))
        }
    }
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
        confirmButton = { TextButton(onClick = onOpenTab) { Text("Abrir comanda") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Já verifiquei") } },
    )
}
