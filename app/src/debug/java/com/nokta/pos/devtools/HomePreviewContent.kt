package com.nokta.pos.devtools

import androidx.compose.runtime.Composable
import com.nokta.pos.access.OperatorAccess
import com.nokta.pos.ui.home.HomeContent
import com.nokta.pos.ui.home.HomeUiState

/**
 * Estados fixos para conferir o acabamento da Home sem parear nem logar.
 * Usa o mesmo `HomeContent` da tela real — nada é reimplementado aqui.
 *
 * `variant` vem por extra do Intent, para inspecionar os casos de exceção:
 *   adb shell am start -n com.nokta.pos/com.nokta.pos.devtools.HomePreviewActivity --es variant pending
 */
@Composable
fun HomePreviewContent(variant: String? = null) {
    val state = when (variant) {
        // Operação registrada offline, ainda na fila.
        "pending" -> HomeUiState(
            operatorName = "Vitor",
            locationName = "Unidade Principal",
            access = OperatorAccess.PERMISSIVE,
            pendingSyncCount = 3,
        )
        "syncing" -> HomeUiState(
            operatorName = "Vitor",
            locationName = "Unidade Principal",
            access = OperatorAccess.PERMISSIVE,
            pendingSyncCount = 3,
            isSyncing = true,
        )
        // Garçom puro: lança itens, não cobra. Nova venda fica inerte.
        "waiter" -> HomeUiState(
            operatorName = "Vitor",
            locationName = "Unidade Principal",
            access = OperatorAccess(
                role = "WAITER",
                permissions = setOf(
                    OperatorAccess.P_MENU_VIEW,
                    OperatorAccess.P_TABLES_VIEW,
                    OperatorAccess.P_TABS_VIEW,
                    OperatorAccess.P_TABS_OPEN,
                    OperatorAccess.P_ORDERS_VIEW,
                    OperatorAccess.P_ORDERS_CREATE,
                ),
            ),
        )
        // Sem rede, nada na fila: pode operar, tudo que fez já subiu.
        "offline" -> HomeUiState(
            operatorName = "Vitor",
            locationName = "Nokta Bar · Unidade Barra da Tijuca",
            access = OperatorAccess.PERMISSIVE,
            isOnline = false,
            lastSyncAt = System.currentTimeMillis() - 2 * 60_000,
        )
        // Sem rede COM venda na fila: o caso perigoso.
        "offline_pending" -> HomeUiState(
            operatorName = "Vitor",
            locationName = "Nokta Bar · Unidade Barra da Tijuca",
            access = OperatorAccess.PERMISSIVE,
            isOnline = false,
            pendingSyncCount = 2,
            lastSyncAt = System.currentTimeMillis() - 47 * 60_000,
        )
        // Nome longo + unidade longa: confere truncamento do cabeçalho.
        "long" -> HomeUiState(
            operatorName = "Maria Fernanda",
            locationName = "Nokta Bar e Restaurante · Unidade Barra da Tijuca",
            access = OperatorAccess.PERMISSIVE,
        )
        else -> HomeUiState(
            operatorName = "Vitor",
            operatorRole = "OWNER",
            locationName = "Unidade Principal",
            access = OperatorAccess.PERMISSIVE,
        )
    }

    HomeContent(
        state = state,
        onNovaVenda = {},
        onMesas = {},
        onComandas = {},
        onLogout = {},
    )
}
